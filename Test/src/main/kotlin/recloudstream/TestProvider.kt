package recloudstream

import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.CloudflareKiller // Thêm import cho CloudflareKiller
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import org.jsoup.nodes.Element

// TvPhim.bid Provider
class TvPhimBidProvider : MainAPI() {
    // Thông tin cơ bản của provider
    override var mainUrl = "https://tvphim.bid"
    override var name = "TVPhim"
    override val hasMainPage = true
    override var lang = "vi"
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(
        TvType.Movie,
        TvType.TvSeries,
    )
    
    // Khởi tạo CloudflareKiller để sử dụng cho tất cả request
    private val cloudflareKiller = CloudflareKiller()

    /**
     * Hàm này dùng để tải dữ liệu cho trang chủ của plugin
     */
    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        // Thêm interceptor vào request để vượt qua Cloudflare
        val document = app.get(mainUrl, interceptor = cloudflareKiller).document
        val homePageList = ArrayList<HomePageList>()

        // Lấy các mục phim như "Phim Lẻ Mới", "Phim Bộ Mới"
        val sections = document.select("div.section")
        sections.forEach { section ->
            val title = section.selectFirst("div.section-title")?.text()?.trim() ?: "Unknown"
            val movies = section.select("div.item.movies").mapNotNull { it.toSearchResult() }
            if (movies.isNotEmpty()) {
                homePageList.add(HomePageList(title, movies))
            }
        }

        return HomePageResponse(homePageList)
    }

    /**
     * Hàm tiện ích để chuyển đổi một phần tử HTML thành đối tượng SearchResponse
     */
    private fun Element.toSearchResult(): SearchResponse? {
        val title = this.selectFirst("div.name a")?.text() ?: return null
        val href = fixUrl(this.selectFirst("a")?.attr("href") ?: return null)
        val posterUrl = this.selectFirst("div.poster img")?.attr("src")

        return newMovieSearchResponse(title, href, TvType.Movie) {
            this.posterUrl = posterUrl
        }
    }

    /**
     * Hàm này được gọi khi người dùng thực hiện tìm kiếm
     */
    override suspend fun search(query: String): List<SearchResponse> {
        val searchUrl = "$mainUrl/tim-kiem/$query/"
        // Thêm interceptor vào request để vượt qua Cloudflare
        val document = app.get(searchUrl, interceptor = cloudflareKiller).document

        return document.select("div.movies-list div.item.movies").mapNotNull {
            it.toSearchResult()
        }
    }

    /**
     * Hàm này được gọi khi người dùng nhấn vào một bộ phim để xem chi tiết
     */
    override suspend fun load(url: String): LoadResponse {
        // Thêm interceptor vào request để vượt qua Cloudflare
        val document = app.get(url, interceptor = cloudflareKiller).document

        val title = document.selectFirst("h1[itemprop=name]")?.text()?.trim() ?: "N/A"
        val poster = document.selectFirst("div.poster img")?.attr("src")
        val plot = document.selectFirst("div.entry-content p")?.text()?.trim()

        val isTvSeries = document.select("div#list_episodes").isNotEmpty()

        if (isTvSeries) {
            val episodes = document.select("div#list_episodes a").map {
                val epUrl = fixUrl(it.attr("href"))
                val epName = it.text().trim()
                newEpisode(epUrl) {
                    this.name = epName
                }
            }.reversed()

            return newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
                this.posterUrl = poster
                this.plot = plot
            }
        } else {
            return newMovieLoadResponse(title, url, TvType.Movie, url) {
                this.posterUrl = poster
                this.plot = plot
            }
        }
    }

    private data class PlayerResponse(
        @JsonProperty("file") val file: String,
    )

    /**
     * Hàm quan trọng nhất: Lấy link video trực tiếp
     */
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        // Thêm interceptor vào request để vượt qua Cloudflare
        val episodePage = app.get(data, interceptor = cloudflareKiller).document

        val script = episodePage.select("script").find {
            it.data().contains("var film_id")
        }?.data() ?: return false

        val filmId = Regex("""var film_id = '(\d+)'""").find(script)?.groupValues?.get(1)
        val tapPhim = Regex("""var tập_phim = '(.+?)'""").find(script)?.groupValues?.get(1)

        if (filmId == null || tapPhim == null) return false

        val playerUrl = "$mainUrl/player.php"
        // Thêm interceptor vào request để vượt qua Cloudflare
        val playerResponse = app.post(
            playerUrl,
            headers = mapOf("X-Requested-With" to "XMLHttpRequest"),
            data = mapOf("film_id" to filmId, "tap_phim" to tapPhim),
            interceptor = cloudflareKiller
        ).parsed<PlayerResponse>()

        callback.invoke(
            ExtractorLink(
                source = this.name,
                name = this.name,
                url = playerResponse.file,
                referer = mainUrl,
                quality = Qualities.Unknown.value,
                type = ExtractorLinkType.M3U8
            )
        )

        return true
    }
}
