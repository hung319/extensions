package recloudstream

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Element

class FapClubProvider : MainAPI() {
    override var mainUrl = "https://fapclub.vip"
    override var name = "FapClub"
    override val hasMainPage = true
    override var lang = "en"
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(TvType.NSFW)

    // Thêm Headers để tránh bị chặn bởi Cloudflare/Firewall
    private val headers = mapOf(
        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
    )

    override val mainPage = mainPageOf(
        "/latest-updates/" to "Latest Updates",
        "/top-rated/" to "Top Rated",
        "/most-popular/" to "Most Popular",
    )

    override suspend fun search(query: String): List<SearchResponse> {
        // Thêm headers vào request
        val searchUrl = "$mainUrl/search/?q=$query"
        val document = app.get(searchUrl, headers = headers).document
        
        // Selector: Lấy trực tiếp div.video để đảm bảo không bị miss do ID cha thay đổi
        return parseHomepage(document.select("div.video"))
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        // Xử lý URL phân trang
        val url = if (page == 1) {
            "$mainUrl${request.data}"
        } else {
            // Logic phân trang của site: /latest-updates/2/
            "$mainUrl${request.data}$page/"
        }

        // Thêm headers vào request
        val document = app.get(url, headers = headers).document
        
        // Lấy danh sách video (chỉ lấy trong content chính nếu cần, nhưng div.video khá an toàn)
        // Dùng filter để loại bỏ các div.video rác nếu có
        val home = parseHomepage(document.select("div#contmain div.video"))
        
        val hasNext = document.select("a.mpages:contains(Next)").isNotEmpty()
        return newHomePageResponse(request.name, home, hasNext)
    }

    override suspend fun load(url: String): LoadResponse {
        val document = app.get(url, headers = headers).document

        val title = document.selectFirst("h1.movtitl")?.text()?.trim()
            ?: throw RuntimeException("Không tìm thấy tiêu đề")

        val description = document.selectFirst("meta[name=description]")?.attr("content")
        val tags = document.select("div.vcatsp a.video_cat").map { it.text() }

        // Logic lấy poster HD từ data-id
        val playerDiv = document.selectFirst("div#player")
        val videoId = playerDiv?.attr("data-id")
        
        val posterUrl = if (videoId != null) {
            val videoIdLong = videoId.toLongOrNull() ?: 0
            val videoFolder = 1000 * (videoIdLong / 1000)
            "https://i.fapclub.vip/contents/videos_screenshots/$videoFolder/$videoId/preview.jpg"
        } else {
            document.selectFirst("link[rel=image_src]")?.attr("href")
        }

        // Related videos: Selector h1#rell ~ div.video để lấy các video ngay sau tiêu đề Related
        val recommendations = parseHomepage(document.select("h1#rell ~ div.video"))

        return newMovieLoadResponse(title, url, TvType.NSFW, url) {
            this.posterUrl = posterUrl
            this.plot = description
            this.tags = tags
            this.recommendations = recommendations
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val document = app.get(data, headers = headers).document
        val playerDiv = document.selectFirst("div#player") ?: return false

        val qualityData = playerDiv.attr("data-q") ?: return false
        val videoId = playerDiv.attr("data-id")
        val serverSubdomain = playerDiv.attr("data-n") ?: "d1"

        val videoIdLong = videoId.toLongOrNull() ?: 0
        val videoFolder = 1000 * (videoIdLong / 1000)
        val videoPath = "$videoFolder/$videoId"

        val domain = "https://$serverSubdomain.vstor.top/"

        // Parse từng chất lượng
        for (qualityBlock in qualityData.split(",")) {
            val parts = qualityBlock.split(";")
            if (parts.size < 6) continue

            val qualityLabel = parts[0]
            val timestamp = parts[4]
            val token = parts[5]

            val qualityPrefix = if (qualityLabel == "720p") "" else "_$qualityLabel"
            val finalUrl = "${domain}whpvid/$timestamp/$token/$videoPath/${videoId}${qualityPrefix}.mp4"

            val link = newExtractorLink(
                source = this.name,
                name = this.name,
                url = finalUrl,
                type = ExtractorLinkType.VIDEO
            ) {
                this.referer = mainUrl
                this.quality = getQualityFromName(qualityLabel)
            }
            callback.invoke(link)
        }
        return true
    }

    private fun parseHomepage(elements: List<Element>): List<MovieSearchResponse> {
        return elements.mapNotNull { element ->
            // Kiểm tra kỹ cấu trúc: div.video -> div.inner -> h2 -> a
            val inner = element.selectFirst("div.inner") ?: return@mapNotNull null
            val linkElement = inner.selectFirst("h2 > a") ?: return@mapNotNull null
            
            val href = linkElement.attr("href")
            val title = linkElement.attr("title")
            // Ảnh thumbnail
            val posterUrl = inner.selectFirst("div.info img")?.attr("src")

            newMovieSearchResponse(title, href, TvType.NSFW) {
                this.posterUrl = posterUrl
            }
        }
    }
}
