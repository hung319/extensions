package recloudstream

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
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

    // Hàm tải thông tin chi tiết của video và danh sách gợi ý
    override suspend fun load(url: String): LoadResponse {
        val document = app.get(url).document

        val title = document.selectFirst("h1.movtitl")?.text()?.trim()
            ?: throw RuntimeException("Không tìm thấy tiêu đề")
        
        val description = document.selectFirst("div.moreinfo")?.text()?.trim()
        val tags = document.select("div.vcatsp a").map { it.text() }

        val recommendations = document.selectFirst("div.sugg")?.let { suggBox ->
            parseHomepage(suggBox)
        } ?: emptyList()
        
        return newMovieLoadResponse(title, url, TvType.NSFW, url) {
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
        
        val hasNext = document.select("a.np:contains(Next)").isNotEmpty()
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

    /**
     * Hàm trích xuất link đã được viết lại hoàn toàn để gọi API ẩn của trang web,
     * mô phỏng chính xác cách trình duyệt lấy link video.
     */
    override suspend fun loadLinks(
        data: String, // URL của trang xem phim
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        // Lấy HTML của trang xem phim
        val document = app.get(data).document

        // Trích xuất các tham số cần thiết cho API call từ thẻ div#player
        val playerDiv = document.selectFirst("div#player") ?: return false
        val videoId = playerDiv.attr("data-id")
        val s = playerDiv.attr("data-s")
        val t = playerDiv.attr("data-t")

        if (videoId.isBlank() || s.isBlank() || t.isBlank()) return false

        // Xác định endpoint của API và dữ liệu để gửi đi
        val playerApiUrl = "$mainUrl/player/"
        val postData = mapOf("id" to videoId, "s" to s, "t" to t)

        // Thực hiện POST request đến API để lấy dữ liệu player
        val playerResponseText = app.post(
            playerApiUrl,
            data = postData,
            referer = data // Gửi referer là URL của trang phim
        ).text

        // Sử dụng regex để tìm tất cả các link .mp4 trong kết quả trả về
        val videoUrlRegex = Regex("""(https?://[^\s'"]+?(\d{3,4}p)\.mp4)""")
        
        videoUrlRegex.findAll(playerResponseText).forEach { match ->
            val url = match.groupValues[1]
            val qualityStr = match.groupValues[2]

            // Thêm link video đã tìm được vào CloudStream
            callback.invoke(
                ExtractorLink(
                    source = this.name,
                    name = "${this.name} $qualityStr",
                    url = url,
                    referer = mainUrl,
                    quality = Qualities.findFromName(qualityStr).value,
                    type = ExtractorLinkType.VIDEO
                )
            )
        }
        
        return true
    }
}
