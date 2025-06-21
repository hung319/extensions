package recloudstream

import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import android.util.Log

// ================================================================
// --- DATA CLASSES ---
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

    // Headers chuẩn với Referer là trang chủ, dùng cho mọi request
    private val browserHeaders = mapOf(
        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36",
        "Referer" to "$mainUrl/"
    )

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

    override suspend fun load(url: String): LoadResponse? {
        val apiUrl = "$mainUrl/api/film/$url"
        
        try {
            // SỬA LỖI QUAN TRỌNG:
            // Luôn sử dụng headers với Referer là trang chủ, vì trang phim cụ thể có thể không tồn tại (404).
            val response = app.get(apiUrl, headers = browserHeaders).parsed<FilmDetails>()
            val details = response.movie ?: return null
            
            val title = details.name ?: details.originName ?: "Unknown"
            val poster = getAbsoluteUrl(details.posterUrl ?: details.thumbUrl)
            val plot = details.plot
            val year = details.year?.toIntOrNull()
            val episodes = details.episodes?.flatMap { server ->
                server.items?.mapNotNull { ep ->
                    val episodeData = "${ep.slug}|${ep.embed}"
                    newEpisode(episodeData) {
                        this.name = "${ep.name} - ${server.serverName}"
                    }
                } ?: emptyList()
            } ?: emptyList()
            val tvType = if (episodes.size > 1) TvType.TvSeries else TvType.Movie
            return if (tvType == TvType.TvSeries) {
                newTvSeriesLoadResponse(title, url, tvType, episodes) {
                    this.posterUrl = poster; this.plot = plot; this.year = year
                }
            } else {
                newMovieLoadResponse(title, url, tvType, episodes) {
                    this.posterUrl = poster; this.plot = plot; this.year = year
                }
            }
        } catch (e: Exception) {
            Log.e("NguoncProvider", "Lỗi khi tải chi tiết phim từ URL: $apiUrl. Lỗi: ${e.message}", e)
            return null
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        Log.d("NguoncProvider", "Hàm loadLinks được gọi với data: $data. Cần logic để xử lý.")
        return false
    }
}
