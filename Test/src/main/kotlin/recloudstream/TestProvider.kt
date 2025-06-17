package recloudstream

import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.core.type.TypeReference
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
// Bỏ import loadExtractor vì không dùng nữa
// Thêm import cho ExtractorLinkType và Qualities
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.extractors.helper.ExtractorLinkType

class KKPhimProvider : MainAPI() {
    override var name = "KKPhim"
    override var mainUrl = "https://kkphim.com"
    override val hasMainPage = true

    private val apiDomain = "https://phimapi.com"
    private val imageCdnDomain = "https://phimimg.com"

    override var supportedTypes = setOf(
        TvType.Movie,
        TvType.TvSeries,
        TvType.Anime
    )

    // Tối giản lại trang chủ để tải nhanh hơn
    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse? {
        val url = "$apiDomain/danh-sach/phim-moi-cap-nhat?page=$page"
        val response = app.get(url).parsed<ApiResponse>()

        val homePageList = response.items?.mapNotNull { item ->
            toSearchResponse(item)
        } ?: emptyList()
        
        val hasNext = (response.pagination?.currentPage ?: 0) < (response.pagination?.totalPages ?: 0)

        return newHomePageResponse(
            list = HomePageList("Phim Mới Cập Nhật", homePageList),
            hasNext = hasNext
        )
    }
    
    private fun toSearchResponse(item: MovieItem): SearchResponse? {
        val movieUrl = "$mainUrl/phim/${item.slug}"
        val tvType = when (item.type) {
            "series" -> TvType.TvSeries
            "single" -> TvType.Movie
            "hoathinh" -> TvType.Anime
            else -> when (item.tmdb?.type) {
                "movie" -> TvType.Movie
                "tv" -> TvType.TvSeries
                else -> null
            }
        } ?: return null

        val poster = item.posterUrl?.let {
            if (it.startsWith("http")) it else "$imageCdnDomain/$it"
        }

        return newMovieSearchResponse(item.name, movieUrl, tvType) {
            this.posterUrl = poster
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val url = "$apiDomain/v1/api/tim-kiem?keyword=$query"
        val response = app.get(url).parsed<SearchApiResponse>()
        
        return response.data?.items?.mapNotNull { item ->
            toSearchResponse(item)
        } ?: listOf()
    }

    override suspend fun load(url: String): LoadResponse? {
        val slug = url.substringAfter("/phim/")
        val apiUrl = "$apiDomain/phim/$slug"
        val response = app.get(apiUrl).parsed<DetailApiResponse>()
        val movie = response.movie ?: return null

        val title = movie.name
        val poster = movie.posterUrl
        val year = movie.year
        val description = movie.content
        val tags = movie.category?.map { it.name }
        val actors = movie.actor?.let { actorList ->
            actorList.map { ActorData(Actor(it)) }
        }
        
        val recommendations = if (movie.recommendations is List<*>) {
            try {
                val movieItems = mapper.convertValue(movie.recommendations, object : TypeReference<List<MovieItem>>() {})
                movieItems.mapNotNull { toSearchResponse(it) }
            } catch (e: Exception) { null }
        } else { null }

        val tvType = if (movie.type == "series") TvType.TvSeries else TvType.Movie

        val episodes = response.episodes?.flatMap { episodeGroup ->
            episodeGroup.serverData.map { episodeData ->
                newEpisode(episodeData) {
                    this.name = "${episodeGroup.serverName}: ${episodeData.name}".replace("#", "").trim()
                }
            }
        } ?: emptyList()
        
        return if (tvType == TvType.TvSeries) {
            newTvSeriesLoadResponse(title, url, tvType, episodes) {
                this.posterUrl = poster; this.year = year; this.plot = description; this.tags = tags; this.actors = actors; this.recommendations = recommendations
            }
        } else {
            newMovieLoadResponse(title, url, tvType, episodes.firstOrNull()?.data) {
                this.posterUrl = poster; this.year = year; this.plot = description; this.tags = tags; this.actors = actors; this.recommendations = recommendations
            }
        }
    }

    // SỬA LỖI TẢI LINK: Dùng ExtractorLink trực tiếp với headers
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val episodeData = mapper.readValue(data, EpisodeData::class.java)
        
        // Tạo headers để giả lập một trình duyệt web
        val headers = mapOf(
            "Referer" to mainUrl,
            "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/108.0.0.0 Safari/537.36"
        )
        
        // Tạo ExtractorLink và gọi callback
        callback.invoke(
            ExtractorLink(
                source = this.name, // Tên nguồn, ví dụ: "KKPhim"
                name = this.name,   // Tên hiển thị cho link
                url = episodeData.linkM3u8, // Link m3u8 trực tiếp
                referer = mainUrl,  // Trang giới thiệu
                quality = Qualities.Unknown.value, // Để trình phát tự nhận diện chất lượng
                type = ExtractorLinkType.M3U8, // Đánh dấu đây là luồng HLS (m3u8)
                headers = headers   // Gửi kèm headers đã tạo
            )
        )

        return true
    }

    // --- DATA CLASSES ---

    data class ApiResponse(@JsonProperty("items") val items: List<MovieItem>? = null, @JsonProperty("pagination") val pagination: Pagination? = null)
    data class SearchApiResponse(@JsonProperty("data") val data: SearchData? = null)
    data class SearchData(@JsonProperty("items") val items: List<MovieItem>? = null, @JsonProperty("params") val params: SearchParams? = null)
    data class SearchParams(@JsonProperty("pagination") val pagination: Pagination? = null)
    data class MovieItem(@JsonProperty("name") val name: String, @JsonProperty("slug") val slug: String, @JsonProperty("poster_url") val posterUrl: String? = null, @JsonProperty("type") val type: String? = null, @JsonProperty("tmdb") val tmdb: TmdbInfo? = null)
    data class TmdbInfo(@JsonProperty("type") val type: String? = null)
    data class Pagination(@JsonProperty("currentPage") val currentPage: Int? = null, @JsonProperty("totalPages") val totalPages: Int? = null)
    data class DetailApiResponse(@JsonProperty("movie") val movie: DetailMovie? = null, @JsonProperty("episodes") val episodes: List<EpisodeGroup>? = null)
    data class DetailMovie(@JsonProperty("name") val name: String, @JsonProperty("content") val content: String? = null, @JsonProperty("poster_url") val posterUrl: String? = null, @JsonProperty("year") val year: Int? = null, @JsonProperty("type") val type: String? = null, @JsonProperty("category") val category: List<Category>? = null, @JsonProperty("actor") val actor: List<String>? = null, @JsonProperty("chieurap") val recommendations: Any? = null)
    g    data class Category(@JsonProperty("name") val name: String)
    data class EpisodeGroup(@JsonProperty("server_name") val serverName: String, @JsonProperty("server_data") val serverData: List<EpisodeData>)
    data class EpisodeData(@JsonProperty("name") val name: String, @JsonProperty("slug") val slug: String, @JsonProperty("link_m3u8") val linkM3u8: String, @JsonProperty("link_embed") val linkEmbed: String)
}
