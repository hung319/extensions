package recloudstream

import com.google.gson.annotations.SerializedName
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import android.util.Log

// ================================================================
// --- DATA CLASSES (ĐÃ XÁC THỰC VỚI API THỰC TẾ) ---
// ================================================================
data class SearchItem(
    @SerializedName("name") val name: String?,
    @SerializedName("slug") val slug: String?,
    @SerializedName("poster_url") val posterUrl: String?,
    @SerializedName("thumb_url") val thumbUrl: String?,
    @SerializedName("total_episodes") val totalEpisodes: Int?,
    @SerializedName("current_episode") val currentEpisode: String?
)

data class SearchApiResponse(
    @SerializedName("status") val status: String?,
    @SerializedName("items") val items: List<SearchItem>?
)

data class EpisodeItem(
    @SerializedName("name") val name: String?,
    @SerializedName("slug") val slug: String?,
    @SerializedName("embed") val embed: String?,
    @SerializedName("m3u8") val m3u8: String?
)

data class ServerItem(
    @SerializedName("server_name") val serverName: String?,
    @SerializedName("items") val items: List<EpisodeItem>?
)

data class MovieDetails(
    @SerializedName("name") val name: String?,
    @SerializedName("original_name") val originName: String?,
    @SerializedName("thumb_url") val thumbUrl: String?,
    @SerializedName("poster_url") val posterUrl: String?,
    @SerializedName("content") val plot: String?,
    @SerializedName("year") val year: String?,
    @SerializedName("episodes") val episodes: List<ServerItem>?
)

data class FilmDetails(
    @SerializedName("status") val status: String?,
    @SerializedName("movie") val movie: MovieDetails?
)

// ================================================================
// --- CLASS PLUGIN CHÍNH (LOGIC ĐÃ HOÀN THIỆN) ---
// ================================================================
class NguoncProvider : MainAPI() {

    override var name = "Phim Nguồn C"
    override var mainUrl = "https://phim.nguonc.com"
    override var lang = "vi"
    override val hasMainPage = true
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)

    private fun getAbsoluteUrl(url: String?): String {
        if (url.isNullOrEmpty()) return ""
        return if (url.startsWith("http")) url else "$mainUrl/$url"
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse? {
        val url = "$mainUrl/api/films/phim-moi-cap-nhat?page=$page"
        val response = app.get(url).parsed<SearchApiResponse>()

        val homeList = response.items?.mapNotNull { item ->
            val slug = item.slug ?: return@mapNotNull null
            val tvType = if ((item.totalEpisodes ?: 0) > 1 || item.currentEpisode?.contains("Tập") == true) {
                TvType.TvSeries
            } else {
                TvType.Movie
            }
            newMovieSearchResponse(item.name ?: "Unknown", slug, tvType) {
                this.posterUrl = getAbsoluteUrl(item.posterUrl ?: item.thumbUrl)
            }
        } ?: return null

        return newHomePageResponse(
            list = HomePageList("Phim Mới Cập Nhật", homeList),
            hasNext = true
        )
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val url = "$mainUrl/api/films/search?keyword=$query"
        val response = app.get(url).parsed<SearchApiResponse>()
        if (response.status != "success" || response.items.isNullOrEmpty()) {
            return emptyList()
        }

        return response.items.mapNotNull { item ->
            val slug = item.slug ?: return@mapNotNull null
            val tvType = if ((item.totalEpisodes ?: 0) > 1 || item.currentEpisode?.contains("Tập") == true) {
                TvType.TvSeries
            } else {
                TvType.Movie
            }
            newMovieSearchResponse(item.name ?: "Unknown", slug, tvType) {
                this.posterUrl = getAbsoluteUrl(item.posterUrl ?: item.thumbUrl)
            }
        }
    }

    override suspend fun load(url: String): LoadResponse? {
        val apiUrl = "$mainUrl/api/film/$url"
        
        try {
            val response = app.get(apiUrl).parsed<FilmDetails>()
            val details = response.movie ?: return null

            val title = details.name ?: details.originName ?: "Unknown"
            val poster = getAbsoluteUrl(details.posterUrl ?: details.thumbUrl)
            val plot = details.plot
            val year = details.year?.toIntOrNull()

            val episodes = details.episodes?.flatMap { server ->
                server.items?.mapNotNull { ep ->
                    // Truyền slug và link embed vào data để `loadLinks` có thể sử dụng
                    val episodeData = "${ep.slug}|${ep.embed}"
                    newEpisode(episodeData) {
                        this.name = "${ep.name} - ${server.serverName}"
                    }
                } ?: emptyList()
            } ?: emptyList()

            val tvType = if(episodes.size > 1) TvType.TvSeries else TvType.Movie

            return if (tvType == TvType.TvSeries) {
                newTvSeriesLoadResponse(title, url, tvType, episodes) {
                    this.posterUrl = poster
                    this.plot = plot
                    this.year = year
                }
            } else {
                newMovieLoadResponse(title, url, tvType, episodes) {
                    this.posterUrl = poster
                    this.plot = plot
                    this.year = year
                }
            }
        } catch (e: Exception) {
            Log.e("NguoncProvider", "Lỗi khi tải chi tiết phim từ URL: $apiUrl", e)
            return null
        }
    }

    /**
     * HÀM LOADLINKS - CÔNG VIỆC CUỐI CÙNG CỦA BẠN
     */
    override suspend fun loadLinks(
        data: String, // `data` giờ chứa "slug|link_embed"
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        // Tách slug và link embed ra từ `data`
        val (slug, embedUrl) = data.split("|")

        // TODO: VIỆC CẦN LÀM CỦA BẠN
        // Nhiệm vụ của bạn là "giải mã" link `embedUrl` này.
        // GỢI Ý:
        // 1. Dùng F12 trên trình duyệt, truy cập `embedUrl` (ví dụ: https://embed18.streamc.xyz/embed.php?hash=...).
        // 2. Xem tab Network để tìm link `.m3u8` hoặc file video mà trang embed đó thực sự tải về.
        // 3. Viết code để tự động hóa quá trình đó ở đây.
        //    - Tải HTML của trang embed: `app.get(embedUrl).text`
        //    - Dùng regex hoặc các hàm xử lý chuỗi để tìm link video bên trong.
        
        Log.d("NguoncProvider", "Đang cố gắng giải mã link embed: $embedUrl")
        
        return false // Trả về false vì chưa có logic hoàn chỉnh
    }
}
