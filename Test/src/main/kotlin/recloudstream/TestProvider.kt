// Save this as SupJav.kt
package com.recloudstream // 1. Package changed

import android.content.Context
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.loadExtractor
import org.jsoup.nodes.Element

// 2. Plugin loader class has been removed. 
// The provider class itself will be registered directly.
class SupJav : MainAPI() {
    override var name = "SupJav"
    override var mainUrl = "https://supjav.com"
    override var lang = "en"
    override val hasMainPage = true // Enable the main page feature
    override val supportedTypes = setOf(TvType.NSFW)

    // Helper function to parse a list of video items from a given HTML element.
    // This helps avoid code duplication between getMainPage() and search().
    private fun parseVideoList(element: Element): List<SearchResponse> {
        return element.select("div.post").mapNotNull {
            val titleElement = it.selectFirst("div.con h3 a")
            val title = titleElement?.attr("title") ?: return@mapNotNull null
            val href = titleElement.attr("href")
            // Use attr("data-original") for lazy-loaded images, fallback to "src"
            val posterUrl = it.selectFirst("a.img img.thumb")?.let { img ->
                img.attr("data-original").ifBlank { img.attr("src") }
            }

            newTvShowSearchResponse(title, href, TvType.NSFW) {
                this.posterUrl = posterUrl
            }
        }
    }

    // 3. Implement getMainPage to build the homepage content
    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val document = app.get(mainUrl).document
        val allPages = ArrayList<HomePageList>()

        // Find all content blocks on the main page (e.g., "Censored", "Uncensored", etc.)
        document.select("div.contents > div.content").forEach { contentBlock ->
            val title = contentBlock.selectFirst(".archive-title h1")?.text() ?: "Unknown Category"
            val videoList = parseVideoList(contentBlock)
            if (videoList.isNotEmpty()) {
                allPages.add(HomePageList(title, videoList))
            }
        }

        return HomePageResponse(allPages)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val url = "$mainUrl/?s=$query"
        val document = app.get(url).document
        // Reuse the helper function to parse search results
        return parseVideoList(document)
    }

    override suspend fun load(url: String): LoadResponse {
        val document = app.get(url).document

        val title = document.selectFirst("div.archive-title h1")?.text()?.trim()
            ?: "No title found"
        val poster = document.selectFirst("div.post-meta img")?.attr("src")
        val plot = "Watch ${title} on SupJav"
        val tags = document.select("div.tags a").map { it.text() }
        
        val episodes = document.select("div.btns a.btn-server").mapNotNull { server ->
            val encryptedData = server.attr("data-link")
            if (encryptedData.isBlank()) return@mapNotNull null
            val serverName = server.text()
            
            newEpisode(encryptedData) {
                this.name = "Server $serverName"
            }
        }.reversed()

        return newTvShowLoadResponse(title, url, TvType.NSFW, episodes) {
            this.posterUrl = poster
            this.plot = plot
            this.tags = tags
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val reversedData = data.reversed()
        val intermediatePageUrl1 = "https://lk1.supremejav.com/supjav.php?c=$reversedData"
        
        val intermediatePage1Doc = app.get(
            intermediatePageUrl1,
            referer = "$mainUrl/"
        ).document

        val finalPlayerUrl = intermediatePage1Doc.selectFirst("iframe")?.attr("src") ?: return false

        return loadExtractor(finalPlayerUrl, intermediatePageUrl1, subtitleCallback, callback)
    }
}
