package recloudstream 

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Element
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.awaitAll

class KuraKura21Provider : MainAPI() {
    override var name = "KuraKura21"
    // [UPDATE]: Changed mainUrl to the new domain
    override var mainUrl = "https://kurakura21.com"
    override var lang = "id"
    override var hasMainPage = true

    override val supportedTypes = setOf(
        TvType.NSFW
    )

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

    // [UPDATE]: Rewritten loadLinks to handle the new site structure and video hosts
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val pageUrl = data
        val document = app.get(pageUrl, referer = mainUrl).document

        // Extract post_id from the body class, a common technique for WordPress themes
        val postId = document.selectFirst("body[class*='postid-']")
            ?.attr("class")
            ?.split(" ")
            ?.find { it.startsWith("postid-") }
            ?.removePrefix("postid-")
            ?: return false

        val ajaxUrl = "$mainUrl/wp-admin/admin-ajax.php"

        coroutineScope {
            // Concurrently fetch all stream and download links
            val tasks = mutableListOf<suspend () -> Unit>()

            // 1. Fetch streaming links from server tabs
            document.select("ul.muvipro-player-tabs li a").forEach { tab ->
                tasks.add {
                    try {
                        val tabId = tab.attr("href").removePrefix("#")
                        if (tabId.isEmpty()) return@add

                        val postData = mapOf(
                            "action" to "muvipro_player_content",
                            "tab" to tabId,
                            "post_id" to postId
                        )

                        // Make the AJAX POST request to get the iframe content
                        val playerContent = app.post(
                            url = ajaxUrl,
                            data = postData,
                            referer = pageUrl
                        ).document

                        // Extract the iframe source URL
                        playerContent.select("iframe").firstOrNull()?.attr("src")?.let { iframeSrc ->
                            // Delegate the complex task of extracting video from the host to CloudStream's built-in extractor
                            loadExtractor(iframeSrc, pageUrl, subtitleCallback, callback)
                        }
                    } catch (e: Exception) {
                        e.printStackTrace()
                    }
                }
            }

            // 2. Fetch download links (if any)
            document.select("div#download a.button").forEach { downloadButton ->
                tasks.add {
                    try {
                        val downloadUrl = downloadButton.attr("href")
                        if (downloadUrl.isNotBlank()) {
                            loadExtractor(downloadUrl, pageUrl, subtitleCallback, callback)
                        }
                    } catch (e: Exception) {
                        e.printStackTrace()
                    }
                }
            }
            
            // Execute all tasks in parallel
            tasks.map { async { it() } }.awaitAll()
        }

        return true
    }
}
