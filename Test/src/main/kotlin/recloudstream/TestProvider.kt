// Save this file as HHDRagonProvider.kt

package recloudstream

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.network.CloudflareKiller // ⭐ Import đã được cập nhật
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.loadExtractor
import org.jsoup.nodes.Element

private data class PlayerAjaxResponse(
    val src_vip: String? = null,
    val src_v1: String? = null,
    val src_hd: String? = null,
    val src_arc: String? = null,
    val src_ok: String? = null,
    val src_dl: String? = null,
    val src_hx: String? = null
)

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
        TvType.Cartoon,
    )

    // Thêm interceptor để xử lý Cloudflare
    init {
        this.app.addInterceptor(CloudflareKiller())
    }
    
    private fun Element.toSearchResponse(forceTvType: TvType = TvType.Anime): SearchResponse? {
        val linkTag = this.selectFirst("a.film-poster-ahref") ?: return null
        val href = fixUrl(linkTag.attr("href"))
        val title = this.selectFirst("h3.film-name a")?.text() ?: return null
        val posterUrl = fixUrlNull(this.selectFirst("img.film-poster-img")?.let {
            it.attr("data-src").ifEmpty { it.attr("src") }
        })
        
        return if(forceTvType == TvType.Cartoon) {
             newTvSeriesSearchResponse(title, href, forceTvType) { this.posterUrl = posterUrl }
        } else {
             newAnimeSearchResponse(title, href, forceTvType) { this.posterUrl = posterUrl }
        }
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        if (page > 1) {
            val type = if (request.data.contains("cn-animation")) TvType.Cartoon else TvType.Anime
            val document = app.get("${request.data}?p=$page").document
            val items = document.select("div.film-list div.film-item").mapNotNull { it.toSearchResponse(type) }
            return newHomePageResponse(request.name, items)
        }

        val allRows = mutableListOf<HomePageList>()
        val frontPageDoc = app.get(mainUrl).document
        frontPageDoc.select("div.tray-item").mapNotNull { block ->
            val header = block.selectFirst("h3.tray-title a")?.text()?.trim()
            if (header != null) {
                val items = block.select("div.film-item").mapNotNull { it.toSearchResponse() }
                if (items.isNotEmpty()) {
                    allRows.add(HomePageList(header, items, false))
                }
            }
        }
        
        val paginatedGrids = listOf(
            mapOf("name" to "Phim Mới Cập Nhật", "url" to "$mainUrl/phim-moi-cap-nhap.html", "type" to TvType.Anime),
            mapOf("name" to "Anime", "url" to "$mainUrl/the-loai/anime.html", "type" to TvType.Anime),
            mapOf("name" to "Hoạt Hình Trung Quốc", "url" to "$mainUrl/the-loai/cn-animation.html", "type" to TvType.Cartoon)
        )

        paginatedGrids.apmap { grid ->
            val name = grid["name"] as String
            val url = grid["url"] as String
            val type = grid["type"] as TvType
            
            try {
                val gridDoc = app.get(url).document
                val items = gridDoc.select("div.film-list div.film-item").mapNotNull { it.toSearchResponse(type) }
                allRows.add(HomePageList(name, items, true, url))
            } catch (_: Exception) { }
        }
        
        return HomePageResponse(allRows)
    }
    
    override suspend fun search(query: String): List<SearchResponse> {
        val document = app.get("$mainUrl/tim-kiem/${query}").document
        return document.select("div.film-list div.film-item").mapNotNull { it.toSearchResponse() }
    }

    override suspend fun load(url: String): LoadResponse {
        val document = app.get(url).document
        val title = document.selectFirst("h1.film-title")?.text()?.trim() ?: "No Title"
        val posterUrl = fixUrlNull(document.selectFirst("div.film-poster img")?.attr("src"))
        val plot = document.selectFirst("div.film-description")?.text()?.trim()
        val tags = document.select("ul.film-meta-info li:contains(Thể loại) a").map { it.text() }
        val year = document.select("ul.film-meta-info li:contains(Năm) a").text().toIntOrNull()

        val tvType = when {
            tags.any { it.equals("CN Animation", ignoreCase = true) } -> TvType.Cartoon
            tags.any { it.equals("Anime", ignoreCase = true) } -> TvType.Anime
            else -> TvType.TvSeries
        }
        
        val episodes = document.select("div.episode-list a.episode-item").map {
            newEpisode(fixUrl(it.attr("href"))) {
                this.name = it.attr("title")
            }
        }.reversed()

        return if (episodes.isNotEmpty() && episodes.size > 1) {
            newTvSeriesLoadResponse(title, url, tvType, episodes) {
                this.posterUrl = posterUrl; this.plot = plot; this.tags = tags; this.year = year
            }
        } else {
            newMovieLoadResponse(title, url, TvType.Movie, url) {
                this.posterUrl = posterUrl; this.plot = plot; this.tags = tags; this.year = year
            }
        }
    }

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

        listOfNotNull(
            ajaxRes.src_vip, ajaxRes.src_v1, ajaxRes.src_hd,
            ajaxRes.src_arc, ajaxRes.src_ok, ajaxRes.src_dl, ajaxRes.src_hx
        ).apmap { url ->
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
