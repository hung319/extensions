// Save this file as XTapesProvider.kt
package recloudstream

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Element
import com.lagradost.cloudstream3.utils.M3u8Helper

class XTapesProvider : MainAPI() {
    override var mainUrl = "https://xtapes.in"
    override var name = "XTapes"
    override val hasMainPage = true
    override var lang = "en"
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(
        TvType.NSFW
    )

    // Helper function to parse video items from the page
    private fun Element.toSearchResponse(): SearchResponse? {
        val link = this.selectFirst("a") ?: return null
        val href = fixUrl(link.attr("href"))
        // Skip if the link is not a video page
        if (!href.contains("xtapes.in/") || href.endsWith(".in/")) return null
        
        val title = link.attr("title")
        val posterUrl = fixUrlNull(this.selectFirst("img")?.attr("src"))

        return newMovieSearchResponse(title, href, TvType.NSFW) {
            this.posterUrl = posterUrl
        }
    }

    override val mainPageLinks = mainPageLinksOf(
        "New Videos" to mainUrl,
        "New Movies" to "$mainUrl/porn-movies-hd/"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = if (page > 1) "${request.data}page/$page/" else request.data
        val document = app.get(url).document
        
        val list = document.select("ul.listing-tube > li, ul.listing-videos > li")
        val homePageList = list.mapNotNull { it.toSearchResponse() }
        
        return newHomePageResponse(request.name, homePageList)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val searchUrl = "$mainUrl/?s=$query"
        val document = app.get(searchUrl).document

        return document.select("ul.listing-videos.listing-tube > li").mapNotNull {
            it.toSearchResponse()
        }
    }

    override suspend fun load(url: String): LoadResponse? {
        val document = app.get(url).document

        val title = document.selectFirst("h1.border-radius-top-5")?.text()?.trim() ?: return null
        val poster = fixUrlNull(document.selectFirst("meta[property=og:image]")?.attr("content"))
        val tags = document.select("#cat-tag li a").map { it.text() }
        val synopsis = document.selectFirst("meta[name=description]")?.attr("content")
        
        val embedUrls = document.select(".video-embed iframe").mapNotNull { it.attr("src") }.filter { it.isNotBlank() }

        val recommendations = document.select(".sidebar-widget:has(> .widget-title > span:contains(Related Videos)) ul.listing-tube > li").mapNotNull {
            it.toSearchResponse()
        }

        // Sửa lỗi: Dùng newMovieLoadResponse thay vì newNsfwLoadResponse
        return newMovieLoadResponse(title, url, TvType.NSFW, embedUrls) {
            this.posterUrl = poster
            this.plot = synopsis
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
        val embedUrls = parseJson<List<String>>(data)
        
        embedUrls.apmap { url ->
            if (url.contains("74k.io")) {
                try {
                    val doc = app.get(url, referer = mainUrl).document
                    val script = doc.select("script").find { it.data().contains("eval(function(p,a,c,k,e,d)") }?.data()
                    if (script != null) {
                        val unpacked = getAndUnpack(script)
                        val m3u8Url = Regex("""sources":\[\{"file":"([^"]+)""").find(unpacked)?.groupValues?.getOrNull(1)?.replace("\\/", "/")
                        if (m3u8Url != null) {
                            M3u8Helper.generateM3u8(
                                name,
                                m3u8Url,
                                mainUrl
                            ).forEach { link -> callback(link) }
                        }
                    }
                } catch (e: Exception) {
                    // Fail gracefully
                }
            } else {
                loadExtractor(url, mainUrl, subtitleCallback, callback)
            }
        }

        return true
    }
}
