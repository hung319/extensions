// To be placed in app/src/main/java/recloudstream/
package recloudstream

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.loadExtractor
import org.jsoup.nodes.Element

/**
 * Main provider for SDFim
 * V2.2 - 2025-08-18
 * - Updated the `load` function with new selectors for the movie info page.
 * - Added extraction for tags, year, and rating.
 */
class SDFimProvider : MainAPI() {
    override var name = "SDFim"
    override var mainUrl = "https://sdfim.net"
    override var lang = "vi"
    override val hasMainPage = true
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries, TvType.Anime)

    // ... (getMainPage, search, toSearchResult from V2.1 are correct and unchanged) ...
    
    // ================== CHANGE START ==================
    override suspend fun load(url: String): LoadResponse? {
        val document = app.get(url).document

        val title = document.selectFirst("div.mvic-desc h3")?.text()?.trim() ?: return null
        val posterUrl = document.selectFirst("div.mvic-thumb img")?.attr("src")
        val plot = document.selectFirst("div.mvic-desc div.desc")?.text()?.trim()
        
        // Extract tags/genres
        val tags = document.select("div.mvici-left p:contains(Thể loại) a").map { it.text() }
        
        // Extract year
        val year = document.selectFirst("div.mvici-right p:contains(Năm SX) a")?.text()?.toIntOrNull()

        // Extract rating
        val rating = document.selectFirst("div.imdb_r span.imdb-r")?.text()?.toRatingInt()

        // New selector for episodes
        val episodes = document.select("div.les-content a").map {
            newEpisode(it.attr("href")) {
                this.name = it.text()
            }
        }.reversed()

        return if (episodes.isNotEmpty()) {
            newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
                this.posterUrl = posterUrl
                this.plot = plot
                this.tags = tags
                this.year = year
                this.rating = rating
            }
        } else {
            // It's a movie, so there are no episodes listed this way. The watch button is the data.
            newMovieLoadResponse(title, url, TvType.Movie, url) {
                this.posterUrl = posterUrl
                this.plot = plot
                this.tags = tags
                this.year = year
                this.rating = rating
            }
        }
    }
    // =================== CHANGE END ===================

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        // The loadLinks logic might need adjustment if the player page has also changed.
        // Assuming it's the same for now.
        val document = app.get(data).document
        // Let's find the iframe on the new page structure as well.
        // The old selector was `div#ip_player iframe`, this might need verification.
        // If the player is on the same page, the selector could be different.
        // Based on the new HTML, the player is likely loaded into `div#content-embed`.
        // A robust selector would be `div#content-embed iframe` or just `iframe`.
        val playerIframeUrl = document.selectFirst("iframe")?.attr("src")
            ?: return false
        if (!playerIframeUrl.startsWith("http")) return false
        return loadExtractor(playerIframeUrl, data, subtitleCallback, callback)
    }

    // --- Các hàm không thay đổi từ V2.1 ---
    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val document = app.get(mainUrl).document
        val homePageList = ArrayList<HomePageList>()

        document.select("div.movies-list-wrap").forEach { block ->
            val title = block.selectFirst("div.ml-title span.pull-left")?.text() ?: "Unknown Category"
            val movies = block.select("div.ml-item").mapNotNull { it.toSearchResult() }
            if (movies.isNotEmpty()) {
                homePageList.add(HomePageList(title, movies))
            }
        }
        return HomePageResponse(homePageList)
    }
    
    private fun Element.toSearchResult(): SearchResponse? {
        val linkElement = this.selectFirst("a.ml-mask") ?: return null
        val href = linkElement.attr("href")
        val title = linkElement.selectFirst("span.mli-info h2")?.text() ?: return null
        val posterUrl = this.selectFirst("img.mli-thumb")?.attr("data-original")
        
        return newAnimeSearchResponse(title, href) {
            this.posterUrl = posterUrl
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val searchUrl = "$mainUrl/?s=$query"
        val document = app.get(searchUrl).document
        return document.select("div.ml-item").mapNotNull { it.toSearchResult() }
    }
}
