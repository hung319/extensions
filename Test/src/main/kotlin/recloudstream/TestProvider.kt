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

    override val mainPage = mainPageOf(
        "/latest-updates/" to "Latest Updates",
        "/top-rated/" to "Top Rated",
        "/most-popular/" to "Most Popular",
    )

    override suspend fun search(query: String): List<SearchResponse> {
        val searchUrl = "$mainUrl/search/?q=$query"
        val document = app.get(searchUrl).document
        // Cấu trúc search mới: div#contmain chứa các div.video
        return parseHomepage(document.select("div#contmain div.video"))
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = if (page == 1) {
            "$mainUrl${request.data}"
        } else {
            // Phân trang có dạng /latest-updates/2/
            "$mainUrl${request.data}$page/"
        }

        val document = app.get(url).document
        val home = parseHomepage(document.select("div#contmain div.video"))
        
        // Kiểm tra nút "Next" trong pagination
        val hasNext = document.select("a.mpages:contains(Next)").isNotEmpty()
        return newHomePageResponse(request.name, home, hasNext)
    }

    override suspend fun load(url: String): LoadResponse {
        val document = app.get(url).document

        // Tiêu đề video nằm trong h1.movtitl
        val title = document.selectFirst("h1.movtitl")?.text()?.trim()
            ?: throw RuntimeException("Không tìm thấy tiêu đề")

        // Mô tả lấy từ thẻ meta description
        val description = document.selectFirst("meta[name=description]")?.attr("content")
        
        // Tags nằm trong div.vcatsp a
        val tags = document.select("div.vcatsp a.video_cat").map { it.text() }

        // Lấy videoId từ div#player để tạo poster chất lượng cao
        val playerDiv = document.selectFirst("div#player")
        val videoId = playerDiv?.attr("data-id")
        
        val posterUrl = if (videoId != null) {
            val videoIdLong = videoId.toLongOrNull() ?: 0
            val videoFolder = 1000 * (videoIdLong / 1000)
            "https://i.fapclub.vip/contents/videos_screenshots/$videoFolder/$videoId/preview.jpg"
        } else {
            document.selectFirst("link[rel=image_src]")?.attr("href")
        }

        // Related videos nằm sau h1#rell
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
        val document = app.get(data).document
        val playerDiv = document.selectFirst("div#player") ?: return false

        // Trích xuất dữ liệu từ data-q, data-id và data-n
        val qualityData = playerDiv.attr("data-q") ?: return false
        val videoId = playerDiv.attr("data-id")
        val serverSubdomain = playerDiv.attr("data-n") ?: "d1"

        val videoIdLong = videoId.toLongOrNull() ?: 0
        val videoFolder = 1000 * (videoIdLong / 1000)
        val videoPath = "$videoFolder/$videoId"

        val domain = "https://$serverSubdomain.vstor.top/"

        // Parse từng chất lượng từ chuỗi data-q
        qualityData.split(",").forEach { qualityBlock ->
            val parts = qualityBlock.split(";")
            if (parts.size >= 6) {
                val qualityLabel = parts[0] // Ví dụ: 1080p, 720p
                val timestamp = parts[4]
                val token = parts[5]

                // Theo logic KernelTeam: 720p là mặc định, các loại khác thêm suffix _{label}
                val qualityPrefix = if (qualityLabel == "720p") "" else "_$qualityLabel"
                val finalUrl = "${domain}whpvid/$timestamp/$token/$videoPath/${videoId}${qualityPrefix}.mp4"

                callback.invoke(
                    ExtractorLink(
                        source = this.name,
                        name = this.name,
                        url = finalUrl,
                        referer = mainUrl,
                        quality = getQualityFromName(qualityLabel)
                    )
                )
            }
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
