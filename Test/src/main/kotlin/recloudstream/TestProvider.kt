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

// JSON response cho player.php
private data class PlayerPhpData(val sources: String)
private data class PlayerPhpResponse(val data: PlayerPhpData, val status: Boolean)


class HHDRagonProvider : MainAPI() {
    override var mainUrl = "https://hhtq.lat"
    override var name = "HHTQ"
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
        "/type/" to "Mới Cập Nhật",
        "/genres/anime" to "Anime",
        "/genres/hhkungfu" to "HHKungfu",
        "/genres/hhpanda" to "HHPanda"
    )
    
    private fun Element.toSearchResponse(forceTvType: TvType? = null): SearchResponse {
        val linkTag = this.selectFirst("a.halim-thumb") 
            ?: return newAnimeSearchResponse("Lỗi", "", TvType.Anime)
        
        val href = fixUrl(linkTag.attr("href"))
        val title = linkTag.attr("title").trim()
        val posterUrl = fixUrlNull(this.selectFirst("figure img")?.attr("data-src"))
        val type = forceTvType ?: TvType.Anime
        val episodeLabel = this.selectFirst("span.movie-label")?.text()

        return newAnimeSearchResponse(title, href, type) {
            this.posterUrl = posterUrl
            this.otherName = episodeLabel
        }
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        withContext(Dispatchers.Main) {
            CommonActivity.activity?.let { activity ->
                showToast(activity, "Provider HHTQ (H4RS)", Toast.LENGTH_LONG)
            }
        }
        val url = "$mainUrl${request.data}" + if (page > 1) "?page=$page" else ""
        val document = app.get(url, headers = headers, interceptor = killer).document

        val type = when {
            request.data.contains("hhkungfu") || request.data.contains("hhpanda") -> TvType.Cartoon
            else -> TvType.Anime
        }

        val home = document.select("div.halim_box article.thumb.grid-item").map { it.toSearchResponse(type) }
        
        val hasNext = document.selectFirst("a.next.page-numbers") != null && home.isNotEmpty()

        return newHomePageResponse(
            list = HomePageList(name = request.name, list = home),
            hasNext = hasNext
        )
    }
    
    override suspend fun search(query: String): List<SearchResponse> {
        val url = "$mainUrl/search?s=${query}"
        val document = app.get(url, headers = headers, interceptor = killer).document
        return document.select("div.halim_box article.thumb.grid-item").map { it.toSearchResponse() }
    }

    override suspend fun load(url: String): LoadResponse {
        val document = app.get(url, headers = headers, interceptor = killer).document
        
        val title = document.selectFirst("div.movie-detail a.entry-title")?.text()?.trim() ?: "No Title"
        val posterUrl = fixUrlNull(document.selectFirst("div.movie-poster img.movie-thumb")?.attr("src"))
        val plot = document.selectFirst("div.entry-content article.item-content > p")?.text()
        val tags = document.select("p.genre a").map { it.text() }
        
        val tvType = when {
            tags.any { it.equals("HHKungfu", ignoreCase = true) || it.equals("HHPanda", ignoreCase = true) } -> TvType.Cartoon
            else -> TvType.Anime 
        }
        
        // **FIX:** Chỉ chọn danh sách tập từ tab-id="0" (Server #1)
        val episodeList = document.selectFirst("div.episode-lists[tab-id=0]")
            ?: document.selectFirst("div.episode-lists") // Fallback

        // **FIX:** Đã xóa .reversed() theo yêu cầu để sắp xếp 1, 2, 3...
        val episodes = episodeList?.select("a.episode-item")?.map {
            newEpisode(fixUrl(it.attr("href"))) {
                this.name = "Tập ${it.text()}"
            }
        } ?: listOf()
        
        return newTvSeriesLoadResponse(title, url, tvType, episodes) {
            this.posterUrl = posterUrl
            this.plot = plot
            this.tags = tags
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        
        // 1. Tải trang xem phim để lấy config và nonce
        val watchPageDoc = app.get(data, headers = headers, referer = mainUrl, interceptor = killer).document

        // 2. Lấy nonce từ body
        val nonce = watchPageDoc.selectFirst("body")?.attr("data-nonce")
            ?: throw ErrorLoadingException("Không tìm thấy data-nonce")

        // 3. Tìm script chứa 'var halim_cfg' (có thể là base64 hoặc inline)
        val script = watchPageDoc.select("script").firstOrNull { it.data().contains("var halim_cfg") }?.data()
            ?: throw ErrorLoadingException("Không tìm thấy halim_cfg script")

        // 4. Trích xuất các biến cần thiết từ halim_cfg
        val postId = Regex("""post_id"\s*:\s*"(\d+)"""").find(script)?.groupValues?.get(1)
            ?: throw ErrorLoadingException("Không tìm thấy post_id")
        
        val playerUrl = Regex("""player_url"\s*:\s*"([^"]+)"""").find(script)?.groupValues?.get(1)?.replace("\\/", "/")
            ?: throw ErrorLoadingException("Không tìm thấy player_url")
            
        val episodeSlug = Regex("""episode_slug"\s*:\s*(\d+)""").find(script)?.groupValues?.get(1)
            ?: throw ErrorLoadingException("Không tìm thấy episode_slug")
        
        val server = Regex("""server"\s*:\s*(\d+)""").find(script)?.groupValues?.get(1)
            ?: throw ErrorLoadingException("Không tìm thấy server")

        // 5. Xây dựng URL cho AJAX call (dựa theo curl của user)
        val fullPlayerUrl = (if (playerUrl.startsWith("http")) playerUrl else "$mainUrl$playerUrl")
        val ajaxUrlWithParams = "$fullPlayerUrl?episode_slug=$episodeSlug&server_id=$server&subsv_id=&post_id=$postId&nonce=$nonce&custom_var="
        
        val ajaxHeaders = headers + mapOf(
            "Accept" to "text/html, */*; q=0.01",
            "X-Requested-With" to "XMLHttpRequest",
            "Referer" to data
        )

        // 6. Gọi AJAX (đây là GET, dựa trên curl của user)
        val ajaxRes = app.get(ajaxUrlWithParams, headers = ajaxHeaders, interceptor = killer)
                        .parsed<PlayerPhpResponse>()

        if (!ajaxRes.status || ajaxRes.data.sources.isBlank()) {
            throw ErrorLoadingException("AJAX call to player.php thất bại hoặc không có sources")
        }

        // 7. Phân tích HTML trả về (bên trong 'sources')
        val playerHtml = ajaxRes.data.sources
        val iframeSrc = Jsoup.parse(playerHtml).selectFirst("iframe")?.attr("src")
            ?: Jsoup.parse(playerHtml).selectFirst("video > source")?.attr("src")
            ?: throw ErrorLoadingException("Không tìm thấy iframe hoặc video source trong AJAX response")

        // 8. Tải extractor từ src của iframe
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
            // Xử lý các link extractor (như streamblue.biz)
            loadExtractor(iframeSrc, data, subtitleCallback, callback)
        }
        
        return true
    }
}
