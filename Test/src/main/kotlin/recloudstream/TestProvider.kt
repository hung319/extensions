// Thêm file này vào thư mục providers của bạn
// Tên file: NguonCProvider.kt

package recloudstream // Đã thêm package theo yêu cầu

import com.fasterxml.jackson.annotation.JsonProperty
import org.jsoup.Jsoup
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.mvvm.suspendSafeApiCall

// Định nghĩa cấu trúc dữ liệu tương ứng với JSON trả về từ API
// =========================================================

// Dành cho danh sách phim (trang chủ, tìm kiếm,...)
data class NguonCItem(
    @JsonProperty("name") val name: String,
    @JsonProperty("slug") val slug: String,
    @JsonProperty("thumb_url") val thumbUrl: String?,
    @JsonProperty("poster_url") val posterUrl: String?,
    @JsonProperty("description") val description: String?,
    @JsonProperty("total_episodes") val totalEpisodes: Int,
    @JsonProperty("quality") val quality: String?,
    @JsonProperty("language") val language: String?,
    @JsonProperty("current_episode") val currentEpisode: String?
)

// Dành cho đối tượng phân trang
data class NguonCPaginate(
    @JsonProperty("current_page") val currentPage: Int,
    @JsonProperty("total_page") val totalPage: Int
)

// Dành cho phản hồi API chứa danh sách phim
data class NguonCListResponse(
    @JsonProperty("items") val items: List<NguonCItem>,
    @JsonProperty("paginate") val paginate: NguonCPaginate
)

// Dành cho thông tin chi tiết của một tập phim
data class NguonCEpisodeItem(
    @JsonProperty("name") val name: String,
    @JsonProperty("slug") val slug: String,
    @JsonProperty("m3u8") val m3u8: String?
)

// Dành cho một server (chứa nhiều tập)
data class NguonCServer(
    @JsonProperty("server_name") val serverName: String,
    @JsonProperty("items") val items: List<NguonCEpisodeItem>
)

// Dành cho thông tin chi tiết của một bộ phim
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

// Dành cho phản hồi API chứa thông tin chi tiết
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

    // URL của API
    private val apiUrl = "$mainUrl/api"

    // Hàm tiện ích để chuyển đổi item từ API sang định dạng của CloudStream
    private fun NguonCItem.toSearchResponse(): SearchResponse {
        // Làm sạch thẻ HTML trong mô tả
        val plot = this.description?.let { Jsoup.parse(it).text() }

        // Xác định là phim bộ hay phim lẻ
        val isMovie = this.totalEpisodes <= 1

        if (isMovie) {
            return MovieSearchResponse(
                this.name,
                "$mainUrl/phim/${this.slug}", // URL để load chi tiết
                this@NguonCProvider.name,
                TvType.Movie,
                this.posterUrl ?: this.thumbUrl,
                null, // year
                plot
            )
        } else {
            return TvSeriesSearchResponse(
                this.name,
                "$mainUrl/phim/${this.slug}",
                this@NguonCProvider.name,
                TvType.TvSeries,
                this.posterUrl ?: this.thumbUrl,
                null, // year
                null, // episodes
                plot
            )
        }
    }

    // Tải các mục trên trang chủ
    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val lists = mutableListOf<HomePageList>()

        // Danh sách các mục trên trang chủ
        val homePageItems = listOf(
            Pair("Phim Mới Cập Nhật", "phim-moi-cap-nhat"),
            Pair("Phim Đang Chiếu", "danh-sach/phim-dang-chieu"),
            Pair("Phim Lẻ Mới", "danh-sach/phim-le"),
            Pair("Phim Bộ Mới", "danh-sach/phim-bo")
        )
        
        // Tạo các danh sách trên trang chủ một cách song song
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
    
    // Xử lý tìm kiếm
    override suspend fun search(query: String): List<SearchResponse> {
        val url = "$apiUrl/films/search?keyword=$query"
        return app.get(url).parsed<NguonCListResponse>().items.map {
            it.toSearchResponse()
        }
    }

    // Tải thông tin chi tiết phim và danh sách tập
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
        
        // Xác định là phim lẻ hay phim bộ
        if (movie.totalEpisodes <= 1) {
            // Đây là phim lẻ
            return newMovieLoadResponse(title, url, TvType.Movie, movie.episodes.firstOrNull()?.items?.firstOrNull()?.m3u8) {
                this.posterUrl = poster
                this.plot = plot
                this.tags = tags
                this.recommendations = emptyList() // Có thể thêm gợi ý nếu API hỗ trợ
            }
        } else {
            // Đây là phim bộ
            val episodes = movie.episodes.flatMap { server ->
                server.items.map { episode ->
                    Episode(
                        data = episode.m3u8 ?: "", // Dữ liệu chính là link m3u8
                        name = "Tập ${episode.name}",
                        season = 1,
                        episode = episode.name.toIntOrNull(),
                        posterUrl = movie.thumbUrl,
                        isFiller = false,
                        description = null,
                        rating = null
                    )
                }
            }

            return newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
                this.posterUrl = poster
                this.plot = plot
                this.tags = tags
                this.recommendations = emptyList()
            }
        }
    }

    // Tải link xem phim (đã cập nhật theo cấu trúc ExtractorLink mới)
    override suspend fun loadLinks(
        data: String, // data ở đây chính là link m3u8 đã lưu từ hàm load()
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
                type = ExtractorLinkType.M3U8 // <<<< ĐÃ CẬP NHẬT THEO CẤU TRÚC MỚI
            )
        )
        return true
    }
}
