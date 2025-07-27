package recloudstream

import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.*
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

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val document = app.get(mainUrl).document
        val homePageList = ArrayList<HomePageList>()

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

    private fun Element.toSearchResult(): SearchResponse? {
        val title = this.selectFirst("div.name a")?.text() ?: return null
        val href = fixUrl(this.selectFirst("a")?.attr("href") ?: return null)
        val posterUrl = this.selectFirst("div.poster img")?.attr("src")

        return newMovieSearchResponse(title, href, TvType.Movie) {
            this.posterUrl = posterUrl
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val searchUrl = "$mainUrl/tim-kiem/$query/"
        val document = app.get(searchUrl).document

        return document.select("div.movies-list div.item.movies").mapNotNull {
            it.toSearchResult()
        }
    }

    override suspend fun load(url: String): LoadResponse {
        val document = app.get(url).document

        val title = document.selectFirst("h1[itemprop=name]")?.text()?.trim() ?: "N/A"
        val poster = document.selectFirst("div.poster img")?.attr("src")
        val plot = document.selectFirst("div.entry-content p")?.text()?.trim()

        val isTvSeries = document.select("div#list_episodes").isNotEmpty()

        if (isTvSeries) {
            // Lấy danh sách các tập phim
            val episodes = document.select("div#list_episodes a").map {
                val epUrl = fixUrl(it.attr("href"))
                val epName = it.text().trim()
                // SỬA LỖI Ở ĐÂY: Dùng newEpisode thay vì Episode()
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

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val episodePage = app.get(data).document

        val script = episodePage.select("script").find {
            it.data().contains("var film_id")
        }?.data() ?: return false

        val filmId = Regex("""var film_id = '(\d+)'""").find(script)?.groupValues?.get(1)
        val tapPhim = Regex("""var tập_phim = '(.+?)'""").find(script)?.groupValues?.get(1)

        if (filmId == null || tapPhim == null) return false

        val playerUrl = "$mainUrl/player.php"
        val playerResponse = app.post(
            playerUrl,
            headers = mapOf("X-Requested-With" to "XMLHttpRequest"),
            data = mapOf("film_id" to filmId, "tap_phim" to tapPhim)
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
