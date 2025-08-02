// Save this file as HHDRagonProvider.kt

package recloudstream

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.network.CloudflareKiller
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
    override var lang = "vi"
    override val hasMainPage = true
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(
        TvType.Movie, TvType.TvSeries, TvType.Anime,
        TvType.AnimeMovie, TvType.Cartoon,
    )

    private val killer = CloudflareKiller()

    // ⭐ [FIX] Sử dụng 'mainPageOf' để định nghĩa trang chủ
    override val mainPage = mainPageOf(
        "/phim-moi-cap-nhap.html" to "Phim Mới Cập Nhật",
        "/the-loai/anime.html" to "Anime",
        "/the-loai/cn-animation.html" to "Hoạt Hình Trung Quốc",
    )

    private fun Element.toSearchResponse(forceTvType: TvType? = null): SearchResponse {
        val linkTag = this.selectFirst("a.film-poster-ahref")!!
        val href = fixUrl(linkTag.attr("href"))
        val title = this.selectFirst("h3.film-name a")!!.text()
        val posterUrl = fixUrlNull(this.selectFirst("img.film-poster-img")?.let {
            it.attr("data-src").ifEmpty { it.attr("src") }
        })
        
        val type = forceTvType ?: if(href.contains("cn-animation")) TvType.Cartoon else TvType.Anime

        return if(type == TvType.Cartoon) {
             newTvSeriesSearchResponse(title, href, type) { this.posterUrl = posterUrl }
        } else {
             newAnimeSearchResponse(title, href, type) { this.posterUrl = posterUrl }
        }
    }

    // ⭐ [FIX] Viết lại hoàn toàn getMainPage theo pattern mới
    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = "$mainUrl${request.data}?p=$page"
        val document = app.get(url, interceptor = killer).document

        // Xác định loại nội dung dựa trên request data
        val type = when {
            request.data.contains("cn-animation") -> TvType.Cartoon
            else -> TvType.Anime
        }

        val home = document.select("div.film-list div.film-item").map { it.toSearchResponse(type) }
        
        // Xác định xem có trang tiếp theo hay không
        val hasNext = document.selectFirst("li.page-item.active + li:not(.disabled)") != null

        return newHomePageResponse(
            list = HomePageList(
                name = request.name,
                list = home
            ),
            hasNext = hasNext
        )
    }
    
    override suspend fun search(query: String): List<SearchResponse> {
        val document = app.get("$mainUrl/tim-kiem/${query}", interceptor = killer).document
        return document.select("div.film-list div.film-item").map { it.toSearchResponse() }
    }

    override suspend fun load(url: String): LoadResponse {
        val document = app.get(url, interceptor = killer).document
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
        val watchPageDoc = app.get(data, referer = mainUrl, interceptor = killer).document
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
        val ajaxRes = app.post(ajaxUrl, headers = headers, data = ajaxData, interceptor = killer).parsed<PlayerAjaxResponse>()

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
