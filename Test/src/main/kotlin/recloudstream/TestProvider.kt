// Tên file: NguonCProvider.kt

package recloudstream

import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import com.lagradost.cloudstream3.utils.AppUtils.toJson
import org.jsoup.Jsoup
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.mvvm.suspendSafeApiCall
import java.text.Normalizer

// Data class để truyền dữ liệu từ load() -> loadLinks()
data class NguonCLoadData(
    @JsonProperty("slug") val slug: String,
    @JsonProperty("episodeNum") val episodeNum: Int
)

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
        // Hàm load chỉ cần gọi API một lần để lấy thông tin hiển thị và tổng số tập
        val res = app.get("$apiUrl/film/$slug").parsedSafe<NguonCDetailResponse>()
            ?: return newMovieLoadResponse(url.substringAfterLast("-"), url, TvType.Movie, url) // Fallback

        val movie = res.movie
        val title = movie.name
        val poster = movie.posterUrl ?: movie.thumbUrl
        val plot = movie.description?.let { Jsoup.parse(it).text() }

        val genres = movie.category?.values?.flatMap { it.list }?.map { it.name } ?: emptyList()
        val isAnime = genres.any { it.equals("Hoạt Hình", ignoreCase = true) }
        val type = if (isAnime) {
            if (movie.totalEpisodes <= 1) TvType.AnimeMovie else TvType.Anime
        } else {
            if (movie.totalEpisodes <= 1) TvType.Movie else TvType.TvSeries
        }

        // Tạo danh sách gợi ý
        val recommendations = mutableListOf<SearchResponse>()
        genres.firstOrNull()?.let { primaryGenre ->
            suspendSafeApiCall {
                val genreSlug = primaryGenre.toUrlSlug()
                app.get("$apiUrl/films/the-loai/$genreSlug?page=1").parsedSafe<NguonCListResponse>()
                    ?.items?.let { recItems ->
                        recommendations.addAll(
                            recItems.filter { it.slug != movie.slug }.mapNotNull { it.toSearchResponse() }
                        )
                    }
            }
        }
        
        // SỬA ĐỔI: Phân biệt cách xử lý cho phim lẻ và phim bộ
        if (movie.totalEpisodes <= 1) {
            // Đối với phim lẻ, chỉ cần 1 nút Play. Dữ liệu chứa slug và số tập là 1.
            val loadData = NguonCLoadData(slug = slug, episodeNum = 1).toJson()
            return newMovieLoadResponse(title, url, type, loadData) {
                this.posterUrl = poster
                this.plot = plot
                this.tags = genres
                this.recommendations = recommendations
            }
        } else {
            // Đối với phim bộ, tạo danh sách các tập đơn giản.
            val episodes = (1..movie.totalEpisodes).map { epNum ->
                val loadData = NguonCLoadData(slug = slug, episodeNum = epNum).toJson()
                Episode(
                    data = loadData,
                    name = "Tập $epNum", // Giữ nguyên tên "Tập X" theo yêu cầu
                    episode = epNum,
                    season = 1,
                    posterUrl = movie.thumbUrl
                )
            }
            return newTvSeriesLoadResponse(title, url, type, episodes) {
                this.posterUrl = poster
                this.plot = plot
                this.tags = genres
                this.recommendations = recommendations
            }
        }
    }

    // SỬA ĐỔI LỚN: Toàn bộ logic lấy link được chuyển xuống đây
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        // Giải mã dữ liệu được truyền từ hàm load()
        val loadData = parseJson<NguonCLoadData>(data)

        // Gọi lại API để lấy thông tin chi tiết các server
        val movie = app.get("$apiUrl/film/${loadData.slug}")
            .parsedSafe<NguonCDetailResponse>()?.movie ?: return false
        
        var foundLinks = false
        // Lặp qua tất cả các server có trong API (Vietsub, Lồng Tiếng, Thuyết Minh...)
        movie.episodes.forEach { server ->
            // Tìm đúng tập phim tương ứng với số thứ tự đã chọn
            val episodeItem = server.items.find {
                it.name.toIntOrNull() == loadData.episodeNum
            }
            
            val link = episodeItem?.m3u8
            if (link != null) {
                // Tạo một link lựa chọn cho mỗi server tìm thấy
                callback(
                    ExtractorLink(
                        source = this.name,
                        name = server.serverName, // Tên link là tên của server
                        url = link,
                        referer = "$mainUrl/",
                        quality = Qualities.Unknown.value,
                        type = ExtractorLinkType.M3U8
                    )
                )
                foundLinks = true
            }
        }
        return foundLinks
    }
}
