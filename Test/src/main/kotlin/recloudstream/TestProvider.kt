// To be placed in app/src/main/java/recloudstream/
package recloudstream

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.runExtractor // Import the correct utility
import org.jsoup.nodes.Element

/**
 * Main provider for SDFim
 * V1.4 - 2025-08-18
 * - Fixed build errors by using runExtractor for iframe embeds.
 * - Updated deprecated Episode constructor to newEpisode helper.
 */
class SDFimProvider : MainAPI() {
    override var name = "SDFim"
    override var mainUrl = "https://sdfim.net"
    override var lang = "vi"
    override val hasMainPage = true
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries, TvType.Anime)

    // ... (Các hàm getMainPage, search, toSearchResult không thay đổi) ...

    override suspend fun load(url: String): LoadResponse? {
        val document = app.get(url).document
        val title = document.selectFirst("h1.film-title")?.text()?.trim() ?: return null
        val posterUrl = document.selectFirst("div.film-thumbnail img")?.attr("src")
        val plot = document.selectFirst("div.film-description div.film-text")?.text()?.trim()
        
        // ================== CHANGE START ==================
        // Use newEpisode helper instead of the deprecated constructor
        val episodes = document.select("div.ip_episode_list a.btn.btn-sm.btn-episode").map {
            newEpisode(it.attr("href")) {
                this.name = it.text()
            }
        }.reversed()
        // =================== CHANGE END ===================

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

    // Function to extract video links for an episode
    override suspend fun loadLinks(
        data: String, // This will be the episode URL
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val episodeDocument = app.get(data).document
        
        val playerIframeUrl = episodeDocument.selectFirst("div#ip_player iframe")?.attr("src") ?: return false
        if (!playerIframeUrl.startsWith("http")) return false

        // ================== CHANGE START ==================
        // Use runExtractor to let CloudStream handle the iframe URL.
        // This is the correct and most robust way.
        return runExtractor(playerIframeUrl, data, subtitleCallback, callback)
        // =================== CHANGE END ===================
    }
    
    // --- Các hàm không thay đổi ---
    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val document = app.get(mainUrl).document
        val homePageList = ArrayList<HomePageList>()
        document.select("div.block.film-list").forEach { block ->
            val title = block.selectFirst("h2.block-title")?.text() ?: "Unknown Category"
            val movies = block.select("div.item.film-item").mapNotNull { it.toSearchResult() }
            if (movies.isNotEmpty()) homePageList.add(HomePageList(title, movies))
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
}
