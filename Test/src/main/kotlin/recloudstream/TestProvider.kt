package recloudstream

import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.core.type.TypeReference
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities

class KKPhimProvider : MainAPI() {
    override var name = "KKPhim"
    override var mainUrl = "https://kkphim.com"
    override val hasMainPage = true

    private val apiDomain = "https://phimapi.com"
    private val imageCdnDomain = "https://phimimg.com"

    private val categories = mapOf(
        "Phim Mới Cập Nhật" to "phim-moi-cap-nhat",
        "Phim Bộ" to "danh-sach/phim-bo",
        "Phim Lẻ" to "danh-sach/phim-le",
        "Hoạt Hình" to "danh-sach/hoat-hinh",
        "TV Shows" to "danh-sach/tv-shows"
    )

    override var supportedTypes = setOf(
        TvType.Movie,
        TvType.TvSeries,
        TvType.Anime
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse? {
        val homePageLists = categories.apmap { (title, slug) ->
            val url = if (slug == "phim-moi-cap-nhat") {
                "$apiDomain/$slug?page=1"
            } else {
                "$apiDomain/v1/api/$slug?page=1"
            }
            try {
                val items = if (slug == "phim-moi-cap-nhat") {
                    app.get(url).parsed<ApiResponse>().items
                } else {
                    app.get(url).parsed<SearchApiResponse>().data?.items
                } ?: emptyList()
                val searchResults = items.mapNotNull { toSearchResponse(it) }
                HomePageList(title, searchResults)
            } catch (e: Exception) {
                HomePageList(title, emptyList())
            }
        }
        
        return newHomePageResponse(homePageLists.filter { it.list.isNotEmpty() })
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

        // CẬP NHẬT QUAN TRỌNG: Sửa lỗi nhận diện sai loại phim
        // Coi "series" và "hoathinh" đều là phim có nhiều tập (TvType.TvSeries)
        val isSeries = movie.type == "series" || movie.type == "hoathinh"
        val tvType = if (isSeries) TvType.TvSeries else TvType.Movie

        val episodes = response.episodes?.flatMap { episodeGroup ->
            episodeGroup.serverData.map { episodeData ->
                newEpisode(episodeData) {
                    this.name = episodeData.name
                }
            }
        } ?: emptyList()
        
        // Dựa vào biến `isSeries` để gọi đúng hàm tạo response
        return if (isSeries) {
            newTvSeriesLoadResponse(title, url, tvType, episodes) {
                this.posterUrl = poster; this.year = year; this.plot = description; this.tags = tags; this.actors = actors; this.recommendations = recommendations
            }
        } else { // Mặc định là phim lẻ
            newMovieLoadResponse(title, url, tvType, episodes.firstOrNull()?.data) {
                this.posterUrl = poster; this.year = year; this.plot = description; this.tags = tags; this.actors = actors; this.recommendations = recommendations
            }
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val episodeData = mapper.readValue(data, EpisodeData::class.java)
        
        val headers = mapOf(
            "Referer" to mainUrl,
            "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/108.0.0.0 Safari/537.36"
        )
        
        callback.invoke(
            ExtractorLink(
                source = this.name,
                name = this.name,
                url = episodeData.linkM3u8,
                referer = mainUrl,
                quality = Qualities.Unknown.value,
                type = ExtractorLinkType.M3U8,
                headers = headers
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
    data class Category(@JsonProperty("name") val name: String)
    data class EpisodeGroup(@JsonProperty("server_name") val serverName: String, @JsonProperty("server_data") val serverData: List<EpisodeData>)
    data class EpisodeData(@JsonProperty("name") val name: String, @JsonProperty("slug") val slug: String, @JsonProperty("link_m3u8") val linkM3u8: String, @JsonProperty("link_embed") val linkEmbed: String)
}
