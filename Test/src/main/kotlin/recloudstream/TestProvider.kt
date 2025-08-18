// To be placed in app/src/main/java/recloudstream/
package recloudstream

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.loadExtractor
import org.jsoup.nodes.Element

/**
 * Main provider for SDFim
 * V1.8 - 2025-08-18
 * - Reverted API-based approach as it was incorrect.
 * - Added a standard User-Agent to mimic a real browser and bypass server blocking.
 * - This should fix the homepage and search not loading issues.
 */
class SDFimProvider : MainAPI() {
    override var name = "SDFim"
    override var mainUrl = "https://sdfim.net"
    override var lang = "vi"
    override val hasMainPage = true
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries, TvType.Anime)
    
    // ================== CHANGE START ==================
    // Add a standard browser User-Agent to all requests from this provider
    override var userAgent = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/108.0.0.0 Safari/537.36"
    // =================== CHANGE END ===================

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val document = app.get(mainUrl).document
        val homePageList = ArrayList<HomePageList>()
        
        // Using the selectors that were correct for the sample HTML files
        document.select("div.block.film-list").forEach { block ->
            val title = block.selectFirst("h2.block-title")?.text() ?: "Unknown Category"
            val movies = block.select("div.item.film-item").mapNotNull { it.toSearchResult() }
            if (movies.isNotEmpty()) {
                homePageList.add(HomePageList(title, movies))
            }
        }
        return HomePageResponse(homePageList)
    }
    
    private fun Element.toSearchResult(): SearchResponse? {
        val linkElement = this.selectFirst("h3.film-title a") ?: return null
        val title = linkElement.text()
        val href = linkElement.attr("href")
        val posterUrl = this.selectFirst("div.film-thumbnail img")?.attr("data-src")
        return newAnimeSearchResponse(title, href) { this.posterUrl = posterUrl }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val document = app.get("$mainUrl/tim-kiem/$query/").document
        return document.select("div.item.film-item").mapNotNull { it.toSearchResult() }
    }
    
    override suspend fun load(url: String): LoadResponse? {
        val document = app.get(url).document
        val title = document.selectFirst("h1.film-title")?.text()?.trim() ?: return null
        val posterUrl = document.selectFirst("div.film-thumbnail img")?.attr("src")
        val plot = document.selectFirst("div.film-description div.film-text")?.text()?.trim()
        
        val episodes = document.select("div.ip_episode_list a.btn.btn-sm.btn-episode").map {
            newEpisode(it.attr("href")) {
                this.name = it.text()
            }
        }.reversed()

        return if (episodes.isNotEmpty()) {
            newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
                this.posterUrl = posterUrl
                this.plot = plot
            }
        } else {
            newMovieLoadResponse(title, url, TvType.Movie, url) {
                this.posterUrl = posterUrl
                this.plot = plot
            }
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val episodeDocument = app.get(data).document
        val playerIframeUrl = episodeDocument.selectFirst("div#ip_player iframe")?.attr("src") ?: return false
        if (!playerIframeUrl.startsWith("http")) return false
        return loadExtractor(playerIframeUrl, data, subtitleCallback, callback)
    }
}
