package recloudstream

/*
* @CloudstreamProvider: BokepIndoProvider
* @Version: 3.3
* @Author: Coder
* @Language: id
* @TvType: Nsfw
* @Url: https://bokepindoh.wtf
* @Info: Full implementation with fixed Coroutines and extracting logic from Meta/Script/DOM.
*/

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Element
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope

class BokepIndoProvider : MainAPI() {
    override var name = "BokepIndo"
    override var mainUrl = "https://bokepindoh.wtf"
    override var supportedTypes = setOf(TvType.NSFW)
    override var lang = "id"
    override var hasMainPage = true
    override var hasDownloadSupport = true

    override val mainPage = mainPageOf(
        mainUrl to "Latest",
        "$mainUrl/category/bokep-indo/" to "Bokep Indo",
        "$mainUrl/category/bokep-viral/" to "Bokep Viral",
        "$mainUrl/category/bokep-jav/" to "Bokep JAV"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = if (page == 1) request.data else "${request.data.removeSuffix("/")}/page/$page/"
        val document = app.get(url).document
        val homePageList = document.select("article.loop-video.thumb-block").mapNotNull { it.toSearchResponse() }
        return newHomePageResponse(HomePageList(request.name, homePageList), hasNext = homePageList.isNotEmpty())
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val document = app.get("$mainUrl/?s=${query.replace(" ", "+")}").document
        return document.select("article.loop-video.thumb-block").mapNotNull { it.toSearchResponse() }
    }

    override suspend fun load(url: String): LoadResponse {
        val document = app.get(url).document
        val title = document.selectFirst("h1.entry-title")?.text() ?: "No Title"
        val poster = document.selectFirst("meta[property='og:image']")?.attr("content")
        val description = document.selectFirst("div.video-description .desc")?.text()
        val tags = document.select("div.tags-list a.label").map { it.text() }
        val recommendations = document.select("div.under-video-block article.loop-video.thumb-block").mapNotNull { it.toSearchResponse() }

        return newMovieLoadResponse(title, url, TvType.NSFW, url) {
            this.posterUrl = poster
            this.plot = description
            this.tags = tags
            this.recommendations = recommendations
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val document = app.get(data).document
        val servers = mutableListOf<String>()

        // 1. Extract from Meta Tag (Highest Priority & Cleanest)
        // Target: <meta itemprop="embedURL" content="https://bebasnonton.online/..." />
        document.selectFirst("meta[itemprop='embedURL']")?.attr("content")?.let { url ->
            if (url.isNotBlank()) servers.add(fixUrl(url))
        }

        // 2. Extract from Script 'wpst_ajax_var'
        // Target: "embed_url":"\u003Ciframe src=\"URL\"..."
        val scriptContent = document.select("script").firstOrNull { 
            it.data().contains("wpst_ajax_var") 
        }?.data()

        if (scriptContent != null) {
            // Regex to capture content between src=\" and \" inside the script
            val regex = Regex("""src=\\"(.*?)\\"""")
            
            regex.findAll(scriptContent).forEach { match ->
                val rawUrl = match.groupValues[1]
                // Fix escaped slashes (e.g., https:\/\/ -> https://)
                val cleanUrl = rawUrl.replace("\\/", "/")
                servers.add(fixUrl(cleanUrl))
            }
        }

        // 3. Fallback: Direct DOM elements (if JS didn't render or Meta missing)
        document.select("div.responsive-player iframe, div#bkpdo-player-container iframe").forEach { element ->
            val src = element.attr("src")
            if (src.isNotBlank()) {
                servers.add(fixUrl(src))
            }
        }

        // 4. Load Extractors safely using standard Coroutines
        val uniqueServers = servers.distinct()
        
        if (uniqueServers.isEmpty()) return false

        // Standard way to run async tasks in Cloudstream providers
        coroutineScope {
            uniqueServers.map { serverUrl ->
                async {
                    loadExtractor(serverUrl, data, subtitleCallback, callback)
                }
            }.awaitAll()
        }

        return true
    }

    private fun Element.toSearchResponse(): SearchResponse? {
        val linkTag = this.selectFirst("a") ?: return null
        val href = fixUrl(linkTag.attr("href"))
        if (href.isBlank()) return null
        val title = linkTag.selectFirst("header.entry-header span")?.text() ?: return null
        val posterUrl = fixUrlNull(linkTag.selectFirst("div.post-thumbnail-container img")?.attr("data-src"))
        
        return newMovieSearchResponse(title, href) { 
            this.posterUrl = posterUrl 
        }
    }
}
