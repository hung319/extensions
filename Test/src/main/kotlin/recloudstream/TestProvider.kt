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
    // Báo cho CloudStream biết plugin có trang chủ
    override var hasMainPage = true

    // Cấu trúc dữ liệu để phân tích JSON từ API video
    data class VideoStream(
        @JsonProperty("quality") val quality: String?,
        @JsonProperty("videoUrl") val videoUrl: String?
    )

    // Hàm xây dựng trang chủ của plugin
    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse? {
        val document = app.get(mainUrl).document
        val homePageList = mutableListOf<HomePageList>()

        // Tìm tất cả các khối nội dung trên trang chủ
        document.select("div.section-title").forEach { sectionElement ->
            try {
                val title = sectionElement.selectFirst("h2")?.text()?.trim() ?: return@forEach
                // Tìm danh sách video ngay sau tiêu đề, đảm bảo đó là video-listing
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

    // Hàm trợ giúp mới để phân tích danh sách video từ một khối HTML
    private fun parseVideoList(element: Element): List<MovieSearchResponse> {
        return element.select("div.video-box").mapNotNull { videoElement ->
            try {
                val link = videoElement.selectFirst("a") ?: return@mapNotNull null
                val href = fixUrl(link.attr("href"))
                // Bỏ qua nếu không có link hợp lệ
                if (href == mainUrl) return@mapNotNull null

                val title = videoElement.selectFirst("div.video-title")?.text()?.trim() ?: return@mapNotNull null
                val posterUrl = videoElement.selectFirst("img")?.attr("data-original")
                
                // Tạo đối tượng phim mới
                newMovieSearchResponse(title, href, TvType.NSFW) {
                    this.posterUrl = posterUrl
                }
            } catch (e: Exception) {
                null // Trả về null nếu có lỗi khi phân tích một video
            }
        }
    }

    // Hàm tìm kiếm
    override suspend fun search(query: String): List<SearchResponse> {
        val url = "$mainUrl/search/?query=$query"
        val document = app.get(url).document
        
        // Sử dụng selector cụ thể cho trang tìm kiếm
        val searchResultContainer = document.selectFirst("#video-listing-search")
        
        return if (searchResultContainer != null) {
            parseVideoList(searchResultContainer)
        } else {
            emptyList()
        }
    }
    
    // Hàm load chi tiết
    override suspend fun load(url: String): LoadResponse {
        val document = app.get(url).document
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
        val videoPage = app.get(url).document
        val scriptContent = videoPage.select("script").find { it.data().contains("new Hls") }?.data() ?: return false
        val encryptedData = Regex("""new Hls\('([^']+)""").find(scriptContent)?.groupValues?.get(1) ?: return false
        val apiUrl = "$mainUrl/media/hls/?s=$encryptedData"
        val apiResponse = app.get(apiUrl, referer = url).text
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
        
        return true
    }
}
