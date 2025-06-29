package recloudstream

// Import các thư viện cần thiết cho CloudStream
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Element
import com.fasterxml.jackson.annotation.JsonProperty

// Lớp chính của plugin, kế thừa từ MainAPI
class YouPornProvider : MainAPI() {
    // Tên sẽ hiển thị trong ứng dụng
    override var name = "YouPorn"
    // URL chính của trang web
    override var mainUrl = "https://www.youporn.com"
    // Các loại nội dung được hỗ trợ
    override var supportedTypes = setOf(TvType.NSFW)
    // Bật lại tính năng trang chủ
    override var hasMainPage = true

    // Thêm một bộ headers đầy đủ để mô phỏng trình duyệt, đây là chìa khóa!
    private val defaultHeaders = mapOf(
        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/108.0.0.0 Safari/537.36",
        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,image/apng,*/*;q=0.8,application/signed-exchange;v=b3;q=0.9",
        "Accept-Language" to "en-US,en;q=0.9",
        "Referer" to "$mainUrl/" // Thêm Referer mặc định
    )

    // Cấu trúc dữ liệu để phân tích JSON từ API video
    data class VideoStream(
        @JsonProperty("quality") val quality: String?,
        @JsonProperty("videoUrl") val videoUrl: String?
    )

    // Hàm xây dựng trang chủ của plugin
    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse? {
        // Yêu cầu getMainPage đầu tiên sẽ hoạt động như một "warm-up" để lấy cookie
        val document = app.get(mainUrl, headers = defaultHeaders).document
        val homePageList = mutableListOf<HomePageList>()

        document.select("div.section-title").forEach { sectionElement ->
            try {
                val title = sectionElement.selectFirst("h2")?.text()?.trim() ?: return@forEach
                val videoContainer = sectionElement.nextElementSibling()
                if (videoContainer?.hasClass("video-listing") == true) {
                    val videos = parseVideoList(videoContainer)
                    if (videos.isNotEmpty()) {
                        homePageList.add(HomePageList(title, videos))
                    }
                }
            } catch (e: Exception) {
                // Bỏ qua nếu có lỗi ở một section nào đó
            }
        }
        
        if (homePageList.isEmpty()) return null
        return HomePageResponse(homePageList)
    }

    // Hàm trợ giúp để phân tích danh sách video từ một khối HTML
    private fun parseVideoList(element: Element): List<MovieSearchResponse> {
        return element.select("div.video-box").mapNotNull { videoElement ->
            try {
                val link = videoElement.selectFirst("a") ?: return@mapNotNull null
                val href = fixUrl(link.attr("href"))
                if (href == mainUrl || !href.contains("/watch/")) return@mapNotNull null

                val title = videoElement.selectFirst("div.video-title")?.text()?.trim() ?: return@mapNotNull null
                val posterUrl = videoElement.selectFirst("img")?.attr("data-original")
                
                newMovieSearchResponse(title, href, TvType.NSFW) {
                    this.posterUrl = posterUrl
                }
            } catch (e: Exception) {
                null
            }
        }
    }

    // Hàm tìm kiếm
    override suspend fun search(query: String): List<SearchResponse> {
         return try {
            val url = "$mainUrl/search/?query=$query"
            // Các yêu cầu sau sẽ tự động sử dụng cookie đã được lấy từ yêu cầu trước đó
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
        
        return newMovieLoadResponse(title, url, TvType.NSFW, url) {
            this.posterUrl = poster
        }
    }

    // Hàm được gọi khi người dùng chọn một video để xem
    override suspend fun loadLinks(
        url: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        return try {
            // Sử dụng url làm referer cho yêu cầu này
            val pageHeaders = defaultHeaders + mapOf("Referer" to url)
            val videoPage = app.get(url, headers = pageHeaders).document
            val scriptContent = videoPage.select("script").find { it.data().contains("new Hls") }?.data() ?: return false
            val encryptedData = Regex("""new Hls\('([^']+)""").find(scriptContent)?.groupValues?.get(1) ?: return false
            val apiUrl = "$mainUrl/media/hls/?s=$encryptedData"
            val apiResponse = app.get(apiUrl, headers = pageHeaders).text
            val videoStreams = AppUtils.parseJson<List<VideoStream>>(apiResponse)

            videoStreams.forEach { stream ->
                val videoUrl = stream.videoUrl ?: return@forEach
                val qualityStr = stream.quality
                
                callback(
                    ExtractorLink(
                        source = this.name,
                        name = "${this.name} - ${qualityStr}p",
                        url = videoUrl,
                        referer = url, // Gửi referer của trang video
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
