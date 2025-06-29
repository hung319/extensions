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
    // Tắt trang chủ, chỉ tập trung vào tìm kiếm
    override var hasMainPage = false

    private val defaultHeaders = mapOf(
        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/108.0.0.0 Safari/537.36",
        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,image/apng,*/*;q=0.8,application/signed-exchange;v=b3;q=0.9",
        "Accept-Language" to "en-US,en;q=0.9",
    )

    // Cấu trúc dữ liệu cho API video
    data class VideoStream(
        @JsonProperty("quality") val quality: String?,
        @JsonProperty("videoUrl") val videoUrl: String?
    )

    // Hàm tìm kiếm với logic hoàn toàn mới
    override suspend fun search(query: String): List<SearchResponse> {
        return try {
            val url = "$mainUrl/search/?query=$query"
            val document = app.get(url, headers = defaultHeaders).document
            val searchResults = mutableListOf<SearchResponse>()

            // Logic mới: Tìm tất cả các thẻ <a> có link video
            document.select("a[href*=/watch/]").forEach { linkElement ->
                try {
                    val href = fixUrl(linkElement.attr("href"))
                    // Tìm phần tử cha chung chứa cả link, ảnh và tiêu đề
                    // Giả sử chúng đều nằm trong một container chung, đi ngược lên 2 cấp
                    val container = linkElement.parent()?.parent() ?: return@forEach

                    // Từ container, tìm tiêu đề và ảnh
                    // Ưu tiên tìm tiêu đề trong chính thẻ <a> trước
                    val title = (linkElement.attr("title").ifBlank { null }
                        ?: linkElement.text().ifBlank { null }
                        ?: container.selectFirst("*[class*=title]")?.text()
                        ?: "Untitled").trim()
                    
                    val posterUrl = container.selectFirst("img")?.attr("data-original")

                    // Chỉ thêm vào danh sách nếu có tiêu đề hợp lệ
                    if (title != "Untitled" && !searchResults.any { it.url == href }) {
                        searchResults.add(newMovieSearchResponse(title, href, TvType.NSFW) {
                            this.posterUrl = posterUrl
                        })
                    }
                } catch (e: Exception) {
                    // Bỏ qua nếu có lỗi với một item
                }
            }
            searchResults
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

    // Hàm load link video
    override suspend fun loadLinks(
        url: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        return try {
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
