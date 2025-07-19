package recloudstream

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.fasterxml.jackson.annotation.JsonProperty
import org.jsoup.Jsoup

// Define the main provider class
class NguonCProvider : MainAPI() {
    override var mainUrl = "https://phim.nguonc.com"
    override var name = "NguonC"
    override val hasMainPage = true
    override var lang = "vi"
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(
        TvType.Movie,
        TvType.TvSeries
    )

    companion object {
        const val API_URL = "https://phim.nguonc.com/api"
    }

    // ============================ Data Classes for JSON Parsing ============================
    data class ListApiResponse(
        @JsonProperty("items") val items: List<MediaItem>
    )

    data class FilmApiResponse(
        @JsonProperty("movie") val movie: MediaItem,
        // SỬA LỖI: Cho phép danh sách tập phim có thể bị thiếu (null)
        @JsonProperty("episodes") val episodes: List<EpisodeServer>?
    )

    data class MediaItem(
        @JsonProperty("name") val name: String?,
        @JsonProperty("slug") val slug: String?,
        @JsonProperty("original_name") val originalName: String?,
        @JsonProperty("thumb_url") val thumbUrl: String?,
        @JsonProperty("poster_url") val posterUrl: String?,
        @JsonProperty("description") val description: String?,
        @JsonProperty("total_episodes") val totalEpisodes: Int?,
        @JsonProperty("casts") val casts: String?,
        @JsonProperty("director") val director: String?,
        @JsonProperty("category") val category: Map<String, CategoryGroup>?
    )

    data class CategoryGroup(
        @JsonProperty("list") val list: List<CategoryItem>
    )

    data class CategoryItem(
        @JsonProperty("name") val name: String?
    )

    data class EpisodeServer(
        @JsonProperty("server_name") val serverName: String?,
        @JsonProperty("items") val items: List<EpisodeData>
    )

    data class EpisodeData(
        @JsonProperty("name") val name: String?,
        @JsonProperty("slug") val slug: String?,
        @JsonProperty("embed") val embed: String?,
        @JsonProperty("m3u8") val m3u8: String?
    )

    // ============================ Helper Functions ============================

    private fun MediaItem.toSearchResult(): SearchResponse {
        val isTvSeries = (this.totalEpisodes ?: 1) > 1
        val url = "$mainUrl/phim/${this.slug}"

        return if (isTvSeries) {
            newTvSeriesSearchResponse(this.name ?: this.originalName ?: "", url) {
                posterUrl = this@toSearchResult.posterUrl ?: this@toSearchResult.thumbUrl
            }
        } else {
            newMovieSearchResponse(this.name ?: this.originalName ?: "", url) {
                posterUrl = this@toSearchResult.posterUrl ?: this@toSearchResult.thumbUrl
            }
        }
    }

    // ============================ Core Provider Functions ============================

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse? {
        if (page > 1) return HomePageResponse(emptyList())

        val sections = listOf(
            "Phim mới cập nhật" to "$API_URL/films/phim-moi-cap-nhat?page=1",
            "Phim lẻ" to "$API_URL/films/danh-sach/phim-le?page=1",
            "Phim bộ" to "$API_URL/films/danh-sach/phim-bo?page=1",
            "Phim đang chiếu" to "$API_URL/films/danh-sach/phim-dang-chieu?page=1",
            "Hoạt hình" to "$API_URL/films/danh-sach/hoat-hinh?page=1"
        )

        val homePageList = sections.apmap { (name, url) ->
            val items = app.get(url).parsedSafe<ListApiResponse>()?.items
                ?.mapNotNull { it.toSearchResult() } ?: emptyList()
            HomePageList(name, items)
        }

        return HomePageResponse(homePageList.filter { it.list.isNotEmpty() })
    }


    override suspend fun search(query: String): List<SearchResponse> {
        val url = "$API_URL/films/search?keyword=$query"
        return try {
            app.get(url).parsed<ListApiResponse>().items.mapNotNull { it.toSearchResult() }
        } catch (e: Exception) {
            emptyList()
        }
    }

    override suspend fun load(url: String): LoadResponse? {
        val slug = url.substringAfterLast("/")
        val apiLink = "$API_URL/film/$slug"
        val res = app.get(apiLink).parsed<FilmApiResponse>()
        val movieInfo = res.movie

        val title = movieInfo.name ?: movieInfo.originalName ?: return null
        val poster = movieInfo.posterUrl ?: movieInfo.thumbUrl
        val description = movieInfo.description?.let { Jsoup.parse(it).text() }
        val actors = movieInfo.casts?.split(",")?.map { ActorData(Actor(it.trim())) }

        var year: Int? = null
        var genres: List<String>? = null
        movieInfo.category?.values?.forEach { group ->
            if (group.list.any { it.name?.toIntOrNull() != null }) {
                year = group.list.firstOrNull()?.name?.toIntOrNull()
            } else if (group.list.any { it.name?.length ?: 0 > 2 }) {
                genres = group.list.mapNotNull { it.name }
            }
        }

        // SỬA LỖI: Xử lý an toàn trường hợp `episodes` là null
        val episodes = res.episodes?.flatMap { server ->
            server.items.map { episode ->
                newEpisode(episode) {
                    this.name = "${server.serverName}: Tập ${episode.name}"
                    this.data = episode.m3u8 ?: episode.embed ?: ""
                }
            }
        } ?: emptyList() // Nếu `episodes` là null, trả về một danh sách rỗng

        return if ((movieInfo.totalEpisodes ?: 1) > 1) {
            newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
                this.posterUrl = poster
                this.year = year
                this.plot = description
                this.actors = actors
                this.tags = genres
            }
        } else {
            // Đối với phim lẻ, link xem phim có thể không có trong 'episodes'.
            // Cần một cách khác để lấy link, tuy nhiên API hiện tại chưa cung cấp.
            // Tạm thời vẫn lấy từ `episodes` nếu có.
            newMovieLoadResponse(title, url, TvType.Movie, episodes.firstOrNull()?.data) {
                this.posterUrl = poster
                this.year = year
                this.plot = description
                this.actors = actors
                this.tags = genres
            }
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        if (data.isBlank()) return false
        loadExtractor(data, mainUrl, subtitleCallback, callback)
        return true
    }
}
