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
        "/search/Brazzer/" to "Brazzer",
        "/search/Milf/" to "Milf",
        "/search/Step-siblings-caught/" to "Step Siblings Caught",
    )

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {
        // Appending page number. For search results, the format is /search/query/page/
        val url = if (request.data.contains("/search/")) {
            "$mainUrl${request.data}$page/"
        } else {
            "$mainUrl${request.data}$page/"
        }
        val document = app.get(url).document
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

        // Scrape related videos for recommendations
        val recommendations = document.select("div.related-videos div.item").mapNotNull {
            it.toSearchResult()
        }
        
        return newMovieLoadResponse(title, url, TvType.NSFW, url) {
            this.posterUrl = poster
            this.plot = description
            this.recommendations = recommendations
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
        
        suspend fun extractAndCallback(url: String?, qualityName: String, qualityValue: String) {
            if (url == null) return
            
            // Make a request but don't follow the redirect
            val response = app.get(url, allowRedirects = false)
            
            // The actual video URL is in the 'Location' header of the 302 response
            val finalUrl = response.headers["Location"] ?: return
            
            callback.invoke(
                ExtractorLink(
                    source = name,
                    name = "$name $qualityName",
                    url = finalUrl,
                    referer = mainUrl,
                    quality = getQualityFromName(qualityValue),
                    type = ExtractorLinkType.VIDEO
                )
            )
        }

        val lqUrl = videoUrlRegex.find(script)?.groups?.get(1)?.value
        val hqUrl = videoAltUrlRegex.find(script)?.groups?.get(1)?.value

        extractAndCallback(lqUrl, "LQ", "360p")
        extractAndCallback(hqUrl, "HQ", "720p")
        
        return true
    }
}
