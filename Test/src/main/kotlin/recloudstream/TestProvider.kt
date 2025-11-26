package recloudstream

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Element

class AnikotoProvider : MainAPI() {
    // ---------------------------------------------------------
    // Config
    // ---------------------------------------------------------
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
    // Main Page (Fixed)
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
                // Selector dựa trên file home và filter
                val list = doc.select("div.film_list-wrap > div.flw-item, div.item").mapNotNull { element ->
                    toSearchResult(element)
                }
                if (list.isNotEmpty()) HomePageList(title, list) else null
            } catch (e: Exception) {
                null
            }
        }

        // SỬA LỖI TẠI ĐÂY: Dùng newHomePageResponse thay vì constructor
        return newHomePageResponse(homePageList)
    }

    // ---------------------------------------------------------
    // Search
    // ---------------------------------------------------------
    override suspend fun search(query: String): List<SearchResponse> {
        val url = "$mainUrl/filter?keyword=$query"
        val doc = app.get(url).document

        return doc.select("div.film_list-wrap > div.flw-item, div.item").mapNotNull {
            toSearchResult(it)
        }
    }

    private fun toSearchResult(element: Element): SearchResponse? {
        val linkElement = element.selectFirst("a") ?: return null
        val href = fixUrl(linkElement.attr("href"))
        val img = element.selectFirst("img")?.attr("data-src")
            ?: element.selectFirst("img")?.attr("src")
        val title = element.selectFirst(".film-name, .title")?.text() ?: "Unknown"

        return newAnimeSearchResponse(title, href, TvType.Anime) {
            this.posterUrl = img
        }
    }

    // ---------------------------------------------------------
    // Load Details
    // ---------------------------------------------------------
    override suspend fun load(url: String): LoadResponse {
        val doc = app.get(url).document

        val title = doc.selectFirst(".film-name, h1.heading_title")?.text() ?: "Unknown Title"
        val description = doc.selectFirst(".description, .film-description")?.text()
        val poster = doc.selectFirst("img.film-poster-img")?.attr("src")
        val background = doc.selectFirst(".backdrop")?.attr("src")

        val tags = doc.select(".film-infor a[href*='genre']").map { it.text() }

        val year = doc.selectFirst(".film-infor span:contains(Year)")?.nextElementSibling()?.text()?.toIntOrNull()
        val status = doc.selectFirst(".film-infor span:contains(Status)")?.nextElementSibling()?.text()

        // Lấy danh sách episodes
        val episodes = doc.select("#episodes-page-1 a, ul.episodes li a").mapNotNull { element ->
            val epHref = fixUrl(element.attr("href"))
            val epName = element.attr("title").ifEmpty { element.text() }
            val epNum = element.attr("data-number").toIntOrNull()
                ?: Regex("Episode (\\d+)").find(epName)?.groupValues?.get(1)?.toIntOrNull()

            newEpisode(epHref) {
                this.name = epName
                this.episode = epNum
            }
        }

        return newAnimeLoadResponse(title, url, TvType.Anime) {
            this.posterUrl = poster
            this.backgroundPosterUrl = background
            this.year = year
            this.plot = description
            this.tags = tags
            this.showStatus = getStatus(status)
            addEpisodes(DubStatus.Subbed, episodes)
        }
    }

    // ---------------------------------------------------------
    // Load Links (Updated)
    // ---------------------------------------------------------
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val doc = app.get(data).document

        // Selector iframe player
        val iframe = doc.selectFirst("#player iframe, iframe.player")
        val iframeSrc = iframe?.attr("src")

        if (iframeSrc.isNullOrBlank()) {
            return false
        }

        val cleanSrc = fixUrl(iframeSrc)

        // 1. Dùng Extractor có sẵn cho các link phổ biến
        if (loadExtractor(cleanSrc, data, subtitleCallback, callback)) {
            return true
        }

        // 2. Xử lý link nội bộ hoặc custom player
        try {
            val iframeDoc = app.get(cleanSrc, referer = data).document
            val script = iframeDoc.select("script").html()

            // Regex tìm file video trong script
            Regex("file:\\s*\"([^\"]+)\"").find(script)?.groupValues?.get(1)?.let { videoUrl ->
                
                // --- SỬ DỤNG newExtractorLink ---
                callback.invoke(
                    newExtractorLink(
                        source = this.name,
                        name = "Anikoto Internal",
                        url = videoUrl,
                        type = ExtractorLinkType.VIDEO // Có thể thay đổi tùy logic plugin của bạn
                    ) {
                        referer = cleanSrc
                        quality = Qualities.Unknown.value
                    }
                )
                return true
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
