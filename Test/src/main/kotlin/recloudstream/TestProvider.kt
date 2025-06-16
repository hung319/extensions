// Đặt file này trong thư mục 'app/src/main/java/com/lagradost/cloudstream3/movieprovider/'
// hoặc thư mục tương ứng trong project của bạn.
package com.lagradost.cloudstream3.movieprovider // Thay đổi package name nếu cần

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.Qualities
import org.jsoup.nodes.Element
import com.lagradost.cloudstream3.utils.ExtractorLinkType

// Đặt tên class theo tên provider, ví dụ: VlxProvider
class VlxProvider : MainAPI() {
    override var name = "Vlx"
    override var mainUrl = "https://vlxx.bz"
    override var supportedTypes = setOf(
        TvType.Movie,
        TvType.TvSeries
    )
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
        
        return newMovieLoadResponse(title, url, TvType.Movie, url) {
            this.posterUrl = posterUrl
            this.plot = plot
        }
    }

    private val sourcesRegex = Regex("""sources:\s*(\[.*?\])""")

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val document = app.get(data).document
        
        val videoId = document.selectFirst("div#video")?.attr("data-id") ?: return false
        val servers = document.select("li.video-server")
        
        // Dùng a-p-map để xử lý các server song song
        servers.apmap { serverElement ->
            try {
                val onclickAttr = serverElement.attr("onclick")
                val serverNum = Regex("""server\((\d+),""").find(onclickAttr)?.groupValues?.get(1)
                
                if (serverNum != null) {
                    val playerPageUrl = "$mainUrl/ajax.php?&id=$videoId&sv=$serverNum"
                    val playerDoc = app.get(playerPageUrl).document
                    val script = playerDoc.select("script").find { it.data().contains("window.\$\$ops") }?.data() ?: ""
                    val sourcesJson = sourcesRegex.find(script)?.groupValues?.get(1)

                    // SỬA ĐỔI: Sử dụng tryParseJson để an toàn hơn
                    val sources = app.tryParseJson<List<VideoSource>>(sourcesJson)
                    
                    // Nếu sources không phải là null (parse thành công)
                    sources?.forEach { source ->
                        if (source.file.isNotBlank()) {
                            callback.invoke(
                                ExtractorLink(
                                    source = this.name,
                                    name = "${this.name} Server $serverNum",
                                    url = source.file,
                                    referer = mainUrl,
                                    quality = Qualities.Unknown.value,
                                    type = ExtractorLinkType.M3U8
                                )
                            )
                        }
                    }
                }
            } catch (e: Exception) {
                // Bỏ qua server nếu có lỗi mạng hoặc lỗi không mong muốn khác
            }
        }
        
        return true
    }
    
    data class VideoSource(
        val file: String,
        val type: String
    )

    private fun Element.toSearchResult(): SearchResponse? {
        val linkTag = this.selectFirst("a") ?: return null
        val href = linkTag.attr("href")
        if (href.isBlank()) return null
        
        val title = linkTag.attr("title").trim()
        val posterUrl = this.selectFirst("img.video-image")?.attr("data-original")

        return newMovieSearchResponse(title, href, TvType.Movie) {
            this.posterUrl = posterUrl
        }
    }
}
