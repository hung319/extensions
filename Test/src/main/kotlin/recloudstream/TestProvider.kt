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
        if (href.contains("eunow4u")) return null // Bỏ qua quảng cáo
        val posterUrl = this.selectFirst("img.thumb")?.attr("data-original")?.let {
            if (it.startsWith("http")) it else "$mainUrl$it"
        }

        // Sử dụng MovieSearchResponse cho video lẻ
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
        
        return document.select("div.thumbs div.item.thumb").mapNotNull {
            it.toSearchResult()
        }
    }

    // --- SỬA LỖI QUAN TRỌNG TẠI ĐÂY ---

    // Hàm load() CHỈ lấy metadata (tên, poster, mô tả,...)
    override suspend fun load(url: String): LoadResponse? {
        val document = app.get(url).document
        
        val title = document.selectFirst("h1.title_video")?.text()?.trim() ?: return null
        val description = document.selectFirst("div.row > div.label > em")?.text()
        val tags = document.select("a.tag_item[href^=/tags/]").map { it.text() }
        
        val scriptContent = document.select("script").html()
        val posterUrl = Regex("""preview_url:\s*'(.*?)'""").find(scriptContent)?.groupValues?.get(1)

        // Sử dụng builder của `newMovieLoadResponse` để trả về metadata.
        // KHÔNG thêm link video ở đây. Framework sẽ tự gọi loadLinks() sau.
        return newMovieLoadResponse(title, url, TvType.Movie, url) {
            this.posterUrl = posterUrl
            this.plot = description
            this.tags = tags
        }
    }

    // Hàm loadLinks() CHỈ lấy link video và callback
    override suspend fun loadLinks(
        data: String, // `data` ở đây chính là `url` được truyền từ `load()`
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        // Tải lại trang (hoặc lấy từ cache nếu có) để lấy link
        val document = app.get(data).document
        
        document.select("div.row_spacer a.tag_item[href*=/get_file/]").forEach { element ->
            val videoUrl = element.attr("href")
            val qualityStr = element.text()
            val quality = qualityStr.substringAfter("MP4 ").trim().replace("p", "").toIntOrNull()

            callback.invoke(
                ExtractorLink(
                    source = this.name,
                    name = qualityStr,
                    url = videoUrl,
                    referer = mainUrl,
                    quality = quality ?: Qualities.Unknown.value,
                    type = ExtractorLinkType.VIDEO
                )
            )
        }
        return true
    }
}
