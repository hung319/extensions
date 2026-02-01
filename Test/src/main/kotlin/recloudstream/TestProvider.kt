package recloudstream

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Element

class BFlixProvider : MainAPI() {
    override var mainUrl = "https://bflix.sh"
    override var name = "BFlix"
    override val hasMainPage = true
    override var lang = "en"
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)

    private val commonHeaders = mapOf(
        "User-Agent" to "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/137.0.0.0 Mobile Safari/537.36",
        "X-Requested-With" to "XMLHttpRequest",
        "Referer" to "$mainUrl/"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val document = app.get(mainUrl, headers = commonHeaders).document
        val homeSets = mutableListOf<HomePageList>()

        // 1. Trending
        val trendingElements = document.select(".swiper-slide .swiper-inner")
        if (trendingElements.isNotEmpty()) {
            val trendingList = trendingElements.mapNotNull { it.toSearchResponse() }
            homeSets.add(HomePageList("Trending", trendingList))
        }

        // 2. Latest Movies / TV Shows
        document.select(".zone").forEach { zone ->
            val title = zone.select(".zone-title").text().trim()
            val elements = zone.select(".film")
            if (title.isNotEmpty() && elements.isNotEmpty() && !title.contains("Comment")) {
                val list = elements.mapNotNull { it.toSearchResponse() }
                homeSets.add(HomePageList(title, list))
            }
        }
        
        // FIX 1: Dùng newHomePageResponse thay vì HomePageResponse constructor
        return newHomePageResponse(homeSets)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val url = "$mainUrl/search?keyword=$query"
        val document = app.get(url, headers = commonHeaders).document
        return document.select(".film").mapNotNull { it.toSearchResponse() }
    }

    private fun Element.toSearchResponse(): SearchResponse? {
        val url = this.selectFirst("a")?.attr("href") ?: return null
        val title = this.selectFirst(".film-name")?.text() ?: return null
        
        val poster = this.selectFirst("img")?.let { img ->
            img.attr("src").takeIf { it.startsWith("http") } ?: img.attr("data-src")
        }?.replace("w185", "w300")

        val isTv = url.contains("/series/") || this.select(".film-meta .end").text().contains("TV", true)

        return if (isTv) {
            newTvSeriesSearchResponse(title, url, TvType.TvSeries) {
                this.posterUrl = poster
            }
        } else {
            newMovieSearchResponse(title, url, TvType.Movie) {
                this.posterUrl = poster
            }
        }
    }

    override suspend fun load(url: String): LoadResponse? {
        val document = app.get(url, headers = commonHeaders).document

        val title = document.selectFirst("h1.film-title")?.text()?.trim() ?: return null
        val desc = document.selectFirst(".film-desc")?.text()?.trim()
        val poster = document.selectFirst(".film-poster img")?.attr("src")?.replace("w185", "original")
        val bgStyle = document.selectFirst(".film-background")?.attr("style")
        val background = bgStyle?.substringAfter("url(")?.substringBefore(")")

        val tags = document.select(".film-meta div").map { it.text() }
        val year = tags.firstOrNull { it.contains("Year:") }?.substringAfter("Year:")?.trim()?.toIntOrNull()

        val isTv = url.contains("/series/")

        if (isTv) {
            val episodes = mutableListOf<Episode>()
            val scriptContent = document.select("script").html()

            val episodeAjaxUrl = Regex("""['"]([^"']*ajax\.php\?episode=[^"']*)['"]""").find(scriptContent)?.groupValues?.get(1)
                ?: Regex("""['"]([^"']*ajax\.php\?vds=[^"']*)['"]""").find(scriptContent)?.groupValues?.get(1)

            if (episodeAjaxUrl != null) {
                val fullUrl = if (episodeAjaxUrl.startsWith("http")) episodeAjaxUrl else "$mainUrl$episodeAjaxUrl"
                
                val responseHtml = app.get(fullUrl, headers = commonHeaders).text
                val epDoc = org.jsoup.Jsoup.parse(responseHtml)
                
                epDoc.select("li a").forEach { aTag ->
                    val epUrl = aTag.attr("href")
                    val epName = aTag.select("span").joinToString(" ") { it.text() }
                    
                    if (epUrl.contains("/series/")) {
                        // FIX 2: Dùng newEpisode thay vì Episode constructor
                        episodes.add(newEpisode(epUrl) {
                            this.name = epName
                        })
                    }
                }
            }

            return newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
                this.posterUrl = poster
                this.backgroundPosterUrl = background
                this.plot = desc
                this.year = year
            }
        } else {
            return newMovieLoadResponse(title, url, TvType.Movie, url) {
                this.posterUrl = poster
                this.backgroundPosterUrl = background
                this.plot = desc
                this.year = year
            }
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val document = app.get(data, headers = commonHeaders).document
        val scriptContent = document.select("script").html()

        val serverAjaxHash = Regex("""vdkz\s*=\s*['"]([^'"]+)['"]""").find(scriptContent)?.groupValues?.get(1)
            ?: Regex("""vdkz=([^'&"]+)""").find(scriptContent)?.groupValues?.get(1)
            ?: Regex("""['"]([^"']*ajax\.php\?vdkz=[^"']*)['"]""").find(scriptContent)?.groupValues?.get(1)

        if (serverAjaxHash != null) {
            val ajaxUrl = if (serverAjaxHash.startsWith("http")) {
                serverAjaxHash
            } else {
                "$mainUrl/ajax/ajax.php?vdkz=$serverAjaxHash"
            }

            val serverResponseHtml = app.get(ajaxUrl, headers = commonHeaders).text
            val serverDoc = org.jsoup.Jsoup.parse(serverResponseHtml)

            serverDoc.select(".sv-item").forEach { server ->
                val embedUrl = server.attr("data-id")
                
                if (embedUrl.isNotBlank()) {
                    loadExtractor(
                        url = embedUrl,
                        referer = "$mainUrl/",
                        subtitleCallback = subtitleCallback,
                        callback = callback
                    )
                }
            }
            return true
        }
        return false
    }
}
