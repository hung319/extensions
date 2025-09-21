package recloudstream

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Element
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.awaitAll
import java.math.BigInteger

class KuraKura21Provider : MainAPI() {
    override var name = "KuraKura21"
    override var mainUrl = "https://kurakura21.com"
    override var lang = "id"
    override var hasMainPage = true

    override val supportedTypes = setOf(
        TvType.NSFW
    )

    // Helper function to de-obfuscate P.A.C.K.E.R. protected JavaScript.
    // This works for both Server 1 (FileMoon) and Server 2 (Go-TV).
    private fun unpackJsAndGetM3u8(script: String): String? {
        try {
            val regex = Regex("""eval\(function\(p,a,c,k,e,d\)\{.*?\}\((.*?),(\d+),(\d+),'(.*?)'\.split\('\|'\)\)\)""")
            val match = regex.find(script) ?: return null

            var payload = match.groupValues[1]
            val radix = match.groupValues[2].toInt()
            var count = match.groupValues[3].toInt()
            val dictionary = match.groupValues[4].split("|")

            fun toBase(n: Int): String {
                return BigInteger.valueOf(n.toLong()).toString(radix)
            }
            
            while (count-- > 0) {
                if (count < dictionary.size && dictionary[count].isNotEmpty()) {
                    val key = toBase(count)
                    payload = payload.replace(Regex("\\b$key\\b"), dictionary[count])
                }
            }
            
            val m3u8Regex = Regex("""(https?://[^\s'"]+master\.m3u8[^\s'"]*)""")
            return m3u8Regex.find(payload)?.groupValues?.get(1)
        } catch (e: Exception) {
            e.printStackTrace()
            return null
        }
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val pages = listOf(
            Pair("Best Rating", "$mainUrl/best-rating/"),
            Pair("18+ Sub Indo", "$mainUrl/tag/18-sub-indo/"),
            Pair("Jav Sub Indo", "$mainUrl/genre/jav-sub-indo/"),
            Pair("Korea 18+", "$mainUrl/genre/korea-18/")
        )

        return coroutineScope {
            val mainPageDocument = app.get(mainUrl).document
            val recentPosts = HomePageList(
                "RECENT POST",
                mainPageDocument.select("div.gmr-item-modulepost").mapNotNull {
                    it.toSearchResult()
                }
            )

            val otherLists = pages.map { (name, url) ->
                async {
                    try {
                        val document = app.get(url).document
                        val list = document.select("article.item-infinite").mapNotNull { element ->
                            val href = element.selectFirst("a")?.attr("href") ?: return@mapNotNull null
                            val title = element.selectFirst("h2.entry-title a")?.text() ?: "N/A"
                            val posterUrl = element.selectFirst("img")?.let { it.attr("data-src").ifBlank { it.attr("src") } }

                            newMovieSearchResponse(title, href, TvType.NSFW) {
                                this.posterUrl = posterUrl
                            }
                        }
                        HomePageList(name, list)
                    } catch (e: Exception) {
                        null
                    }
                }
            }.awaitAll().filterNotNull()

            newHomePageResponse(listOf(recentPosts) + otherLists)
        }
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val href = this.selectFirst("a")?.attr("href") ?: return null
        val title = this.selectFirst("h2.entry-title a")?.text() ?: "Không có tiêu đề"
        val posterUrl = this.selectFirst("img")?.attr("data-src")

        return newMovieSearchResponse(
            name = title,
            url = href,
            type = TvType.NSFW
        ) {
            this.posterUrl = posterUrl
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val searchUrl = "$mainUrl/?s=$query"
        val document = app.get(searchUrl).document

        return document.select("article.item-infinite").mapNotNull {
            it.toSearchResult()
        }
    }

    override suspend fun load(url: String): LoadResponse? {
        val document = app.get(url).document

        val title = document.selectFirst("h1.entry-title")?.text()?.trim() ?: "Không có tiêu đề"
        val poster = document.selectFirst("div.gmr-movie-data img")?.attr("data-src")
        val description = document.selectFirst("div.entry-content")?.text()?.trim()
        val tags = document.select("div.gmr-moviedata a[rel=tag]").map { it.text() }

        val recommendations = document.select("div.gmr-grid:has(h3.gmr-related-title) article.item").mapNotNull {
            it.toSearchResult()
        }

        return newMovieLoadResponse(
            name = title,
            url = url,
            type = TvType.NSFW,
            dataUrl = url
        ) {
            this.posterUrl = poster
            this.plot = description
            this.tags = tags
            this.recommendations = recommendations
        }
    }
    
    // [UPDATE]: Re-implemented to handle multiple servers concurrently, including the nested iframe pattern of Server 1.
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val pageUrl = data
        val document = app.get(pageUrl, referer = mainUrl).document

        val postId = document.selectFirst("body[class*='postid-']")
            ?.attr("class")
            ?.split(" ")
            ?.find { it.startsWith("postid-") }
            ?.removePrefix("postid-")
            ?: return false

        val ajaxUrl = "$mainUrl/wp-admin/admin-ajax.php"

        coroutineScope {
            document.select("ul.muvipro-player-tabs li a").map { tab ->
                async {
                    try {
                        val tabId = tab.attr("href").removePrefix("#")
                        if (tabId.isEmpty()) return@async

                        val postData = mapOf(
                            "action" to "muvipro_player_content",
                            "tab" to tabId,
                            "post_id" to postId
                        )
                        
                        val playerContent = app.post(
                            url = ajaxUrl,
                            data = postData,
                            referer = pageUrl
                        ).document

                        val firstIframeSrc = playerContent.selectFirst("iframe")?.attr("src") ?: return@async
                        
                        // Fetch the first iframe's content
                        val firstIframeDoc = app.get(firstIframeSrc, referer = pageUrl).document
                        
                        // Check for a nested iframe (like in Server 1)
                        val nestedIframeSrc = firstIframeDoc.selectFirst("iframe")?.attr("src")

                        val finalHtml: String
                        val finalReferer: String

                        if (nestedIframeSrc != null) {
                            // It's a wrapped player (Server 1), follow the nested iframe
                            finalHtml = app.get(nestedIframeSrc, referer = firstIframeSrc).text
                            finalReferer = nestedIframeSrc
                        } else {
                            // It's a direct player (Server 2), use the first iframe's content
                            finalHtml = firstIframeDoc.html()
                            finalReferer = firstIframeSrc
                        }

                        val m3u8Url = unpackJsAndGetM3u8(finalHtml)
                        
                        if (m3u8Url != null) {
                            callback.invoke(
                                ExtractorLink(
                                    source = this.name,
                                    name = tab.text(), // Use the tab text as server name
                                    url = m3u8Url,
                                    referer = finalReferer,
                                    quality = Qualities.Unknown.value,
                                    type = ExtractorLinkType.M3U8
                                )
                            )
                        }
                    } catch (e: Exception) {
                        e.printStackTrace()
                    }
                }
            }.awaitAll()
        }
        
        return true
    }
}
