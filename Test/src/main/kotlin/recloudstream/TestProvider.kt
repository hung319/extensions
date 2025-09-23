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

// =================== DATA CLASSES ===================
data class OnflixApiResponse(
    val data: List<OnflixMovie>?
)
data class OnflixMovie(
    val name: String?,
    @JsonProperty("original_name") val originalName: String?,
    val slug: String?,
    val content: String?,
    @JsonProperty("imgur_thumb") val imgurThumb: String?,
    @JsonProperty("imgur_poster") val imgurPoster: String?,
    @JsonProperty("created_at") val createdAt: String?,
    @JsonProperty("loai_phim") val movieType: String?
)
data class OnflixSearchResult(
    val name: String?,
    @JsonProperty("original_name") val originalName: String?,
    @JsonProperty("thumb_url") val thumbUrl: String?,
    val slug: String?
)
data class OnflixDetailResponse(
    val episodes: List<OnflixServerGroup>?
)
data class OnflixServerGroup(
    @JsonProperty("server_name") val serverName: String?,
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
    override var mainUrl = "https://api_4k.idoyu.com"
    private val searchUrl = "https://onflix.me"
    override var name = "Onflix"
    override val hasMainPage = true
    override var lang = "vi"
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)

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
        val response = app.get(url).parsedSafe<OnflixApiResponse>()?.data ?: return null
        val homeList = response.mapNotNull { movie -> toSearchResponse(movie) }
        return newHomePageResponse(request.name, homeList)
    }

    private fun toSearchResponse(movie: OnflixMovie): SearchResponse? {
        val year = movie.createdAt?.take(4)?.toIntOrNull()
        // Hàm này vẫn cần thiết cho getMainPage
        return if (movie.movieType == "Phim bộ") {
            newTvSeriesSearchResponse(
                name = movie.name ?: return null,
                url = movie.toJson()
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
    
    // =================== HÀM SEARCH ĐÃ ĐƯỢC ĐƠN GIẢN HÓA VÀ TĂNG TỐC ===================
    override suspend fun search(query: String): List<SearchResponse>? {
        val url = "$searchUrl/search.php?term=$query"
        val headers = mapOf("X-Requested-With" to "XMLHttpRequest")
        val response = app.get(url, headers = headers).parsedSafe<List<OnflixSearchResult>>() ?: return null

        return response.mapNotNull { searchResult ->
            // Tạo một đối tượng OnflixMovie "giả lập" chỉ với những thông tin cần thiết
            // cho hàm load. `movieType` không còn quan trọng ở bước này.
            val syntheticMovie = OnflixMovie(
                name = searchResult.name,
                originalName = searchResult.originalName,
                slug = searchResult.slug,
                imgurThumb = searchResult.thumbUrl,
                imgurPoster = searchResult.thumbUrl,
                movieType = null, // Sẽ được xác định trong `load`
                content = null,
                createdAt = null
            )
            
            // Chỉ cần tạo MovieSearchResponse là đủ, vì `load` sẽ tự xác định đúng loại
            newMovieSearchResponse(
                name = syntheticMovie.name ?: return@mapNotNull null,
                url = syntheticMovie.toJson()
            ) {
                this.posterUrl = syntheticMovie.imgurPoster
            }
        }
    }
    // =================================================================================

    // =================== HÀM LOAD ĐÃ TRỞ NÊN THÔNG MINH HƠN ===================
    override suspend fun load(url: String): LoadResponse? {
        // Lấy thông tin cơ bản được truyền từ search hoặc mainPage
        val movieData = parseJson<OnflixMovie>(url)
        val slug = movieData.slug ?: return null

        // Gọi API chi tiết để lấy danh sách tập
        val detailApiUrl = "$mainUrl/api/a_movies.php?slug=$slug"
        val detailResponse = app.get(detailApiUrl).parsedSafe<OnflixDetailResponse>()
        
        // **Logic xác định loại phim dựa trên quy tắc "name": "FULL"**
        val isMovie = detailResponse?.episodes?.firstOrNull()?.items?.let { items ->
            items.size == 1 && items.first().name == "FULL"
        } == true

        val poster = movieData.imgurPoster ?: movieData.imgurThumb
        val year = movieData.createdAt?.take(4)?.toIntOrNull()

        return if (isMovie) {
            val movieItemData = detailResponse?.episodes?.firstOrNull()?.items?.firstOrNull()?.toJson() ?: return null
            newMovieLoadResponse(
                name = movieData.name ?: "N/A",
                url = url,
                type = TvType.Movie,
                dataUrl = movieItemData
            ) {
                this.posterUrl = poster
                this.year = year
                this.plot = movieData.content ?: "Đang cập nhật..."
            }
        } else { // Mặc định là Phim Bộ nếu không khớp quy tắc trên
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
                this.plot = movieData.content ?: "Đang cập nhật..."
            }
        }
    }
    // ==========================================================================

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
                    referer = mainUrl,
                    quality = Qualities.Unknown.value,
                    type = ExtractorLinkType.M3U8
                )
            )
        }
        item.nameGetSub?.let { subKey ->
            try {
                val subUrl = "$mainUrl/api/a_get_sub.php?file=$subKey"
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
