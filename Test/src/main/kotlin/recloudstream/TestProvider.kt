// Đặt file này trong thư mục 'app/src/main/java/com/lagradost/cloudstream3/movieprovider/'
// hoặc thư mục tương ứng trong project của bạn.
package com.lagradost.cloudstream3.movieprovider // Thay đổi package name nếu cần

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.Qualities
import org.jsoup.nodes.Element

// Đặt tên class theo tên provider, ví dụ: VlxProvider
class VlxProvider : MainAPI() {
    override var name = "Vlx"
    override var mainUrl = "https://vlxx.bz"
    override var supportedTypes = setOf(
        TvType.Movie,
        TvType.TvSeries
    )
    override var lang = "vi"

    // Hàm này dùng để tải trang chủ
    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {
        val url = if (page == 1) mainUrl else "$mainUrl/new/$page/"
        val document = app.get(url).document
        
        // Selector đúng cho các item trên trang chủ
        val home = document.select("div.video-item").mapNotNull {
            it.toSearchResult()
        }

        return newHomePageResponse(request.name, home)
    }
    
    // Hàm này dùng để tìm kiếm
    override suspend fun search(query: String): List<SearchResponse> {
        val searchUrl = "$mainUrl/search/$query/"
        val document = app.get(searchUrl).document

        // Selector đúng cho các item trên trang tìm kiếm
        return document.select("div.video-item").mapNotNull {
            it.toSearchResult()
        }
    }

    // Hàm này dùng để lấy thông tin chi tiết của phim
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

    // Regex để trích xuất json từ script
    private val sourcesRegex = Regex("""sources:\s*(\[.*?\])""")

    // Hàm này dùng để lấy link stream cuối cùng
    override suspend fun loadLinks(
        data: String, // Đây là URL của trang chi tiết phim
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val document = app.get(data).document
        
        val videoId = document.selectFirst("div#video")?.attr("data-id") ?: return false
        val servers = document.select("li.video-server")
        
        servers.apmap { serverElement ->
            val onclickAttr = serverElement.attr("onclick")
            val serverNum = Regex("""server\((\d+),""").find(onclickAttr)?.groupValues?.get(1)
            
            if (serverNum != null) {
                val playerPageUrl = "$mainUrl/ajax.php?&id=$videoId&sv=$serverNum"
                
                try {
                    val playerDoc = app.get(playerPageUrl).document
                    val script = playerDoc.select("script").find { it.data().contains("window.\$\$ops") }?.data() ?: ""
                    val sourcesJson = sourcesRegex.find(script)?.groupValues?.get(1) ?: return@apmap

                    val sources = parseJson<List<VideoSource>>(sourcesJson)
                    sources.forEach { source ->
                        if (source.file.isNotBlank()) {
                            // =================== PHẦN ĐƯỢC CẬP NHẬT =================== //
                            callback.invoke(
                                ExtractorLink(
                                    source = this.name, // Tên provider
                                    name = "${this.name} Server $serverNum", // Tên nguồn, ví dụ "Vlx Server 1"
                                    url = source.file,
                                    referer = mainUrl,
                                    quality = Qualities.Unknown.value,
                                    // Thay isM3u8 = true bằng type = ExtractorLinkType.M3U8
                                    // Dựa vào HTML, trang trả về type là "hls"
                                    type = ExtractorLinkType.M3U8
                                )
                            )
                            // ========================================================== //
                        }
                    }
                } catch (e: Exception) {
                    // Bỏ qua nếu có lỗi
                }
            }
        }
        
        return true
    }
    
    // Data class để parse JSON từ player
    data class VideoSource(
        val file: String,
        val type: String
    )

    // Hàm tiện ích để chuyển đổi một element HTML thành SearchResponse
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
