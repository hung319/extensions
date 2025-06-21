package recloudstream

import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import android.util.Log

// ================================================================
// --- DATA CLASSES (Giữ nguyên) ---
// ================================================================
data class SearchItem(
    @JsonProperty("name") val name: String?,
    @JsonProperty("slug") val slug: String?,
    @JsonProperty("poster_url") val posterUrl: String?,
    @JsonProperty("thumb_url") val thumbUrl: String?,
    @JsonProperty("total_episodes") val totalEpisodes: Int?,
    @JsonProperty("current_episode") val currentEpisode: String?
)

data class SearchApiResponse(
    @JsonProperty("status") val status: String?,
    @JsonProperty("items") val items: List<SearchItem>?
)

data class EpisodeItem(
    @JsonProperty("name") val name: String?,
    @JsonProperty("slug") val slug: String?,
    @JsonProperty("embed") val embed: String?,
    @JsonProperty("m3u8") val m3u8: String?
)

data class ServerItem(
    @JsonProperty("server_name") val serverName: String?,
    @JsonProperty("items") val items: List<EpisodeItem>?
)

data class MovieDetails(
    @JsonProperty("name") val name: String?,
    @JsonProperty("original_name") val originName: String?,
    @JsonProperty("thumb_url") val thumbUrl: String?,
    @JsonProperty("poster_url") val posterUrl: String?,
    @JsonProperty("content") val plot: String?,
    @JsonProperty("year") val year: String?,
    @JsonProperty("episodes") val episodes: List<ServerItem>?
)

data class FilmDetails(
    @JsonProperty("status") val status: String?,
    @JsonProperty("movie") val movie: MovieDetails?
)

// ================================================================
// --- CLASS PLUGIN CHÍNH ---
// ================================================================
class NguoncProvider : MainAPI() {

    override var name = "Phim Nguồn C"
    override var mainUrl = "https://phim.nguonc.com"
    override var lang = "vi"
    override val hasMainPage = true
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)

    private val browserHeaders = mapOf(
        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36",
        "Referer" to "$mainUrl/"
    )

    // Các hàm khác giữ nguyên, không cần thay đổi
    private fun getAbsoluteUrl(url: String?): String {
        if (url.isNullOrEmpty()) return ""
        return if (url.startsWith("http")) url else "$mainUrl/$url"
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse? {
        val url = "$mainUrl/api/films/phim-moi-cap-nhat?page=$page"
        val response = app.get(url, headers = browserHeaders).parsed<SearchApiResponse>()
        val homeList = response.items?.mapNotNull { item ->
            val slug = item.slug ?: return@mapNotNull null
            val tvType = if ((item.totalEpisodes ?: 0) > 1 || item.currentEpisode?.contains("Tập") == true) TvType.TvSeries else TvType.Movie
            newMovieSearchResponse(item.name ?: "Unknown", slug, tvType) {
                this.posterUrl = getAbsoluteUrl(item.posterUrl ?: item.thumbUrl)
            }
        } ?: return null
        return newHomePageResponse(list = HomePageList("Phim Mới Cập Nhật", homeList), hasNext = true)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val url = "$mainUrl/api/films/search?keyword=$query"
        val response = app.get(url, headers = browserHeaders).parsed<SearchApiResponse>()
        if (response.status != "success" || response.items.isNullOrEmpty()) return emptyList()
        return response.items.mapNotNull { item ->
            val slug = item.slug ?: return@mapNotNull null
            val tvType = if ((item.totalEpisodes ?: 0) > 1 || item.currentEpisode?.contains("Tập") == true) TvType.TvSeries else TvType.Movie
            newMovieSearchResponse(item.name ?: "Unknown", slug, tvType) {
                this.posterUrl = getAbsoluteUrl(item.posterUrl ?: item.thumbUrl)
            }
        }
    }


    // ================================================================
    // --- PHIÊN BẢN HÀM LOAD ĐẶC BIỆT ĐỂ GỠ LỖI ---
    // ================================================================
    override suspend fun load(url: String): LoadResponse? {
        val apiUrl = "$mainUrl/api/film/$url"
        val dynamicHeaders = browserHeaders + ("Referer" to "$mainUrl/$url")
        
        // Biến để lưu trữ thông tin gỡ lỗi
        var debugInfo: String

        try {
            // Thực hiện gọi API và lấy nội dung text thô
            val responseText = app.get(apiUrl, headers = dynamicHeaders).text
            debugInfo = "ĐÃ NHẬN PHẢN HỒI TỪ API. Nội dung (1000 ký tự đầu):\n\n${responseText.take(1000)}"

        } catch (e: Exception) {
            // Nếu có lỗi, ghi lại thông tin lỗi
            debugInfo = "LỖI KHI GỌI API. Chi tiết lỗi:\n\n${e.toString().take(1000)}"
        }

        // Tạo một trang phim giả để hiển thị thông tin gỡ lỗi
        // Tên phim sẽ là "KẾT QUẢ DEBUG"
        // Phần mô tả phim (plot) sẽ chính là thông tin gỡ lỗi của chúng ta
        return newMovieLoadResponse(
            name = "--- KẾT QUẢ DEBUG ---", 
            url = url, 
            type = TvType.Movie,
            dataUrl = url 
        ) {
            this.plot = debugInfo // Hiển thị thông tin debug ở đây
            this.posterUrl = "" // Bỏ trống ảnh
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        // Tạm thời không làm gì
        return false
    }
}
