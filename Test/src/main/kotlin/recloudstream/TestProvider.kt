package recloudstream

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

    // Tag dùng để filter trong Logcat
    private val TAG = "[BokepIndoDebug]"

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
        println("$TAG: ================= START LOADLINKS ==================")
        println("$TAG: Requesting URL: $data")

        val document = app.get(data).document
        val servers = mutableListOf<String>()

        // 1. Extract from Meta Tag (Highest Priority & Cleanest)
        val metaUrl = document.selectFirst("meta[itemprop='embedURL']")?.attr("content")
        if (!metaUrl.isNullOrBlank()) {
            val fixedMeta = fixUrl(metaUrl)
            println("$TAG: Found Meta embedURL: $fixedMeta")
            servers.add(fixedMeta)
        } else {
            println("$TAG: Meta embedURL not found or empty")
        }

        // 2. Extract from Script 'wpst_ajax_var'
        val scriptContent = document.select("script").firstOrNull { 
            it.data().contains("wpst_ajax_var") 
        }?.data()

        if (scriptContent != null) {
            println("$TAG: Found script containing 'wpst_ajax_var'")
            val regex = Regex("""src=\\"(.*?)\\"""")
            
            val matches = regex.findAll(scriptContent).toList()
            println("$TAG: Regex matches found: ${matches.size}")

            matches.forEach { match ->
                val rawUrl = match.groupValues[1]
                val cleanUrl = rawUrl.replace("\\/", "/")
                val fixedUrl = fixUrl(cleanUrl)
                println("$TAG: Extracted from Script: $fixedUrl")
                servers.add(fixedUrl)
            }
        } else {
            println("$TAG: Script 'wpst_ajax_var' NOT found")
        }

        // 3. Fallback: Direct DOM elements
        val iframes = document.select("div.responsive-player iframe, div#bkpdo-player-container iframe")
        println("$TAG: Found ${iframes.size} iframe elements in DOM")
        
        iframes.forEach { element ->
            val src = element.attr("src")
            if (src.isNotBlank()) {
                val fixedSrc = fixUrl(src)
                println("$TAG: Extracted from DOM Iframe: $fixedSrc")
                servers.add(fixedSrc)
            }
        }

        // 4. Load Extractors
        val uniqueServers = servers.distinct()
        println("$TAG: Total unique servers to load: ${uniqueServers.size}")
        println("$TAG: Server List: $uniqueServers")
        
        if (uniqueServers.isEmpty()) {
            println("$TAG: No servers found. Returning false.")
            return false
        }

        coroutineScope {
            uniqueServers.map { serverUrl ->
                async {
                    println("$TAG: Invoking loadExtractor for: $serverUrl")
                    loadExtractor(serverUrl, data, subtitleCallback, callback)
                }
            }.awaitAll()
        }

        println("$TAG: ================= END LOADLINKS ==================")
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
