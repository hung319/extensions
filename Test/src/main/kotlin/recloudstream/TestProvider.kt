package recloudstream

import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import com.lagradost.cloudstream3.utils.newExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import org.jsoup.Jsoup
import com.lagradost.cloudstream3.newEpisode

// Định nghĩa cấu trúc dữ liệu JSON để parse
// Dùng cho Trang chủ và cả Tìm kiếm
data class OphimItem(
    @JsonProperty("name") val name: String,
    @JsonProperty("slug") val slug: String,
    @JsonProperty("poster_url") val posterUrl: String,
    @JsonProperty("year") val year: Int?,
    // Dùng tmdb.type để phân biệt movie và tv series, an toàn hơn 'type'
    @JsonProperty("tmdb") val tmdb: TmdbInfo?
)

data class TmdbInfo(
    @JsonProperty("type") val type: String?
)

// Dùng cho Trang chủ
data class OphimHomepage(
    @JsonProperty("status") val status: Boolean,
    @JsonProperty("items") val items: List<OphimItem>,
    @JsonProperty("pathImage") val pathImage: String
)

// Dùng cho Chi tiết phim
data class OphimDetail(
    @JsonProperty("movie") val movie: MovieDetail,
    @JsonProperty("episodes") val episodes: List<EpisodeServer>
)

data class MovieDetail(
    @JsonProperty("name") val name: String,
    @JsonProperty("origin_name") val originName: String,
    @JsonProperty("content") val content: String,
    @JsonProperty("poster_url") val posterUrl: String,
    @JsonProperty("thumb_url") val thumbUrl: String,
    @JsonProperty("year") val year: Int?,
    @JsonProperty("actor") val actor: List<String>?,
    @JsonProperty("category") val category: List<Category>?,
    @JsonProperty("country") val country: List<Country>?,
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

// Thêm các data class để parse dữ liệu JSON từ trang tìm kiếm
data class NextData(
    @JsonProperty("props") val props: Props
)

data class Props(
    @JsonProperty("pageProps") val pageProps: PageProps
)

data class PageProps(
    @JsonProperty("data") val data: SearchData
)

data class SearchData(
    @JsonProperty("items") val items: List<OphimItem>
)


class OphimProvider : MainAPI() {
    // Tên miền chính cho trang chủ và chi tiết phim
    override var mainUrl = "https://ophim1.com"
    override var name = "Ophim"
    override var lang = "vi"
    override val hasMainPage = true
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)

    private fun getUrl(path: String): String {
        return if (path.startsWith("http")) path else "$mainUrl/$path"
    }

    private fun getImageUrl(path: String?): String? {
        if (path == null) return null
        return if (path.startsWith("http")) {
            path
        } else {
            "https://img.ophim.live/uploads/movies/$path"
        }
    }

    override val mainPage = mainPageOf(
        "$mainUrl/danh-sach/phim-moi-cap-nhat?page=" to "Phim mới cập nhật"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = request.data + page
        val response = app.get(url).text
        val homepageData = parseJson<OphimHomepage>(response)

        val results = homepageData.items.mapNotNull { item ->
            val tvType = if (item.tmdb?.type == "tv") TvType.TvSeries else TvType.Movie
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

    override suspend fun search(query: String): List<SearchResponse> {
        // SỬA: Dùng tên miền ophim16.cc cố định cho chức năng tìm kiếm
        val searchUrl = "https://ophim16.cc/tim-kiem?keyword=$query"
        val doc = app.get(searchUrl).document

        // Lấy dữ liệu từ thẻ script#__NEXT_DATA__
        val scriptData = doc.selectFirst("script#__NEXT_DATA__")?.data()
            ?: return emptyList()

        // Parse dữ liệu JSON
        val searchJson = parseJson<NextData>(scriptData)
        val searchItems = searchJson.props.pageProps.data.items

        return searchItems.map { item ->
            val tvType = if (item.tmdb?.type == "tv") TvType.TvSeries else TvType.Movie
            newMovieSearchResponse(
                name = item.name,
                url = getUrl("phim/${item.slug}"),
                type = tvType
            ) {
                this.posterUrl = getImageUrl(item.posterUrl)
                this.year = item.year
            }
        }
    }

    override suspend fun load(url: String): LoadResponse {
        val response = app.get(url).text
        val detailData = parseJson<OphimDetail>(response)
        val movieInfo = detailData.movie

        val title = movieInfo.name
        val posterUrl = getImageUrl(movieInfo.posterUrl)
        val year = movieInfo.year
        val plot = Jsoup.parse(movieInfo.content).text()
        val tags = movieInfo.category?.map { it.name }
        val actors = movieInfo.actor?.map { ActorData(Actor(it)) }

        return if (movieInfo.type == "series") {
            val episodes = detailData.episodes.flatMap { server ->
                server.serverData.map { episodeData ->
                    newEpisode(data = episodeData.linkM3u8) {
                        this.name = "Tập ${episodeData.name}"
                    }
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
            newMovieLoadResponse(
                title,
                url,
                TvType.Movie,
                detailData.episodes.firstOrNull()?.serverData?.firstOrNull()?.linkM3u8
            ) {
                this.posterUrl = posterUrl
                this.year = year
                this.plot = plot
                this.tags = tags
                this.actors = actors
            }
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        callback.invoke(
            newExtractorLink(
                source = this.name,
                name = "Vietsub",
                url = data,
                type = ExtractorLinkType.M3U8
            ) {
                this.referer = "$mainUrl/" // Referer vẫn có thể dùng mainUrl hoặc tên miền search đều được
                this.quality = Qualities.Unknown.value
            }
        )
        return true
    }
}
