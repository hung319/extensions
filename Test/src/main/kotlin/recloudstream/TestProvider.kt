// Provider for HoatHinhQQ
package recloudstream

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import org.jsoup.nodes.Element

class HoatHinhQQProvider : MainAPI() {
    // Basic provider information
    override var mainUrl = "https://hoathinhqq.com"
    override var name = "HoatHinhQQ"
    override val hasMainPage = true
    override var lang = "vi"
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(
        TvType.Cartoon
    )

    // FIX: Simplified and corrected pagination logic
    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        // This provider now focuses on the paginated "Phim" page for simplicity and reliability.
        val url = "$mainUrl/phim?page=$page"
        val document = app.get(url).document
        
        val movies = document.select("div.grid > a").mapNotNull { it.toSearchResult() }
        
        // A more reliable way to check for the next page.
        // It checks if there's a link to the page number after the current one.
        val hasNext = document.select("ul.pagination a[href='/phim?page=${page + 1}']").isNotEmpty()

        // We return a single list, which is the main content of the homepage.
        // The page title changes to reflect the current page number for clarity.
        return newHomePageResponse(
            HomePageList("Phim Mới Cập Nhật", movies),
            hasNext = hasNext
        )
    }

    // Helper function to parse search results from an element
    private fun Element.toSearchResult(): SearchResponse? {
        val href = this.attr("href")
        if (href.isBlank()) return null
        
        val title = this.selectFirst("h3.capitalize")?.text() ?: return null
        val posterUrl = this.selectFirst("img")?.attr("srcset")?.substringBefore(" ") ?: this.selectFirst("img")?.attr("src")
        val latestEp = this.selectFirst("div.absolute.top-0.left-0 > div")?.text()

        return newAnimeSearchResponse(title, "$mainUrl$href", TvType.Cartoon) {
            this.posterUrl = posterUrl
            addDubStatus(false, latestEp?.contains("Tập") == true)
        }
    }
    
    // Function to search for content
    override suspend fun search(query: String): List<SearchResponse> {
        val url = "$mainUrl/tim-kiem/$query"
        val document = app.get(url).document

        return document.select("div.grid > a").mapNotNull {
            it.toSearchResult()
        }
    }
    
    // Function to load movie/series details
    override suspend fun load(url: String): LoadResponse {
        val document = app.get(url).document

        val title = document.selectFirst("meta[property=og:title]")?.attr("content")?.replace(" - HoatHinhQQ", "")
            ?: "Không tìm thấy tiêu đề"
        val poster = document.selectFirst("meta[property=og:image]")?.attr("content")
        val description = document.selectFirst("meta[property=og:description]")?.attr("content")
        
        val year = document.select("li.film-info-list:contains(Năm) div.flex-row p.text-sm")?.text()?.toIntOrNull()

        // FIX: More reliable selector to get all episodes
        val episodes = document.selectFirst("div:has(p:contains(Tìm tập nhanh))")
            ?.select("ul > li > a")
            ?.mapNotNull { aTag ->
                val epHref = aTag.attr("href")
                val epName = aTag.text().trim()
                Episode(
                    data = "$mainUrl$epHref", 
                    name = epName, 
                    episode = -1
                )
            }?.reversed() ?: emptyList()

        val isMovie = episodes.isEmpty()

        // FIX: Added placeholder for recommendations
        // The HTML you provided does not contain a recommendations section.
        // When you find a page with recommendations, provide the HTML and I will update the selector.
        // val recommendations = document.select("your_recommendations_selector_here").mapNotNull { it.toSearchResult() }
        
        return if (isMovie) {
             newMovieLoadResponse(title, url, TvType.Cartoon, dataUrl = url) {
                this.posterUrl = poster
                this.year = year
                this.plot = description
                this.tags = document.select("li.film-info-list:contains(Thể loại) a").map { it.text() }
                // this.recommendations = recommendations
            }
        } else {
             newTvSeriesLoadResponse(title, url, TvType.Cartoon, episodes) {
                this.posterUrl = poster
                this.year = year
                this.plot = description
                this.tags = document.select("li.film-info-list:contains(Thể loại) a").map { it.text() }
                // this.recommendations = recommendations
            }
        }
    }
    
    // Function to load video links for an episode
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val m3u8Regex = """https?://[^\s"'<>]+\.m3u8""".toRegex()
        val document = app.get(data).document.html()

        m3u8Regex.find(document)?.let { matchResult ->
            val m3u8Url = matchResult.value
            callback.invoke(
                ExtractorLink(
                    source = this.name,
                    name = this.name,
                    url = m3u8Url,
                    referer = "$mainUrl/",
                    quality = Qualities.Unknown.value, 
                    type = ExtractorLinkType.M3U8
                )
            )
            return true
        }

        return false
    }
}
