package recloudstream

// Import các lớp và hàm cần thiết từ CloudStream API
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Element

// Lớp Provider chính
open class Rule34VideoProvider : MainAPI() {
    override var mainUrl = "https://rule34video.com"
    override var name = "Rule34Video"
    override val hasMainPage = true
    override val supportedTypes = setOf(TvType.NSFW)

    // Các mục trên trang chủ
    override val mainPage = mainPageOf(
        "/latest-updates/" to "Newest Videos",
        "/most-popular/" to "Most Popular",
        "/top-rated/" to "Top Rated"
    )

    // Lấy dữ liệu cho trang chủ
    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = "$mainUrl${request.data}${page}/"
        val document = app.get(url).document
        
        val home = document.select("div.thumbs div.item.thumb").mapNotNull {
            it.toSearchResult() 
        }

        // Sửa lỗi: newHomePageResponse yêu cầu một đối tượng HomePageList
        return newHomePageResponse(
            list = HomePageList(
                name = request.name,
                list = home
            ),
            hasNext = true
        )
    }

    // Hàm chuyển đổi một phần tử HTML thành đối tượng SearchResponse
    private fun Element.toSearchResult(): SearchResponse? {
        val title = this.selectFirst("div.thumb_title")?.text() ?: return null
        val href = this.selectFirst("a.th")?.attr("href") ?: return null
        // Bỏ qua các mục quảng cáo
        if (href.contains("eunow4u")) return null
        val posterUrl = this.selectFirst("img.thumb")?.attr("data-original")?.let {
            if (it.startsWith("http")) it else "$mainUrl$it"
        }

        // Sử dụng constructor của AnimeSearchResponse
        return AnimeSearchResponse(
            title,
            href,
            this@Rule34VideoProvider.name,
            TvType.NSFW,
            posterUrl,
            null,
            null,
            quality = SearchQuality.HD
        )
    }
    
    // Hàm tìm kiếm
    override suspend fun search(query: String): List<SearchResponse> {
        val url = "$mainUrl/search/$query/"
        val document = app.get(url).document
        
        // Dùng mapNotNull để tự động lọc ra các kết quả null
        return document.select("div.thumbs div.item.thumb").mapNotNull {
            it.toSearchResult()
        }
    }

    // Load thông tin chi tiết và link video
    override suspend fun load(url: String): LoadResponse? {
        val document = app.get(url).document
        
        val title = document.selectFirst("h1.title_video")?.text()?.trim() ?: return null
        val poster = document.selectFirst("div.player-wrap div")?.attr("data-preview_url")
        val description = document.selectFirst("div.row > div.label > em")?.text()
        val tags = document.select("a.tag_item[href^=/tags/]").map { it.text() }
        
        // Sử dụng cấu trúc builder mới cho AnimeLoadResponse
        return newAnimeLoadResponse(title, url, TvType.NSFW) {
            this.posterUrl = poster
            this.plot = description
            this.tags = tags
            // loadLinks sẽ được gọi tự động để thêm link
        }
    }

    // Hàm loadLinks giờ đây là nơi chính để lấy và cung cấp link video
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        // Tải lại trang để lấy link
        val document = app.get(data).document
        
        document.select("div.row_spacer a.tag_item[href*=/get_file/]").forEach { element ->
            val videoUrl = element.attr("href")
            val qualityStr = element.text()
            val quality = qualityStr.substringAfter("MP4 ").trim().replace("p", "").toIntOrNull()

            callback.invoke(
                ExtractorLink(
                    source = this.name,
                    name = qualityStr, // Đặt tên là chất lượng, ví dụ "MP4 1080p"
                    url = videoUrl,
                    referer = mainUrl,
                    quality = quality ?: Qualities.Unknown.value,
                    type = ExtractorLinkType.VIDEO // Loại là VIDEO
                )
            )
        }
        return true
    }
}
