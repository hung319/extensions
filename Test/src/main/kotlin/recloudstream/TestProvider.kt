package recloudstream

// Import các thư viện cần thiết cho CloudStream
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Element
import com.fasterxml.jackson.annotation.JsonProperty

// Lớp chính của plugin, kế thừa từ MainAPI
class YouPornProvider : MainAPI() {
    override var name = "YouPorn"
    override var mainUrl = "https://www.youporn.com"
    override var supportedTypes = setOf(TvType.NSFW)
    override var hasMainPage = true

    private val defaultHeaders = mapOf(
        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/108.0.0.0 Safari/537.36",
        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,image/apng,*/*;q=0.8,application/signed-exchange;v=b3;q=0.9",
        "Accept-Language" to "en-US,en;q=0.9",
        "Referer" to "$mainUrl/"
    )

    data class VideoStream(
        @JsonProperty("quality") val quality: String?,
        @JsonProperty("videoUrl") val videoUrl: String?
    )

    // Logic trang chủ đã hoạt động
    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse? {
        return try {
            val document = app.get(mainUrl, headers = defaultHeaders).document
            val videos = parseVideoList(document)
            if (videos.isEmpty()) return null
            HomePageResponse(listOf(HomePageList("Homepage", videos)))
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }
    
    // Logic phân tích danh sách video đã hoạt động
    private fun parseVideoList(element: Element): List<MovieSearchResponse> {
        val results = mutableListOf<SearchResponse>()
        element.select("a[href*=/watch/]").forEach { linkElement ->
            try {
                val href = fixUrl(linkElement.attr("href"))
                val container = linkElement.parent()?.parent() ?: return@forEach
                val title = (linkElement.attr("title").ifBlank { null }
                    ?: linkElement.text().ifBlank { null }
                    ?: container.selectFirst("*[class*=title]")?.text()
                    ?: "Untitled").trim()
                val posterUrl = container.selectFirst("img")?.attr("data-original")
                if (title != "Untitled" && !results.any { it.url == href }) {
                    results.add(newMovieSearchResponse(title, href, TvType.NSFW) {
                        this.posterUrl = posterUrl
                    })
                }
            } catch (e: Exception) { /* Bỏ qua lỗi item */ }
        }
        return results.filterIsInstance<MovieSearchResponse>()
    }

    // Hàm tìm kiếm đã hoạt động
    override suspend fun search(query: String): List<SearchResponse> {
        return try {
            val url = "$mainUrl/search/?query=$query"
            val document = app.get(url, headers = defaultHeaders).document
            parseVideoList(document)
        } catch (e: Exception) {
            e.printStackTrace()
            emptyList()
        }
    }

    // Hàm load chi tiết
    override suspend fun load(url: String): LoadResponse {
        val document = app.get(url, headers = defaultHeaders).document
        val title = document.selectFirst("h1.video-title")?.text()?.trim() ?: "Video"
        val poster = document.selectFirst("meta[property=og:image]")?.attr("content")
        val description = document.selectFirst("meta[name=description]")?.attr("content")
        return newMovieLoadResponse(title, url, TvType.NSFW, url) {
            this.posterUrl = poster
            this.plot = description
        }
    }

    // Hàm loadLinks đang bị chặn bởi trang web
    override suspend fun loadLinks(
        url: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        // Ghi chú: Logic dưới đây là đúng về mặt lý thuyết,
        // nhưng yêu cầu đến trang video đang bị máy chủ của YouPorn chặn hoặc trả về nội dung khác.
        // Vấn đề này nằm ngoài khả năng xử lý của plugin bằng các phương pháp thông thường.
        return try {
            val pageHeaders = defaultHeaders + mapOf("Referer" to mainUrl)
            val videoPage = app.get(url, headers = pageHeaders).document

            val keyRegex = Regex("""player_hls_source\s*=\s*['"](ey[a-zA-Z0-9=_\-]+)['"]""")
            var encryptedData: String? = null

            videoPage.select("script").forEach { script ->
                if (encryptedData == null && script.data().contains("player_hls_source")) {
                     encryptedData = keyRegex.find(script.data())?.groupValues?.get(1)
                }
            }
            
            if (encryptedData == null) return false

            val apiUrl = "$mainUrl/media/hls/?s=$encryptedData"
            val apiResponse = app.get(apiUrl, headers = pageHeaders).text
            val videoStreams = AppUtils.parseJson<List<VideoStream>>(apiResponse)

            if (videoStreams.isEmpty()) return false

            videoStreams.forEach { stream ->
                val videoUrl = stream.videoUrl ?: return@forEach
                val qualityStr = stream.quality
                callback(
                    ExtractorLink(
                        source = this.name,
                        name = "${this.name} - ${qualityStr}p",
                        url = videoUrl,
                        referer = url,
                        quality = qualityStr?.toIntOrNull() ?: Qualities.Unknown.value,
                        type = ExtractorLinkType.M3U8,
                    )
                )
            }
            true
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }
}
