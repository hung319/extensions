package com.recloudstream.extractors

import android.util.Log
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
        try {
            Log.d(name, "loadLinks started for: $data")
            val document = app.get(data).document
            
            val script = document.select("script").find { it.data().contains("var flashvars") }?.data()
                ?: throw Exception("FPO Plugin: Flashvars script not found!")
            
            // LOG QUAN TRỌNG: In toàn bộ script ra để kiểm tra
            Log.d(name, "Flashvars content: $script")

            // Regex linh hoạt hơn, bỏ qua khoảng trắng
            val videoUrlRegex = Regex("""'video_url'\s*:\s*'function/0/([^']+)""")
            val videoAltUrlRegex = Regex("""'video_alt_url'\s*:\s*'function/0/([^']+)""")

            val lqUrl = videoUrlRegex.find(script)?.groups?.get(1)?.value
            val hqUrl = videoAltUrlRegex.find(script)?.groups?.get(1)?.value

            if (lqUrl == null && hqUrl == null) {
                throw Exception("FPO Plugin: Could not extract any video URLs from flashvars!")
            }
            
            suspend fun extractAndCallback(url: String?, qualityName: String, qualityValue: String) {
                if (url == null) return
                Log.d(name, "Initial URL ($qualityName): $url")
                
                val response = app.get(url, allowRedirects = false)
                Log.d(name, "Response code for $qualityName: ${response.code}")

                val finalUrl = response.headers["Location"]
                    ?: throw Exception("FPO Plugin: 'Location' header not found for $qualityName URL: $url")
                
                Log.d(name, "Final URL ($qualityName): $finalUrl")
                
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

            extractAndCallback(lqUrl, "LQ", "360p")
            extractAndCallback(hqUrl, "HQ", "720p")

        } catch (e: Exception) {
            Log.e(name, "Error in loadLinks", e)
            throw e
        }
        
        return true
    }
}
