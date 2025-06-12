package recloudstream // Đã thêm dòng này

import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import org.jsoup.Jsoup

// Định nghĩa cấu trúc dữ liệu JSON để parse
data class OphimHomepage(
    @JsonProperty("status") val status: Boolean,
    @JsonProperty("items") val items: List<OphimItem>,
    @JsonProperty("pathImage") val pathImage: String
)

data class OphimItem(
    @JsonProperty("name") val name: String,
    @JsonProperty("slug") val slug: String,
    @JsonProperty("poster_url") val posterUrl: String,
    @JsonProperty("year") val year: Int?,
    // Dùng tmdb.type để phân biệt movie và tv series
    @JsonProperty("tmdb") val tmdb: TmdbInfo?
)

data class TmdbInfo(
    @JsonProperty("type") val type: String?
)

data class OphimDetail(
    @JsonProperty("movie") val movie: MovieDetail,
    @JsonProperty("episodes") val episodes: List<EpisodeServer>
)

data class MovieDetail(
    @JsonProperty("name") val name: String,
    @JsonProperty("origin_name") val originName: String,
    // content chứa mã HTML, cần phải parse
    @JsonProperty("content") val content: String,
    @JsonProperty("poster_url") val posterUrl: String,
    @JsonProperty("thumb_url") val thumbUrl: String,
    @JsonProperty("year") val year: Int?,
    @JsonProperty("actor") val actor: List<String>?,
    @JsonProperty("category") val category: List<Category>?,
    @JsonProperty("country") val country: List<Country>?,
    // Dùng type để phân biệt movie và tv series
    @JsonProperty("type") val type: String
)

data class Category(
    @JsonProperty("name") val name: String
)

data class Country(
    @JsonProperty("name") val name: String
)

data class EpisodeServer(
    @JsonProperty("server_name") val serverName: String,
    @JsonProperty("server_data") val serverData: List<EpisodeData>
)

data class EpisodeData(
    @JsonProperty("name") val name: String,
    @JsonProperty("slug") val slug: String,
    @JsonProperty("link_m3u8") val linkM3u8: String
)

class OphimProvider : MainAPI() {
    // Thông tin cơ bản của Provider
    override var mainUrl = "https://ophim1.com"
    override var name = "Ophim"
    override var lang = "vi"
    override val hasMainPage = true
    // Hỗ trợ cả phim lẻ và phim bộ
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)

    // Hàm tạo URL hoàn chỉnh
    private fun getUrl(path: String): String {
        return if (path.startsWith("http")) path else "$mainUrl/$path"
    }

    // Xử lý URL ảnh, vì API trả về lúc thì full path, lúc thì relative
    private fun getImageUrl(path: String?): String? {
        if (path == null) return null
        return if (path.startsWith("http")) {
            path
        } else {
            // Dựa vào cấu trúc API homepage, ảnh nằm trong 1 folder cố định
            "https://img.ophim.live/uploads/movies/$path"
        }
    }

    // Trang chủ
    override val mainPage = mainPageOf(
        "$mainUrl/danh-sach/phim-moi-cap-nhat?page=" to "Phim mới cập nhật"
        // Bạn có thể thêm các danh mục khác ở đây nếu có API
        // ví dụ: "$mainUrl/danh-sach/phim-le?page=" to "Phim Lẻ",
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        // Gọi API danh sách phim
        val url = request.data + page
        val response = app.get(url).text
        val homepageData = parseJson<OphimHomepage>(response)

        // Map kết quả API vào SearchResponse của Cloudstream
        val results = homepageData.items.mapNotNull { item ->
            val tvType = if (item.tmdb?.type == "tv") TvType.TvSeries else TvType.Movie
            // Tạo URL để load chi tiết phim, dùng slug
            val movieUrl = getUrl("phim/${item.slug}")
            newMovieSearchResponse(
                name = item.name,
                url = movieUrl,
                type = tvType
            ) {
                this.posterUrl = getImageUrl(item.posterUrl)
                this.year = item.year
            }
        }
        return newHomePageResponse(request.name, results)
    }

    // Load chi tiết phim và danh sách tập
    override suspend fun load(url: String): LoadResponse {
        // Gọi API chi tiết phim
        val response = app.get(url).text
        val detailData = parseJson<OphimDetail>(response)
        val movieInfo = detailData.movie

        // Lấy các thông tin chi tiết
        val title = movieInfo.name
        val posterUrl = getImageUrl(movieInfo.posterUrl)
        val year = movieInfo.year
        // Parse HTML trong content để lấy text thuần
        val plot = Jsoup.parse(movieInfo.content).text()
        val tags = movieInfo.category?.map { it.name }
        val actors = movieInfo.actor?.map { Actor(it) }

        // Kiểm tra loại phim (series hay movie)
        return if (movieInfo.type == "series") {
            // Xử lý phim bộ
            val episodes = detailData.episodes.flatMap { server ->
                server.serverData.map { episodeData ->
                    Episode(
                        data = episodeData.linkM3u8, // Lưu link m3u8 vào data
                        name = "Tập ${episodeData.name}",
                        // Thêm tên server để phân biệt nếu có nhiều server
                        displayName = "${server.serverName} - Tập ${episodeData.name}"
                    )
                }
            }
            newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
                this.posterUrl = posterUrl
                this.year = year
                this.plot = plot
                this.tags = tags
                this.actors = actors
            }
        } else {
            // Xử lý phim lẻ
            newMovieLoadResponse(title, url, TvType.Movie, detailData.episodes.firstOrNull()?.serverData?.firstOrNull()?.linkM3u8) {
                this.posterUrl = posterUrl
                this.year = year
                this.plot = plot
                this.tags = tags
                this.actors = actors
            }
        }
    }

    // Load link để xem phim
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        // Data chính là link m3u8 chúng ta đã lưu từ hàm load
        callback.invoke(
            ExtractorLink(
                source = this.name,
                name = "${this.name} Vietsub",
                url = data,
                referer = "$mainUrl/",
                quality = Qualities.Unknown.value // Chất lượng không xác định từ link
            )
        )
        return true
    }
}
