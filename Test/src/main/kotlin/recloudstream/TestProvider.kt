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

    override val mainPage = mainPageOf(
        "phim-moi-cap-nhat" to "Phim Mới Cập Nhật",
        "danh-sach/phim-le" to "Phim Lẻ Mới",
        "danh-sach/phim-bo" to "Phim Bộ Mới",
        "the-loai/hoat-hinh" to "Anime Mới"
    )

    private val nonLatin = "[^\\w-]".toRegex()
    private val whitespace = "\\s+".toRegex()
    private fun String.toUrlSlug(): String {
        val normalized = Normalizer.normalize(this, Normalizer.Form.NFD)
        val slug = nonLatin.replace(normalized, "")
        return whitespace.replace(slug, "-").lowercase()
    }

    private fun NguonCItem.toSearchResponse(): SearchResponse? {
        val year = this.created?.substringBefore("-")?.toIntOrNull()
        val isMovie = this.totalEpisodes <= 1

        if (isMovie) {
            return newMovieSearchResponse(
                name = this.name,
                url = "$mainUrl/phim/${this.slug}",
                type = TvType.Movie
            ) {
                this.posterUrl = this@toSearchResponse.posterUrl ?: this@toSearchResponse.thumbUrl
                this.year = year
            }
        } else {
            return newTvSeriesSearchResponse(
                name = this.name,
                url = "$mainUrl/phim/${this.slug}",
                type = TvType.TvSeries
            ) {
                this.posterUrl = this@toSearchResponse.posterUrl ?: this@toSearchResponse.thumbUrl
                this.year = year
            }
        }
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = "$apiUrl/films/${request.data}?page=$page"
        val response = app.get(url).parsedSafe<NguonCListResponse>() ?: return newHomePageResponse(request.name, emptyList())
        val items = response.items.mapNotNull { it.toSearchResponse() }
        return newHomePageResponse(request.name, items, hasNext = response.paginate.currentPage < response.paginate.totalPage)
    }
    
    override suspend fun search(query: String): List<SearchResponse> {
        val url = "$apiUrl/films/search?keyword=$query"
        return app.get(url).parsedSafe<NguonCListResponse>()?.items?.mapNotNull {
            it.toSearchResponse()
        } ?: emptyList()
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

        val genres = movie.category?.values?.flatMap { it.list }?.map { it.name } ?: emptyList()
        val isAnime = genres.any { it.equals("Hoạt Hình", ignoreCase = true) }

        // SỬA ĐỔI: Cải tiến logic lấy danh sách phim gợi ý
        val recommendations = mutableListOf<SearchResponse>()
        // Lặp qua tất cả các thể loại của phim để tìm danh sách gợi ý
        for (genreName in genres) {
            suspendSafeApiCall {
                val genreSlug = genreName.toUrlSlug()
                val recResponse = app.get("$apiUrl/films/the-loai/$genreSlug?page=1").parsedSafe<NguonCListResponse>()
                
                // Nếu API trả về kết quả thành công
                recResponse?.items?.let { recItems ->
                    if (recItems.isNotEmpty()) {
                        recommendations.addAll(
                            recItems
                                .filter { it.slug != movie.slug } // Lọc bỏ phim hiện tại
                                .mapNotNull { it.toSearchResponse() }
                        )
                    }
                }
            }
            // Nếu đã tìm thấy danh sách gợi ý thì không cần tìm ở các thể loại khác nữa
            if (recommendations.isNotEmpty()) {
                break
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
