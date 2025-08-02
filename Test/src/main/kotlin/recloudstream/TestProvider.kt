// Save this file as HHDRagonProvider.kt

package recloudstream

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.loadExtractor
import org.jsoup.nodes.Element

// Helper data class to parse the JSON response from the AJAX request
private data class PlayerAjaxResponse(
    val src_vip: String? = null,
    val src_v1: String? = null,
    val src_hd: String? = null,
    val src_arc: String? = null,
    val src_ok: String? = null,
    val src_dl: String? = null,
    val src_hx: String? = null
)

/**
 * Main provider for HHDRagon.COM
 * Updated selectors for new site layout as of August 2025.
 */
class HHDRagonProvider : MainAPI() {
    override var mainUrl = "https://hhdragon.com"
    override var name = "HHDRagon"
    override val hasMainPage = true
    override var lang = "vi"
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(
        TvType.Movie,
        TvType.TvSeries,
        TvType.Anime,
        TvType.AnimeMovie,
    )

    // ⭐ Updated helper function to parse new movie card structure
    private fun Element.toSearchResponse(): SearchResponse? {
        // Selector for the <a> tag which contains the link and is a parent for other info
        val linkTag = this.selectFirst("a.film-poster-ahref") ?: return null
        val href = fixUrl(linkTag.attr("href"))
        val title = this.selectFirst("h3.film-name a")?.text() ?: return null
        val posterUrl = fixUrlNull(this.selectFirst("img.film-poster-img")?.let {
            it.attr("data-src").ifEmpty { it.attr("src") }
        })
        
        return newAnimeSearchResponse(title, href, TvType.Anime) { this.posterUrl = posterUrl }
    }

    // ⭐ Updated to handle new homepage layout
    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val document = app.get("$mainUrl/").document
        // New selector for homepage sections
        val homePageList = document.select("div.tray-item").mapNotNull { block ->
            // New selector for section title
            val header = block.selectFirst("h3.tray-title a")?.text()?.trim() ?: return@mapNotNull null
            // New selector for movie items within a section
            val items = block.select("div.film-item").mapNotNull { it.toSearchResponse() }
            if (items.isNotEmpty()) HomePageList(header, items) else null
        }
        return HomePageResponse(homePageList)
    }
    
    // ⭐ Updated to handle new search results layout
    override suspend fun search(query: String): List<SearchResponse> {
        val document = app.get("$mainUrl/tim-kiem/${query}").document
        // New selector for search result items
        return document.select("div.film-list div.film-item").mapNotNull { it.toSearchResponse() }
    }

    // Load function remains largely the same as the detail page structure hasn't changed as much
    override suspend fun load(url: String): LoadResponse {
        val document = app.get(url).document
        // Using more specific selectors to be safe
        val title = document.selectFirst("h1.film-title")?.text()?.trim() ?: "No Title"
        val posterUrl = fixUrlNull(document.selectFirst("div.film-poster img")?.attr("src"))
        val plot = document.selectFirst("div.film-description")?.text()?.trim()
        val tags = document.select("ul.film-meta-info li:contains(Thể loại) a").map { it.text() }
        val year = document.select("ul.film-meta-info li:contains(Năm) a").text().toIntOrNull()
        
        val episodes = document.select("div.episode-list a.episode-item").map {
            newEpisode(fixUrl(it.attr("href"))) {
                this.name = it.attr("title")
            }
        }.reversed()

        return if (episodes.isNotEmpty() && episodes.size > 1) {
            newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
                this.posterUrl = posterUrl; this.plot = plot; this.tags = tags; this.year = year
            }
        } else {
            newMovieLoadResponse(title, url, TvType.Movie, url) {
                this.posterUrl = posterUrl; this.plot = plot; this.tags = tags; this.year = year
            }
        }
    }

    // loadLinks logic remains the same as it depends on the AJAX endpoint, not page layout
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val watchPageDoc = app.get(data, referer = mainUrl).document
        val csrfToken = watchPageDoc.selectFirst("meta[name=csrf-token]")?.attr("content") 
            ?: throw ErrorLoadingException("Không tìm thấy CSRF token")
        val movieId = Regex("""var\s+MOVIE_ID\s*=\s*['"](\d+)['"];""").find(watchPageDoc.html())?.groupValues?.get(1)
            ?: throw ErrorLoadingException("Không tìm thấy MovieID")
        val episodeId = Regex("""episode-id-(\d+)\.html""").find(data)?.groupValues?.get(1)
            ?: throw ErrorLoadingException("Không tìm thấy EpisodeID")

        val ajaxUrl = "$mainUrl/server/ajax/player"
        val ajaxData = mapOf("MovieID" to movieId, "EpisodeID" to episodeId)
        val headers = mapOf(
            "Content-Type" to "application/x-www-form-urlencoded; charset=UTF-8",
            "X-CSRF-TOKEN" to csrfToken,
            "X-Requested-With" to "XMLHttpRequest",
            "Referer" to data
        )
        val ajaxRes = app.post(ajaxUrl, headers = headers, data = ajaxData).parsed<PlayerAjaxResponse>()

        val sources = listOfNotNull(
            ajaxRes.src_vip, ajaxRes.src_v1, ajaxRes.src_hd,
            ajaxRes.src_arc, ajaxRes.src_ok, ajaxRes.src_dl, ajaxRes.src_hx
        )

        sources.apmap { url ->
            if (url.endsWith(".mp4")) {
                 callback(
                    ExtractorLink(
                        this.name, "Archive.org MP4", url, data,
                        quality = Qualities.Unknown.value, type = ExtractorLinkType.VIDEO,
                    )
                )
            } else {
                loadExtractor(url, data, subtitleCallback, callback)
            }
        }
        
        return true
    }
}
