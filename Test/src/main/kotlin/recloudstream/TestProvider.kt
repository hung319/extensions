// Bấm vào "Copy" ở góc trên bên phải để sao chép mã
package recloudstream

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Element
import com.lagradost.cloudstream3.network.CloudflareKiller

class XpornTvProvider : MainAPI() {
    override var mainUrl = "https://www.xporn.tv"
    override var name = "Xporn.tv"
    override val hasMainPage = true
    override var lang = "en"
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(
        TvType.NSFW
    )
    
    // BẮT BUỘC: Thêm interceptor để vượt qua Cloudflare như đã xác định từ cURL
    override val interceptor = CloudflareKiller()

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

        // Sử dụng newMovieSearchResponse và không thêm thông tin phụ
        // vì phiên bản API của bạn không hỗ trợ
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
        
        val poster = document.selectFirst("meta[property=og:image]")?.attr("content")
        val description = document.selectFirst("meta[name=description]")?.attr("content")
        val tags = document.select("div.item:contains(Tags) a").map { it.text() }
        
        val recommendations = document.select("div.related-videos div.item").mapNotNull {
            it.toSearchResult()
        }
        
        // Trả về thông tin phim và truyền `url` của trang cho `loadLinks`
        return newMovieLoadResponse(title, url, TvType.NSFW, url) {
            this.posterUrl = poster
            this.plot = description
            this.tags = tags
            this.recommendations = recommendations
        }
    }
    
    // ================= HÀM LOADLINKS ĐÃ HOÀN THIỆN =================
    override suspend fun loadLinks(
        data: String, // `data` là URL của trang video, ví dụ: "https://www.xporn.tv/videos/23672/..."
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        // Tải nội dung của trang video
        val document = app.get(data).document

        // Tìm đoạn script có chứa 'flashvars'
        val script = document.select("script").find { it.data().contains("flashvars") }?.data()
            ?: throw ErrorLoadingException("Không tìm thấy script chứa thông tin video")

        // Dùng Regex để trích xuất URL động từ trong script
        // Cấu trúc URL được lấy từ việc phân tích cURL và HTML
        val videoUrlRegex = Regex("""video_url'\s*:\s*'.*?/(get_file/.+?\.mp4/\S*)'""")
        val videoUrl = videoUrlRegex.find(script)?.groups?.get(1)?.value
            ?: throw ErrorLoadingException("Không thể trích xuất link video từ script")

        // Tạo link đầy đủ
        val fullVideoUrl = "$mainUrl/$videoUrl"

        callback.invoke(
            ExtractorLink(
                source = this.name,
                name = this.name,
                url = fullVideoUrl, // Link .mp4 đầy đủ và động
                referer = data, // Referer phải là trang video, như trong cURL
                quality = Qualities.Unknown.value,
                type = ExtractorLinkType.VIDEO
            )
        )
        return true
    }
}
