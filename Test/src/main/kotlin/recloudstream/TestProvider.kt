// Save this file as HHDRagonProvider.kt (hoặc HHTQProvider.kt)

package recloudstream

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.network.CloudflareKiller
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.loadExtractor
import org.jsoup.nodes.Element
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import com.lagradost.cloudstream3.CommonActivity.showToast
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import android.widget.Toast
// Thêm import cho newExtractorLink
import com.lagradost.cloudstream3.utils.newExtractorLink
// Thêm Jsoup để phân tích HTML từ AJAX
import org.jsoup.Jsoup

// JSON response mới cho AJAX request trong loadLinks
private data class HalimAjaxResponse(
    val html: String
)

class HHDRagonProvider : MainAPI() {
    override var mainUrl = "https://hhtq.lat" // Đã thay đổi
    override var name = "HHTQ" // Đã thay đổi
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
        "/type/" to "Mới Cập Nhật", // Đã thay đổi (lấy từ link section title)
        "/genres/anime" to "Anime", // Đã thay đổi
        "/genres/hhkungfu" to "HHKungfu", // Đã thay đổi
        "/genres/hhpanda" to "HHPanda" // Đã thêm
    )
    
    private fun Element.toSearchResponse(forceTvType: TvType? = null): SearchResponse {
        // Selector đã thay đổi
        val linkTag = this.selectFirst("a.halim-thumb") 
            ?: return newAnimeSearchResponse("Lỗi", "", TvType.Anime) // failsafe
        
        val href = fixUrl(linkTag.attr("href"))
        val title = linkTag.attr("title").trim()
        // Lấy ảnh từ data-src
        val posterUrl = fixUrlNull(this.selectFirst("figure img")?.attr("data-src"))
        val type = forceTvType ?: TvType.Anime // Caller nên chỉ định type
        // Lấy nhãn (Tập 196)
        val episodeLabel = this.selectFirst("span.movie-label")?.text()

        return newAnimeSearchResponse(title, href, type) {
            this.posterUrl = posterUrl
            this.otherName = episodeLabel // Dùng otherName để hiển thị nhãn tập
        }
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        withContext(Dispatchers.Main) {
            CommonActivity.activity?.let { activity ->
                showToast(activity, "Provider HHTQ (H4RS)", Toast.LENGTH_LONG)
            }
        }
        // Logic phân trang đã thay đổi: ?page=
        val url = "$mainUrl${request.data}" + if (page > 1) "?page=$page" else ""
        val document = app.get(url, headers = headers, interceptor = killer).document

        val type = when {
            request.data.contains("hhkungfu") || request.data.contains("hhpanda") -> TvType.Cartoon
            else -> TvType.Anime
        }

        // Selector item đã thay đổi
        val home = document.select("div.halim_box article.thumb.grid-item").map { it.toSearchResponse(type) }
        
        // Selector pagination đã thay đổi
        val hasNext = document.selectFirst("a.next.page-numbers") != null && home.isNotEmpty()

        return newHomePageResponse(
            list = HomePageList(name = request.name, list = home),
            hasNext = hasNext
        )
    }
    
    override suspend fun search(query: String): List<SearchResponse> {
        // URL tìm kiếm đã thay đổi
        val url = "$mainUrl/search?s=${query}"
        val document = app.get(url, headers = headers, interceptor = killer).document
        // Selector item đã thay đổi
        return document.select("div.halim_box article.thumb.grid-item").map { it.toSearchResponse() }
    }

    override suspend fun load(url: String): LoadResponse {
        val document = app.get(url, headers = headers, interceptor = killer).document
        
        // Selector tiêu đề đã thay đổi
        val title = document.selectFirst("div.movie-detail a.entry-title")?.text()?.trim() ?: "No Title"
        // Selector poster đã thay đổi
        val posterUrl = fixUrlNull(document.selectFirst("div.movie-poster img.movie-thumb")?.attr("src"))
        // Selector nội dung đã thay đổi
        val plot = document.selectFirst("div.entry-content article.item-content > p")?.text()
        // Selector thể loại đã thay đổi
        val tags = document.select("p.genre a").map { it.text() }
        
        // Không tìm thấy năm sản xuất rõ ràng trong HTML mới, tạm thời bỏ qua
        // val year = null 

        val tvType = when {
            // Cập nhật logic phát hiện type
            tags.any { it.equals("HHKungfu", ignoreCase = true) || it.equals("HHPanda", ignoreCase = true) } -> TvType.Cartoon
            else -> TvType.Anime 
        }
        
        // Selector danh sách tập đã thay đổi
        val episodes = document.select("div.episode-lists a.episode-item").map {
            newEpisode(fixUrl(it.attr("href"))) {
                // Tên tập lấy từ text() thay vì title
                this.name = "Tập ${it.text()}"
            }
        }.reversed()
        
        // Phim đề xuất bị comment-out trong HTML mới, tạm thời bỏ qua
        // val recommendations = null
        
        return newTvSeriesLoadResponse(title, url, tvType, episodes) {
            this.posterUrl = posterUrl
            this.plot = plot
            this.tags = tags
            // this.year = year // Bỏ qua
            // this.recommendations = recommendations // Bỏ qua
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        
        // 1. Phân tích server và episode_slug từ URL
        val (server, episodeSlug) = Regex("""sv-(\d+)-tap-([\d-]+)""").find(data)?.destructured
            ?: throw ErrorLoadingException("Không thể phân tích server/episode từ URL: $data")

        // 2. Tải trang xem phim
        val watchPageDoc = app.get(data, headers = headers, referer = mainUrl, interceptor = killer).document

        // 3. Tìm script chứa 'var halim_cfg'
        val script = watchPageDoc.select("script[data-optimized=\"1\"]").firstOrNull { it.data().contains("var halim_cfg") }?.data()
            ?: watchPageDoc.select("script").firstOrNull { it.data().contains("var halim_cfg") }?.data()
            ?: throw ErrorLoadingException("Không tìm thấy halim_cfg script")

        // 4. Trích xuất post_id từ script
        val postId = Regex("""post_id"\s*:\s*"(\d+)"""").find(script)?.groupValues?.get(1)
            ?: throw ErrorLoadingException("Không tìm thấy post_id trong halim_cfg")
        
        // 5. Trích xuất ajax_url (từ halim_cfg hoặc script dự phòng)
        val ajaxUrl = Regex("""ajax_url"\s*:\s*"([^"]+)"""").find(script)?.groupValues?.get(1)?.replace("\\/", "/")
            ?: run {
                // Thử tìm trong script#halim-init-js-extra
                val initScript = watchPageDoc.selectFirst("script#halim-init-js-extra")?.data()
                Regex("""ajax_url"\s*:\s*"([^"]+)"""").find(initScript ?: "")?.groupValues?.get(1)?.replace("\\/", "/")
            }
            ?: throw ErrorLoadingException("Không tìm thấy ajax_url")

        val fullAjaxUrl = if (ajaxUrl.startsWith("http")) ajaxUrl else (if(ajaxUrl.startsWith("/")) "$mainUrl$ajaxUrl" else "$mainUrl/$ajaxUrl")

        // 6. Chuẩn bị dữ liệu cho AJAX call
        val ajaxData = mapOf(
            "action" to "halim_ajax_player",
            "post" to postId,
            "server" to server,
            "episode" to episodeSlug
        )
        
        val ajaxHeaders = headers + mapOf(
            "Content-Type" to "application/x-www-form-urlencoded; charset=UTF-8",
            "X-Requested-With" to "XMLHttpRequest",
            "Referer" to data
        )

        // 7. Gọi AJAX để lấy HTML của trình phát
        val ajaxRes = app.post(fullAjaxUrl, headers = ajaxHeaders, data = ajaxData, interceptor = killer)
                        .parsed<HalimAjaxResponse>()

        // 8. Phân tích HTML trả về để tìm iframe hoặc video
        val playerHtml = ajaxRes.html
        val iframeSrc = Jsoup.parse(playerHtml).selectFirst("iframe")?.attr("src")
            // Dự phòng: tìm thẻ video trực tiếp
            ?: Jsoup.parse(playerHtml).selectFirst("video > source")?.attr("src")
            ?: throw ErrorLoadingException("Không tìm thấy iframe hoặc video source trong AJAX response")

        if (iframeSrc.endsWith(".mp4") || iframeSrc.contains(".mp4?")) {
            callback(newExtractorLink(
                source = name,
                name = "Direct MP4",
                url = iframeSrc,
                type = ExtractorLinkType.VIDEO
            ) {
                this.referer = data
                this.quality = Qualities.Unknown.value
            })
        } else {
            // 9. Tải extractor từ src của iframe
            loadExtractor(iframeSrc, data, subtitleCallback, callback)
        }
        
        return true
    }
}
