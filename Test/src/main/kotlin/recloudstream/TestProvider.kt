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
        // Sử dụng coroutines để xử lý song song, tăng hiệu suất
        val home = document.select("div.thumbs div.item.thumb").apmap {
            it.toSearchResult()
        }
        return newHomePageResponse(request.name, home)
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

        // Sử dụng constructor của AnimeSearchResponse thay cho hàm cũ
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
        // Phân tích và trả về kết quả
        return document.select("div.thumbs div.item.thumb").apmap {
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
        val year = document.select("div.item_info span")
            .find { it.text().contains("ago") }
            ?.text()?.let { parseDate(it)?.year }

        // Lấy link từ các nút download
        val sources = document.select("div.row_spacer a.tag_item[href*=/get_file/]").mapNotNull {
            val videoUrl = it.attr("href")
            val qualityStr = it.text()
            val quality = qualityStr.substringAfter("MP4 ").trim().replace("p", "").toIntOrNull()

            // Sử dụng constructor của ExtractorLink
            ExtractorLink(
                source = this.name,
                name = qualityStr,
                url = videoUrl,
                referer = mainUrl,
                quality = quality ?: Qualities.Unknown.value,
                type = ExtractorLinkType.VIDEO
            )
        }
        
        // Sử dụng constructor của AnimeLoadResponse thay cho hàm cũ
        return AnimeLoadResponse(
            title,
            url,
            this.name,
            TvType.Anime, // Hoặc TvType.NSFW tùy bạn
            sources,
            posterUrl = poster,
            year = year,
            plot = description,
            tags = tags
        )
    }

    // Hàm loadLinks không còn cần thiết nếu đã xử lý trong load()
    // Tuy nhiên, để cho chắc chắn, ta vẫn có thể giữ nó.
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val document = app.get(data).document
        document.select("div.row_spacer a.tag_item[href*=/get_file/]").forEach { element ->
            val qualityStr = element.text()
            val videoUrl = element.attr("href")
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
