package com.recloudstream.extractors

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.getQualityFromName
import org.jsoup.nodes.Element

class Fpo : MainAPI() {
    override var mainUrl = "https://www.fpo.xxx"
    override var name = "FPO.XXX"
    override val hasMainPage = true
    override var lang = "en"
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(
        TvType.NSFW
    )

    override val mainPage = mainPageOf(
        "/new-1/" to "Latest Videos",
        "/top-2/" to "Top Rated",
        "/popular-2/" to "Most Popular",
    )

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {
        val document = app.get("$mainUrl${request.data}$page/").document
        val home = document.select("div.list-videos div.item").mapNotNull {
            it.toSearchResult()
        }
        return newHomePageResponse(request.name, home)
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val title = this.selectFirst("strong.title")?.text() ?: return null
        val href = this.selectFirst("a")?.attr("href") ?: return null
        val posterUrl = this.selectFirst("img.thumb")?.attr("data-original")
        
        return newMovieSearchResponse(title, href, TvType.NSFW) {
            this.posterUrl = posterUrl
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val searchResponse = app.get("$mainUrl/search/$query/").document
        return searchResponse.select("div.list-videos div.item").mapNotNull {
            it.toSearchResult()
        }
    }

    override suspend fun load(url: String): LoadResponse? {
        val document = app.get(url).document

        val title = document.selectFirst("h1")?.text()?.trim() ?: return null
        val poster = document.selectFirst("meta[property=og:image]")?.attr("content")
        val description = document.selectFirst("meta[property=og:description]")?.attr("content")
        
        val script = document.select("script").find { it.data().contains("var flashvars") }?.data() ?: return null
        
        // Extract video URLs from the flashvars object
        val videoUrlRegex = Regex("""'video_url': 'function/0/(.*?)'""")
        val videoAltUrlRegex = Regex("""'video_alt_url': 'function/0/(.*?)'""")
        
        val videoUrl = videoUrlRegex.find(script)?.groups?.get(1)?.value
        val videoAltUrl = videoAltUrlRegex.find(script)?.groups?.get(1)?.value

        // Use loadLinks to fetch the extractor links
        return newMovieLoadResponse(title, url, TvType.NSFW, url) {
            this.posterUrl = poster
            this.plot = description
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val document = app.get(data).document
        val script = document.select("script").find { it.data().contains("var flashvars") }?.data() ?: return false
        
        val videoUrlRegex = Regex("""'video_url': 'function/0/(.*?)'""")
        val videoAltUrlRegex = Regex("""'video_alt_url': 'function/0/(.*?)'""")
        
        videoUrlRegex.find(script)?.groups?.get(1)?.value?.let {
            callback.invoke(
                ExtractorLink(
                    source = name,
                    name = "$name LQ",
                    url = it,
                    referer = mainUrl,
                    quality = getQualityFromName("360p"), // Assuming LQ is 360p
                    type = ExtractorLinkType.VIDEO
                )
            )
        }

        videoAltUrlRegex.find(script)?.groups?.get(1)?.value?.let {
            callback.invoke(
                ExtractorLink(
                    source = name,
                    name = "$name HQ",
                    url = it,
                    referer = mainUrl,
                    quality = getQualityFromName("720p"), // Assuming HQ is 720p
                    type = ExtractorLinkType.VIDEO
                )
            )
        }
        
        return true
    }
}
