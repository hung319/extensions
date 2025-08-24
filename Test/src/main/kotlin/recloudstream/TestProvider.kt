// Save this file as Motchill.kt
package recloudstream

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Element
import com.lagradost.cloudstream3.utils.AppUtils.parseJson

// The provider class can now be registered directly in your app's repository
class Motchill : MainAPI() {
    override var mainUrl = "https://www.motchill21.com"
    override var name = "Motchill"
    override val hasMainPage = true
    override var lang = "vi"
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(
        TvType.Movie,
        TvType.TvSeries
    )

    override val mainPage = mainPageOf(
        "$mainUrl/phim-moi" to "Phim Mới",
        "$mainUrl/phim-le" to "Phim Lẻ",
        "$mainUrl/phim-bo" to "Phim Bộ",
        "$mainUrl/the-loai/phim-chieu-rap" to "Phim Chiếu Rạp"
    )

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {
        val url = if (page == 1) request.data else "${request.data}/page/$page"
        val document = app.get(url).document
        val home = document.select("div.list-films ul li.film-item").mapNotNull {
            it.toSearchResult()
        }
        return newHomePageResponse(request.name, home)
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val title = this.selectFirst("a")?.attr("title") ?: return null
        val href = this.selectFirst("a")?.attr("href") ?: return null
        val posterUrl = this.selectFirst("img")?.attr("data-src")
        val episode = this.selectFirst("div.meta span.episode")?.text()

        return newMovieSearchResponse(title, href, TvType.Movie) {
            this.posterUrl = posterUrl
            addQuality(episode)
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val searchUrl = "$mainUrl/tim-kiem/${query}.html"
        val document = app.get(searchUrl).document
        return document.select("div.list-films ul li.film-item").mapNotNull {
            it.toSearchResult()
        }
    }

    override suspend fun load(url: String): LoadResponse? {
        val document = app.get(url).document
        val title = document.selectFirst("div.info h1.name")?.text() ?: return null
        val poster = document.selectFirst("div.poster img")?.attr("src")
        val plot = document.selectFirst("div.film-content")?.text()

        val episodes = document.select("div#list-episode ul li a").map {
            val epName = it.text()
            val epHref = it.attr("href")
            // Using newEpisode for safer episode creation
            newEpisode(epHref) {
                name = epName
            }
        }

        return newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
            this.posterUrl = poster
            this.plot = plot
        }
    }

    data class AjaxResponse(
        val status: Boolean,
        val link: String
    )

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val episodePage = app.get(data).document
        
        val token = episodePage.selectFirst("input#token")?.attr("value") ?: return false
        val episodeId = Regex(""".*-(\d+)\.html""").find(data)?.groupValues?.get(1) ?: return false

        val servers = episodePage.select("div.list-server span.server")
        
        servers.apmap { server ->
            val serverId = server.attr("data-id")
            val ajaxUrl = "$mainUrl/ajax/get_link/"
            
            val postData = mapOf(
                "id" to episodeId,
                "sv" to serverId,
                "_token" to token
            )

            val ajaxRes = app.post(
                ajaxUrl,
                data = postData,
                headers = mapOf("X-Requested-With" to "XMLHttpRequest")
            ).text

            try {
                val parsedJson = parseJson<AjaxResponse>(ajaxRes)
                if (parsedJson.status) {
                    M3u8Helper.generateM3u8(
                        name,
                        parsedJson.link,
                        mainUrl
                    ).forEach(callback)
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
        return true
    }
}
