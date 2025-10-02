package recloudstream

/*
* @CloudstreamProvider: BokepIndoProvider
* @Version: 3.1
* @Author: Coder
* @Language: id
* @TvType: Nsfw
* @Url: https://bokepindoh.monster
* @Info: Fixed compilation error in toSearchResponse.
*/

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Element
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import java.net.URI

class TestProvider : MainAPI() {
    override var name = "BokepIndo"
    override var mainUrl = "https://bokepindoh.monster"
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

        val scriptContent = document.select("script").firstOrNull { it.data().contains("wpst_ajax_var") }?.data() ?: ""
        val embedUrlRegex = Regex("""["']embed_url["']:\s*["']<iframe src=\\"(.*?)\\"""")
        val videoUrlRegex = Regex("""["']video_url["']:\s*["']<iframe.*?src=\\"(.*?)\\"""")

        embedUrlRegex.find(scriptContent)?.groupValues?.get(1)?.let { servers.add(it) }
        videoUrlRegex.find(scriptContent)?.groupValues?.get(1)?.let { servers.add(it) }

        if (servers.isEmpty()) {
             document.select("div.responsive-player iframe").forEach { element ->
                servers.add(element.attr("src"))
            }
        }

        if (servers.isEmpty()) return false

        coroutineScope {
            servers.map { serverUrl ->
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
        
        // SỬA LỖI TẠI ĐÂY: Dùng newMovieSearchResponse thay vì newMovieLoadResponse
        return newMovieSearchResponse(title, href) { 
            this.posterUrl = posterUrl 
        }
    }
}
