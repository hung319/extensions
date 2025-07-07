// File: SupJav.kt
package recloudstream

// Bổ sung đầy đủ các import cần thiết
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.log.Log // <--- THÊM DÒNG NÀY ĐỂ SỬA LỖI
import org.jsoup.nodes.Element

class SupJav : MainAPI() {
    override var name = "SupJav"
    override var mainUrl = "https://supjav.com"
    override var lang = "en"
    override val hasMainPage = true
    override val supportedTypes = setOf(TvType.NSFW)

    private fun parseVideoList(element: Element): List<SearchResponse> {
        return element.select("div.post").mapNotNull {
            val titleElement = it.selectFirst("div.con h3 a")
            val title = titleElement?.attr("title") ?: return@mapNotNull null
            val href = titleElement.attr("href")
            val posterUrl = it.selectFirst("a.img img.thumb")?.let { img ->
                img.attr("data-original").ifBlank { img.attr("src") }
            }
            newMovieSearchResponse(title, href, TvType.NSFW) {
                this.posterUrl = posterUrl
            }
        }
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val document = app.get(mainUrl).document
        val allPages = ArrayList<HomePageList>()
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
        return parseVideoList(document)
    }

    override suspend fun load(url: String): LoadResponse {
        val document = app.get(url).document
        val title = document.selectFirst("div.archive-title h1")?.text()?.trim() ?: "No title found"
        val poster = document.selectFirst("div.post-meta img")?.attr("src")
        val plot = "Watch ${title} on SupJav"
        val tags = document.select("div.tags a").map { it.text() }

        val dataLinks = document.select("div.btns a.btn-server")
            .mapNotNull { it.attr("data-link") }
            .joinToString("\n")

        return newMovieLoadResponse(title, url, TvType.NSFW, dataLinks) {
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
        Log.d(name, "loadLinks called with data: $data")
        
        data.split("\n").forEach { link ->
            if (link.isNotBlank()) {
                try {
                    val reversedData = link.reversed()
                    val intermediatePageUrl1 = "https://lk1.supremejav.com/supjav.php?c=$reversedData"
                    
                    val intermediatePage1Doc = app.get(
                        intermediatePageUrl1,
                        referer = "$mainUrl/"
                    ).document

                    val finalPlayerUrl = intermediatePage1Doc.selectFirst("iframe")?.attr("src") ?: return@forEach
                    Log.d(name, "Extracted player URL: $finalPlayerUrl")

                    if (finalPlayerUrl.contains("emturbovid.com")) {
                        val playerDoc = app.get(finalPlayerUrl, referer = intermediatePageUrl1).document
                        val scriptContent = playerDoc.select("script").find { it.data().contains("var urlPlay") }?.data()

                        if (scriptContent != null) {
                            val videoUrlRegex = Regex("""var urlPlay = '(?<url>https?://[^']+\.m3u8[^']*)'""")
                            val videoUrl = videoUrlRegex.find(scriptContent)?.groups?.get("url")?.value
                            
                            if (videoUrl != null) {
                                Log.d(name, "Found M3U8 link: $videoUrl")
                                callback.invoke(
                                    ExtractorLink(
                                        source = this.name,
                                        name = "EmturboVid",
                                        url = videoUrl,
                                        referer = finalPlayerUrl,
                                        quality = Qualities.Unknown.value,
                                        type = ExtractorLinkType.M3U8
                                    )
                                )
                            }
                        }
                    } else {
                        loadExtractor(finalPlayerUrl, intermediatePageUrl1, subtitleCallback, callback)
                    }

                } catch (e: Exception) {
                    Log.e(name, "Error in loadLinks for link: $link", e)
                    throw e 
                }
            }
        }
        return true
    }
}
