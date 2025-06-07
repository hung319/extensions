// Desription: CloudStream 3 Plugin for phub.com
// Author: Coder
// Date: 2025-06-07

package com.lagradost.cloudstream3.movieprovider

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
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
        val videoList = document.select("ul#videoCategory li.pcVideoListItem")
            .mapNotNull { parseVideoCard(it) }

        val home = listOf(
            HomePageList("Latest Videos", videoList)
        )
        
        return newHomePageResponse(home, videoList.isNotEmpty())
    }

    // Function to handle search queries
    override suspend fun search(query: String): List<SearchResponse> {
        val searchUrl = "$mainUrl/video/search?search=$query"
        val document = app.get(searchUrl).document

        return document.select("ul#videoSearchResult li.pcVideoListItem")
            .mapNotNull { parseVideoCard(it) }
    }

    // Function to load details of a specific video AND get recommendations
    override suspend fun load(url: String): LoadResponse {
        val document = app.get(url).document

        val title = document.selectFirst("h1.title span.inlineFree")?.text()?.trim() ?: "No title"
        val poster = document.selectFirst("meta[property=og:image]")?.attr("content")
        val tags = document.select("div.video-tags-list a[href*=/tags/]").map { it.text() }
        val synopsis = document.selectFirst("div.video-description-text")?.text()?.trim()

        val recommendations = document.select("ul#relatedVideosCenter li.videoblock")
            .mapNotNull { parseRecommendationCard(it) }

        return newMovieLoadResponse(title, url, TvType.NSFW, url) {
            this.posterUrl = poster
            this.plot = synopsis
            this.tags = tags
            this.recommendations = recommendations
        }
    }

    // **Function to extract video stream links (QUALITY FILTERED)**
    override suspend fun loadLinks(
        data: String, // URL of the video
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val response = app.get(data)
        val scriptRegex = Regex("var flashvars_\\d+ = (\\{.*?\\});")
        
        val scriptText = scriptRegex.find(response.text)?.groupValues?.get(1) ?: return false

        val mediaDefinitions =
            mapper.readTree(scriptText).get("mediaDefinitions") ?: return false

        mediaDefinitions.forEach { media ->
            // Ensure both URL and quality string are present and not blank
            val videoUrl = media.get("videoUrl")?.asText()?.ifBlank { null } ?: return@forEach
            val qualityStr = media.get("quality")?.asText()?.ifBlank { null } ?: return@forEach
            
            // **NEW: Filter out 240p and 480p qualities**
            if (qualityStr == "240" || qualityStr == "480") {
                return@forEach // Skips the current iteration
            }

            val qualityInt = qualityStr.toIntOrNull()?.let {
                when {
                    it >= 1080 -> Qualities.P1080.value
                    it >= 720 -> Qualities.P720.value
                    // 480p and 240p are now filtered out before this point
                    else -> Qualities.Unknown.value
                }
            } ?: Qualities.Unknown.value

            callback.invoke(
                ExtractorLink(
                    source = this.name,
                    name = this.name,
                    url = videoUrl,
                    referer = data, 
                    quality = qualityInt,
                    type = if (videoUrl.contains(".m3u8")) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                )
            )
        }
        
        return true
    }
}
