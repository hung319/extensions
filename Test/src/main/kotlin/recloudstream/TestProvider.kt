// To be placed in app/src/main/java/recloudstream/
package recloudstream

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.loadExtractor // <= **ĐÃ THÊM IMPORT CHÍNH XÁC**
import org.jsoup.nodes.Element

/**
 * Main provider for SDFim
 * V1.6 - 2024-08-18
 * - Corrected build error by importing and using the 'loadExtractor' utility function correctly.
 * - This version should compile successfully.
 */
class SDFimProvider : MainAPI() {
    override var name = "SDFim"
    override var mainUrl = "https://sdfim.net"
    override var lang = "vi"
    override val hasMainPage = true
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries, TvType.Anime)

    // ... (Các hàm getMainPage, search, load, toSearchResult không thay đổi) ...

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
        // Call the imported `loadExtractor` function from the utils package.
        // This is the correct implementation.
        return loadExtractor(playerIframeUrl, data, subtitleCallback, callback)
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
}
