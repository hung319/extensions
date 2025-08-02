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
 * Updated to use AJAX endpoint, correct ExtractorLinkType, and newEpisode factory method.
 */
class HHDRagonProvider : MainAPI() { // Reverted class name
    // Overrides
    override var mainUrl = "https://hhdragon.com"
    override var name = "HHDRagon" // Reverted provider name
    override val hasMainPage = true
    override var lang = "vi"
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(
        TvType.Movie,
        TvType.TvSeries,
        TvType.Anime,
        TvType.AnimeMovie,
    )

    // Helper function to parse movie/series cards
    private fun Element.toSearchResponse(): SearchResponse? {
        val linkTag = this.selectFirst("a") ?: return null
        val href = fixUrl(linkTag.attr("href"))
        val title = this.selectFirst(".name")?.text() ?: return null
        val posterUrl = fixUrlNull(this.selectFirst("div.image img")?.let {
            it.attr("data-src").ifEmpty { it.attr("src") }
        })
        return newAnimeSearchResponse(title, href, TvType.Anime) { this.posterUrl = posterUrl }
    }

    // Load main page content
    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val document = app.get("$mainUrl/").document
        val homePageList = document.select("div.block.body").mapNotNull { block ->
            val header = block.selectFirst("div.block-title")?.text()?.trim() ?: return@mapNotNull null
            val items = block.select("li.item").mapNotNull { it.toSearchResponse() }
            if (items.isNotEmpty()) HomePageList(header, items) else null
        }
        return HomePageResponse(homePageList)
    }
    
    // Search functionality
    override suspend fun search(query: String): List<SearchResponse> {
        val document = app.get("$mainUrl/tim-kiem/${query}").document
        return document.select("ul.list-film > li.item").mapNotNull { it.toSearchResponse() }
    }

    // Load movie/series details
    override suspend fun load(url: String): LoadResponse {
        val document = app.get(url).document
        val title = document.selectFirst("h1.name")?.text()?.trim() ?: "No Title"
        val posterUrl = fixUrlNull(document.selectFirst("div.info-image img")?.attr("src"))
        val plot = document.selectFirst("div.info-film-text")?.text()?.trim()
        val tags = document.select("div.info-dd[itemprop=genre] a").map { it.text() }
        val year = document.select("dt:contains(Năm sản xuất) + dd").text().toIntOrNull()
        
        val episodes = document.select("div.list-episode a").map {
            newEpisode(fixUrl(it.attr("href"))) {
                this.name = it.text().trim()
            }
        }.reversed()

        return if (episodes.size > 1) {
            newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
                this.posterUrl = posterUrl; this.plot = plot; this.tags = tags; this.year = year
            }
        } else {
            newMovieLoadResponse(title, url, TvType.Movie, url) {
                this.posterUrl = posterUrl; this.plot = plot; this.tags = tags; this.year = year
            }
        }
    }

    // Main logic for extracting video links
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
