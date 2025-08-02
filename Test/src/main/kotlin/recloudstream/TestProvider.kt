// Save this file as HHDRagonProvider.kt

package recloudstream

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.network.CloudflareKiller
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.loadExtractor
import org.jsoup.nodes.Element

// JSON response cho AJAX request trong loadLinks
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
        "/phim-moi-cap-nhap.html" to "Mới Cập Nhật",
        "/the-loai/anime.html" to "Anime",
        "/the-loai/cn-animation.html" to "Hoạt Hình Trung Quốc",
    )

    // ⭐ [FIX] Helper được viết lại để parse đúng cấu trúc 'div.movie-item'
    private fun Element.toSearchResponse(forceTvType: TvType? = null): SearchResponse {
        val linkTag = this.selectFirst("a")!!
        val href = fixUrl(linkTag.attr("href"))
        val title = linkTag.attr("title")
        val posterUrl = fixUrlNull(this.selectFirst("img")?.attr("src"))
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

        // ⭐ [FIX] Selector chính xác cho các mục phim là 'div.movie-item'
        val home = document.select("div.movie-item").map { it.toSearchResponse(type) }
        
        // Selector cho pagination dựa trên file main.html
        val hasNext = document.selectFirst("a.page-link:contains(Cuối)") != null && home.isNotEmpty()

        return newHomePageResponse(
            list = HomePageList(name = request.name, list = home),
            hasNext = hasNext
        )
    }
    
    override suspend fun search(query: String): List<SearchResponse> {
        val url = "$mainUrl/tim-kiem/?keyword=${query}"
        val document = app.get(url, headers = headers, interceptor = killer).document
        // ⭐ [FIX] Trang tìm kiếm cũng dùng 'div.movie-item'
        return document.select("div.movie-item").map { it.toSearchResponse() }
    }

    override suspend fun load(url: String): LoadResponse {
        val document = app.get(url, headers = headers, interceptor = killer).document
        
        // ⭐ [FIX] Selectors được cập nhật theo file load.html
        val title = document.selectFirst("h1.heading_movie")?.text() ?: "No Title"
        val posterUrl = fixUrlNull(document.selectFirst("div.first img")?.attr("src"))
        val plot = document.selectFirst("div.desc div.list-item-episode")?.text()
        val tags = document.select("div.list_cate div a").map { it.text() }
        val yearText = document.select("div.update_time div")?.last()?.text()
        val year = yearText?.let { Regex("""\d{4}""").find(it)?.value?.toIntOrNull() }

        val tvType = when {
            tags.any { it.equals("CN Animation", ignoreCase = true) } -> TvType.Cartoon
            else -> TvType.Anime 
        }
        
        val episodes = document.select("div.list-item-episode a").map {
            newEpisode(fixUrl(it.attr("href"))) {
                this.name = it.attr("title")
            }
        }.reversed()
        
        return newTvSeriesLoadResponse(title, url, tvType, episodes) {
            this.posterUrl = posterUrl
            this.plot = plot
            this.tags = tags
            this.year = year
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val watchPageDoc = app.get(data, headers = headers, referer = mainUrl, interceptor = killer).document

        // ⭐ [FIX] Logic AJAX được xác nhận là đúng, dùng regex để lấy ID từ script
        val script = watchPageDoc.select("script").firstOrNull { it.data().contains("var \$info_play_video") }?.data()
            ?: throw ErrorLoadingException("Không tìm thấy script chứa thông tin phim")

        val csrfToken = watchPageDoc.selectFirst("meta[name=csrf-token]")?.attr("content")
            ?: throw ErrorLoadingException("Không tìm thấy CSRF token")
        
        val movieId = Regex("""MovieID:\s*(\d+)""").find(script)?.groupValues?.get(1)
            ?: throw ErrorLoadingException("Không tìm thấy Movie ID")
        
        val episodeId = Regex("""EpisodeID:\s*(\d+)""").find(script)?.groupValues?.get(1)
            ?: throw ErrorLoadingException("Không tìm thấy Episode ID")

        val ajaxUrl = "$mainUrl/server/ajax/player"
        val ajaxData = mapOf("MovieID" to movieId, "EpisodeID" to episodeId)
        
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
            loadExtractor(url, data, subtitleCallback, callback)
        }
        
        return true
    }
}
