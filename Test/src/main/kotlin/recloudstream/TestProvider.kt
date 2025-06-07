// Desription: CloudStream 3 Plugin for phub.com
// Author: Coder
// Date: 2025-06-07

package com.lagradost.cloudstream3.movieprovider

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import org.jsoup.nodes.Element

// Define the main provider class
class PornhubProvider : MainAPI() {
    // Basic provider information
    override var mainUrl = "https://www.pornhub.com"
    override var name = "Pornhub"
    override val hasMainPage = true
    override var lang = "en"
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(
        TvType.NSFW
    )

    // Helper function to parse video elements from the main page and search results
    private fun parseVideoCard(element: Element): SearchResponse? {
        val linkTag = element.selectFirst("a") ?: return null
        val link = fixUrl(linkTag.attr("href"))
        val title = linkTag.attr("title")

        // Improved poster logic: Try data-src, then data-thumb_url, then src
        val img = element.selectFirst("img")
        val poster = img?.attr("data-src")?.ifBlank { null }
            ?: img?.attr("data-thumb_url")?.ifBlank { null }
            ?: img?.attr("src")?.ifBlank { null }

        if (title.isBlank() || link.isBlank()) return null

        return newMovieSearchResponse(title, link, TvType.NSFW) {
            this.posterUrl = poster
        }
    }

    // Helper function to parse recommended video elements
    private fun parseRecommendationCard(element: Element): SearchResponse? {
        val linkTag = element.selectFirst("a") ?: return null
        val link = fixUrl(linkTag.attr("href"))
        val title = element.selectFirst("span.title a")?.text() ?: linkTag.attr("title")

        // Poster logic for recommendation cards
        val poster = element.selectFirst("img")?.attr("data-thumb_url")

        if (title.isBlank() || !link.contains("view_video")) return null

        return newMovieSearchResponse(title, link, TvType.NSFW) {
            this.posterUrl = poster
        }
    }

    // Function to fetch content for the main page
    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val document = app.get("$mainUrl/video?page=$page").document
        val homePageList = ArrayList<HomePageList>()

        // Find the main video container and parse each item
        val videoList = document.select("ul#videoCategory li.pcVideoListItem")
            .mapNotNull { parseVideoCard(it) }

        if (videoList.isNotEmpty()) {
            homePageList.add(HomePageList("Latest Videos", videoList))
        }

        return HomePageResponse(homePageList)
    }

    // Function to handle search queries
    override suspend fun search(query: String): List<SearchResponse> {
        val searchUrl = "$mainUrl/video/search?search=$query"
        val document = app.get(searchUrl).document

        // Find the search results container and parse each item
        return document.select("ul#videoSearchResult li.pcVideoListItem")
            .mapNotNull { parseVideoCard(it) }
    }

    // Function to load details of a specific video AND get recommendations
    override suspend fun load(url: String): LoadResponse {
        val document = app.get(url).document

        // Extract detailed information about the video
        val title = document.selectFirst("h1.title span.inlineFree")?.text()?.trim() ?: "No title"
        val poster = document.selectFirst("meta[property=og:image]")?.attr("content")
        val tags = document.select("div.video-tags-list a[href*=/tags/]").map { it.text() }
        val synopsis = document.selectFirst("div.video-description-text")?.text()?.trim()

        // NEW: Find and parse recommended videos
        val recommendations = document.select("ul#relatedVideosCenter li.videoblock")
            .mapNotNull { parseRecommendationCard(it) }
        val recList = if (recommendations.isNotEmpty()) {
            listOf(HomePageList("Đề xuất cho bạn", recommendations))
        } else {
            null
        }

        return newMovieLoadResponse(title, url, TvType.NSFW, url) {
            this.posterUrl = poster
            this.plot = synopsis
            this.tags = tags
            // Add the recommendations to the load response
            this.recommendations = recList
        }
    }

    // Function to extract video stream links
    override suspend fun loadLinks(
        data: String, // URL of the video
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        // Tạm thời bỏ qua theo yêu cầu.
        // Sẽ triển khai khi được yêu cầu.
        return false
    }
}
