package recloudstream // Package đã được thay đổi theo yêu cầu

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Element

// Khai báo lớp provider
class Kurakura21Provider : MainAPI() {
    // Thông tin cơ bản về provider
    override var mainUrl = "https://kurakura21.net"
    override var name = "Kurakura21"
    override val hasMainPage = true
    override var lang = "id" // Ngôn ngữ Indonesia
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(
        TvType.Movie,
        TvType.TvSeries
    )

    // Hàm hỗ trợ để lấy URL đầy đủ
    private fun String.toFullUrl(): String {
        return if (this.startsWith("http")) this else "$mainUrl$this"
    }

    // Hàm để phân tích kết quả tìm kiếm và danh sách phim
    private fun Element.toSearchResult(): SearchResponse? {
        val title = this.selectFirst("h2.entry-title a")?.text() ?: return null
        val href = this.selectFirst("a")?.attr("href") ?: return null
        val posterUrl = this.selectFirst("img")?.attr("data-src")

        return newMovieSearchResponse(title, href) {
            this.posterUrl = posterUrl
            // Thêm tag chất lượng nếu có
            this.quality =
                this.selectFirst(".gmr-quality-item a")?.text()?.let {
                    getQualityFromString(it)
                }
        }
    }
    
    // --- Phân tích trang chủ ---
    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val document = app.get(mainUrl).document
        val homePageList = mutableListOf<HomePageList>()

        // Phân tích tất cả các khu vực phim trên trang chủ
        document.select("div.home-widget").forEach { block ->
            val header = block.selectFirst("h3.homemodule-title")?.text() ?: return@forEach
            val movies = block.select("article, div.gmr-item-modulepost").mapNotNull {
                it.toSearchResult()
            }
            if (movies.isNotEmpty()) {
                homePageList.add(HomePageList(header, movies))
            }
        }
        
        // Phân tích khu vực "Latest Movie" (Phim mới nhất) ở dưới cùng
        val latestMoviesHeader = document.selectFirst("#primary h3.homemodule-title")?.text() ?: "Latest Movies"
        val latestMovies = document.select("#gmr-main-load article.item-infinite").mapNotNull {
            it.toSearchResult()
        }
        if(latestMovies.isNotEmpty()) {
            homePageList.add(HomePageList(latestMoviesHeader, latestMovies))
        }

        return HomePageResponse(homePageList)
    }

    // --- Chức năng tìm kiếm ---
    override suspend fun search(query: String): List<SearchResponse> {
        val searchUrl = "$mainUrl/?s=$query"
        val document = app.get(searchUrl).document

        return document.select("article.item-infinite").mapNotNull {
            it.toSearchResult()
        }
    }

    // --- Tải dữ liệu phim/series ---
    override suspend fun load(url: String): LoadResponse {
        val document = app.get(url).document
        val title = document.selectFirst("h1.entry-title")?.text()?.trim() ?: "No Title"
        val poster = document.selectFirst("figure.pull-left img")?.attr("data-src")
        val description = document.selectFirst("div.entry-content")?.text()?.trim()
        val year = document.select("div.gmr-moviedata").toString().let {
            Regex("""(\d{4})""").find(it)?.groupValues?.get(1)?.toIntOrNull()
        }
        
        // ID của bài đăng (post ID) cần thiết cho lời gọi AJAX để lấy máy chủ video
        val postId = document.body().attr("class").let {
            Regex("postid-(\\d+)").find(it)?.groupValues?.get(1)
        } ?: throw ErrorLoadingException("Failed to get post ID")

        // Tìm các tab máy chủ
        val servers = document.select("ul.muvipro-player-tabs li a")

        val episodes = servers.mapIndexed { index, server ->
            val serverName = server.text()
            val embedUrl = "$mainUrl/wp-admin/admin-ajax.php" // Điểm cuối của AJAX
            val postData = mapOf(
                "action" to "muvipro_player_content",
                "post_id" to postId,
                "player" to (index + 1).toString() // Chỉ số player bắt đầu từ 1
            )

            // Tạo một tập phim với một callback để tải liên kết sau
            Episode(
                data = embedUrl,
                name = serverName,
                // Truyền postData như một phần của dữ liệu Episode
                extra = mapOf("postData" to postData)
            )
        }

        return newMovieLoadResponse(title, url, TvType.Movie, episodes) {
            this.posterUrl = poster
            this.year = year
            this.plot = description
        }
    }

    // --- Trích xuất liên kết video từ các trang nhúng ---
    override suspend fun loadLinks(
        data: String, // Đây sẽ là URL của AJAX
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        // Lấy postData từ bản đồ extra của Episode
        val postData = (AppUtils.parseJson<Map<String, Any>>(data)["extra"] as? Map<*, *>)
            ?.get("postData") as? Map<String, String>
            ?: throw ErrorLoadingException("Failed to get post data for AJAX call")

        // Thực hiện lời gọi AJAX để lấy iframe
        val ajaxResponse = app.post(data, data = postData).document
        val iframeSrc = ajaxResponse.selectFirst("iframe")?.attr("src")
            ?: throw ErrorLoadingException("Failed to find iframe source")

        // Sử dụng trình trích xuất tích hợp để xử lý nguồn iframe
        // Trình trích xuất này có thể tự động xử lý nhiều máy chủ video phổ biến
        return loadExtractor(iframeSrc, subtitleCallback, callback)
    }
}
