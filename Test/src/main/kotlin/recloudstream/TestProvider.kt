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

    // Cập nhật Headers Y HỆT như lệnh Curl bạn cung cấp
    // Lưu ý: "Cookie" ở đây chỉ set những cái tĩnh như tst_pass để qua mặt script check.
    // cf_clearance sẽ được App tự động thêm vào sau khi bạn mở WebView 1 lần.
    private val customHeaders = mapOf(
        "Authority" to "fapclub.vip",
        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,image/apng,*/*;q=0.8,application/signed-exchange;v=b3;q=0.7",
        "Accept-Language" to "vi-VN,vi;q=0.9",
        "Cache-Control" to "max-age=0",
        // QUAN TRỌNG: Gửi kèm cookie tst_pass=1 để bypass màn hình "Checking your browser..."
        "Cookie" to "tst_pass=1; device_verified=true", 
        "Sec-Ch-Ua" to "\"Chromium\";v=\"137\", \"Not/A)Brand\";v=\"24\"",
        "Sec-Ch-Ua-Mobile" to "?1",
        "Sec-Ch-Ua-Platform" to "\"Android\"",
        "Sec-Fetch-Dest" to "document",
        "Sec-Fetch-Mode" to "navigate",
        "Sec-Fetch-Site" to "none",
        "Upgrade-Insecure-Requests" to "1",
        "User-Agent" to "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/137.0.0.0 Mobile Safari/537.36"
    )

    override val mainPage = mainPageOf(
        "/latest-updates/" to "Latest Updates",
        "/top-rated/" to "Top Rated",
        "/most-popular/" to "Most Popular",
    )

    override suspend fun search(query: String): List<SearchResponse> {
        val searchUrl = "$mainUrl/search/?q=$query"
        val document = app.get(searchUrl, headers = customHeaders).document
        return parseHomepage(document.select("div#contmain div.video"))
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = if (page == 1) {
            "$mainUrl${request.data}"
        } else {
            "$mainUrl${request.data}$page/"
        }

        val document = app.get(url, headers = customHeaders).document
        
        // Check xem có bị dính màn hình Security Check không
        if (document.title().contains("Security Check") || document.select("div#contmain").isEmpty()) {
            // Nếu vẫn bị chặn, ném lỗi để người dùng biết cần mở WebView
            throw ErrorLoadingException("Bị chặn bởi Cloudflare. Hãy bấm 'Open in WebView' để xác thực.")
        }

        val home = parseHomepage(document.select("div#contmain div.video"))
        val hasNext = document.select("a.mpages:contains(Next)").isNotEmpty()
        return newHomePageResponse(request.name, home, hasNext)
    }

    override suspend fun load(url: String): LoadResponse {
        val document = app.get(url, headers = customHeaders).document

        // Check lỗi Security Check
        if (document.title().contains("Security Check")) {
             throw ErrorLoadingException("Bị chặn bởi Cloudflare. Hãy bấm 'Open in WebView'.")
        }

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
        val document = app.get(data, headers = customHeaders).document
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
            val linkElement = inner.selectFirst("h2 > a") ?: return@mapNotNull null
            
            val href = linkElement.attr("href")
            val title = linkElement.attr("title")
            val posterUrl = inner.selectFirst("div.info img")?.attr("src")

            newMovieSearchResponse(title, href, TvType.NSFW) {
                this.posterUrl = posterUrl
            }
        }
    }
}
