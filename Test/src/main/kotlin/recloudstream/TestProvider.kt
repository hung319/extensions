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

    // UPDATE: The entire 'load' function has been updated with new selectors
    override suspend fun load(url: String): LoadResponse? {
        val document = app.get(url).document

        // New selector for the title from the <h1> tag
        val title = document.selectFirst("h1.movie-title span.title-1")?.text() ?: return null
        
        // Poster selector is still the same
        val poster = document.selectFirst("div.poster img")?.attr("src")
        
        // New selector for the plot/synopsis
        val plot = document.selectFirst("div.detail-content-main")?.text()
        
        // New selector for the year
        val year = document.selectFirst("h1.movie-title span.title-year")
            ?.text()?.trim()?.removeSurrounding("(", ")")?.toIntOrNull()
            
        // New selector for tags
        val tags = document.select("div#tags a").map { it.text().trim() }

        // New selector for the episode list
        val episodes = document.select("div.page-tap ul > li > a").map {
            // The episode name is now inside a <span>
            val epName = it.selectFirst("span")?.text() ?: it.text()
            val epHref = it.attr("href")
            newEpisode(epHref) {
                name = epName.replace("Xem tập", "").trim()
            }
        }.reversed() // Reverse the list to show episode 1 first

        // New selector for recommendations
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
        // This function does not need changes as the viewing page logic seems to be the same
        val episodePage = app.get(data).document
        
        val token = episodePage.selectFirst("input#token")?.attr("value") ?: return false
        // The episode ID might be in the URL differently, let's check
        // Example: /phim/than-tuong-con-thu/tap-1 -> this doesn't contain the ID.
        // We need to find the ID from the script or AJAX calls on the page.
        // Let's assume the old logic for getting links from `loadlinks.html` is still valid for now.
        // If it fails, this part needs re-investigation by checking network requests on the website.
        val episodeIdRegex = Regex(""".*-(\d+)\.html""")
        val episodeId = if(data.contains(".html")) episodeIdRegex.find(data)?.groupValues?.get(1) else {
            // If the URL is like /phim/abc/tap-1, we need to find the ID inside the page
            // This requires re-inspecting the watch page. For now, let's assume it fails gracefully.
             episodePage.body().html().let {
                Regex(""""episode_id":(\d+),""").find(it)?.groupValues?.get(1)
            } ?: return false
        }
        
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
