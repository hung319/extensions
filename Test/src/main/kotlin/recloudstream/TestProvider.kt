package recloudstream

import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import android.util.Log
import java.util.EnumSet

// ================================================================
// --- DATA CLASSES ---
// ================================================================
data class SearchItem(
    @JsonProperty("name") val name: String?,
    @JsonProperty("slug") val slug: String?,
    @JsonProperty("poster_url") val posterUrl: String?,
    @JsonProperty("thumb_url") val thumbUrl: String?,
    @JsonProperty("total_episodes") val totalEpisodes: Int?,
    @JsonProperty("current_episode") val currentEpisode: String?,
    // Thêm trường language để đọc thông tin thuyết minh
    @JsonProperty("language") val language: String? 
)

data class SearchApiResponse(
    @JsonProperty("status") val status: String?,
    @JsonProperty("items") val items: List<SearchItem>?
)

data class EpisodeItem(
    @JsonProperty("name") val name: String?,
    @JsonProperty("slug") val slug: String?,
    @JsonProperty("embed") val embed: String?,
    @JsonProperty("m3u8") val m3u8: String?,
    var serverName: String? = null
)

data class ServerItem(
    @JsonProperty("server_name") val serverName: String?,
    @JsonProperty("items") val items: List<EpisodeItem>?
)

data class CategoryItem(
    @JsonProperty("name") val name: String?
)
data class CategoryGroup(
    @JsonProperty("name") val name: String?
)
data class CategoryInfo(
    @JsonProperty("group") val group: CategoryGroup?,
    @JsonProperty("list") val list: List<CategoryItem>?
)

data class MovieDetails(
    @JsonProperty("name") val name: String?,
    @JsonProperty("original_name") val originName: String?,
    @JsonProperty("thumb_url") val thumbUrl: String?,
    @JsonProperty("poster_url") val posterUrl: String?,
    @JsonProperty("content") val plot: String?,
    @JsonProperty("year") val year: String?,
    @JsonProperty("total_episodes") val total_episodes: Int?,
    @JsonProperty("episodes") val episodes: List<ServerItem>?,
    @JsonProperty("category") val category: Map<String, CategoryInfo>?
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
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries, TvType.Anime)

    private val browserHeaders = mapOf(
        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36",
        "Referer" to "$mainUrl/"
    )

    private fun getAbsoluteUrl(url: String?): String {
        if (url.isNullOrEmpty()) return ""
        return if (url.startsWith("http")) url else "$mainUrl/$url"
    }

    // Hàm tiện ích để tạo SearchResponse, tránh lặp code
    private fun toSearchResponse(item: SearchItem): SearchResponse? {
        val slug = item.slug ?: return null
        val apiLink = "$mainUrl/api/film/$slug"
        
        // LOGIC MỚI: Kiểm tra ngôn ngữ để thêm tag Dubbed
        val isDubbed = item.language?.contains("thuyết minh", ignoreCase = true) == true ||
                       item.language?.contains("lồng tiếng", ignoreCase = true) == true
        
        val tvType = if ((item.totalEpisodes ?: 0) > 1 || item.currentEpisode?.contains("Tập") == true) TvType.TvSeries else TvType.Movie
        
        return newMovieSearchResponse(item.name ?: "Unknown", apiLink, tvType) {
            this.posterUrl = getAbsoluteUrl(item.posterUrl ?: item.thumbUrl)
            // Thêm tag DUBBLED vào poster nếu có
            if (isDubbed) {
                this.dubStatus = EnumSet.of(DubStatus.Dubbed)
            }
        }
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse? {
        val url = "$mainUrl/api/films/phim-moi-cap-nhat?page=$page"
        val response = app.get(url, headers = browserHeaders).parsed<SearchApiResponse>()
        // Sử dụng hàm tiện ích để tạo response
        val homeList = response.items?.mapNotNull { toSearchResponse(it) } ?: return null
        return newHomePageResponse(list = HomePageList("Phim Mới Cập Nhật", homeList), hasNext = true)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val url = "$mainUrl/api/films/search?keyword=$query"
        val response = app.get(url, headers = browserHeaders).parsed<SearchApiResponse>()
        if (response.status != "success" || response.items.isNullOrEmpty()) return emptyList()
        // Sử dụng hàm tiện ích để tạo response
        return response.items.mapNotNull { toSearchResponse(it) }
    }

    override suspend fun load(url: String): LoadResponse? {
        val apiUrl = url
        try {
            val response = app.get(apiUrl, headers = browserHeaders).parsed<FilmDetails>()
            val details = response.movie ?: return null
            val title = details.name ?: details.originName ?: "Unknown"
            val poster = getAbsoluteUrl(details.posterUrl ?: details.thumbUrl)
            val plot = details.plot
            val year = details.year?.toIntOrNull()

            val isAnime = details.category?.values?.any { cat ->
                cat.group?.name == "Thể loại" && cat.list?.any { it.name == "Hoạt Hình" } == true
            } == true
            
            // LOGIC MỚI: Quay lại việc nhóm các tập phim theo slug để gộp lại
            val episodesBySlug = mutableMapOf<String, MutableList<EpisodeItem>>()
            details.episodes?.forEach { server ->
                server.items?.forEach { episode ->
                    episode.serverName = server.serverName
                    val slug = episode.slug ?: return@forEach
                    episodesBySlug.getOrPut(slug) { mutableListOf() }.add(episode)
                }
            }

            // Tạo danh sách tập phim cuối cùng cho UI, đã được gộp và chuẩn hóa tên
            val finalEpisodes = episodesBySlug.values.mapNotNull { episodeVersions ->
                val representativeEpisode = episodeVersions.firstOrNull() ?: return@mapNotNull null
                val allVersionsData = AppUtils.toJson(episodeVersions)
                
                newEpisode(allVersionsData) {
                    // Chuẩn hóa tên thành "Tập X"
                    this.name = "Tập ${representativeEpisode.name}"
                    this.episode = representativeEpisode.name?.toIntOrNull()
                }
            }.sortedBy { it.episode }

            val tvType = if (isAnime) {
                TvType.Anime
            } else {
                if (details.total_episodes ?: 1 > 1) TvType.TvSeries else TvType.Movie
            }

            return if (tvType == TvType.TvSeries || tvType == TvType.Anime) {
                newTvSeriesLoadResponse(title, url, tvType, finalEpisodes) {
                    this.posterUrl = poster; this.plot = plot; this.year = year
                }
            } else { 
                newMovieLoadResponse(title, url, tvType, finalEpisodes) {
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
        Log.d("NguoncProvider", "Hàm loadLinks được gọi với data (JSON): $data. Cần logic để xử lý.")
        return false
    }
}
