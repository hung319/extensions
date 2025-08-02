// Save this file as HHDRagonProvider.kt

package recloudstream

import com.lagradost.cloudstream3.*
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

    private val userAgent = "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/127.0.0.0 Mobile Safari/537.36"
    private val headers = mapOf("User-Agent" to userAgent)

    override val mainPage = mainPageOf(
        "/phim-moi" to "Phim Mới Cập Nhật",
        "/the-loai/anime" to "Anime",
        "/the-loai/cn-animation" to "Hoạt Hình Trung Quốc",
    )

    // ⭐ [FIX] Cập nhật helper để parse cấu trúc HTML mới
    private fun Element.toSearchResponse(forceTvType: TvType? = null): SearchResponse {
        val linkTag = this.selectFirst("a")!!
        val href = fixUrl(linkTag.attr("href"))
        val title = linkTag.attr("title")
        // Trang web có thể dùng `src` hoặc `data-src` cho ảnh
        val posterUrl = fixUrlNull(linkTag.selectFirst("img")?.let {
            it.attr("data-src").ifEmpty { it.attr("src") }
        })
        
        val type = forceTvType ?: if(href.contains("cn-animation")) TvType.Cartoon else TvType.Anime

        return if(type == TvType.Cartoon) {
             newTvSeriesSearchResponse(title, href, type) { this.posterUrl = posterUrl }
        } else {
             newAnimeSearchResponse(title, href, type) { this.posterUrl = posterUrl }
        }
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        // Cấu trúc URL phân trang của trang cũng đã thay đổi
        val url = "$mainUrl${request.data}/page/$page"
        val document = app.get(url, headers = headers).document

        val type = when {
            request.data.contains("cn-animation") -> TvType.Cartoon
            else -> TvType.Anime
        }

        // ⭐ [FIX] Sử dụng selector mới cho danh sách phim
        val home = document.select("ul.halim-row > li.halim-item").map { it.toSearchResponse(type) }
        
        // Kiểm tra phân trang bằng selector mới
        val hasNext = document.selectFirst("a.next.page-numbers") != null

        return newHomePageResponse(
            list = HomePageList(
                name = request.name,
                list = home
            ),
            hasNext = hasNext
        )
    }
    
    override suspend fun search(query: String): List<SearchResponse> {
        val document = app.get("$mainUrl/?s=${query}", headers = headers).document
        // ⭐ [FIX] Sử dụng selector mới cho trang tìm kiếm
        return document.select("ul.halim-row > li.halim-item").map { it.toSearchResponse() }
    }

    override suspend fun load(url: String): LoadResponse {
        val document = app.get(url, headers = headers).document
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
        val watchPageDoc = app.get(data, headers = headers, referer = mainUrl).document
        
        // Logic trích xuất link của trang này rất phức tạp, cần tìm đúng player
        // Giả sử logic cũ với AJAX vẫn có thể hoạt động nếu ta tìm được ID
        val movieId = watchPageDoc.body().attr("data-postid")
        if (movieId.isBlank()) throw ErrorLoadingException("Không tìm thấy Movie ID")

        // Trang này có vẻ không còn dùng AJAX nữa, mà dùng iframe trực tiếp
        val iframeSrc = watchPageDoc.selectFirst("iframe#halim-player")?.attr("src")
            ?: throw ErrorLoadingException("Không tìm thấy Iframe Player")

        // Tải nội dung từ iframe để tìm link video
        val iframeDoc = app.get(iframeSrc, headers = headers, referer = data).document
        val script = iframeDoc.select("script").firstOrNull { it.data().contains("sources:") }?.data()
            ?: throw ErrorLoadingException("Không tìm thấy script chứa sources")

        // Regex để tìm link m3u8 trong script
        Regex("""file:\s*['"]([^'"]+\.m3u8)['"]""").findAll(script).forEach { match ->
            val m3u8Url = match.groupValues[1]
            callback(
                ExtractorLink(
                    this.name,
                    this.name,
                    m3u8Url,
                    referer = mainUrl,
                    quality = Qualities.Unknown.value,
                    type = ExtractorLinkType.M3U8,
                )
            )
        }

        return true
    }
}
