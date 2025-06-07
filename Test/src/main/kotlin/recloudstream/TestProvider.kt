package com.recloudstream.extractors.pornhub

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink

class PornhubProvider : MainAPI() {
    override var name = "Pornhub"
    override var mainUrl = "https://www.pornhub.com"
    override var lang = "en"
    override var hasMainPage = true
    override val supportedTypes = setOf(TvType.NSFW)

    private val userAgent = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/114.0.0.0 Safari/537.36"
    private val headers get() = mapOf("User-Agent" to userAgent)

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {
        val document = app.get("$mainUrl/", headers = headers).document
        
        val home = document.select("li.pcVideoListItem").mapNotNull {
            val titleElement = it.selectFirst("span.title a")
            val title = titleElement?.attr("title") ?: ""
            val href = titleElement?.absUrl("href") ?: return@mapNotNull null
            val image = it.selectFirst("img")?.let { img -> 
                img.attr("data-src").ifEmpty { img.attr("src") }
            }

            newMovieSearchResponse(name = title, url = href, type = TvType.NSFW) {
                this.posterUrl = image
            }
        }
        
        return newHomePageResponse("Recommended", home)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val searchUrl = "$mainUrl/video/search?search=$query"
        val document = app.get(searchUrl, headers = headers).document
        
        return document.select("li.pcVideoListItem").mapNotNull {
            val titleElement = it.selectFirst("span.title a")
            val title = titleElement?.attr("title") ?: ""
            val href = titleElement?.absUrl("href") ?: return@mapNotNull null
            val image = it.selectFirst("img")?.let { img ->
                img.attr("data-src").ifEmpty { img.attr("src") }
            }

            newMovieSearchResponse(name = title, url = href, type = TvType.NSFW) {
                this.posterUrl = image
            }
        }
    }

    /**
     * Hàm load đã được đơn giản hóa, không còn lấy danh sách đề xuất.
     */
    override suspend fun load(url: String): LoadResponse {
        val document: org.jsoup.nodes.Document
        
        // --- Giai đoạn 1: Bắt lỗi khi tải trang ---
        try {
            document = app.get(url, headers = headers).document
        } catch (e: Exception) {
            // Nếu không tải được trang, tạo một phim giả với thông tin lỗi
            return newMovieLoadResponse("Lỗi Mạng (Network Error)", url, TvType.NSFW, url) {
                this.plot = "Không thể tải dữ liệu trang. Lỗi: ${e.message}"
            }
        }

        // --- Giai đoạn 2: Bắt lỗi khi phân tích dữ liệu ---
        try {
            val title = document.selectFirst("h1.title span.inlineFree")?.text() 
                ?: document.selectFirst("title")?.text() 
                ?: "Không tìm thấy tiêu đề"

            val pageText = document.html()
            val poster = Regex("""image_url":"([^"]+)""").find(pageText)?.groupValues?.get(1)
            
            val plot = document.selectFirst("div.video-description-text")?.text() ?: "Không có mô tả."

            // Nếu mọi thứ thành công, trả về kết quả bình thường
            return newMovieLoadResponse(name = title, url = url, type = TvType.NSFW, dataUrl = url) {
                this.posterUrl = poster
                this.plot = plot
            }
        } catch (e: Exception) {
            // Nếu có lỗi khi phân tích, tạo một phim giả với thông tin lỗi
            return newMovieLoadResponse("Lỗi Phân Tích (Parsing Error)", url, TvType.NSFW, url) {
                this.plot = "Không thể phân tích dữ liệu từ HTML. Lỗi: ${e.message}"
            }
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        // Đây sẽ là thử thách tiếp theo của chúng ta!
        throw NotImplementedError("loadLinks is not yet implemented.")
    }
}
