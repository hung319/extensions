// File: PornDosProvider.kt
package recloudstream

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.LoadResponse.Companion.addToggledLinks
import org.jsoup.nodes.Element
import com.lagradost.cloudstream3.SubtitleFile

/**
 * CloudStream 3 provider for PornDos
 * Target: recloudstream fork
 */
class PornDosProvider : MainAPI() {
    override var mainUrl = "https://www.porndos.com"
    override var name = "PornDos"
    override val hasMainPage = true
    override var lang = "en"
    override val supportedTypes = setOf(TvType.NSFW)

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
        val durationText = element.selectFirst("span.right")?.text()?.trim()
        
        val durationInSeconds = durationText?.split(":")?.let {
            if (it.size == 2) {
                (it[0].toIntOrNull() ?: 0) * 60 + (it[1].toIntOrNull() ?: 0)
            } else 0
        } ?: 0

        return newVideoSearchResponse(title, href, TvType.NSFW) {
            this.posterUrl = posterUrl
            this.duration = durationInSeconds
        }
    }

    override val mainPage = mainPageOf(
        "/videos/" to "New Exclusive Videos",
        "/most-viewed/" to "Most Viewed Videos",
        "/top-rated/" to "Top Rated Videos"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = "$mainUrl${request.data}"
        val document = app.get(url).document
        
        val home = document.select("div.thumbs-wrap div.thumb")
            .mapNotNull {
                try {
                    parseVideoCard(it)
                } catch (e: Exception) {
                    null
                }
            }
        
        return newHomePageResponse(request.name, home)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val searchUrl = "$mainUrl/search/"
        val response = app.post(
            searchUrl,
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

    // `load` now only fetches metadata and adds a toggle to trigger `loadLinks`
    override suspend fun load(url: String): LoadResponse {
        val document = app.get(url).document

        val title = document.selectFirst("h1[itemprop=name]")?.text()?.trim() ?: "No Title"
        val poster = document.selectFirst("meta[itemprop=thumbnailUrl]")?.attr("content")?.let { fixUrl(it) }
        val synopsis = document.selectFirst("div.full-text p")?.text()?.trim()
        
        val tags = document.select("div.full-links-items a[href*=category]").map { it.text() }
        val actors = document.select("div.full-links-items a[href*=pornstar]").map { Actor(it.text()) }
        val recommendations = document.select("div.video-related div.thumb").mapNotNull { 
            try {
                parseVideoCard(it)
            } catch (e: Exception) {
                null
            }
        }

        return newMovieLoadResponse(title, url, TvType.NSFW, url) {
            this.posterUrl = poster
            this.plot = synopsis
            this.tags = tags
            this.actors = actors
            this.recommendations = recommendations
            // Add a toggle that will call loadLinks when clicked
            // The data passed to loadLinks will be the page's URL
            addToggledLinks(
                name = "Video Links", // This is the name of the toggle button
                data = url,           // This data is passed to loadLinks
                callback = null,      // We let loadLinks handle the callback
                source = this@PornDosProvider.name
            )
        }
    }

    // `loadLinks` is now responsible for extracting the video streams.
    override suspend fun loadLinks(
        data: String, // This is the page URL passed from `load`
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val pageText = app.get(data).text

        val videoUrlRegex = Regex("""video_url: '(.*?)'""")
        val videoAltUrlRegex = Regex("""video_alt_url: '(.*?)'""")
        val videoAltUrl2Regex = Regex("""video_alt_url2: '(.*?)'""")

        // Using a try-catch block for safety
        try {
            videoUrlRegex.find(pageText)?.groupValues?.get(1)?.let { streamUrl ->
                callback(
                    ExtractorLink(
                        source = this.name,
                        name = "PornDos - 360p",
                        url = streamUrl,
                        referer = mainUrl,
                        quality = Qualities.P360.value,
                        type = ExtractorLinkType.VIDEO // Use VIDEO for direct MP4 files
                    )
                )
            }
            videoAltUrlRegex.find(pageText)?.groupValues?.get(1)?.let { streamUrl ->
                callback(
                    ExtractorLink(
                        source = this.name,
                        name = "PornDos - 480p",
                        url = streamUrl,
                        referer = mainUrl,
                        quality = Qualities.P480.value,
                        type = ExtractorLinkType.VIDEO
                    )
                )
            }
            videoAltUrl2Regex.find(pageText)?.groupValues?.get(1)?.let { streamUrl ->
                callback(
                    ExtractorLink(
                        source = this.name,
                        name = "PornDos - 720p",
                        url = streamUrl,
                        referer = mainUrl,
                        quality = Qualities.P720.value,
                        type = ExtractorLinkType.VIDEO
                    )
                )
            }
        } catch (e: Exception) {
            // Log the error if something goes wrong during extraction
            e.printStackTrace()
            return false
        }

        return true
    }
}
