// Tên file: NguonCProvider.kt

package recloudstream

import com.fasterxml.jackson.annotation.JsonProperty
import org.jsoup.Jsoup
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.mvvm.suspendSafeApiCall

// Định nghĩa cấu trúc dữ liệu tương ứng với JSON trả về từ API
// =========================================================

data class NguonCItem(
    @JsonProperty("name") val name: String,
    @JsonProperty("slug") val slug: String,
    @JsonProperty("thumb_url") val thumbUrl: String?,
    @JsonProperty("poster_url") val posterUrl: String?,
    @JsonProperty("description") val description: String?,
    @JsonProperty("total_episodes") val totalEpisodes: Int,
    @JsonProperty("quality") val quality: String?,
    @JsonProperty("language") val language: String?,
    @JsonProperty("current_episode") val currentEpisode: String?,
    @JsonProperty("created") val created: String?
)

data class NguonCPaginate(
    @JsonProperty("current_page") val currentPage: Int,
    @JsonProperty("total_page") val totalPage: Int
)

data class NguonCListResponse(
    @JsonProperty("items") val items: List<NguonCItem>,
    @JsonProperty("paginate") val paginate: NguonCPaginate
)

data class NguonCEpisodeItem(
    @JsonProperty("name") val name: String,
    @JsonProperty("slug") val slug: String,
    @JsonProperty("m3u8") val m3u8: String?
)

data class NguonCServer(
    @JsonProperty("server_name") val serverName: String,
    @JsonProperty("items") val items: List<NguonCEpisodeItem>
)

data class NguonCDetailMovie(
    @JsonProperty("name") val name: String,
    @JsonProperty("slug") val slug: String,
    @JsonProperty("original_name") val originalName: String?,
    @JsonProperty("thumb_url") val thumbUrl: String?,
    @JsonProperty("poster_url") val posterUrl: String?,
    @JsonProperty("description") val description: String?,
    @JsonProperty("total_episodes") val totalEpisodes: Int,
    @JsonProperty("time") val time: String?,
    @JsonProperty("quality") val quality: String?,
    @JsonProperty("language") val language: String?,
    @JsonProperty("director") val director: String?,
    @JsonProperty("casts") val casts: String?,
    @JsonProperty("episodes") val episodes: List<NguonCServer>
)

data class NguonCDetailResponse(
    @JsonProperty("movie") val movie: NguonCDetailMovie
)


// Lớp chính của Plugin
// ====================

class NguonCProvider : MainAPI() { // <<<< ĐÃ ĐỔI LẠI TÊN CLASS GỐC
    override var mainUrl = "https://phim.nguonc.com"
    override var name = "Nguồn C"
    override val supportedTypes = setOf(TvType.TvSeries, TvType.Movie, TvType.Anime)
    override var lang = "vi"

    private val apiUrl = "$mainUrl/api"

    private fun NguonCItem.toSearchResponse(): SearchResponse {
        val plot = this.description?.let { Jsoup.parse(it).text() }
        val year = this.created?.substringBefore("-")?.toIntOrNull()
        val isMovie = this.totalEpisodes <= 1

        if (isMovie) {
            return MovieSearchResponse(
                name = this.name,
                url = "$mainUrl/phim/${this.slug}",
                apiName = this@NguonCProvider.name, // <<<< Đã sửa lại tên class ở đây
                type = TvType.Movie,
                posterUrl = this.posterUrl ?: this.thumbUrl,
                year = year,
                plot = plot
            )
        } else {
            return TvSeriesSearchResponse(
                name = this.name,
                url = "$mainUrl/phim/${this.slug}",
                apiName = this@NguonCProvider.name, // <<<< Đã sửa lại tên class ở đây
                type = TvType.TvSeries,
                posterUrl = this.posterUrl ?: this.thumbUrl,
                year = year,
                plot = plot
            )
        }
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val lists = mutableListOf<HomePageList>()
        val homePageItems = listOf(
            Pair("Phim Mới Cập Nhật", "phim-moi-cap-nhat"),
            Pair("Phim Đang Chiếu", "danh-sach/phim-dang-chieu"),
            Pair("Phim Lẻ Mới", "danh-sach/phim-le"),
            Pair("Phim Bộ Mới", "danh-sach/phim-bo")
        )
        homePageItems.apmap { (title, slug) ->
             suspendSafeApiCall {
                val url = "$apiUrl/films/$slug"
                val response = app.get(url).parsed<NguonCListResponse>()
                if (response.items.isNotEmpty()) {
                    lists.add(HomePageList(title, response.items.map { it.toSearchResponse() }))
                }
            }
        }
        return HomePageResponse(lists)
    }
    
    override suspend fun search(query: String): List<SearchResponse> {
        val url = "$apiUrl/films/search?keyword=$query"
        return app.get(url).parsed<NguonCListResponse>().items.map {
            it.toSearchResponse()
        }
    }

    override suspend fun load(url: String): LoadResponse {
        val slug = url.substringAfterLast('/')
        val apiDetailUrl = "$apiUrl/film/$slug"

        val res = app.get(apiDetailUrl).parsed<NguonCDetailResponse>()
        val movie = res.movie

        val title = movie.name
        val poster = movie.posterUrl ?: movie.thumbUrl
        val plot = movie.description?.let { Jsoup.parse(it).text() }
        val tags = mutableListOf<String>()
        movie.language?.let { tags.add(it) }
        movie.quality?.let { tags.add(it) }
        
        if (movie.totalEpisodes <= 1) {
            return newMovieLoadResponse(title, url, TvType.Movie, movie.episodes.firstOrNull()?.items?.firstOrNull()?.m3u8) {
                this.posterUrl = poster
                this.plot = plot
                this.tags = tags
            }
        } else {
            val episodes = movie.episodes.flatMap { server ->
                server.items.map { episode ->
                    Episode(
                        data = episode.m3u8 ?: "",
                        name = "Tập ${episode.name}",
                        season = 1,
                        episode = episode.name.toIntOrNull(),
                        posterUrl = movie.thumbUrl,
                        description = null,
                        rating = null
                    )
                }
            }
            return newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
                this.posterUrl = poster
                this.plot = plot
                this.tags = tags
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
        callback(
            ExtractorLink(
                source = this.name,
                name = this.name,
                url = data,
                referer = "$mainUrl/",
                quality = Qualities.Unknown.value,
                type = ExtractorLinkType.M3U8
            )
        )
        return true
    }
}
