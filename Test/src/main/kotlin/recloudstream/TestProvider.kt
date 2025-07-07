// Bấm vào "Copy" ở góc trên bên phải để sao chép mã
package recloudstream

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Element
import android.net.Uri

class XpornTvProvider : MainAPI() {
    override var mainUrl = "https://www.xporn.tv"
    override var name = "Xporn.tv"
    override val hasMainPage = true
    override var lang = "en"
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(
        TvType.NSFW
    )

    override val mainPage = mainPageOf(
        "$mainUrl/latest-updates/" to "Latest Videos",
        "$mainUrl/most-popular/" to "Most Popular",
        "$mainUrl/top-rated/" to "Top Rated",
    )

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {
        val url = if (page > 1) "${request.data}$page" else request.data
        val document = app.get(url).document
        
        val home = document.select("div.list-videos div.item").mapNotNull {
            it.toSearchResult()
        }
        return newHomePageResponse(request.name, home)
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val title = this.selectFirst("strong.title")?.text()?.trim() ?: return null
        val href = this.selectFirst("a")?.attr("href") ?: return null
        val posterUrl = this.selectFirst("img.thumb")?.attr("src")

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

    override suspend fun load(url: String): LoadResponse {
        val document = app.get(url).document

        val title = document.selectFirst("h1")?.text()?.trim()
            ?: document.selectFirst("meta[property=og:title]")?.attr("content")
            ?: "Video"
        
        val videoId = url.split("/")[4]
        
        val poster = document.selectFirst("meta[property=og:image]")?.attr("content")
        val description = document.selectFirst("meta[name=description]")?.attr("content")
        
        val recommendations = document.select("div.related-videos div.item").mapNotNull {
            it.toSearchResult()
        }
        
        return newMovieLoadResponse(title, url, TvType.NSFW, videoId) {
            this.posterUrl = poster
            this.plot = description
            this.recommendations = recommendations
        }
    }
    
    override suspend fun loadLinks(
        data: String, // `data` là videoId
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val videoId = data
        val videoIdInt = videoId.toIntOrNull() ?: return false

        // Lấy tên miền gốc từ mainUrl để dễ bảo trì
        val hostName = Uri.parse(mainUrl).host?.replace("www.", "") ?: "xporn.tv"

        // Tính toán thư mục
        val videoFolder = (videoIdInt / 1000) * 1000
        
        // ================== LOGIC MỚI Ở ĐÂY ==================
        // Tạo ra danh sách các link có khả năng hoạt động
        val urlsToTest = listOf(
            "https://vid2.$hostName/videos1X/$videoFolder/$videoId/$videoId.mp4", // Cấu trúc mới
            "https://vid.$hostName/videos/$videoFolder/$videoId/$videoId.mp4"    // Cấu trúc cũ
        )

        var workingUrl: String? = null

        // Vòng lặp để kiểm tra từng link
        for (url in urlsToTest) {
            try {
                // Dùng app.head để kiểm tra sự tồn tại của link mà không cần tải toàn bộ file
                // Mã 200 (OK) có nghĩa là link hoạt động
                if (app.head(url, referer = mainUrl).code == 200) {
                    workingUrl = url
                    break // Tìm thấy link hoạt động, thoát khỏi vòng lặp
                }
            } catch (e: Exception) {
                // Bỏ qua lỗi (ví dụ 404) và tiếp tục thử link tiếp theo
            }
        }
        
        // Nếu tìm thấy link hoạt động, gửi nó cho trình phát
        if (workingUrl != null) {
            callback.invoke(
                ExtractorLink(
                    source = this.name,
                    name = this.name,
                    url = workingUrl,
                    referer = mainUrl, // Dùng mainUrl làm referer theo yêu cầu
                    quality = Qualities.Unknown.value,
                    type = ExtractorLinkType.VIDEO 
                )
            )
            return true
        }

        // Nếu không có link nào hoạt động, báo lỗi
        throw ErrorLoadingException("Không tìm thấy link video hợp lệ")
    }
}
