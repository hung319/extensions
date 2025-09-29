package recloudstream

/*
* @CloudstreamProvider: BokepIndoProvider
* @Version: 3.3
* @Author: Coder
* @Language: id
* @TvType: Nsfw
* @Url: https://bokepindoh.monster
* @Info: Provider with hybrid logic for both multi-server and single-server pages.
*/

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import org.jsoup.nodes.Element

class BokepIndoProvider : MainAPI() {
    override var name = "BokepIndo"
    override var mainUrl = "https://bokepindoh.monster"
    override var supportedTypes = setOf(TvType.NSFW)
    override var hasMainPage = true
    override var hasDownloadSupport = true

    override val mainPage = mainPageOf(
        mainUrl to "Mới Nhất",
        "$mainUrl/category/bokep-indo/" to "Bokep Indo",
        "$mainUrl/category/bokep-viral/" to "Bokep Viral",
        "$mainUrl/category/bokep-jav/" to "Bokep JAV"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = if (page == 1) request.data else "${request.data.removeSuffix("/")}/page/$page/"
        val document = app.get(url).document
        val homePageList = document.select("article.loop-video.thumb-block").mapNotNull {
            it.toSearchResponse()
        }
        return newHomePageResponse(
            HomePageList(request.name, homePageList),
            hasNext = true
        )
    }
    
    override suspend fun search(query: String): List<SearchResponse> {
        val searchUrl = "$mainUrl/?s=${query.replace(" ", "+")}"
        val document = app.get(searchUrl).document
        return document.select("article.loop-video.thumb-block").mapNotNull {
            it.toSearchResponse()
        }
    }

    override suspend fun load(url: String): LoadResponse {
        val document = app.get(url).document
        val title = document.selectFirst("h1.entry-title")?.text() ?: "Không có tiêu đề"
        val poster = document.selectFirst("meta[property='og:image']")?.attr("content")
        val description = document.selectFirst("div.video-description .desc")?.text()
        val tags = document.select("div.tags-list a[class=label]").map { it.text() }
        val recommendations = document.select("div.under-video-block article.loop-video.thumb-block").mapNotNull {
            it.toSearchResponse()
        }

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
        val mainDocument = app.get(data).document
        var foundLinks = false

        val multiServerScript = mainDocument.selectFirst("script[id=wpst-main-js-extra]")

        if (multiServerScript != null) {
            val scriptContent = multiServerScript.html()
            val oriIframeTag = Regex("""embed_url":"(.*?)"""").find(scriptContent)?.groupValues?.get(1)
            val doodIframeTag = Regex("""video_url":"(.*?)"""").find(scriptContent)?.groupValues?.get(1)
            val srcRegex = Regex("""src=\\"(.*?)\\""")
            val oriUrl = oriIframeTag?.let { srcRegex.find(it)?.groupValues?.get(1) }
            val doodUrl = doodIframeTag?.let { srcRegex.find(it)?.groupValues?.get(1) }

            coroutineScope {
                listOfNotNull(
                    oriUrl?.let { Pair(it, "Server Ori") },
                    doodUrl?.let { Pair(it, "Server Dood") }
                ).forEach { (url, name) ->
                    launch {
                        try {
                            if (name == "Server Dood") {
                                if (loadExtractor(url, data, subtitleCallback, callback)) foundLinks = true
                            } else if (name == "Server Ori") {
                                val doc = app.get(url).document
                                doc.select("script").mapNotNull { script ->
                                    val scriptData = script.data()
                                    if (scriptData.contains("eval(function(p,a,c,k,e,d)")) {
                                        val unpacked = getAndUnpack(scriptData)
                                        val videoUrl = Regex("""file:"(.*?\.mp4)"""").find(unpacked)?.groupValues?.get(1)
                                        if (videoUrl != null) {
                                            callback(ExtractorLink(this@BokepIndoProvider.name, name, videoUrl, url, Qualities.Unknown.value, type = ExtractorLinkType.VIDEO))
                                            foundLinks = true
                                        }
                                    }
                                }
                            }
                        } catch (e: Exception) {
                            // SỬA LỖI: Dùng e.printStackTrace() thay cho logError(e)
                            e.printStackTrace()
                        }
                    }
                }
            }
        } else {
            val iframeSrc = mainDocument.selectFirst("div.responsive-player iframe")?.attr("src")
            if (iframeSrc != null) {
                try {
                    val iframeHtmlContent = app.get(iframeSrc).text
                    val m3u8Regex = Regex("""sources:\s*\[\{file:"([^"]+master\.m3u8[^"]+)"""")
                    val match = m3u8Regex.find(iframeHtmlContent)
                    val m3u8Url = match?.groups?.get(1)?.value

                    if (m3u8Url != null) {
                        callback(ExtractorLink(this.name, "LuluStream", m3u8Url, iframeSrc, Qualities.Unknown.value, type = ExtractorLinkType.M3U8))
                        foundLinks = true
                    }
                } catch (e: Exception) {
                    // SỬA LỖI: Dùng e.printStackTrace() thay cho logError(e)
                    e.printStackTrace()
                }
            }
        }

        return foundLinks
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
