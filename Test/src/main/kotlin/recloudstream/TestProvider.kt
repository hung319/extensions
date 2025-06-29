package recloudstream // Thêm package theo yêu cầu

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
    override var supportedTypes = setOf(TvType.Movie)
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
            val title = sectionElement.selectFirst("h2")?.text()?.trim() ?: return@forEach
            // Tìm danh sách video ngay sau tiêu đề
            val videoContainer = sectionElement.nextElementSibling()
            if (videoContainer != null) {
                val videos = parsePage(videoContainer)
                if(videos.isNotEmpty()) {
                    homePageList.add(HomePageList(title, videos))
                }
            }
        }
        
        if (homePageList.isEmpty()) return null
        return HomePageResponse(homePageList)
    }

    // Hàm trợ giúp để phân tích và trích xuất video từ một khối HTML
    private fun parsePage(element: Element): List<MovieSearchResponse> {
        // Tìm tất cả các phần tử có class 'video-box'
        return element.select("div.video-box").mapNotNull { videoElement ->
            val link = videoElement.selectFirst("a") ?: return@mapNotNull null
            val href = fixUrl(link.attr("href"))
            val title = videoElement.selectFirst("div.video-title")?.text()?.trim() ?: return@mapNotNull null
            val posterUrl = videoElement.selectFirst("img")?.attr("data-original")
            
            // Tạo đối tượng phim mới
            newMovieSearchResponse(title, href, TvType.Movie) {
                this.posterUrl = posterUrl
            }
        }
    }

    // Hàm tìm kiếm
    override suspend fun search(query: String): List<SearchResponse> {
        val url = "$mainUrl/search/?query=$query"
        val document = app.get(url).document
        // Sử dụng lại hàm parsePage để xử lý kết quả
        return parsePage(document)
    }
    
    // Hàm load giờ sẽ không cần thiết vì đã có getMainPage và search
    // Nhưng chúng ta giữ lại để tương thích nếu cần
    override suspend fun load(url: String): LoadResponse {
        val document = app.get(url).document
        val results = parsePage(document)
        return newMovieLoadResponse(this.name, url, TvType.Movie, results)
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
            if (stream.videoUrl != null && stream.quality != null) {
                callback(
                    ExtractorLink(
                        source = this.name,
                        name = "${this.name} - ${stream.quality}p",
                        url = stream.videoUrl,
                        referer = mainUrl,
                        quality = Qualities.P${stream.quality}.value,
                        isM3u8 = true // Thêm dòng này để báo cho trình phát đây là link HLS
                    )
                )
            }
        }
        
        return true
    }
}
