// Tên file: NguonCProvider.kt

package recloudstream

import com.fasterxml.jackson.annotation.JsonProperty
import org.jsoup.Jsoup
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.mvvm.suspendSafeApiCall
import java.text.Normalizer

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

data class NguonCCategoryInfo(
    @JsonProperty("name") val name: String
)

data class NguonCCategoryGroup(
    @JsonProperty("list") val list: List<NguonCCategoryInfo>
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
    @JsonProperty("episodes") val episodes: List<NguonCServer>,
    @JsonProperty("category") val category: Map<String, NguonCCategoryGroup>?
)

data class NguonCDetailResponse(
    @JsonProperty("movie") val movie: NguonCDetailMovie
)


// Lớp chính của Plugin
// ====================

class NguonCProvider : MainAPI() {
    override var mainUrl = "https://phim.nguonc.com"
    override var name = "Nguồn C"
    override val supportedTypes = setOf(TvType.TvSeries, TvType.Movie, TvType.Anime)
    override var lang = "vi"
    override val hasMainPage = true
    private val apiUrl = "$mainUrl/api"

    // Hàm tiện ích để chuyển đổi chuỗi thành slug URL
    private val nonLatin = "[^\\w-]".toRegex()
    private val whitespace = "\\s+".toRegex()
    private fun String.toUrlSlug(): String {
        val normalized = Normalizer.normalize(this, Normalizer.Form.NFD)
        val slug = nonLatin.replace(normalized, "")
        return whitespace.replace(slug, "-").lowercase()
    }

    private fun NguonCItem.toSearchResponse(): SearchResponse {
        val year = this.created?.substringBefore("-")?.toIntOrNull()
        val isMovie = this.totalEpisodes <= 1

        if (isMovie) {
            return MovieSearchResponse(
                name = this.name,
                url = "$mainUrl/phim/${this.slug}",
                apiName = this@NguonCProvider.name,
                type = TvType.Movie,
                posterUrl = this.posterUrl ?: this.thumbUrl,
                year = year
            )
        } else {
            return TvSeriesSearchResponse(
                name = this.name,
                url = "$mainUrl/phim/${this.slug}",
                apiName = this@NguonCProvider.name,
                type = TvType.TvSeries,
                posterUrl = this.posterUrl ?: this.thumbUrl,
                year = year
            )
        }
    }

    // SỬA ĐỔI: Thêm logic phân trang và sắp xếp lại grid
    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        // Nếu đang cuộn để tải trang tiếp theo của một mục
        if (page > 1) {
            val slug = request.data
            if (slug.isNotEmpty()) {
                val response = app.get("$apiUrl/films/$slug?page=$page").parsed<NguonCListResponse>()
                return HomePageResponse(listOf(HomePageList(
                    name = request.name,
                    list = response.items.map { it.toSearchResponse() },
                    isHorizontal = true,
                    data = slug // Truyền lại data để phân trang tiếp
                )))
            } else {
                return HomePageResponse(emptyList())
            }
        }
        
        // Tải lần đầu (page = 1)
        val lists = mutableListOf<HomePageList>()
        // Sắp xếp lại grid theo yêu cầu và bỏ "Phim Đang Chiếu"
        val homePageItems = listOf(
            Pair("Phim Mới Cập Nhật", "phim-moi-cap-nhat"),
            Pair("Phim Lẻ Mới", "danh-sach/phim-le"),
            Pair("Phim Bộ Mới", "danh-sach/phim-bo"),
            Pair("Anime Mới", "the-loai/hoat-hinh")
        )

        homePageItems.apmap { (title, slug) ->
             suspendSafeApiCall {
                val response = app.get("$apiUrl/films/$slug?page=1").parsed<NguonCListResponse>()
                if (response.items.isNotEmpty()) {
                    // Thêm 'data = slug' để app biết cần tải trang tiếp theo cho mục nào
                    lists.add(HomePageList(title, response.items.map { it.toSearchResponse() }, data = slug))
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

    // SỬA ĐỔI: Thêm danh sách gợi ý (recommendations)
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

        val genres = movie.category?.values?.flatMap { it.list }?.map { it.name } ?: emptyList()
        val isAnime = genres.any { it.equals("Hoạt Hình", ignoreCase = true) }

        // Tải danh sách gợi ý dựa trên thể loại đầu tiên
        val recommendations = mutableListOf<SearchResponse>()
        genres.firstOrNull()?.let { primaryGenre ->
            suspendSafeApiCall {
                val genreSlug = primaryGenre.toUrlSlug()
                val recResponse = app.get("$apiUrl/films/the-loai/$genreSlug?page=1").parsed<NguonCListResponse>()
                recommendations.addAll(
                    recResponse.items
                        .filter { it.slug != movie.slug } // Lọc bỏ phim hiện tại
                        .map { it.toSearchResponse() }
                )
            }
        }

        if (movie.totalEpisodes <= 1) {
            val movieType = if (isAnime) TvType.AnimeMovie else TvType.Movie
            return newMovieLoadResponse(title, url, movieType, movie.episodes.firstOrNull()?.items?.firstOrNull()?.m3u8) {
                this.posterUrl = poster
                this.plot = plot
                this.tags = tags + genres
                this.recommendations = recommendations
            }
        } else {
            val seriesType = if (isAnime) TvType.Anime else TvType.TvSeries
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
            return newTvSeriesLoadResponse(title, url, seriesType, episodes) {
                this.posterUrl = poster
                this.plot = plot
                this.tags = tags + genres
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
