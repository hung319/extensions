// To be placed in app/src/main/java/recloudstream/
package recloudstream

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.getAndUnpack
import com.lagradost.cloudstream3.utils.Qualities
import org.jsoup.nodes.Element

/**
 * Main provider for SDFim
 * V1.3 - 2024-08-18
 * - Reworked loadLinks to handle iframe and obfuscated player URL. This is the correct method.
 */
class SDFimProvider : MainAPI() {
    override var name = "SDFim"
    override var mainUrl = "https://sdfim.net"
    override var lang = "vi"
    override val hasMainPage = true
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries, TvType.Anime)

    // ... (Các hàm getMainPage, search, load, toSearchResult không thay đổi) ...

    // Function to extract video links for an episode
    override suspend fun loadLinks(
        data: String, // This will be the episode URL
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        // Step 1: Get the episode page HTML
        val episodeDocument = app.get(data).document
        
        // Step 2: Find the iframe URL from the player container
        // The iframe is dynamically loaded into div#ip_player
        val playerIframeUrl = episodeDocument.selectFirst("div#ip_player iframe")?.attr("src") 
            ?: return false // Return if no iframe is found

        // The URL looks like: https://ssplay.net/v2/embed/31055/...
        // We can directly call this URL to get the player page
        if (!playerIframeUrl.startsWith("http")) return false

        // Step 3: Fetch the player page and extract the real video link
        // The player page often contains packed/obfuscated JavaScript that reveals the source
        return getAndUnpack(playerIframeUrl, referer = data).await.forEach { link ->
             if (link.url.contains(".m3u8")) {
                callback.invoke(
                    ExtractorLink(
                        source = this.name,
                        name = this.name, // Name can be simple
                        url = link.url,
                        referer = "https://ssplay.net/", // Referer should be the player domain
                        quality = Qualities.Unknown.value,
                        type = ExtractorLinkType.M3U8,
                    )
                )
             }
        }
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
            Episode(it.attr("href"), name = it.text())
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
