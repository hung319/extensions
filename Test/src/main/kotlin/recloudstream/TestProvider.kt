// Đặt file này trong thư mục 'app/src/main/java/com/lagradost/cloudstream3/movieprovider/'
// hoặc thư mục tương ứng trong project của bạn.
package com.lagradost.cloudstream3.movieprovider // Thay đổi package name nếu cần

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.AppUtils
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.Qualities
import org.jsoup.nodes.Element
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import org.jsoup.Jsoup

// Đặt tên class theo tên provider, ví dụ: VlxProvider
class VlxProvider : MainAPI() {
    override var name = "Vlx"
    override var mainUrl = "https://vlxx.com"
    override var hasMainPage = true
    override var supportedTypes = setOf(TvType.NSFW)
    override var lang = "vi"

    private var baseUrl: String? = null

    private suspend fun getBaseUrl(): String {
        if (baseUrl == null) {
            val document = app.get(mainUrl).document
            val realUrl = document.selectFirst("a.button")?.attr("href")?.trimEnd('/') ?: mainUrl
            baseUrl = realUrl
        }
        return baseUrl!!
    }

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {
        val currentBaseUrl = getBaseUrl()
        val url = if (page == 1) currentBaseUrl else "$currentBaseUrl/new/$page/"
        val document = app.get(url).document
        
        val home = document.select("div.video-item").mapNotNull {
            it.toSearchResult()
        }

        return newHomePageResponse(request.name, home)
    }
    
    override suspend fun search(query: String): List<SearchResponse> {
        val searchUrl = "${getBaseUrl()}/search/$query/"
        val document = app.get(searchUrl).document

        return document.select("div.video-item").mapNotNull {
            it.toSearchResult()
        }
    }

    // =================== HÀM load ĐÃ ĐƯỢC CẬP NHẬT ===================
    override suspend fun load(url: String): LoadResponse? {
        val document = app.get(url).document

        val title = document.selectFirst("h2#page-title")?.text()?.trim() ?: return null
        val posterUrl = document.selectFirst("meta[property=og:image]")?.attr("content")
        val plot = document.selectFirst("div.video-description")?.text()?.trim()
        
        // THÊM MỚI: Tìm và phân tích danh sách phim liên quan
        val recommendations = document.select("div#video-list div.video-item").mapNotNull {
            it.toSearchResult()
        }
        
        // CẬP NHẬT: Thêm 'recommendations' vào LoadResponse
        return newMovieLoadResponse(title, url, TvType.NSFW, url) {
            this.posterUrl = posterUrl
            this.plot = plot
            this.recommendations = recommendations
        }
    }

    data class PlayerResponse(val player: String)
    data class VideoSource(val file: String, val type: String)

    private val sourcesRegex = Regex("""sources:\s*(\[.*?\])""")

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val document = app.get(data).document
        
        val videoId = document.selectFirst("div#video")?.attr("data-id") ?: return false
        
        (1..2).forEach { serverNum ->
            try {
                val postData = mapOf(
                    "vlxx_server" to "2",
                    "id" to videoId,
                    "server" to serverNum.toString()
                )
                
                val headers = mapOf("X-Requested-With" to "XMLHttpRequest", "Referer" to data)
                val ajaxUrl = "${getBaseUrl()}/ajax.php"
                val ajaxResponse = app.post(ajaxUrl, data = postData, headers = headers).parsed<PlayerResponse>()
                
                val iframeDocument = Jsoup.parse(ajaxResponse.player)
                val iframeSrc = iframeDocument.selectFirst("iframe")?.attr("src")

                if (iframeSrc != null) {
                    val iframeHeaders = mapOf(
                        "Referer" to data,
                        "User-Agent" to "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/113.0.0.0 Mobile Safari/537.36"
                    )
                    val finalPlayerPage = app.get(iframeSrc, headers = iframeHeaders).document

                    val script = finalPlayerPage.select("script").find { it.data().contains("window.\$\$ops") }?.data() ?: ""
                    val sourcesJson = sourcesRegex.find(script)?.groupValues?.get(1)
                    val sources = AppUtils.tryParseJson<List<VideoSource>>(sourcesJson)
                    
                    sources?.forEach { source ->
                        if (source.file.isNotBlank()) {
                            callback.invoke(
                                ExtractorLink(
                                    source = this.name,
                                    name = "${this.name} Server $serverNum",
                                    url = source.file,
                                    referer = iframeSrc,
                                    quality = Qualities.Unknown.value,
                                    type = ExtractorLinkType.M3U8
                                )
                            )
                        }
                    }
                }
            } catch (e: Exception) {
                // Bỏ qua nếu server bị lỗi và tiếp tục với server tiếp theo
            }
        }
        
        return true
    }
    
    private suspend fun Element.toSearchResult(): SearchResponse? {
        val linkTag = this.selectFirst("a") ?: return null
        var href = linkTag.attr("href")
        if (href.isBlank()) return null
        
        if (!href.startsWith("http")) {
            href = "${getBaseUrl()}$href"
        }
        
        val title = linkTag.attr("title").trim()
        val posterUrl = this.selectFirst("img.video-image")?.attr("data-original")

        return newMovieSearchResponse(title, href, TvType.NSFW) {
            this.posterUrl = posterUrl
        }
    }
}
