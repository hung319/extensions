// Save this file as XTapesProvider.kt
package recloudstream

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import org.jsoup.nodes.Element
import com.lagradost.cloudstream3.utils.M3u8Helper
import com.fasterxml.jackson.annotation.JsonProperty
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch

class XTapesProvider : MainAPI() {
    override var mainUrl = "https://xtapes.in"
    override var name = "XTapes"
    override val hasMainPage = true
    override var lang = "en"
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(
        TvType.NSFW
    )

    // Data class để parse đối tượng 'links' từ Javascript
    private data class VideoLinks(
        @JsonProperty("hls3") val hls3: String? = null,
        @JsonProperty("hls2") val hls2: String? = null,
        @JsonProperty("hls") val hls: String? = null, // Thêm các key dự phòng
        @JsonProperty("file") val file: String? = null
    )

    private fun Element.toSearchResponse(): SearchResponse? {
        val link = this.selectFirst("a") ?: return null
        val href = fixUrl(link.attr("href"))
        if (!href.contains("xtapes.in/") || href.endsWith(".in/")) return null
        val title = link.attr("title")
        val posterUrl = fixUrlNull(this.selectFirst("img")?.attr("src"))
        return newMovieSearchResponse(title, href, TvType.NSFW) {
            this.posterUrl = posterUrl
        }
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = when (request.name) {
            "New Movies" -> if (page > 1) "$mainUrl/porn-movies-hd/page/$page/" else "$mainUrl/porn-movies-hd/"
            else -> if (page > 1) "$mainUrl/page/$page/" else mainUrl
        }

        val document = app.get(url).document
        val list = document.select("ul.listing-tube > li, ul.listing-videos > li")
        val homePageList = list.mapNotNull { it.toSearchResponse() }
        
        return newHomePageResponse(request.name, homePageList, hasNext = homePageList.isNotEmpty())
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val searchUrl = "$mainUrl/?s=$query"
        val document = app.get(searchUrl).document
        return document.select("ul.listing-videos.listing-tube > li").mapNotNull {
            it.toSearchResponse()
        }
    }

    override suspend fun load(url: String): LoadResponse? {
        val document = app.get(url).document
        val title = document.selectFirst("h1.border-radius-top-5")?.text()?.trim() ?: return null
        val poster = fixUrlNull(document.selectFirst("meta[property=og:image]")?.attr("content"))
        val tags = document.select("#cat-tag li a").map { it.text() }
        val synopsis = document.selectFirst("meta[name=description]")?.attr("content")
        val embedUrls = document.select(".video-embed iframe").mapNotNull { it.attr("src") }.filter { it.isNotBlank() }
        val recommendations = document.select(".sidebar-widget:has(> .widget-title > span:contains(Related Videos)) ul.listing-tube > li").mapNotNull {
            it.toSearchResponse()
        }

        return newMovieLoadResponse(title, url, TvType.NSFW, embedUrls) {
            this.posterUrl = poster
            this.plot = synopsis
            this.tags = tags
            this.recommendations = recommendations
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val embedUrls = parseJson<List<String>>(data)
        
        coroutineScope {
            embedUrls.forEach { url ->
                launch {
                    if (url.contains("74k.io")) {
                        // CẬP NHẬT LOGIC EXTRACTOR CHO 74K.IO
                        try {
                            val doc = app.get(url, referer = mainUrl).document
                            val script = doc.select("script").find { it.data().contains("eval(function(p,a,c,k,e,d)") }?.data()
                            if (script != null) {
                                val unpacked = getAndUnpack(script)
                                // Regex mới để lấy đối tượng 'links'
                                val linksJson = Regex("""var\s+links\s*=\s*(\{.*?\})""").find(unpacked)?.groupValues?.getOrNull(1)
                                
                                if (linksJson != null) {
                                    val videoData = parseJson<VideoLinks>(linksJson)
                                    // Ưu tiên lấy link hls3 hoặc hls2
                                    val m3u8Url = videoData.hls3 ?: videoData.hls2 ?: videoData.hls ?: videoData.file
                                    if (m3u8Url != null) {
                                        M3u8Helper.generateM3u8(name, m3u8Url, mainUrl)
                                            .forEach(callback)
                                    }
                                }
                            }
                        } catch (e: Exception) {
                            // Bỏ qua nếu có lỗi
                        }
                    } else {
                        loadExtractor(url, mainUrl, subtitleCallback, callback)
                    }
                }
            }
        }
        return true
    }
}
