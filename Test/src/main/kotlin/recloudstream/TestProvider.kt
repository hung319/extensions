package recloudstream

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Element
import com.lagradost.cloudstream3.mvvm.logError
import android.util.Log

class AnikotoProvider : MainAPI() {
    override var mainUrl = "https://anikoto.tv"
    override var name = "Anikoto"
    override val hasMainPage = true
    override var lang = "en"
    override val hasQuickSearch = false

    override val supportedTypes = setOf(
        TvType.Anime,
        TvType.Movie,
        TvType.OVA
    )

    // Helper logging
    private fun debugLog(msg: String) {
        Log.d("AnikotoDebug", msg)
    }

    // ---------------------------------------------------------
    // Main Page
    // ---------------------------------------------------------
    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse? {
        val items = listOf(
            Pair("$mainUrl/filter?sort=recently_updated&page=$page", "Recently Updated"),
            Pair("$mainUrl/filter?sort=popular&page=$page", "Popular Anime"),
            Pair("$mainUrl/filter?type=movie&page=$page", "Movies")
        )

        val homePageList = items.mapNotNull { (url, title) ->
            try {
                val doc = app.get(url).document
                
                // Selector mở rộng để bắt nhiều loại template
                var elements = doc.select("div.flw-item")
                if (elements.isEmpty()) elements = doc.select("div.item")
                if (elements.isEmpty()) elements = doc.select(".film_list-wrap > div")
                
                // Debug log nếu không tìm thấy element
                if (elements.isEmpty()) {
                    debugLog("No elements found for $title at $url. HTML snippet: ${doc.html().take(500)}")
                }

                val list = elements.mapNotNull { element ->
                    toSearchResult(element)
                }
                
                if (list.isNotEmpty()) HomePageList(title, list) else null
            } catch (e: Exception) {
                logError(e)
                null
            }
        }

        return newHomePageResponse(homePageList)
    }

    // ---------------------------------------------------------
    // Search
    // ---------------------------------------------------------
    override suspend fun search(query: String): List<SearchResponse> {
        val url = "$mainUrl/filter?keyword=$query"
        val doc = app.get(url).document
        
        // Dùng chung selector với Home
        var elements = doc.select("div.flw-item")
        if (elements.isEmpty()) elements = doc.select("div.item")
        if (elements.isEmpty()) elements = doc.select(".film_list-wrap > div")

        return elements.mapNotNull { toSearchResult(it) }
    }

    private fun toSearchResult(element: Element): SearchResponse? {
        // Tìm thẻ A chứa link phim
        val linkElement = element.selectFirst("a.film-poster-ahref") 
            ?: element.selectFirst("a.dynamic-name")
            ?: element.selectFirst("a") 
            ?: return null
            
        val href = fixUrl(linkElement.attr("href"))
        
        // Tìm title
        val title = element.selectFirst(".film-name a")?.text() 
            ?: element.selectFirst(".film-name")?.text() 
            ?: linkElement.attr("title")
            ?: element.text() // Fallback

        // Tìm image (Data-src thường dùng cho lazy load)
        val imgElement = element.selectFirst("img")
        val img = imgElement?.attr("data-src")?.takeIf { it.isNotBlank() }
            ?: imgElement?.attr("src")

        if (title.isBlank()) return null

        return newAnimeSearchResponse(title, href, TvType.Anime) {
            this.posterUrl = img
        }
    }

    // ---------------------------------------------------------
    // Load Details (Xử lý AJAX Episodes)
    // ---------------------------------------------------------
    override suspend fun load(url: String): LoadResponse {
        val doc = app.get(url).document

        // 1. Metadata (Ưu tiên Meta Tags vì DOM hay thay đổi)
        val ogTitle = doc.selectFirst("meta[property=og:title]")?.attr("content")
        val ogImage = doc.selectFirst("meta[property=og:image]")?.attr("content")
        val ogDesc = doc.selectFirst("meta[property=og:description]")?.attr("content")

        // DOM Fallback
        val domTitle = doc.selectFirst(".film-name, .heading_title")?.text()
        val title = ogTitle?.replace("Watch ", "")?.substringBefore("Episode")?.trim() 
                   ?: domTitle 
                   ?: "Unknown Title"
        
        val poster = ogImage ?: doc.selectFirst("img.film-poster-img")?.attr("src")
        val description = ogDesc ?: doc.selectFirst(".film-description")?.text()

        // 2. Episodes - Xử lý AJAX
        val episodes = ArrayList<Episode>()
        
        // Cách 1: Tìm list có sẵn trong HTML (nếu site render server-side)
        var episodeElements = doc.select("#episodes-page-1 .ss-list a, .ss-list a")
        
        // Cách 2: Nếu HTML trống, thử gọi API AJAX (Standard cho Zoro/Anikoto templates)
        if (episodeElements.isEmpty()) {
            // Tìm movie_id ẩn trong trang
            val movieId = doc.selectFirst("#movie-id")?.attr("value") 
                ?: doc.selectFirst("input[name=movie_id]")?.attr("value")
                ?: doc.selectFirst(".rating-stars")?.attr("data-id") // Thường ID phim hay giấu ở rating
            
            if (movieId != null) {
                try {
                    val ajaxUrl = "$mainUrl/ajax/v2/episode/list/$movieId"
                    val json = app.get(
                        ajaxUrl, 
                        headers = mapOf("X-Requested-With" to "XMLHttpRequest")
                    ).parsedSafe<AjaxResponse>()
                    
                    if (json?.html != null) {
                        val ajaxDoc = org.jsoup.Jsoup.parse(json.html)
                        episodeElements = ajaxDoc.select(".ss-list a, a.ep-item")
                    }
                } catch (e: Exception) {
                    debugLog("AJAX fetch failed: ${e.message}")
                }
            } else {
                debugLog("Could not find Movie ID for AJAX call")
            }
        }

        episodeElements.forEach { element ->
            val epHref = fixUrl(element.attr("href"))
            val epName = element.attr("title").ifEmpty { element.text() }
            // Cố gắng parse số tập từ text hoặc attr
            val epNum = element.attr("data-number").toIntOrNull()
                ?: Regex("Episode (\\d+)").find(epName)?.groupValues?.get(1)?.toIntOrNull()
                ?: element.text().trim().toIntOrNull()

            episodes.add(
                newEpisode(epHref) {
                    this.name = epName
                    this.episode = epNum
                }
            )
        }

        return newAnimeLoadResponse(title, url, TvType.Anime) {
            this.posterUrl = poster
            this.plot = description
            this.tags = doc.select(".film-infor a[href*='genre']").map { it.text() }
            addEpisodes(DubStatus.Subbed, episodes)
        }
    }

    // ---------------------------------------------------------
    // Load Links
    // ---------------------------------------------------------
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val doc = app.get(data).document

        // Tìm Iframe
        val iframe = doc.selectFirst("#player iframe") ?: doc.selectFirst("iframe")
        val iframeSrc = iframe?.attr("src")

        if (!iframeSrc.isNullOrBlank()) {
            val cleanSrc = fixUrl(iframeSrc)
            if (loadExtractor(cleanSrc, data, subtitleCallback, callback)) {
                return true
            }
            // Fallback cho custom player
            callback.invoke(
                newExtractorLink(name, "Embed", cleanSrc, ExtractorLinkType.VIDEO) {
                    referer = data
                }
            )
            return true
        }
        return false
    }
    
    // Class hứng response JSON từ Ajax
    data class AjaxResponse(
        val status: Boolean? = null,
        val html: String? = null
    )
}
