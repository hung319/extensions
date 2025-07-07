// File: SupJav.kt
package recloudstream

import android.util.Log
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.loadExtractor
import org.jsoup.nodes.Element

class SupJav : MainAPI() {
    override var name = "SupJav"
    override var mainUrl = "https://supjav.com"
    override var lang = "en"
    override val hasMainPage = true
    override val supportedTypes = setOf(TvType.NSFW)

    // parseVideoList, getMainPage, search, load functions remain unchanged...
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

    // Rewritten loadLinks to be more robust and prevent silent fails
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        Log.d(name, "loadLinks called with data: $data")
        var isLinkLoaded = false

        data.split("\n").forEach { link ->
            if (link.isNotBlank()) {
                try {
                    val reversedData = link.reversed()
                    val intermediatePageUrl1 = "https://lk1.supremejav.com/supjav.php?c=$reversedData"
                    val intermediatePage1Doc = app.get(intermediatePageUrl1, referer = "$mainUrl/").document
                    val finalPlayerUrl = intermediatePage1Doc.selectFirst("iframe")?.attr("src") 
                        ?: throw Exception("L1: Could not find iframe")

                    Log.d(name, "Extracted player URL: $finalPlayerUrl")

                    if (finalPlayerUrl.contains("emturbovid.com")) {
                        val playerDoc = app.get(finalPlayerUrl, referer = intermediatePageUrl1).document
                        val scriptContent = playerDoc.select("script").find { it.data().contains("var urlPlay") }?.data()
                            ?: throw Exception("L2: Could not find script in EmturboVid")
                        
                        val videoUrlRegex = Regex("""var urlPlay = '(?<url>https?://[^']+\.m3u8[^']*)'""")
                        val videoUrl = videoUrlRegex.find(scriptContent)?.groups?.get("url")?.value
                            ?: throw Exception("L3: Could not extract M3U8 from EmturboVid")
                        
                        callback.invoke(
                            ExtractorLink(
                                source = this.name,
                                name = "EmturboVid - OK",
                                url = videoUrl,
                                referer = finalPlayerUrl,
                                quality = Qualities.Unknown.value,
                                type = ExtractorLinkType.M3U8
                            )
                        )
                        isLinkLoaded = true
                    } else {
                        // For other players, check the return value of loadExtractor
                        if (loadExtractor(finalPlayerUrl, intermediatePageUrl1, subtitleCallback, callback)) {
                            isLinkLoaded = true
                        }
                    }
                } catch (e: Exception) {
                    // We don't throw here, just log it. We will throw a final error if nothing works.
                    Log.e(name, "A server failed: ${e.message}")
                }
            }
        }
        
        // If after trying all servers, no link was loaded, throw an exception.
        if (!isLinkLoaded) {
            throw Exception("All servers failed to return a valid video link.")
        }

        return true
    }
}
