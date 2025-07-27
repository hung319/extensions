package recloudstream

import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.network.CloudflareKiller
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import org.jsoup.nodes.Element

// TvPhim.bid Provider
class TvPhimBidProvider : MainAPI() {
    override var mainUrl = "https://tvphim.bid"
    override var name = "TVPhim"
    override val hasMainPage = true
    override var lang = "vi"
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(
        TvType.Movie,
        TvType.TvSeries,
    )

    private val cloudflareKiller = CloudflareKiller()

    // =================== CẬP NHẬT SELECTORS ===================

    // Selector cho các mục trên trang chủ (Phim lẻ mới, Phim bộ mới,...)
    private val homePageSectionSelector = "div.left-content > div.section"
    // Selector cho danh sách phim trong mỗi mục
    private val movieItemSelector = "div.movies-list > div.item.movies"
    // Selector cho danh sách phim trên trang tìm kiếm
    private val searchResultSelector = "div.page-content div.movies-list > div.item.movies"
    
    // ==========================================================

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val document = app.get(mainUrl, interceptor = cloudflareKiller).document
        val homePageList = ArrayList<HomePageList>()
        
        // Sử dụng selector đã được cập nhật
        val sections = document.select(homePageSectionSelector)

        if (sections.isEmpty()) {
            throw Exception("Lỗi Trang Chủ: Không tìm thấy mục phim nào. HTML nhận được: ${document.html()}")
        }

        sections.forEach { section ->
            val title = section.selectFirst("div.section-title")?.text()?.trim() ?: "N/A"
            // Sử dụng selector đã được cập nhật
            val movies = section.select(movieItemSelector).mapNotNull { it.toSearchResult() }
            if (movies.isNotEmpty()) {
                homePageList.add(HomePageList(title, movies))
            }
        }

        return HomePageResponse(homePageList)
    }

    private fun Element.toSearchResult(): SearchResponse? {
        // Selector bên trong item không đổi vì đã khá tối ưu
        val linkElement = this.selectFirst("a") ?: return null
        val href = fixUrl(linkElement.attr("href"))
        val name = linkElement.attr("title")
        val posterUrl = this.selectFirst("div.poster img")?.attr("src")

        return newMovieSearchResponse(name, href, TvType.Movie) {
            this.posterUrl = posterUrl
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val searchUrl = "$mainUrl/tim-kiem/$query/"
        val document = app.get(searchUrl, interceptor = cloudflareKiller).document

        // Sử dụng selector đã được cập nhật
        val searchResults = document.select(searchResultSelector)
        
        if (searchResults.isEmpty() && document.selectFirst("div.no-results") == null) {
            throw Exception("Lỗi Tìm Kiếm: Không tìm thấy kết quả. HTML nhận được: ${document.html()}")
        }

        return searchResults.mapNotNull { it.toSearchResult() }
    }

    override suspend fun load(url: String): LoadResponse {
        val document = app.get(url, interceptor = cloudflareKiller).document

        val title = document.selectFirst("h1[itemprop=name]")?.text()?.trim() ?: "N/A"
        val poster = document.selectFirst("div.poster img")?.attr("src")
        val plot = document.selectFirst("div.entry-content p, div.entry-content")?.text()?.trim()

        val isTvSeries = document.select("div#list_episodes").isNotEmpty()

        if (isTvSeries) {
            val episodes = document.select("div#list_episodes a").map {
                val epUrl = fixUrl(it.attr("href"))
                val epName = it.text().trim()
                newEpisode(epUrl) { this.name = epName }
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

    private data class PlayerResponse(@JsonProperty("file") val file: String)

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val episodePage = app.get(data, interceptor = cloudflareKiller).document
        val script = episodePage.select("script:containsData(var film_id)").firstOrNull()?.data() ?: return false
        
        val filmId = Regex("""var film_id\s*=\s*'(\d+)'""").find(script)?.groupValues?.get(1)
        val tapPhim = Regex("""var tập_phim\s*=\s*'(.+?)'""").find(script)?.groupValues?.get(1)

        if (filmId == null || tapPhim == null) return false

        val playerUrl = "$mainUrl/player.php"
        val playerResponse = app.post(
            playerUrl,
            headers = mapOf("X-Requested-With" to "XMLHttpRequest", "Referer" to data),
            data = mapOf("film_id" to filmId, "tap_phim" to tapPhim),
            interceptor = cloudflareKiller
        ).parsed<PlayerResponse>()
        
        callback.invoke(
            ExtractorLink(
                this.name, this.name, playerResponse.file, mainUrl, Qualities.Unknown.value, type = ExtractorLinkType.M3U8
            )
        )
        return true
    }
}
