// Save this file as Motchill.kt
package recloudstream

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Element
import com.lagradost.cloudstream3.utils.AppUtils.parseJson

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
        val url = if (page == 1) request.data else "${request.data}-page-$page/"
        val document = app.get(url).document
        
        val home = document.select("ul.list-film > li").mapNotNull {
            it.toSearchResult()
        }
        return newHomePageResponse(request.name, home)
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val linkElement = this.selectFirst("div.inner > a") ?: return null
        val title = linkElement.attr("title").ifBlank { return null }
        val href = linkElement.attr("href")
        val posterUrl = linkElement.selectFirst("img")?.attr("src")

        return newMovieSearchResponse(title, href, TvType.Movie) {
            this.posterUrl = posterUrl
        }
    }
    
    override suspend fun search(query: String): List<SearchResponse> {
        val searchUrl = "$mainUrl/tim-kiem/${query}.html"
        val document = app.get(searchUrl).document
        
        return document.select("ul.list-film > li").mapNotNull {
            it.toSearchResult()
        }
    }

    override suspend fun load(url: String): LoadResponse? {
        val document = app.get(url).document

        val title = document.selectFirst("h1.movie-title span.title-1")?.text() ?: return null
        val poster = document.selectFirst("div.poster img")?.attr("src")
        val plot = document.selectFirst("div.detail-content-main")?.text()
        val year = document.selectFirst("h1.movie-title span.title-year")
            ?.text()?.trim()?.removeSurrounding("(", ")")?.toIntOrNull()
        val tags = document.select("div#tags a").map { it.text().trim() }

        val episodes = document.select("div.page-tap ul > li > a").map {
            val epName = it.selectFirst("span")?.text() ?: it.text()
            val epHref = it.attr("href")
            newEpisode(epHref) {
                name = epName.replace("Xem tập", "").trim()
            }
        }.reversed()

        val recommendations = document.select("div.top-movie li.film-item-ver").mapNotNull {
            val recTitle = it.selectFirst("p.data1")?.text() ?: return@mapNotNull null
            val recHref = it.selectFirst("a")?.attr("href") ?: return@mapNotNull null
            val recPoster = it.selectFirst("img.avatar")?.attr("src")
            newMovieSearchResponse(recTitle, recHref, TvType.Movie) {
                this.posterUrl = recPoster
            }
        }

        return newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
            this.posterUrl = poster
            this.plot = plot
            this.year = year
            this.tags = tags
            this.recommendations = recommendations
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
        
        // UPDATE: Fixed the logic to ensure episodeId is never null
        val episodeId = (if (data.contains(".html")) {
            // Case 1: ID is in the URL (old format)
            Regex(""".*-(\d+)\.html""").find(data)?.groupValues?.get(1)
        } else {
            // Case 2: ID is in the page script (new format)
            episodePage.body().html().let { html ->
                Regex(""""episode_id":(\d+),""").find(html)?.groupValues?.get(1)
            }
        }) ?: return false // If ID is not found in either case, stop.

        val servers = episodePage.select("div.list-server span.server")
        
        servers.apmap { server ->
            val serverId = server.attr("data-id")
            val ajaxUrl = "$mainUrl/ajax/get_link/"
            
            // This map is now safe because episodeId, serverId, and token are all non-nullable Strings
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
