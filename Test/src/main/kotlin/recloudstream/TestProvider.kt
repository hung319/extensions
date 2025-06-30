package recloudstream // Đã thêm package theo yêu cầu

// Import các thư viện cần thiết của CloudStream và Jsoup
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Element
import org.json.JSONObject

// Lớp Provider chính, kế thừa từ MainAPI
class SpankbangProvider : MainAPI() {
    // Thông tin cơ bản về provider
    override var mainUrl = "https://spankbang.party"
    override var name = "SpankBang"
    override var lang = "en"
    override val hasMainPage = true
    // Xác định loại nội dung được hỗ trợ
    override val supportedTypes = setOf(
        TvType.NSFW
    )

    // Hàm tạo các mục trên trang chủ
    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val document = app.get(mainUrl).document
        val homePageList = mutableListOf<HomePageList>()

        // Lấy các mục từ thanh điều hướng chính
        document.select("div.video-list[data-testid=video-list]").forEach { list ->
            val title = list.parent()?.selectFirst("h2")?.text() ?: "Trending"
            val videos = list.select("div.video-item").mapNotNull { element ->
                val linkElement = element.selectFirst("a.thumb")
                val videoTitle = linkElement?.attr("title")?.trim()
                val href = linkElement?.attr("href")
                val posterUrl = element.selectFirst("img.cover")?.attr("data-src")

                if (videoTitle != null && href != null && posterUrl != null) {
                    MovieSearchResponse(
                        name = videoTitle,
                        url = fixUrl(href),
                        apiName = this.name,
                        type = TvType.Movie,
                        posterUrl = posterUrl
                    )
                } else {
                    null
                }
            }
            if (videos.isNotEmpty()) {
                homePageList.add(HomePageList(title, videos))
            }
        }
        return HomePageResponse(homePageList)
    }
    
    // Hàm phân tích HTML để lấy danh sách phim
    private fun parseVideoItems(html: String): List<SearchResponse> {
        val document = app.parseHTML(html)
        val videoItems = document.select("div.video-item")

        return videoItems.mapNotNull { element ->
            val linkElement = element.selectFirst("a.thumb")
            val title = linkElement?.attr("title")?.trim()
            val href = linkElement?.attr("href")
            // Sử dụng data-src cho hình ảnh lazy-loaded
            val posterUrl = element.selectFirst("img.cover")?.attr("data-src")

            if (title != null && href != null && posterUrl != null) {
                MovieSearchResponse(
                    name = title,
                    url = fixUrl(href),
                    apiName = this.name,
                    type = TvType.Movie,
                    posterUrl = posterUrl
                )
            } else {
                null
            }
        }
    }

    // Hàm xử lý tìm kiếm
    override suspend fun search(query: String): List<SearchResponse> {
        val searchUrl = "$mainUrl/s/${query.replace(" ", "+")}/"
        val response = app.get(searchUrl)
        return parseVideoItems(response.text)
    }

    // Hàm tải chi tiết phim (ở đây ta chỉ cần tiêu đề và url nên không cần load gì thêm)
    override suspend fun load(url: String): LoadResponse {
        val response = app.get(url).document
        val title = response.selectFirst("h1.main_content_title")?.text()?.trim()
            ?: response.selectFirst("meta[property=og:title]")?.attr("content")?.trim()
            ?: "Video"
        
        return newMovieLoadResponse(
            name = title,
            url = url,
            type = TvType.NSFW,
            // Các thông tin khác có thể được thêm ở đây nếu cần
        )
    }

    // ⭐ HÀM CẬP NHẬT: Sử dụng cấu trúc ExtractorLink mới
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val response = app.get(data)
        val document = response.text

        // Sử dụng regex để tìm và trích xuất đối tượng JSON 'stream_data'
        val streamDataRegex = Regex("""var stream_data = (\{.*?\});""")
        val matchResult = streamDataRegex.find(document)

        if (matchResult != null) {
            val streamDataJson = matchResult.groupValues[1]
            val streamData = JSONObject(streamDataJson)

            // Ưu tiên link HLS (m3u8) để có chất lượng thích ứng
            streamData.optJSONArray("m3u8")?.let {
                if (it.length() > 0) {
                    val m3u8Url = it.getString(0)
                    callback(
                        ExtractorLink(
                            source = this.name,
                            name = "HLS (Auto)",
                            url = m3u8Url,
                            referer = mainUrl,
                            quality = Qualities.Unknown.value,
                            type = ExtractorLinkType.M3U8 // <-- Cấu trúc mới
                        )
                    )
                }
            }

            // Thêm các link MP4 với chất lượng cụ thể
            val qualities = listOf("1080p", "720p", "480p", "240p")
            qualities.forEach { quality ->
                streamData.optJSONArray(quality)?.let {
                    if (it.length() > 0) {
                        val mp4Url = it.getString(0)
                        callback(
                            ExtractorLink(
                                source = this.name,
                                name = "MP4 $quality",
                                url = mp4Url,
                                referer = mainUrl,
                                quality = quality.replace("p", "").toIntOrNull() ?: Qualities.Unknown.value, // Chuyển "1080p" thành 1080
                                type = ExtractorLinkType.VIDEO // <-- Cấu trúc mới
                            )
                        )
                    }
                }
            }
        }

        return true
    }
}
