// Thêm vào file: OnflixProvider.kt

package recloudstream

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import com.lagradost.cloudstream3.utils.AppUtils.toJson
import com.fasterxml.jackson.annotation.JsonProperty

// =================== DATA CLASSES ===================

data class PaginationData(
    @JsonProperty("has_next") val hasNext: Boolean?
)

data class OnflixApiResponse(
    val data: List<OnflixMovieItem>?,
    val pagination: PaginationData?
)

data class OnflixMovieItem(
    val slug: String?,
    val name: String?,
    @JsonProperty("original_name") val originalName: String?,
    @JsonProperty("imgur_poster") val imgurPoster: String?,
    @JsonProperty("imgur_thumb") val imgurThumb: String?,
    val casts: String?,
    val director: String?,
    @JsonProperty("the_loai") val theLoai: String?,
    @JsonProperty("loai_phim") val loaiPhim: String?,
    val nam: String?,
    val content: String?
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
    @JsonProperty("m3u8") val m3u8Url: String?,
    @JsonProperty("link_embed") val linkEmbed: String?
)

data class OnflixSubtitleResponse(
    val subtitles: List<OnflixSubtitleItem>?
)
data class OnflixSubtitleItem(
    val language: String?,
    @JsonProperty("subtitle_file") val subtitleFile: String?
)

data class PlayerSubtitle(
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

    private val perPage = 20

    override val mainPage = mainPageOf(
        "/api/bo_loc.php?action=filter&per_page=$perPage" to "Phim Mới Cập Nhật",
        "/api/bo_loc.php?action=filter&per_page=$perPage&slug_loai_phim=phim-le" to "Phim Lẻ",
        "/api/bo_loc.php?action=filter&per_page=$perPage&slug_loai_phim=phim-bo" to "Phim Bộ"
    )

    private fun toSearchResponse(movie: OnflixMovieItem): SearchResponse? {
        val name = movie.name ?: return null
        val isMovie = movie.loaiPhim?.contains("Phim bộ") != true
        
        return if (isMovie) {
            newMovieSearchResponse(name, movie.toJson()) {
                this.posterUrl = movie.imgurThumb
                this.year = movie.nam?.toIntOrNull()
            }
        } else {
            newTvSeriesSearchResponse(name, movie.toJson()) {
                this.posterUrl = movie.imgurThumb
                this.year = movie.nam?.toIntOrNull()
            }
        }
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = "$mainUrl${request.data}&page=$page"
        val response = app.get(url).parsedSafe<OnflixApiResponse>()

        val homeList = response?.data?.mapNotNull { toSearchResponse(it) } ?: emptyList()
        
        val apiHasNext = response?.pagination?.hasNext ?: false
        val hasNext = if (apiHasNext) true else homeList.size >= perPage

        return newHomePageResponse(request.name, homeList, hasNext = hasNext)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val url = "$mainUrl/api/bo_loc.php?action=filter&search=$query"
        val response = app.get(url).parsedSafe<OnflixApiResponse>()?.data ?: return emptyList()
        return response.mapNotNull { toSearchResponse(it) }
    }

    override suspend fun load(url: String): LoadResponse? {
        val movieData = parseJson<OnflixMovieItem>(url)
        val detailApiUrl = "$mainUrl/api/a_movies.php?slug=${movieData.slug}"
        val detailResponse = app.get(detailApiUrl).parsedSafe<OnflixDetailResponse>()
        
        val isMovie = movieData.loaiPhim?.contains("Phim bộ") != true

        return if (isMovie) {
            val movieItemData = detailResponse?.episodes?.firstOrNull()?.items?.firstOrNull()?.toJson() ?: return null
            newMovieLoadResponse(
                name = movieData.name ?: "N/A",
                url = url,
                type = TvType.Movie,
                dataUrl = movieItemData
            ) {
                this.posterUrl = movieData.imgurPoster ?: movieData.imgurThumb
                this.year = movieData.nam?.toIntOrNull()
                this.plot = movieData.content
                this.tags = movieData.theLoai?.split(',')
                this.actors = movieData.casts?.split(',')?.map { ActorData(Actor(it.trim())) }
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
                name = movieData.name ?: "N/A",
                url = url,
                type = TvType.TvSeries,
                episodes = episodes.sortedBy { it.name?.filter { c -> c.isDigit() }?.toIntOrNull() }
            ) {
                this.posterUrl = movieData.imgurPoster ?: movieData.imgurThumb
                this.year = movieData.nam?.toIntOrNull()
                this.plot = movieData.content
                this.tags = movieData.theLoai?.split(',')
                this.actors = movieData.casts?.split(',')?.map { ActorData(Actor(it.trim())) }
            }
        }
    }
    
    private val videoIdRegex = Regex("""const videoId = '(.*?)'""")
    private val subtitleDataRegex = Regex("""const subtitleData = (\[.*?\]);""")

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val item = parseJson<OnflixServerItem>(data)

        if (!item.m3u8Url.isNullOrBlank()) {
            callback(
                ExtractorLink(name, name, item.m3u8Url, mainUrl, Qualities.Unknown.value, type = ExtractorLinkType.M3U8)
            )
            return true
        }

        val embedUrl = item.linkEmbed
        if (!embedUrl.isNullOrBlank()) {
            try {
                val embedHtml = app.get(embedUrl).text
                
                videoIdRegex.find(embedHtml)?.groupValues?.get(1)?.let { videoUrl ->
                    callback(
                        ExtractorLink("$name (Embed)", "$name (Embed)", videoUrl, embedUrl, Qualities.Unknown.value, type = ExtractorLinkType.M3U8)
                    )
                }

                subtitleDataRegex.find(embedHtml)?.groupValues?.get(1)?.let { subJson ->
                    parseJson<List<PlayerSubtitle>>(subJson).forEach { sub ->
                        if (sub.subtitleFile != null && sub.language != null) {
                            subtitleCallback(SubtitleFile(sub.language, sub.subtitleFile))
                        }
                    }
                }
                return true
            } catch (e: Exception) {
                return false
            }
        }

        return false
    }
}
