// Thêm vào file: OnflixProvider.kt

package recloudstream

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import com.lagradost.cloudstream3.utils.AppUtils.toJson
import com.fasterxml.jackson.annotation.JsonProperty

// =================== DATA CLASSES ===================

// --- Lớp Data cho API Trang chủ (data-static.onflixcdn.workers.dev) ---
data class NewOnflixApiResponse(
    val success: Boolean?,
    val data: List<NewOnflixMovieItem>?
)
data class NewOnflixMovieItem(
    val slug: String?,
    val name: String?,
    @JsonProperty("original_name") val originalName: String?,
    val imageUrl: String?
)

// --- Lớp Data cho API Search (onflix.me) - MỚI ---
data class OnflixMeSearchResult(
    val name: String?,
    @JsonProperty("original_name") val originalName: String?,
    @JsonProperty("thumb_url") val thumbUrl: String?,
    val slug: String?
)

// --- Lớp Data trung gian để truyền thông tin giữa các hàm ---
private data class LoadData(
    val slug: String,
    val name: String,
    val posterUrl: String?,
    val movieType: String // "phim-le", "phim-bo", hoặc "unknown"
)

// --- Lớp Data cho API chi tiết/link phim (api_4k.idoyu.com) ---
data class OnflixDetailResponse(
    val episodes: List<OnflixServerGroup>?
)
data class OnflixServerGroup(
    val items: List<OnflixServerItem>?
)
data class OnflixServerItem(
    val name: String?,
    @JsonProperty("name_get_sub") val nameGetSub: String?,
    @JsonProperty("m3u8") val m3u8Url: String?
)
data class OnflixSubtitleResponse(
    val subtitles: List<OnflixSubtitleItem>?
)
data class OnflixSubtitleItem(
    val language: String?,
    @JsonProperty("subtitle_file") val subtitleFile: String?
)

// =================== PROVIDER IMPLEMENTATION ===================

class OnflixProvider : MainAPI() {
    // --- Cấu hình các URL API khác nhau ---
    override var mainUrl = "https://data-static.onflixcdn.workers.dev" // API chính cho Trang chủ
    private val searchUrl = "https://onflix.me"                       // API riêng cho Tìm kiếm
    private val detailUrl = "https://api_4k.idoyu.com"                 // API phụ để lấy link phim

    override var name = "Onflix"
    override val hasMainPage = true
    override var lang = "vi"
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)

    override val mainPage = mainPageOf(
        "/?type=category&limit=20&category=phim-le" to "Phim Lẻ Mới",
        "/?type=category&limit=20&category=phim-bo" to "Phim Bộ Mới"
    )

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse? {
        val url = "$mainUrl${request.data}"
        val response = app.get(url).parsedSafe<NewOnflixApiResponse>()?.data ?: return null
        val movieType = if (request.data.contains("phim-bo")) "phim-bo" else "phim-le"

        val homeList = response.mapNotNull { item ->
            val loadData = LoadData(
                slug = item.slug ?: return@mapNotNull null,
                name = item.name ?: return@mapNotNull null,
                posterUrl = item.imageUrl,
                movieType = movieType
            )
            newMovieSearchResponse(
                name = loadData.name,
                url = loadData.toJson()
            ) {
                this.posterUrl = loadData.posterUrl
            }
        }
        return newHomePageResponse(request.name, homeList)
    }

    // =================== HÀM SEARCH ĐÃ ĐƯỢC CẬP NHẬT SANG API ONFLIX.ME ===================
    override suspend fun search(query: String): List<SearchResponse> {
        val url = "$searchUrl/search.php?term=$query"
        val response = app.get(url).parsedSafe<List<OnflixMeSearchResult>>() ?: return emptyList()

        return response.mapNotNull { item ->
            val loadData = LoadData(
                slug = item.slug ?: return@mapNotNull null,
                name = item.name ?: return@mapNotNull null,
                posterUrl = item.thumbUrl,
                movieType = "unknown" // Luôn là "unknown" để hàm `load` tự xác định
            )
            newMovieSearchResponse(
                name = loadData.name,
                url = loadData.toJson()
            ) {
                this.posterUrl = loadData.posterUrl
            }
        }
    }
    // ====================================================================================

    override suspend fun load(url: String): LoadResponse? {
        val loadData = parseJson<LoadData>(url)
        val detailApiUrl = "$detailUrl/api/a_movies.php?slug=${loadData.slug}"
        val detailResponse = app.get(detailApiUrl).parsedSafe<OnflixDetailResponse>()

        val isMovie: Boolean = if (loadData.movieType != "unknown") {
            (loadData.movieType == "phim-le")
        } else {
            detailResponse?.episodes?.firstOrNull()?.items?.let { items ->
                items.size == 1 && items.first().name == "FULL"
            } == true
        }

        return if (isMovie) {
            val movieItemData = detailResponse?.episodes?.firstOrNull()?.items?.firstOrNull()?.toJson() ?: return null
            newMovieLoadResponse(
                name = loadData.name,
                url = url,
                type = TvType.Movie,
                dataUrl = movieItemData
            ) {
                this.posterUrl = loadData.posterUrl
            }
        } else {
            val episodes = mutableListOf<Episode>()
            detailResponse?.episodes?.forEach { server ->
                server.items?.forEach { item ->
                    episodes.add(newEpisode(item.toJson()) {
                        this.name = "Tập ${item.name}"
                    })
                }
            }
            newTvSeriesLoadResponse(
                name = loadData.name,
                url = url,
                type = TvType.TvSeries,
                episodes = episodes.sortedBy { it.name?.filter { c -> c.isDigit() }?.toIntOrNull() }
            ) {
                this.posterUrl = loadData.posterUrl
            }
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        if (data.isBlank() || data == "null") return false
        val item = parseJson<OnflixServerItem>(data)
        
        val videoUrl = item.m3u8Url
        if (videoUrl != null) {
            callback(
                ExtractorLink(
                    source = this.name,
                    name = if(item.name == "FULL") "Xem phim" else "Tập ${item.name}",
                    url = videoUrl,
                    referer = detailUrl,
                    quality = Qualities.Unknown.value,
                    type = ExtractorLinkType.M3U8
                )
            )
        }
        item.nameGetSub?.let { subKey ->
            try {
                val subUrl = "$detailUrl/api/a_get_sub.php?file=$subKey"
                app.get(subUrl).parsedSafe<OnflixSubtitleResponse>()?.subtitles?.forEach { sub ->
                    if (sub.subtitleFile != null && sub.language != null) {
                        subtitleCallback(SubtitleFile(sub.language, sub.subtitleFile))
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
        return true
    }
}
