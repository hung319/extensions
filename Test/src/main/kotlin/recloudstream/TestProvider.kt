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
    // Tạm thời vô hiệu hóa trang chủ để tập trung vào tìm kiếm
    override var hasMainPage = false

    // Thêm User-Agent của trình duyệt để tránh bị chặn
    private val defaultHeaders = mapOf(
        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/91.0.4472.124 Safari/537.36"
    )

    // Cấu trúc dữ liệu để phân tích JSON từ API video
    data class VideoStream(
        @JsonProperty("quality") val quality: String?,
        @JsonProperty("videoUrl") val videoUrl: String?
    )
    
    // Bỏ qua getMainPage

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

    // Hàm tìm kiếm - sử dụng logic phân tích đơn giản nhất
    override suspend fun search(query: String): List<SearchResponse> {
         return try {
            val url = "$mainUrl/search/?query=$query"
            val document = app.get(url, headers = defaultHeaders).document
            // Quét toàn bộ document thay vì một container cụ thể
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
            val videoPage = app.get(url, headers = defaultHeaders).document
            val scriptContent = videoPage.select("script").find { it.data().contains("new Hls") }?.data() ?: return false
            val encryptedData = Regex("""new Hls\('([^']+)""").find(scriptContent)?.groupValues?.get(1) ?: return false
            val apiUrl = "$mainUrl/media/hls/?s=$encryptedData"
            val apiResponse = app.get(apiUrl, referer = url, headers = defaultHeaders).text
            val videoStreams = AppUtils.parseJson<List<VideoStream>>(apiResponse)

            videoStreams.forEach { stream ->
                val videoUrl = stream.videoUrl ?: return@forEach
                val qualityStr = stream.quality
                
                callback(
                    ExtractorLink(
                        source = this.name,
                        name = "${this.name} - ${qualityStr}p",
                        url = videoUrl,
                        referer = mainUrl,
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
