package recloudstream

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import com.lagradost.cloudstream3.utils.Qualities

// Định nghĩa lớp Provider chính
class FapClubProvider : MainAPI() {
    // Thông tin cơ bản về provider
    override var mainUrl = "https://fapclub.vip"
    override var name = "FapClub"
    override val hasMainPage = true
    override var lang = "en"
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(
        TvType.NSFW
    )

    // Các mục sẽ hiển thị trên trang chính của plugin
    override val mainPage = mainPageOf(
        "/latest-updates/" to "Latest Updates",
        "/top-rated/" to "Top Rated",
        "/most-popular/" to "Most Popular",
    )

    // Hàm tìm kiếm video
    override suspend fun search(query: String): List<SearchResponse> {
        val searchUrl = "$mainUrl/search/?q=$query"
        val document = app.get(searchUrl).document
        return parseHomepage(document)
    }

    /**
     * Hàm tải thông tin chi tiết của video và danh sách gợi ý.
     */
    override suspend fun load(url: String): LoadResponse {
        val document = app.get(url).document

        val title = document.selectFirst("h1.movtitl")?.text()?.trim()
            ?: throw RuntimeException("Không tìm thấy tiêu đề")
        
        val description = document.selectFirst("div.moreinfo")?.text()?.trim()
        val tags = document.select("div.vcatsp a").map { it.text() }

        // SỬA LỖI: Lấy poster chất lượng cao từ logic của player
        val videoId = document.selectFirst("div#player")?.attr("data-id")
        val posterUrl = if (videoId != null) {
            val videoIdLong = videoId.toLongOrNull() ?: 0
            val videoFolder = 1000 * (videoIdLong / 1000)
            "https://i.fapclub.vip/contents/videos_screenshots/$videoFolder/$videoId/preview.jpg"
        } else {
            // Fallback to og:image if data-id is not found
            document.selectFirst("meta[property=og:image]")?.attr("content")
        }

        // GIỚI HẠN: RCM list có thể không hoạt động do được tải bằng JS
        val recommendations = document.selectFirst("div.sugg")?.let { suggBox ->
            parseHomepage(suggBox)
        } ?: emptyList()
        
        return newMovieLoadResponse(title, url, TvType.NSFW, url) {
            this.posterUrl = posterUrl // Gán lại poster đã được lấy
            this.plot = description
            this.tags = tags
            this.recommendations = recommendations
        }
    }

    // Hàm tải trang chính và xử lý phân trang
    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = if (page == 1) {
            "$mainUrl${request.data}"
        } else {
            "$mainUrl${request.data}$page/" 
        }

        val document = app.get(url).document
        val home = parseHomepage(document)
        
        // SỬA LỖI: Cập nhật lại selector cho nút "Next"
        val hasNext = document.select("a.pbutton:contains(Next)").isNotEmpty()
        return newHomePageResponse(request.name, home, hasNext)
    }

    // Hàm helper chung để phân tích và trích xuất danh sách video
    private fun parseHomepage(document: Element): List<MovieSearchResponse> {
        return document.select("div.video").mapNotNull { element ->
            val inner = element.selectFirst("div.inner") ?: return@mapNotNull null
            val linkElement = inner.selectFirst("h2 > a")
            val href = linkElement?.attr("href") ?: return@mapNotNull null
            val title = linkElement.attr("title")
            val posterUrl = inner.selectFirst("div.info > a > img")?.attr("src")

            newMovieSearchResponse(title, href, TvType.NSFW) {
                this.posterUrl = posterUrl
            }
        }
    }

    // Hàm loadLinks dựa trên việc giải mã KernelTeamp.js
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val document = app.get(data).document
        val playerDiv = document.selectFirst("div#player") ?: return false

        val qualityData = playerDiv.attr("data-q")
        val videoId = playerDiv.attr("data-id")
        val serverSubdomain = playerDiv.attr("data-n")

        val videoIdLong = videoId.toLongOrNull() ?: 0
        val videoFolder = 1000 * (videoIdLong / 1000)
        val videoPath = "$videoFolder/$videoId"

        val domain = "https://$serverSubdomain.vstor.top/"
        
        qualityData.split(",").forEach { qualityBlock ->
            val parts = qualityBlock.split(";")
            if (parts.size < 6) return@forEach

            val qualityLabel = parts[0]
            val timestamp = parts[4]
            val token = parts[5]

            val qualityPrefix = if (qualityLabel == "720p") "" else "_$qualityLabel"
            
            val finalUrl = "${domain}whpvid/$timestamp/$token/$videoPath/${videoId}${qualityPrefix}.mp4"

            callback.invoke(
                ExtractorLink(
                    source = this.name,
                    name = "${this.name} $qualityLabel",
                    url = finalUrl,
                    referer = mainUrl,
                    quality = qualityLabel.replace("p", "").toIntOrNull() ?: Qualities.Unknown.value,
                    type = ExtractorLinkType.VIDEO
                )
            )
        }
        
        return true
    }
}
