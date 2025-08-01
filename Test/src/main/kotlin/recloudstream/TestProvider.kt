// File: TestProvider.kt
package recloudstream

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Element

/**
 * CloudStream 3 provider for PornDos
 * Version: 3.0 (Final)
 */
class PornDosProvider : MainAPI() {
    // Provider metadata
    override var mainUrl = "https://www.porndos.com"
    override var name = "PornDos"
    override val hasMainPage = true
    override var lang = "en"
    override val supportedTypes = setOf(TvType.NSFW)

    // --- Helper Functions ---

    private fun fixUrl(url: String): String {
        return if (url.startsWith("http")) {
            url
        } else {
            "$mainUrl$url"
        }
    }

    private fun parseVideoCard(element: Element): SearchResponse {
        val link = element.selectFirst("a")!!
        val href = fixUrl(link.attr("href"))
        val title = element.selectFirst("p")?.text() ?: "No Title"
        val posterUrl = fixUrl(element.selectFirst("img")?.attr("data-src") ?: "")

        // As requested, using newMovieSearchResponse.
        // NOTE: This response type does not support the 'duration' field, so it has been removed.
        return newMovieSearchResponse(title, href, TvType.NSFW) {
            this.posterUrl = posterUrl
        }
    }

    // --- Main Page ---

    override val mainPage = mainPageOf(
        "/videos/" to "New Exclusive Videos",
        "/most-viewed/" to "Most Viewed Videos",
        "/top-rated/" to "Top Rated Videos"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val document = app.get("$mainUrl${request.data}").document
        val home = document.select("div.thumbs-wrap div.thumb").mapNotNull {
            try {
                parseVideoCard(it)
            } catch (e: Exception) {
                null
            }
        }
        return newHomePageResponse(request.name, home)
    }

    // --- Search ---

    override suspend fun search(query: String): List<SearchResponse> {
        val response = app.post(
            "$mainUrl/search/",
            data = mapOf("search_query" to query)
        ).document
        
        return response.select("div.thumbs-wrap div.thumb").mapNotNull {
            try {
                parseVideoCard(it)
            } catch (e: Exception) {
                null
            }
        }
    }

    // --- Loading ---

    override suspend fun load(url: String): LoadResponse {
        val document = app.get(url).document

        val title = document.selectFirst("h1[itemprop=name]")?.text()?.trim() ?: "No Title"
        val poster = document.selectFirst("meta[itemprop=thumbnailUrl]")?.attr("content")?.let { fixUrl(it) }
        val synopsis = document.selectFirst("div.full-text p")?.text()?.trim()
        val tags = document.select("div.full-links-items a[href*=category]").map { it.text() }
        val actors = document.select("div.full-links-items a[href*=pornstar]").map { ActorData(Actor(it.text())) }
        val recommendations = document.select("div.video-related div.thumb").mapNotNull { 
            try {
                parseVideoCard(it)
            } catch (e: Exception) {
                null
            }
        }

        return newMovieLoadResponse(title, url, TvType.NSFW, data = url) {
            this.posterUrl = poster
            this.plot = synopsis
            this.tags = tags
            this.actors = actors
            this.recommendations = recommendations
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val pageText = app.get(data).text

        // Regex to find stream URLs in the page's script
        val videoUrlRegex = Regex("""video_url: '(.*?)'""")
        val videoAltUrlRegex = Regex("""video_alt_url: '(.*?)'""")
        val videoAltUrl2Regex = Regex("""video_alt_url2: '(.*?)'""")

        // Extract and callback for each quality
        videoUrlRegex.find(pageText)?.groupValues?.get(1)?.let { streamUrl ->
            callback(
                ExtractorLink(this.name, "360p", streamUrl, mainUrl, Qualities.P360.value, type = ExtractorLinkType.VIDEO)
            )
        }
        videoAltUrlRegex.find(pageText)?.groupValues?.get(1)?.let { streamUrl ->
            callback(
                ExtractorLink(this.name, "480p", streamUrl, mainUrl, Qualities.P480.value, type = ExtractorLinkType.VIDEO)
            )
        }
        videoAltUrl2Regex.find(pageText)?.groupValues?.get(1)?.let { streamUrl ->
            callback(
                ExtractorLink(this.name, "720p", streamUrl, mainUrl, Qualities.P720.value, type = ExtractorLinkType.VIDEO)
            )
        }

        return true
    }
}
