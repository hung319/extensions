// To be placed in app/src/main/java/recloudstream/
package recloudstream

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.loadExtractor
import org.jsoup.nodes.Element

/**
 * Main provider for SDFim
 * V2.0 - 2025-08-18
 * - Fixed build error by setting userAgent in the init block instead of overriding it.
 * - This should be the final, working version.
 */
class SDFimProvider : MainAPI() {
    override var name = "SDFim"
    override var mainUrl = "https://sdfim.net"
    override var lang = "vi"
    override val hasMainPage = true
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries, TvType.Anime)
    
    // ================== CHANGE START ==================
    // Set the User-Agent in the init block. This is the correct way.
    init {
        this.userAgent = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/108.0.0.0 Safari/537.36"
    }
    // =================== CHANGE END ===================

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
        val playerIframeUrl = episodeDocument.selectFirst("div#ip_player iframe")?.attr("src")
            ?: return false
        if (!playerIframeUrl.startsWith("http")) return false
        return loadExtractor(playerIframeUrl, data, subtitleCallback, callback)
    }
}
