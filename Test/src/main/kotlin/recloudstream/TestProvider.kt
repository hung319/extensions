package recloudstream

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.fasterxml.jackson.annotation.JsonProperty
import org.jsoup.Jsoup // Thư viện để xử lý HTML

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
    // Cấu trúc các class này khớp chính xác với JSON API trả về.

    // Dành cho API danh sách (trang chủ, tìm kiếm)
    data class ListApiResponse(
        @JsonProperty("items") val items: List<MediaItem>
    )

    // Dành cho API chi tiết phim
    data class FilmApiResponse(
        @JsonProperty("movie") val movie: MediaItem,
        @JsonProperty("episodes") val episodes: List<EpisodeServer>
    )

    // Đại diện cho một phim/series
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

    // Đại diện cho một nhóm thể loại (Thể loại, Năm, Quốc gia)
    data class CategoryGroup(
        @JsonProperty("list") val list: List<CategoryItem>
    )

    data class CategoryItem(
        @JsonProperty("name") val name: String?
    )

    // Đại diện cho một server chứa các tập phim
    data class EpisodeServer(
        @JsonProperty("server_name") val serverName: String?,
        @JsonProperty("items") val items: List<EpisodeData>
    )

    // Đại diện cho dữ liệu của một tập phim
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
            newTvSeries(this.name ?: this.originalName ?: "", url) {
                posterUrl = this.posterUrl ?: this.thumbUrl
            }
        } else {
            newMovie(this.name ?: this.originalName ?: "", url) {
                posterUrl = this.posterUrl ?: this.thumbUrl
            }
        }
    }

    // ============================ Core Provider Functions ============================

    override val mainPage = mainPageOf(
        "$API_URL/films/phim-moi-cap-nhat?page=" to "Phim mới cập nhật",
        "$API_URL/films/danh-sach/phim-le?page=" to "Phim lẻ",
        "$API_URL/films/danh-sach/phim-bo?page=" to "Phim bộ",
        "$API_URL/films/danh-sach/phim-dang-chieu?page=" to "Phim đang chiếu",
        "$API_URL/films/danh-sach/hoat-hinh?page=" to "Hoạt hình"
    )

    override suspend fun mainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = request.data + page
        val response = app.get(url).parsed<ListApiResponse>()
        val home = response.items.mapNotNull { it.toSearchResult() }
        return newHomePageResponse(request.name, home)
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
        // Dùng Jsoup để loại bỏ tag HTML <p> khỏi mô tả
        val description = movieInfo.description?.let { Jsoup.parse(it).text() }
        val cast = movieInfo.casts?.split(",")?.map { it.trim() }
        val directors = movieInfo.director?.split(",")?.map { it.trim() }

        // Lấy năm và thể loại từ object "category"
        var year: Int? = null
        var genres: List<String>? = null
        movieInfo.category?.values?.forEach { group ->
            // Tìm năm trong nhóm "Năm"
            if (group.list.any { it.name?.toIntOrNull() != null }) {
                year = group.list.firstOrNull()?.name?.toIntOrNull()
            }
            // Tìm thể loại trong nhóm "Thể loại"
            else if (group.list.any { it.name?.length ?: 0 > 2 }) { // Giả định thể loại có tên dài
                 genres = group.list.mapNotNull { it.name }
            }
        }

        val episodes = res.episodes.flatMap { server ->
            server.items.map { episode ->
                newEpisode(episode) {
                    this.name = "Tập ${episode.name}".replace("Tập Tập", "Tập")
                    this.data = episode.m3u8 ?: episode.embed ?: ""
                    // Thêm tên server (Vietsub, Lồng tiếng) vào tên tập phim để phân biệt
                    this.displayName = "${server.serverName}: Tập ${episode.name}"
                }
            }
        }

        return if ((movieInfo.totalEpisodes ?: 1) > 1) {
            newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
                this.posterUrl = poster
                this.year = year
                this.plot = description
                this.cast = cast
                this.creators = directors
                this.tags = genres
            }
        } else {
            newMovieLoadResponse(title, url, TvType.Movie, episodes.firstOrNull()?.data) {
                this.posterUrl = poster
                this.year = year
                this.plot = description
                this.cast = cast
                this.creators = directors
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
        
        // Hàm loadExtractor sẽ tự động xử lý link m3u8 hoặc các link embed phổ biến
        loadExtractor(data, mainUrl, subtitleCallback, callback)
        return true
    }
}
