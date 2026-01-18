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

    // 1. QUAN TRỌNG: Giả lập Headers giống hệt lệnh Curl/Trình duyệt Android của bạn
    // Cloudstream sẽ tự động xử lý Cookie (cf_clearance) nếu request hợp lệ
    override val headers = mapOf(
        "User-Agent" to "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36",
        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,image/apng,*/*;q=0.8",
        "Accept-Language" to "vi-VN,vi;q=0.9",
        "Referer" to "$mainUrl/",
        "Sec-Fetch-Dest" to "document",
        "Sec-Fetch-Mode" to "navigate",
        "Sec-Fetch-Site" to "same-origin",
        "Upgrade-Insecure-Requests" to "1"
    )

    override val mainPage = mainPageOf(
        "/latest-updates/" to "Latest Updates",
        "/top-rated/" to "Top Rated",
        "/most-popular/" to "Most Popular",
    )

    override suspend fun search(query: String): List<SearchResponse> {
        val searchUrl = "$mainUrl/search/?q=$query"
        // Luôn truyền headers = this.headers
        val document = app.get(searchUrl, headers = this.headers).document
        return parseHomepage(document.select("div#contmain div.video"))
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = if (page == 1) {
            "$mainUrl${request.data}"
        } else {
            "$mainUrl${request.data}$page/"
        }

        val document = app.get(url, headers = this.headers).document
        val home = parseHomepage(document.select("div#contmain div.video"))
        
        val hasNext = document.select("a.mpages:contains(Next)").isNotEmpty()
        return newHomePageResponse(request.name, home, hasNext)
    }

    override suspend fun load(url: String): LoadResponse {
        val document = app.get(url, headers = this.headers).document

        val title = document.selectFirst("h1.movtitl")?.text()?.trim()
            ?: throw RuntimeException("Không tìm thấy tiêu đề")

        val description = document.selectFirst("meta[name=description]")?.attr("content")
        val tags = document.select("div.vcatsp a.video_cat").map { it.text() }

        val playerDiv = document.selectFirst("div#player")
        val videoId = playerDiv?.attr("data-id")
        
        val posterUrl = if (videoId != null) {
            val videoIdLong = videoId.toLongOrNull() ?: 0
            val videoFolder = 1000 * (videoIdLong / 1000)
            "https://i.fapclub.vip/contents/videos_screenshots/$videoFolder/$videoId/preview.jpg"
        } else {
            document.selectFirst("link[rel=image_src]")?.attr("href")
        }

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
        val document = app.get(data, headers = this.headers).document
        val playerDiv = document.selectFirst("div#player") ?: return false

        val qualityData = playerDiv.attr("data-q") ?: return false
        val videoId = playerDiv.attr("data-id")
        val serverSubdomain = playerDiv.attr("data-n") ?: "d1"

        val videoIdLong = videoId.toLongOrNull() ?: 0
        val videoFolder = 1000 * (videoIdLong / 1000)
        val videoPath = "$videoFolder/$videoId"

        val domain = "https://$serverSubdomain.vstor.top/"

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
            val inner = element.selectFirst("div.inner") ?: return@mapNotNull null
            // Selector chính xác dựa trên HTML bạn gửi: div.inner -> h2 -> a
            val linkElement = inner.selectFirst("h2 > a") ?: return@mapNotNull null
            
            val href = linkElement.attr("href")
            val title = linkElement.attr("title")
            // Selector ảnh: div.inner -> div.info -> a -> img
            val posterUrl = inner.selectFirst("div.info img")?.attr("src")

            newMovieSearchResponse(title, href, TvType.NSFW) {
                this.posterUrl = posterUrl
            }
        }
    }
}
