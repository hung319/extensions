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
    override var mainUrl = "https://vlxx.bz"
    override var hasMainPage = true
    override var supportedTypes = setOf(TvType.NSFW)
    override var lang = "vi"

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {
        val url = if (page == 1) mainUrl else "$mainUrl/new/$page/"
        val document = app.get(url).document
        
        val home = document.select("div.video-item").mapNotNull {
            it.toSearchResult()
        }

        return newHomePageResponse(request.name, home)
    }
    
    override suspend fun search(query: String): List<SearchResponse> {
        val searchUrl = "$mainUrl/search/$query/"
        val document = app.get(searchUrl).document

        return document.select("div.video-item").mapNotNull {
            it.toSearchResult()
        }
    }

    override suspend fun load(url: String): LoadResponse? {
        val document = app.get(url).document

        val title = document.selectFirst("h2#page-title")?.text()?.trim() ?: return null
        val posterUrl = document.selectFirst("meta[property=og:image]")?.attr("content")
        val plot = document.selectFirst("div.video-description")?.text()?.trim()
        
        return newMovieLoadResponse(title, url, TvType.NSFW, url) {
            this.posterUrl = posterUrl
            this.plot = plot
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
        
        // THAY ĐỔI: Bỏ đếm server, mặc định lặp 2 lần cho Server 1 và Server 2
        (1..2).forEach { serverNum ->
            try {
                val postData = mapOf(
                    "vlxx_server" to "2",
                    "id" to videoId,
                    "server" to serverNum.toString()
                )
                
                val headers = mapOf("X-Requested-With" to "XMLHttpRequest", "Referer" to data)
                val ajaxUrl = "$mainUrl/ajax.php"
                val ajaxResponse = app.post(ajaxUrl, data = postData, headers = headers).parsed<PlayerResponse>()
                
                val iframeDocument = Jsoup.parse(ajaxResponse.player)
                val iframeSrc = iframeDocument.selectFirst("iframe")?.attr("src")

                if (iframeSrc != null) {
                    val finalPlayerPage = app.get(iframeSrc, referer = data).document
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
    
    private fun Element.toSearchResult(): SearchResponse? {
        val linkTag = this.selectFirst("a") ?: return null
        val href = linkTag.attr("href")
        if (href.isBlank()) return null
        
        val title = linkTag.attr("title").trim()
        val posterUrl = this.selectFirst("img.video-image")?.attr("data-original")

        return newMovieSearchResponse(title, href, TvType.NSFW) {
            this.posterUrl = posterUrl
        }
    }
}
