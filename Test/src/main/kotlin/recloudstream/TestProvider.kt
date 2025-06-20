package recloudstream // Thêm package recloudstream ở đầu file

// Thêm các import cần thiết
import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.LoadResponse.Companion.newAnimeLoadResponse
import com.lagradost.cloudstream3.SearchResponse.Companion.newAnimeSearchResponse
import org.jsoup.nodes.Element
import com.lagradost.cloudstream3.extractors.ExtractorLink // Đảm bảo import đúng
import com.lagradost.cloudstream3.network.CloudflareKiller // Có thể cần nếu trang web dùng Cloudflare

// Khai báo lớp Provider
open class Rule34VideoProvider : MainAPI() {
    override var mainUrl = "https://rule34video.com"
    override var name = "Rule34Video"
    override val hasMainPage = true
    override val hasChromecastSupport = true
    override val supportedTypes = setOf(TvType.NSFW)

    // Dữ liệu trang chủ
    override val mainPage = mainPageOf(
        "/latest-updates/" to "Newest Videos",
        "/most-popular/" to "Most Popular",
        "/top-rated/" to "Top Rated"
    )

    // Hàm lấy danh sách video từ trang chủ và tìm kiếm
    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = "$mainUrl${request.data}${page}/"
        val document = app.get(url).document
        val home = document.select("div.thumbs div.item.thumb").mapNotNull { it.toSearchResult() }
        return newHomePageResponse(request.name, home)
    }

    // Hàm tìm kiếm
    override suspend fun search(query: String): List<SearchResponse> {
        val url = "$mainUrl/search/$query/"
        val document = app.get(url).document
        val results = document.select("div.thumbs div.item.thumb").mapNotNull { it.toSearchResult() }
        return listOf(newAnimeSearchResponse(query, results.toMutableList(), TvType.Anime))
    }
    
    // Hàm chuyển đổi element HTML thành SearchResponse
    private fun Element.toSearchResult(): SearchResponse? {
        val title = this.selectFirst("div.thumb_title")?.text() ?: return null
        val href = this.selectFirst("a.th")?.attr("href") ?: return null
        // Loại bỏ các item quảng cáo
        if (href.contains("eunow4u")) return null
        val posterUrl = this.selectFirst("img.thumb")?.attr("data-original")
        
        return newAnimeSearchResponse(title, href, TvType.NSFW) {
            this.posterUrl = posterUrl
            this.quality = SearchQuality.HD
        }
    }

    // Hàm load thông tin chi tiết của video
    override suspend fun load(url: String): LoadResponse? {
        val document = app.get(url).document
        
        val title = document.selectFirst("h1.title_video")?.text()?.trim() ?: "No title"
        val poster = document.selectFirst("div.player-wrap div")?.attr("data-preview_url")
        val description = document.selectFirst("div.row > div.label > em")?.text()
        
        val tags = document.select("a.tag_item[href^=/tags/]").map { it.text() }

        // Lấy link download để làm nguồn phát video
        val sources = mutableListOf<ExtractorLink>()
        document.select("div.row_spacer a.tag_item[href*=/get_file/]").forEach { element ->
            val qualityStr = element.text()
            val videoUrl = element.attr("href")
            
            // Lấy chất lượng từ text (ví dụ: "MP4 1080p")
            val quality = qualityStr.substringAfter("MP4 ").trim().replace("p", "").toIntOrNull() ?: 0

            // Sử dụng cấu trúc ExtractorLink mới
            sources.add(
                ExtractorLink(
                    source = this.name,
                    name = "$name - $qualityStr",
                    url = videoUrl,
                    referer = mainUrl,
                    quality = quality,
                    type = ExtractorLinkType.VIDEO // Thêm loại extractor
                )
            )
        }
        
        return newAnimeLoadResponse(title, url, TvType.NSFW) {
            this.posterUrl = poster
            this.plot = description
            this.tags = tags
            addLinks(sources)
        }
    }
    
    // Hàm load link video để phát
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        // Tải lại trang để lấy link
        val document = app.get(data).document
        document.select("div.row_spacer a.tag_item[href*=/get_file/]").forEach { element ->
            val qualityStr = element.text()
            val videoUrl = element.attr("href")
            val quality = qualityStr.substringAfter("MP4 ").trim().replace("p", "").toIntOrNull() ?: 0

            // Sử dụng cấu trúc ExtractorLink mới
            callback.invoke(
                ExtractorLink(
                    source = this.name,
                    name = "$name - $qualityStr",
                    url = videoUrl,
                    referer = mainUrl,
                    quality = quality,
                    type = ExtractorLinkType.VIDEO // Thêm loại extractor
                )
            )
        }
        return true
    }
}
