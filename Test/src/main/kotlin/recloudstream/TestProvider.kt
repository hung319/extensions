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

    // Helper function to parse video elements from the page
    private fun parseVideoCard(element: Element): SearchResponse? {
        // Extract the link, title, and poster image URL
        val link = element.selectFirst("a")?.attr("href") ?: return null
        val title = element.selectFirst("span.title a")?.text() ?: return null
        // Use data-src for poster image as it loads dynamically
        val poster = element.selectFirst("img")?.attr("data-src")

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

    // Function to load details of a specific video
    override suspend fun load(url: String): LoadResponse {
        val document = app.get(url).document

        // Extract detailed information about the video
        val title = document.selectFirst("h1.title span.inlineFree")?.text()?.trim() ?: "No title"
        val poster = document.selectFirst("meta[property=og:image]")?.attr("content")
        val tags = document.select("div.video-tags-list a[href*=/tags/]").map { it.text() }
        val synopsis = document.selectFirst("div.video-description-text")?.text()?.trim()

        return newMovieLoadResponse(title, url, TvType.NSFW, url) {
            this.posterUrl = poster
            this.plot = synopsis
            this.tags = tags
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
