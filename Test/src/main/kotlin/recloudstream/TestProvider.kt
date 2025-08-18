// To be placed in app/src/main/java/recloudstream/
package recloudstream

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.loadExtractor
import org.jsoup.nodes.Element

/**
 * Main provider for SDFim
 * V2.5 - 2025-08-18
 * - Fixed build error by using a simpler, more compatible ExtractorLink constructor.
 */
class SDFimProvider : MainAPI() {
    override var name = "SDFim"
    override var mainUrl = "https://sdfim.net"
    override var lang = "vi"
    override val hasMainPage = true
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries, TvType.Anime)

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

    // ================== CHANGE START ==================
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val document = app.get(data).document
        var foundLinks = false

        val movieServers = document.select("div.player_nav ul.idTabs li")
        if (movieServers.isNotEmpty()) {
            movieServers.forEach { serverTab ->
                val serverName = serverTab.selectFirst("strong")?.text() ?: "Server"
                val tabId = serverTab.attr("data")
                val iframe = document.selectFirst("div#$tabId iframe")
                val iframeSrc = iframe?.attr("src")
                
                if (iframeSrc != null && iframeSrc.startsWith("http")) {
                    val customCallback = { link: ExtractorLink ->
                        // Use a simpler constructor to ensure compatibility
                        callback.invoke(
                            ExtractorLink(
                                source = link.source,
                                name = "$serverName - ${link.name}", // Set the new name
                                url = link.url,
                                referer = link.referer,
                                quality = link.quality,
                                type = if (link.url.contains(".m3u8")) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                            )
                        )
                    }
                    foundLinks = loadExtractor(iframeSrc, data, subtitleCallback, customCallback) || foundLinks
                }
            }
        } else {
            val iframe = document.selectFirst("div#content-embed iframe")
            val iframeSrc = iframe?.attr("src")
            if (iframeSrc != null && iframeSrc.startsWith("http")) {
                foundLinks = loadExtractor(iframeSrc, data, subtitleCallback, callback)
            }
        }
        
        return foundLinks
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
}
