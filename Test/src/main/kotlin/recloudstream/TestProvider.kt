package recloudstream

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

        // newHomePageResponse yêu cầu một đối tượng HomePageList
        return newHomePageResponse(
            list = HomePageList(
                name = request.name,
                list = home
            ),
            hasNext = true // Luôn có trang tiếp theo để cuộn
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

        // Sử dụng constructor của MovieSearchResponse vì đây là video lẻ
        return MovieSearchResponse(
            title,
            href,
            this@Rule34VideoProvider.name,
            posterUrl = posterUrl,
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
        val description = document.selectFirst("div.row > div.label > em")?.text()
        val tags = document.select("a.tag_item[href^=/tags/]").map { it.text() }
        
        // Sửa lỗi lấy Poster: Dùng regex để trích xuất 'preview_url' từ script
        val scriptContent = document.select("script").html()
        val posterUrl = Regex("""preview_url:\s*'(.*?)'""").find(scriptContent)?.groupValues?.get(1)

        // Lấy link từ các nút download và tạo danh sách ExtractorLink
        val links = document.select("div.row_spacer a.tag_item[href*=/get_file/]").mapNotNull {
            val videoUrl = it.attr("href")
            val qualityStr = it.text()
            val quality = qualityStr.substringAfter("MP4 ").trim().replace("p", "").toIntOrNull()

            ExtractorLink(
                source = this.name,
                name = qualityStr, // Đặt tên là chất lượng, ví dụ "MP4 1080p"
                url = videoUrl,
                referer = mainUrl,
                quality = quality ?: Qualities.Unknown.value,
                type = ExtractorLinkType.VIDEO // Loại là VIDEO
            )
        }
        
        // Sử dụng MovieLoadResponse vì đây là video đơn lẻ
        return MovieLoadResponse(
            name = title,
            url = url,
            apiName = this.name,
            dataUrl = url,
            posterUrl = posterUrl,
            plot = description,
            tags = tags,
            links = links // Truyền trực tiếp danh sách link vào đây
        )
    }
}
