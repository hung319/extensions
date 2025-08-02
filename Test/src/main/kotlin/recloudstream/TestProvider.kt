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
        TvType.Anime,
        TvType.AnimeMovie,
        TvType.Cartoon,
    )

    private val killer = CloudflareKiller()
    private val userAgent = "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/127.0.0.0 Mobile Safari/537.36"
    private val headers = mapOf("User-Agent" to userAgent)

    override val mainPage = mainPageOf(
        "/phim-moi-cap-nhap.html" to "Phim Mới Cập Nhật",
        "/the-loai/anime.html" to "Anime",
        "/the-loai/cn-animation.html" to "Hoạt Hình Trung Quốc",
    )

    private fun Element.toSearchResponse(forceTvType: TvType? = null): SearchResponse {
        val linkTag = this.selectFirst("a")!!
        val href = fixUrl(linkTag.attr("href"))
        val title = linkTag.attr("title")

        val posterUrl = fixUrlNull(this.selectFirst("img")?.let {
            it.attr("data-src").ifEmpty { it.attr("src") }
        })
        
        val type = forceTvType ?: if(href.contains("cn-animation")) TvType.Cartoon else TvType.Anime

        return newAnimeSearchResponse(title, href, type) { this.posterUrl = posterUrl }
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = "$mainUrl${request.data}?p=$page"
        val document = app.get(url, headers = headers, interceptor = killer).document

        val type = when {
            request.data.contains("cn-animation") -> TvType.Cartoon
            else -> TvType.Anime
        }

        val home = document.select("div.halim-item").map { it.toSearchResponse(type) }
        
        // ⭐ [DEBUG] Ném ra lỗi kèm theo nội dung HTML nếu không tìm thấy item nào
        if (home.isEmpty()) {
            throw ErrorLoadingException("getMainPage không tìm thấy item. HTML nhận được:\n${document.html()}")
        }
        
        val hasNext = document.selectFirst("li.page-item.active + li:not(.disabled)") != null
        return newHomePageResponse(
            list = HomePageList(name = request.name, list = home),
            hasNext = hasNext
        )
    }
    
    override suspend fun search(query: String): List<SearchResponse> {
        val url = "$mainUrl/tim-kiem/${query}.html"
        val document = app.get(url, headers = headers, interceptor = killer).document
        
        val results = document.select("div.halim-item").map { it.toSearchResponse() }

        // ⭐ [DEBUG] Ném ra lỗi kèm theo nội dung HTML nếu không tìm thấy kết quả
        if (results.isEmpty()) {
            throw ErrorLoadingException("Search không tìm thấy kết quả. HTML nhận được:\n${document.html()}")
        }

        return results
    }

    override suspend fun load(url: String): LoadResponse {
        val document = app.get(url, headers = headers, interceptor = killer).document
        val title = document.selectFirst("h1.entry-title")?.text()?.trim() ?: "No Title"
        val posterUrl = fixUrlNull(document.selectFirst("div.poster img")?.attr("src"))
        val plot = document.selectFirst("div.entry-content > p")?.text()?.trim()
        val tags = document.select("a[rel=tag]").map { it.text() }
        val year = tags.firstOrNull { it.toIntOrNull() != null }?.toIntOrNull()

        val tvType = when {
            tags.any { it.equals("CN Animation", ignoreCase = true) } -> TvType.Cartoon
            tags.any { it.equals("Anime", ignoreCase = true) } -> TvType.Anime
            else -> TvType.Anime 
        }
        
        val episodes = document.select("ul.list-server li > a").map {
            newEpisode(fixUrl(it.attr("href"))) {
                this.name = it.text().trim()
            }
        }.reversed()
        
        return newTvSeriesLoadResponse(title, url, tvType, episodes) {
            this.posterUrl = posterUrl; this.plot = plot; this.tags = tags; this.year = year
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val watchPageDoc = app.get(data, headers = headers, referer = mainUrl, interceptor = killer).document

        val csrfToken = watchPageDoc.selectFirst("meta[name=csrf-token]")?.attr("content")
            ?: throw ErrorLoadingException("Không tìm thấy CSRF token")

        val movieId = watchPageDoc.body().attr("data-postid")
        if (movieId.isBlank()) throw ErrorLoadingException("Không tìm thấy Movie ID")
        
        val episodeId = watchPageDoc.selectFirst("a.streaming-server-item.active")?.attr("data-id")
            ?: throw ErrorLoadingException("Không tìm thấy Episode ID")

        val ajaxUrl = "$mainUrl/ajax/player"
        val ajaxData = mapOf("episodeId" to episodeId, "postId" to movieId)
        
        val ajaxHeaders = headers + mapOf(
            "Content-Type" to "application/x-www-form-urlencoded; charset=UTF-8",
            "X-CSRF-TOKEN" to csrfToken,
            "X-Requested-With" to "XMLHttpRequest",
            "Referer" to data
        )
        
        val ajaxRes = app.post(ajaxUrl, headers = ajaxHeaders, data = ajaxData, interceptor = killer).parsed<PlayerAjaxResponse>()

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
