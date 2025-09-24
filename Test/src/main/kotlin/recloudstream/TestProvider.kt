// Thêm vào file: OnflixProvider.kt

package recloudstream

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import com.lagradost.cloudstream3.utils.AppUtils.toJson
import com.fasterxml.jackson.annotation.JsonProperty
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope

// =================== DATA CLASSES (Chỉ cho api_4k.idoyu.com) ===================

// --- Lớp Data cho API danh sách (a_api.php) ---
data class IdoyuApiResponse(
    val data: List<IdoyuMovieItem>?
)

data class IdoyuMovieItem(
    val name: String?,
    @JsonProperty("original_name") val originalName: String?,
    val slug: String?,
    val content: String?,
    @JsonProperty("imgur_thumb") val imgurThumb: String?,
    @JsonProperty("imgur_poster") val imgurPoster: String?,
    @JsonProperty("created_at") val createdAt: String?,
    @JsonProperty("loai_phim") val movieType: String?
)

// --- Lớp Data cho API chi tiết (a_movies.php) ---
data class IdoyuDetailResponse(
    val episodes: List<IdoyuServerGroup>?
)
data class IdoyuServerGroup(
    val items: List<IdoyuServerItem>?
)
data class IdoyuServerItem(
    val name: String?,
    @JsonProperty("name_get_sub") val nameGetSub: String?,
    @JsonProperty("m3u8") val m3u8Url: String?
)

// --- Lớp Data cho API phụ đề (a_get_sub.php) ---
data class IdoyuSubtitleResponse(
    val subtitles: List<IdoyuSubtitleItem>?
)
data class IdoyuSubtitleItem(
    val language: String?,
    @JsonProperty("subtitle_file") val subtitleFile: String?
)

// =================== PROVIDER IMPLEMENTATION ===================

class OnflixProvider : MainAPI() {
    override var mainUrl = "https://api_4k.idoyu.com"
    override var name = "Onflix"
    override val hasMainPage = true
    override var lang = "vi"
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)
    
    // Số trang sẽ tải về để thực hiện tìm kiếm phía client
    private val CLIENT_SEARCH_PAGE_LIMIT = 5 

    override val mainPage = mainPageOf(
        "/api/a_api.php?per_page=20" to "Phim Mới Cập Nhật",
        "/api/a_api.php?per_page=20&category=phim-le" to "Phim Lẻ",
        "/api/a_api.php?per_page=20&category=phim-bo" to "Phim Bộ"
    )

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse? {
        val url = "$mainUrl${request.data}&page=$page"
        val response = app.get(url).parsedSafe<IdoyuApiResponse>()?.data ?: return null
        val homeList = response.mapNotNull { movie -> toSearchResponse(movie) }
        return newHomePageResponse(request.name, homeList)
    }

    private fun toSearchResponse(movie: IdoyuMovieItem): SearchResponse? {
        val year = movie.createdAt?.take(4)?.toIntOrNull()
        return if (movie.movieType == "Phim bộ") {
            newTvSeriesSearchResponse(
                name = movie.name ?: return null,
                url = movie.toJson() // Truyền toàn bộ thông tin cho `load`
            ) {
                this.posterUrl = movie.imgurPoster ?: movie.imgurThumb
                this.year = year
            }
        } else {
            newMovieSearchResponse(
                name = movie.name ?: return null,
                url = movie.toJson()
            ) {
                this.posterUrl = movie.imgurPoster ?: movie.imgurThumb
                this.year = year
            }
        }
    }
    
    // =================== HÀM SEARCH PHÍA CLIENT ===================
    // Do API không có endpoint search, ta sẽ tải về một list phim và tự lọc
    override suspend fun search(query: String): List<SearchResponse> = coroutineScope {
        // Tải song song nhiều trang kết quả để có danh sách phim lớn
        val allMovies = (1..CLIENT_SEARCH_PAGE_LIMIT).map { page ->
            async {
                app.get("$mainUrl/api/a_api.php?per_page=20&page=$page")
                    .parsedSafe<IdoyuApiResponse>()?.data
                    ?: emptyList()
            }
        }.awaitAll().flatten()

        // Lọc danh sách phim đã tải về dựa trên query
        return@coroutineScope allMovies.filter { movie ->
            val vietnameseNameMatch = movie.name?.contains(query, ignoreCase = true) == true
            val originalNameMatch = movie.originalName?.contains(query, ignoreCase = true) == true
            vietnameseNameMatch || originalNameMatch
        }.mapNotNull { movie ->
            toSearchResponse(movie)
        }
    }
    // =============================================================

    override suspend fun load(url: String): LoadResponse? {
        val movieData = parseJson<IdoyuMovieItem>(url)
        val detailApiUrl = "$mainUrl/api/a_movies.php?slug=${movieData.slug}"
        val detailResponse = app.get(detailApiUrl).parsedSafe<IdoyuDetailResponse>()
        val poster = movieData.imgurPoster ?: movieData.imgurThumb
        val year = movieData.createdAt?.take(4)?.toIntOrNull()

        return if (movieData.movieType == "Phim bộ") {
            val episodes = mutableListOf<Episode>()
            detailResponse?.episodes?.forEach { server ->
                server.items?.forEach { item ->
                    episodes.add(newEpisode(item.toJson()) {
                        this.name = "Tập ${item.name}"
                    })
                }
            }
            newTvSeriesLoadResponse(
                name = movieData.name ?: "N/A",
                url = url,
                type = TvType.TvSeries,
                episodes = episodes.sortedBy { it.name?.filter { c -> c.isDigit() }?.toIntOrNull() }
            ) {
                this.posterUrl = poster
                this.year = year
                this.plot = movieData.content
            }
        } else {
            val movieItemData = detailResponse?.episodes?.firstOrNull()?.items?.firstOrNull()?.toJson() ?: return null
            newMovieLoadResponse(
                name = movieData.name ?: "N/A",
                url = url,
                type = TvType.Movie,
                dataUrl = movieItemData
            ) {
                this.posterUrl = poster
                this.year = year
                this.plot = movieData.content
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
        val item = parseJson<IdoyuServerItem>(data)
        
        val videoUrl = item.m3u8Url
        if (videoUrl != null) {
            callback(
                ExtractorLink(
                    source = this.name,
                    name = if(item.name == "FULL") "Xem phim" else "Tập ${item.name}",
                    url = videoUrl,
                    referer = mainUrl,
                    quality = Qualities.Unknown.value,
                    type = ExtractorLinkType.M3U8
                )
            )
        }
        item.nameGetSub?.let { subKey ->
            try {
                val subUrl = "$mainUrl/api/a_get_sub.php?file=$subKey"
                app.get(subUrl).parsedSafe<IdoyuSubtitleResponse>()?.subtitles?.forEach { sub ->
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
