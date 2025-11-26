package recloudstream

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Element

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
                // Thử nhiều selector bao quát hơn cho item phim
                val list = doc.select("div.flw-item, div.item, .film_list-wrap > div").mapNotNull { element ->
                    toSearchResult(element)
                }
                if (list.isNotEmpty()) HomePageList(title, list) else null
            } catch (e: Exception) {
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
        
        return doc.select("div.flw-item, div.item, .film_list-wrap > div").mapNotNull {
            toSearchResult(it)
        }
    }

    // Hàm convert item ở trang chủ/search
    private fun toSearchResult(element: Element): SearchResponse? {
        // Tìm thẻ A: ưu tiên class film-poster-ahref, nếu không thì lấy thẻ a đầu tiên
        val linkElement = element.selectFirst("a.film-poster-ahref") ?: element.selectFirst("a") ?: return null
        val href = fixUrl(linkElement.attr("href"))
        
        // Lấy title: Ưu tiên title attribute, sau đó là text bên trong .film-name
        val title = element.selectFirst(".film-name a")?.text() 
            ?: element.selectFirst(".film-name")?.text() 
            ?: linkElement.attr("title")
            ?: element.text() // Fallback cuối cùng

        // Lấy ảnh: Ưu tiên data-src (lazy load), sau đó là src
        val imgElement = element.selectFirst("img")
        val img = imgElement?.attr("data-src")?.ifBlank { null }
            ?: imgElement?.attr("src")

        if (title.isBlank()) return null

        return newAnimeSearchResponse(title, href, TvType.Anime) {
            this.posterUrl = img
        }
    }

    // ---------------------------------------------------------
    // Load Details (SỬA MẠNH PHẦN NÀY)
    // ---------------------------------------------------------
    override suspend fun load(url: String): LoadResponse {
        val doc = app.get(url).document

        // 1. Lấy Metadata từ thẻ <head> (Chính xác 99%)
        val ogTitle = doc.selectFirst("meta[property=og:title]")?.attr("content")
        val ogImage = doc.selectFirst("meta[property=og:image]")?.attr("content")
        val ogDesc = doc.selectFirst("meta[property=og:description]")?.attr("content")
            ?: doc.selectFirst("meta[name=description]")?.attr("content")

        // Fallback nếu meta tags lỗi: Lấy từ DOM
        val domTitle = doc.selectFirst(".film-name, h1.heading_title, .anisc-detail .film-name")?.text()
        val domPoster = doc.selectFirst("img.film-poster-img, .anisc-poster .film-poster-img")?.attr("src")
        
        val title = ogTitle?.substringBefore("Episode")?.trim() ?: domTitle ?: "Unknown Title"
        val poster = ogImage ?: domPoster
        val description = ogDesc ?: doc.selectFirst(".film-description .text")?.text()

        // Các thông tin phụ
        val tags = doc.select(".film-infor a[href*='genre']").map { it.text() }
        val year = doc.selectFirst(".film-infor span:contains(Year)")?.nextElementSibling()?.text()?.toIntOrNull()
        val statusText = doc.selectFirst(".film-infor span:contains(Status)")?.nextElementSibling()?.text()

        // 2. Lấy danh sách Episodes (Mở rộng Selector)
        // Các template này thường dùng ID #episodes-page-1, bên trong có .ss-list
        val episodeSelectors = listOf(
            "#episodes-page-1 .ss-list a", // Chuẩn nhất
            ".ss-list a",                  // Fallback 1
            "ul.episodes li a",            // Fallback 2
            "#episodes-page-1 a",          // Fallback 3
            ".episodes-list a"             // Fallback 4
        )
        
        var episodeElements = emptyList<Element>()
        for (selector in episodeSelectors) {
            episodeElements = doc.select(selector)
            if (episodeElements.isNotEmpty()) break
        }

        val episodes = episodeElements.mapNotNull { element ->
            val epHref = fixUrl(element.attr("href"))
            // Title thường nằm trong attribute title hoặc data-number
            val epName = element.attr("title").ifEmpty { element.text() }
            val epNum = element.attr("data-number").toIntOrNull()
                ?: Regex("Episode (\\d+)").find(epName)?.groupValues?.get(1)?.toIntOrNull()
            
            // Lọc bỏ link rác (nếu có)
            if (epHref.contains("javascript:void")) return@mapNotNull null

            newEpisode(epHref) {
                this.name = epName
                this.episode = epNum
            }
        }

        return newAnimeLoadResponse(title, url, TvType.Anime) {
            this.posterUrl = poster
            this.year = year
            this.plot = description
            this.tags = tags
            this.showStatus = getStatus(statusText)
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

        val iframe = doc.selectFirst("#player iframe, iframe.player, .player-container iframe")
        val iframeSrc = iframe?.attr("src")

        if (iframeSrc.isNullOrBlank()) return false

        val cleanSrc = fixUrl(iframeSrc)

        if (loadExtractor(cleanSrc, data, subtitleCallback, callback)) {
            return true
        }

        // Xử lý Custom Player (Anikoto Internal)
        try {
            // Cố gắng tìm script chứa source nếu không phải link embed phổ biến
            val iframeDoc = app.get(cleanSrc, referer = data).document
            val script = iframeDoc.select("script").html()

            // Pattern tìm file: "..." hoặc source: "..."
            val regexes = listOf(
                Regex("file:\\s*\"([^\"]+)\""),
                Regex("source:\\s*\"([^\"]+)\""),
                Regex("src:\\s*\"([^\"]+)\"")
            )

            for (regex in regexes) {
                regex.find(script)?.groupValues?.get(1)?.let { videoUrl ->
                     callback.invoke(
                        newExtractorLink(
                            source = this.name,
                            name = "Anikoto Internal",
                            url = videoUrl,
                            type = ExtractorLinkType.VIDEO
                        ) {
                            referer = cleanSrc
                            quality = Qualities.Unknown.value
                        }
                    )
                    return true
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }

        return false
    }

    private fun getStatus(t: String?): ShowStatus {
        return when (t?.lowercase()) {
            "completed" -> ShowStatus.Completed
            "ongoing" -> ShowStatus.Ongoing
            else -> ShowStatus.Completed
        }
    }
}
