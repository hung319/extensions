package recloudstream

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Element
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.awaitAll

class KuraKura21Provider : MainAPI() {
    override var name = "KuraKura21"
    override var mainUrl = "https://kurakura21.com"
    override var lang = "id"
    override var hasMainPage = true

    override val supportedTypes = setOf(
        TvType.NSFW
    )

    // --- Helper Methods ---

    private fun Element.toSearchResult(): SearchResponse? {
        val href = this.selectFirst("a")?.attr("href") ?: return null
        val title = this.selectFirst(".entry-title a, .gmr-box-content h2 a")?.text() ?: "N/A"
        
        // Logic lấy ảnh tối ưu cho Flying Press (Lazy Load)
        val imgTag = this.selectFirst("img")
        val posterUrl = imgTag?.attr("data-src")
            ?.takeIf { it.isNotEmpty() }
            ?: imgTag?.attr("src")

        return newMovieSearchResponse(
            name = title,
            url = href,
            type = TvType.NSFW
        ) {
            this.posterUrl = posterUrl
        }
    }

    // --- Main Logic ---

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val pages = listOf(
            Pair("Best Rating", "$mainUrl/best-rating/"),
            Pair("18+ Sub Indo", "$mainUrl/tag/18-sub-indo/"),
            Pair("Jav Sub Indo", "$mainUrl/genre/jav-sub-indo/"),
            Pair("Korea 18+", "$mainUrl/genre/korea-18/")
        )

        return coroutineScope {
            val mainPageDocument = app.get(mainUrl).document
            
            // Selector grid: .gmr-grid .item (bao quát cả trang chủ và module)
            val recentPosts = HomePageList(
                "RECENT POST",
                mainPageDocument.select(".gmr-grid .item").mapNotNull {
                    it.toSearchResult()
                }
            )

            val otherLists = pages.map { (name, url) ->
                async {
                    try {
                        val document = app.get(url).document
                        val list = document.select(".gmr-grid .item").mapNotNull { 
                            it.toSearchResult() 
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

    override suspend fun search(query: String): List<SearchResponse> {
        val searchUrl = "$mainUrl/?s=$query"
        val document = app.get(searchUrl).document

        return document.select(".gmr-grid .item").mapNotNull {
            it.toSearchResult()
        }
    }

    override suspend fun load(url: String): LoadResponse? {
        val document = app.get(url).document

        val title = document.selectFirst("h1.entry-title")?.text()?.trim() ?: "Không có tiêu đề"
        
        // Lấy ảnh poster chất lượng cao nhất có thể
        val imgTag = document.selectFirst(".gmr-movie-data img, .content-thumbnail img")
        val poster = imgTag?.attr("data-src")?.takeIf { it.isNotEmpty() } ?: imgTag?.attr("src")
        
        val description = document.selectFirst(".entry-content p")?.text()?.trim()
        val tags = document.select(".gmr-moviedata a[rel=tag]").map { it.text() }

        val recommendations = document.select(".gmr-grid:has(.gmr-related-title) .item, #gmr-related .item").mapNotNull {
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
    
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val pageUrl = data
        val document = app.get(pageUrl, referer = mainUrl).document

        // Tìm Post ID (Logic fallback 3 lớp để đảm bảo luôn lấy được)
        val postId = document.selectFirst("body")?.classNames()
            ?.find { it.startsWith("postid-") }
            ?.removePrefix("postid-")
            ?: document.selectFirst("link[rel='shortlink']")?.attr("href")?.substringAfter("?p=")
            ?: document.selectFirst("input[name='comment_post_ID']")?.attr("value")
            ?: return false

        val ajaxUrl = "$mainUrl/wp-admin/admin-ajax.php"

        coroutineScope {
            // Lấy danh sách tabs server
            val tabs = document.select(".muvipro-player-tabs li a, .gmr-player-nav li a")
            
            tabs.map { tab ->
                async {
                    try {
                        val tabId = tab.attr("href").removePrefix("#")
                        if (tabId.isNotEmpty()) {
                            val postData = mapOf(
                                "action" to "muvipro_player_content",
                                "tab" to tabId,
                                "post_id" to postId
                            )
                            
                            // Gọi AJAX để lấy nội dung player
                            val playerContent = app.post(
                                url = ajaxUrl,
                                data = postData,
                                referer = pageUrl,
                                headers = mapOf("X-Requested-With" to "XMLHttpRequest")
                            ).document

                            // 1. Lấy link iframe gốc
                            val rawIframeSrc = playerContent.selectFirst("iframe")?.attr("src") ?: return@async
                            
                            // Tự động fix link relative theo mainUrl
                            val iframeSrc = fixUrl(rawIframeSrc) 
                            
                            // 2. Giao hoàn toàn cho Extractor xử lý (StreamWish, FileMoon, etc.)
                            loadExtractor(iframeSrc, subtitleCallback, callback)
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
