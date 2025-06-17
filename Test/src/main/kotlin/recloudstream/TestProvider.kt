package recloudstream

import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.loadExtractor

class KKPhimProvider : MainAPI() {
    override var name = "KKPhim"
    override var mainUrl = "https://kkphim.com"
    private val apiDomain = "https://phimapi.com"
    private val imageCdnDomain = "https://phimimg.com"

    override var supportedTypes = setOf(
        TvType.Movie,
        TvType.TvSeries,
        TvType.Anime
    )

    // NÂNG CẤP HÀM GETMAINPAGE
    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse? {
        // Định nghĩa các danh mục cho trang chủ
        val categories = mapOf(
            "Phim Mới Cập Nhật" to "phim-moi-cap-nhat",
            "Phim Bộ" to "danh-sach/phim-bo",
            "Phim Lẻ" to "danh-sach/phim-le",
            "Hoạt Hình" to "danh-sach/hoat-hinh",
            "TV Shows" to "danh-sach/tv-shows"
        )

        // Tải dữ liệu cho mỗi danh mục một cách song song để tăng tốc độ
        val homePageLists = categories.apmap { (title, slug) ->
            // API "phim-moi-cap-nhat" có cấu trúc khác một chút
            val url = if (slug == "phim-moi-cap-nhat") {
                "$apiDomain/$slug?page=1"
            } else {
                "$apiDomain/v1/api/$slug?page=1"
            }

            // API "phim-moi-cap-nhat" có cấu trúc gốc, các API còn lại có cấu trúc trong "data"
            val items = if (slug == "phim-moi-cap-nhat") {
                 app.get(url).parsed<ApiResponse>().items
            } else {
                 app.get(url).parsed<SearchApiResponse>().data?.items ?: emptyList()
            }
            
            val searchResponses = items.mapNotNull { toSearchResponse(it) }
            HomePageList(title, searchResponses)
        }

        return HomePageResponse(homePageLists)
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
        val recommendations = response.movie.recommendations?.mapNotNull {
            toSearchResponse(it)
        }

        val tvType = if (movie.type == "series") TvType.TvSeries else TvType.Movie

        val episodes = response.episodes.flatMap { episodeGroup ->
            episodeGroup.serverData.map { episodeData ->
                newEpisode(episodeData) {
                    this.name = episodeData.name
                    // Thêm tên server vào trước tên tập để phân biệt
                    this.displayName = "${episodeGroup.serverName}: ${episodeData.name}".replace("#", "").trim()
                }
            }
        }
        
        return if (tvType == TvType.TvSeries) {
            newTvSeriesLoadResponse(title, url, tvType, episodes) {
                this.posterUrl = poster
                this.year = year
                this.plot = description
                this.tags = tags
                this.actors = actors
                this.recommendations = recommendations
            }
        } else {
            newMovieLoadResponse(title, url, tvType, episodes.firstOrNull()?.data) {
                this.posterUrl = poster
                this.year = year
                this.plot = description
                this.tags = tags
                this.actors = actors
                this.recommendations = recommendations
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
        return loadExtractor(episodeData.linkM3u8, mainUrl, subtitleCallback, callback)
    }

    // --- DATA CLASSES ---

    // Dành cho API phim mới cập nhật
    data class ApiResponse(
        @JsonProperty("items") val items: List<MovieItem>,
        @JsonProperty("pagination") val pagination: Pagination
    )

    // Dành cho API tìm kiếm & danh sách
    data class SearchApiResponse(
        @JsonProperty("data") val data: SearchData?
    )
    
    data class SearchData(
        @JsonProperty("items") val items: List<MovieItem>?,
        @JsonProperty("params") val params: SearchParams?
    )

    data class SearchParams(
        @JsonProperty("pagination") val pagination: Pagination?
    )

    data class MovieItem(
        @JsonProperty("name") val name: String,
        @JsonProperty("slug") val slug: String,
        @JsonProperty("poster_url") val posterUrl: String?,
        @JsonProperty("type") val type: String?,
        @JsonProperty("tmdb") val tmdb: TmdbInfo?
    )

    data class TmdbInfo(
        @JsonProperty("type") val type: String?
    )

    data class Pagination(
        @JsonProperty("currentPage") val currentPage: Int,
        @JsonProperty("totalPages") val totalPages: Int
    )

    data class DetailApiResponse(
        @JsonProperty("movie") val movie: DetailMovie?,
        @JsonProperty("episodes") val episodes: List<EpisodeGroup>
    )

    data class DetailMovie(
        @JsonProperty("name") val name: String,
        @JsonProperty("content") val content: String?,
        @JsonProperty("poster_url") val posterUrl: String?,
        @JsonProperty("year") val year: Int?,
        @JsonProperty("type") val type: String?,
        @JsonProperty("category") val category: List<Category>?,
        @JsonProperty("actor") val actor: List<String>?,
        // API dùng trường "chieurap" cho phim đề cử, ta tận dụng nó
        @JsonProperty("chieurap") val recommendations: List<MovieItem>?
    )
    
    data class Category(
        @JsonProperty("name") val name: String
    )

    data class EpisodeGroup(
        @JsonProperty("server_name") val serverName: String,
        @JsonProperty("server_data") val serverData: List<EpisodeData>
    )

    data class EpisodeData(
        @JsonProperty("name") val name: String,
        @JsonProperty("slug") val slug: String,
        @JsonProperty("link_m3u8") val linkM3u8: String
    )
}
