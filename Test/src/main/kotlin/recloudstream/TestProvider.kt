// Thêm vào file: OnflixProvider.kt

package recloudstream

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import com.lagradost.cloudstream3.utils.AppUtils.toJson
import com.fasterxml.jackson.annotation.JsonProperty

// =================== DATA CLASSES ===================
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
data class OnflixMeSearchResult(
    val name: String?,
    @JsonProperty("original_name") val originalName: String?,
    @JsonProperty("thumb_url") val thumbUrl: String?,
    val slug: String?
)
private data class LoadData(
    val slug: String,
    val name: String,
    val posterUrl: String?,
    val movieType: String
)
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
    override var mainUrl = "https://data-static.onflixcdn.workers.dev"
    private val searchUrl = "https://onflix.me"
    private val detailUrl = "https://api_4k.idoyu.com"

    override var name = "Onflix"
    override val hasMainPage = true
    override var lang = "vi"
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)

    // =================== CẬP NHẬT TRANG CHỦ ===================
    override val mainPage = mainPageOf(
        "/?type=category&limit=20&category=phim-moi" to "Phim Mới",
        "/?type=category&limit=20&category=phim-hot" to "Phim Hot",
        "/?type=category&limit=20&category=phim-le" to "Phim Lẻ",
        "/?type=category&limit=20&category=phim-bo" to "Phim Bộ"
    )
    // ==========================================================

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse? {
        val url = "$mainUrl${request.data}"
        val response = app.get(url).parsedSafe<NewOnflixApiResponse>()?.data ?: return null
        
        // Xác định loại phim dựa vào URL, mặc định là phim lẻ nếu không rõ
        val movieType = when {
            request.data.contains("phim-bo") -> "phim-bo"
            request.data.contains("phim-le") -> "phim-le"
            else -> "phim-le" // Mặc định cho phim mới, phim hot
        }

        val homeList = response.mapNotNull { item ->
            val loadData = LoadData(
                slug = item.slug ?: return@mapNotNull null,
                name = item.name ?: return@mapNotNull null,
                posterUrl = item.imageUrl?.trim(), // Thêm .trim() để làm sạch URL
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

    override suspend fun search(query: String): List<SearchResponse> {
        val url = "$searchUrl/search.php?term=$query"
        val response = app.get(url).parsedSafe<List<OnflixMeSearchResult>>() ?: return emptyList()

        return response.mapNotNull { item ->
            val loadData = LoadData(
                slug = item.slug ?: return@mapNotNull null,
                name = item.name ?: return@mapNotNull null,
                posterUrl = item.thumbUrl?.trim(), // Thêm .trim() để làm sạch URL
                movieType = "unknown"
            )
            newMovieSearchResponse(
                name = loadData.name,
                url = loadData.toJson()
            ) {
                this.posterUrl = loadData.posterUrl
            }
        }
    }

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
