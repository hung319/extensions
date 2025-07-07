// File: SupJav.kt
package recloudstream

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.loadExtractor
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
            newTvShowSearchResponse(title, href, TvType.NSFW) {
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

    // Updated 'load' function to use newMovieLoadResponse
    override suspend fun load(url: String): LoadResponse {
        val document = app.get(url).document
        val title = document.selectFirst("div.archive-title h1")?.text()?.trim() ?: "No title found"
        val poster = document.selectFirst("div.post-meta img")?.attr("src")
        val plot = "Watch ${title} on SupJav"
        val tags = document.select("div.tags a").map { it.text() }

        // Since this is a movie, we don't have episodes.
        // We will pass all server links as a single string, separated by a newline.
        // The loadLinks function will then try them one by one.
        val dataLinks = document.select("div.btns a.btn-server")
            .mapNotNull { it.attr("data-link") }
            .joinToString("\n") // Join all links into one string

        return newMovieLoadResponse(title, url, TvType.Movie, dataLinks) {
            this.posterUrl = poster
            this.plot = plot
            this.tags = tags
        }
    }

    // Updated 'loadLinks' to handle multiple data-links
    override suspend fun loadLinks(
        data: String, // This 'data' now contains all server links, separated by newlines
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        // Split the data string into individual links and try each one
        data.split("\n").forEach { link ->
            if (link.isNotBlank()) {
                try {
                    val reversedData = link.reversed()
                    val intermediatePageUrl1 = "https://lk1.supremejav.com/supjav.php?c=$reversedData"
                    
                    val intermediatePage1Doc = app.get(
                        intermediatePageUrl1,
                        referer = "$mainUrl/"
                    ).document

                    val finalPlayerUrl = intermediatePage1Doc.selectFirst("iframe")?.attr("src") ?: return@forEach // Continue to next link if not found

                    // Use loadExtractor for the found player URL
                    loadExtractor(finalPlayerUrl, intermediatePageUrl1, subtitleCallback, callback)
                } catch (e: Exception) {
                    // If one link fails, continue to the next one
                    e.printStackTrace()
                }
            }
        }
        return true
    }
}
