// Thêm vào file: OnflixProvider.kt

package recloudstream

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import com.lagradost.cloudstream3.utils.AppUtils.toJson
import com.fasterxml.jackson.annotation.JsonProperty

// =================== DATA CLASSES ===================
// Các lớp này định nghĩa cấu trúc dữ liệu JSON để parse an toàn

// --- Dành cho API trang chủ (`a_api.php`) ---
data class OnflixApiResponse(
    val data: List<OnflixMovie>?
)

data class OnflixMovie(
    val name: String?,
    val slug: String?,
    val content: String?, // Dùng làm plot/description
    @JsonProperty("imgur_thumb") val imgurThumb: String?,
    @JsonProperty("imgur_poster") val imgurPoster: String?,
    @JsonProperty("created_at") val createdAt: String?,
    @JsonProperty("loai_phim") val movieType: String?
)

// --- Dành cho API chi tiết (`a_movies.php`) ---
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

// --- Dành cho API phụ đề (`a_get_sub.php`) - ĐÃ CẬP NHẬT ---
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
    override var name = "Onflix"
    override val hasMainPage = true
    override var lang = "vi"
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)

    override val mainPage = mainPageOf(
        "/api/a_api.php?per_page=20" to "Phim Mới"
    )

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse? {
        val url = "$mainUrl${request.data}&page=$page"
        val response = app.get(url).parsedSafe<OnflixApiResponse>()?.data ?: return null

        val homeList = response.mapNotNull { movie ->
            val year = movie.createdAt?.take(4)?.toIntOrNull()
            
            newAnimeSearchResponse(
                name = movie.name ?: return@mapNotNull null,
                url = toJson(movie), // Nhúng toàn bộ object movie vào URL
                posterUrl = movie.imgurPoster ?: movie.imgurThumb,
                year = year
            ) {
                this.type = if (movie.movieType == "Phim bộ") TvType.TvSeries else TvType.Movie
            }
        }
        
        return newHomePageResponse(request.name, homeList)
    }

    override suspend fun search(query: String): List<SearchResponse>? {
        return null // API không hỗ trợ tìm kiếm
    }

    override suspend fun load(url: String): LoadResponse? {
        val movieData = parseJson<OnflixMovie>(url)
        
        val detailApiUrl = "$mainUrl/api/a_movies.php?slug=${movieData.slug}"
        val detailResponse = app.get(detailApiUrl).parsedSafe<OnflixDetailResponse>()
        
        val poster = movieData.imgurPoster ?: movieData.imgurThumb
        val year = movieData.createdAt?.take(4)?.toIntOrNull()

        val episodesData = detailResponse?.episodes.let { toJson(it) }

        return if (movieData.movieType == "Phim bộ") {
            newTvSeriesLoadResponse(
                name = movieData.name ?: "N/A",
                url = url,
                posterUrl = poster,
                year = year,
                plot = movieData.content,
            ) {
                addEpisode("Xem Phim", episodesData)
            }
        } else {
            newMovieLoadResponse(
                name = movieData.name ?: "N/A",
                url = url,
                posterUrl = poster,
                year = year,
                plot = movieData.content,
            ) {
                this.dataUrl = episodesData
            }
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val serverGroups = parseJson<List<OnflixServerGroup>>(data)

        serverGroups.forEach { group ->
            group.items?.forEach { item ->
                val videoUrl = item.m3u8Url
                if (videoUrl != null) {
                    callback(
                        ExtractorLink(
                            source = "${this.name} - ${group.serverName ?: ""}".trim(),
                            name = item.name ?: "Chất lượng cao",
                            url = videoUrl,
                            referer = mainUrl,
                            quality = Qualities.Unknown.value,
                            type = ExtractorLinkType.M3U8
                        )
                    )
                }

                // Lấy phụ đề - ĐÃ CẬP NHẬT LOGIC PARSE
                item.nameGetSub?.let { subKey ->
                    try {
                        val subUrl = "$mainUrl/api/a_get_sub.php?file=$subKey"
                        // Parse theo cấu trúc object mới
                        app.get(subUrl).parsedSafe<OnflixSubtitleResponse>()?.subtitles?.forEach { sub ->
                            // Dùng đúng tên trường: subtitleFile và language
                            if (sub.subtitleFile != null && sub.language != null) {
                                subtitleCallback(SubtitleFile(sub.language, sub.subtitleFile))
                            }
                        }
                    } catch (e: Exception) {
                        e.printStackTrace()
                    }
                }
            }
        }
        return true
    }
}
