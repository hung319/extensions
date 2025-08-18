// To be placed in app/src/main/java/recloudstream/
package recloudstream

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink 
import com.lagradost.cloudstream3.utils.loadExtractor
import org.jsoup.nodes.Element

/**
 * Main provider for SDFim
 * V2.8 - 2025-08-18
 * - Added detailed logging to `loadLinks` and throws an Exception for debugging purposes.
 */
class SDFimProvider : MainAPI() {
    override var name = "SDFim"
    override var mainUrl = "https://sdfim.net"
    override var lang = "vi"
    override var hasMainPage = true
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries, TvType.Anime)

    // ... (Các hàm getMainPage, search, load, toSearchResult giữ nguyên) ...

    // ================== CHANGE START ==================
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        // Build a log string for debugging
        var log = "--- START SDFim loadLinks DEBUG ---\n"
        log += "Data URL: $data\n"

        val document = app.get(data).document
        log += "Fetched document HTML (first 500 chars): ${document.html().take(500)}\n\n"

        var foundLinks = false

        // Logic for movies with multiple server tabs
        val movieServers = document.select("div.player_nav ul.idTabs li")
        log += "Found ${movieServers.size} potential movie servers using selector 'div.player_nav ul.idTabs li'.\n"

        if (movieServers.isNotEmpty()) {
            movieServers.forEachIndexed { index, serverTab ->
                val tabId = serverTab.attr("data")
                val serverName = serverTab.selectFirst("strong")?.text() ?: "Unknown"
                val iframe = document.selectFirst("div#$tabId iframe")
                val iframeSrc = iframe?.attr("src")
                
                log += "  - Server #${index + 1}: Name='${serverName}', TabID='${tabId}', IframeSrc='${iframeSrc}'\n"

                // Only process if it's a Google Drive link
                if (iframeSrc != null && iframeSrc.contains("drive.google.com")) {
                    log += "    -> Found Google Drive link. Attempting to load extractor...\n"
                    if (loadExtractor(iframeSrc, data, subtitleCallback, callback)) {
                        foundLinks = true
                    }
                } else {
                     log += "    -> Skipping, not a Google Drive link.\n"
                }
            }
        } else {
            // Logic for TV series or single-player movies
            log += "No movie server tabs found. Checking for single player iframe using selector 'div#content-embed iframe'.\n"
            val iframe = document.selectFirst("div#content-embed iframe")
            val iframeSrc = iframe?.attr("src")
            log += "  - Found single iframe with src: '${iframeSrc}'\n"
            
            // Only process if it's a Google Drive link
            if (iframeSrc != null && iframeSrc.contains("drive.google.com")) {
                log += "    -> Found Google Drive link. Attempting to load extractor...\n"
                foundLinks = loadExtractor(iframeSrc, data, subtitleCallback, callback)
            } else {
                 log += "    -> Skipping, not a Google Drive link.\n"
            }
        }
        
        log += "Finished processing. Found links: $foundLinks\n"
        log += "--- END SDFim loadLinks DEBUG ---"
        
        // Throw exception to display the entire log
        throw Exception(log)
    }
    // =================== CHANGE END ===================

    // --- Các hàm không thay đổi ---
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
    
    override suspend fun load(url: String): LoadResponse? {
        val document = app.get(url).document

        val title = document.selectFirst("div.mvic-desc h3")?.text()?.trim() ?: return null
        val posterUrl = document.selectFirst("div.mvic-thumb img")?.attr("src")
        val plot = document.selectFirst("div.mvic-desc div.desc")?.text()?.trim()
        val tags = document.select("div.mvici-left p:contains(Thể loại) a").map { it.text() }
        val year = document.selectFirst("div.mvici-right p:contains(Năm SX) a")?.text()?.toIntOrNull()
        val rating = document.selectFirst("div.imdb_r span.imdb-r")?.text()?.toRatingInt()

        val tvSeriesEpisodes = document.select("div#seasons div.les-content a")
        
        return if (tvSeriesEpisodes.isNotEmpty()) {
            val episodes = tvSeriesEpisodes.map {
                newEpisode(it.attr("href")) {
                    this.name = it.text().trim()
                }
            }.reversed()

            newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
                this.posterUrl = posterUrl
                this.plot = plot
                this.tags = tags
                this.year = year
                this.rating = rating
            }
        } else {
            newMovieLoadResponse(title, url, TvType.Movie, url) {
                this.posterUrl = posterUrl
                this.plot = plot
                this.tags = tags
                this.year = year
                this.rating = rating
            }
        }
    }
}
