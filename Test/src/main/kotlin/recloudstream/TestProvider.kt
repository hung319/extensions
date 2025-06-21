package recloudstream

import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import android.util.Log
import com.lagradost.cloudstream3.utils.AppUtils.toJson

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

    private fun toSearchResponse(item: SearchItem): SearchResponse? {
        val slug = item.slug ?: return null
        val apiLink = "$mainUrl/api/film/$slug"
        
        val isDubbed = item.language?.contains("thuyết minh", ignoreCase = true) == true ||
                       item.language?.contains("lồng tiếng", ignoreCase = true) == true
        
        val displayName = if (isDubbed) "${item.name} [Dub]" else item.name ?: "Unknown"

        val tvType = if ((item.totalEpisodes ?: 0) > 1 || item.currentEpisode?.contains("Tập") == true) TvType.TvSeries else TvType.Movie
        
        return newMovieSearchResponse(displayName, apiLink, tvType) {
            this.posterUrl = getAbsoluteUrl(item.posterUrl ?: item.thumbUrl)
        }
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse? {
        val url = "$mainUrl/api/films/phim-moi-cap-nhat?page=$page"
        val response = app.get(url, headers = browserHeaders).parsed<SearchApiResponse>()
        val homeList = response.items?.mapNotNull { toSearchResponse(it) } ?: return null
        return newHomePageResponse(list = HomePageList("Phim Mới Cập Nhật", homeList), hasNext = true)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val url = "$mainUrl/api/films/search?keyword=$query"
        val response = app.get(url, headers = browserHeaders).parsed<SearchApiResponse>()
        if (response.status != "success" || response.items.isNullOrEmpty()) return emptyList()
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
            
            val allEpisodes = details.episodes?.flatMap { server ->
                server.items?.map { episode ->
                    episode.serverName = server.serverName
                    episode
                } ?: emptyList()
            } ?: emptyList()

            // LOGIC MỚI: KIỂM TRA PHIM LẺ (1 TẬP DUY NHẤT)
            if (allEpisodes.size == 1) {
                val singleEpisode = allEpisodes.first()
                // SỬA LỖI BIÊN DỊCH: Gọi hàm toJson theo đúng cú pháp extension function.
                val episodeData = listOf(singleEpisode).toJson()

                return newMovieLoadResponse(title, url, if(isAnime) TvType.Anime else TvType.Movie, episodeData) {
                    this.posterUrl = poster
                    this.plot = plot
                    this.year = year
                }
            }
            
            // LOGIC GỘP TẬP CHO PHIM BỘ
            val episodesBySlug = mutableMapOf<String, MutableList<EpisodeItem>>()
            allEpisodes.forEach { episode ->
                val slug = episode.slug ?: return@forEach
                episodesBySlug.getOrPut(slug) { mutableListOf() }.add(episode)
            }

            val finalEpisodes = episodesBySlug.values.mapNotNull { episodeVersions ->
                val representativeEpisode = episodeVersions.firstOrNull() ?: return@mapNotNull null
                val allVersionsData = episodeVersions.toJson()
                
                Episode(
                    data = allVersionsData,
                    name = "Tập ${representativeEpisode.name}",
                    episode = representativeEpisode.name?.toIntOrNull()
                )
            }.sortedBy { it.episode }

            val tvType = if (isAnime) TvType.Anime else TvType.TvSeries

            return newTvSeriesLoadResponse(title, url, tvType, finalEpisodes) {
                this.posterUrl = poster
                this.plot = plot
                this.year = year
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
