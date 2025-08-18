// To be placed in app/src/main/java/recloudstream/
package recloudstream

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.loadExtractor
import org.jsoup.nodes.Element

/**
 * Main provider for SDFim
 * V2.3 - 2025-08-18
 * - Corrected logic in `load` to differentiate between Movies and TV Series.
 * - `loadLinks` now properly handles multiple servers for Movies.
 * - Ensured TV Series episodes are reversed correctly.
 * - Updated all selectors to match the latest site structure.
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
        val tags = document.select("div.mvici-left p:contains(Thể loại) a").map { it.text() }
        val year = document.selectFirst("div.mvici-right p:contains(Năm SX) a")?.text()?.toIntOrNull()
        val rating = document.selectFirst("div.imdb_r span.imdb-r")?.text()?.toRatingInt()

        // Check for TV series episodes. This is the most reliable way to distinguish.
        val tvSeriesEpisodes = document.select("div#seasons div.les-content a")
        
        return if (tvSeriesEpisodes.isNotEmpty()) {
            // It's a TV Series
            val episodes = tvSeriesEpisodes.map {
                newEpisode(it.attr("href")) {
                    this.name = it.text().trim()
                }
            }.reversed() // Reverse episode order as requested

            newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
                this.posterUrl = posterUrl
                this.plot = plot
                this.tags = tags
                this.year = year
                this.rating = rating
            }
        } else {
            // It's a Movie. The main URL itself is the "episode".
            newMovieLoadResponse(title, url, TvType.Movie, url) {
                this.posterUrl = posterUrl
                this.plot = plot
                this.tags = tags
                this.year = year
                this.rating = rating
            }
        }
    }

    override suspend fun loadLinks(
        data: String, // This is the movie/episode URL
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val document = app.get(data).document
        var foundLinks = false

        // Logic for Movies with multiple server tabs
        val movieServers = document.select("div.player_nav ul.idTabs li")
        if (movieServers.isNotEmpty()) {
            movieServers.forEach { serverTab ->
                val serverName = serverTab.selectFirst("strong")?.text() ?: "Server"
                val tabId = serverTab.attr("data")
                val iframe = document.selectFirst("div#$tabId iframe")
                val iframeSrc = iframe?.attr("src")
                
                if (iframeSrc != null && iframeSrc.startsWith("http")) {
                    // Create a custom callback to add the server name to the extracted link
                    val customCallback = { link: ExtractorLink ->
                        callback.invoke(link.copy(name = "$serverName - ${link.name}"))
                    }
                    foundLinks = loadExtractor(iframeSrc, data, subtitleCallback, customCallback) || foundLinks
                }
            }
        } else {
            // Logic for TV Series episodes (or movies with a single player)
            val iframe = document.selectFirst("div#content-embed iframe")
            val iframeSrc = iframe?.attr("src")
            if (iframeSrc != null && iframeSrc.startsWith("http")) {
                foundLinks = loadExtractor(iframeSrc, data, subtitleCallback, callback)
            }
        }
        
        return foundLinks
    }
    // =================== CHANGE END ===================

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
