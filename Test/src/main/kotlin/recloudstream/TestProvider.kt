// Save this file as HHDRagonProvider.kt

package recloudstream

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
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
import com.lagradost.cloudstream3.utils.newExtractorLink
import org.jsoup.Jsoup
import java.util.Base64

// === START FIX: Sửa cấu trúc Data Class ===
// PlayerPhpData (đối tượng bên trong "data")
private data class PlayerPhpData(
    val sources: String,
    val status: Boolean // 'status' nằm ở đây
)

// PlayerPhpResponse (đối tượng top-level)
private data class PlayerPhpResponse(
    val data: PlayerPhpData // Chỉ chứa 'data'
)
// === END FIX ===

// Data classes để parse JSON từ streamblue
private data class StreamBlueSourceItem(
    val file: String,
    val label: String
)

private data class StreamBlueMessage(
    val type: String,
    val source: String // Đây là một JSON string
)

private data class StreamBlueResponse(
    val status: Boolean,
    val message: StreamBlueMessage
)


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
    private val userAgent = "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/5.37.36 (KHTML, like Gecko) Chrome/127.0.0.0 Mobile Safari/5.36"
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
        
        val episodeList = document.selectFirst("div.episode-lists[tab-id=0]")
            ?: document.selectFirst("div.episode-lists")

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
        
        // === BƯỚC 1: LẤY IFRAME SRC TỪ HHTQ ===

        val watchPageDoc = app.get(data, headers = headers, referer = mainUrl, interceptor = killer).document
        val nonce = watchPageDoc.selectFirst("body")?.attr("data-nonce")
            ?: throw ErrorLoadingException("Không tìm thấy data-nonce")

        var script: String = ""
        val scriptElements = watchPageDoc.select("script")

        for (scriptElement in scriptElements) {
            val inlineScript = scriptElement.data()
            if (inlineScript.contains("var halim_cfg")) {
                script = inlineScript
                break
            }

            val srcAttr = scriptElement.attr("src")
            if (srcAttr.startsWith("data:text/javascript;base64,")) {
                val base64Data = srcAttr.substringAfter("data:text/javascript;base64,")
                try {
                    val decodedScript = Base64.getDecoder().decode(base64Data).toString(Charsets.UTF_8)
                    if (decodedScript.contains("var halim_cfg")) {
                        script = decodedScript
                        break
                    }
                } catch (e: Exception) {
                    continue
                }
            }
        }

        if (script.isBlank()) {
            throw ErrorLoadingException("Không tìm thấy halim_cfg script")
        }
        
        val postId = Regex("""post_id"\s*:\s*"(\d+)"""").find(script)?.groupValues?.get(1)
            ?: throw ErrorLoadingException("Không tìm thấy post_id")
        
        val playerUrl = Regex("""player_url"\s*:\s*"([^"]+)"""").find(script)?.groupValues?.get(1)?.replace("\\/", "/")
            ?: throw ErrorLoadingException("Không tìm thấy player_url")
            
        val episodeSlug = Regex("""episode_slug"\s*:\s*["']?([\d\w-]+)["']?""").find(script)?.groupValues?.get(1)
            ?: throw ErrorLoadingException("Không tìm thấy episode_slug")
        
        val server = Regex("""server"\s*:\s*["']?(\d+)["']?""").find(script)?.groupValues?.get(1)
            ?: throw ErrorLoadingException("Không tìm thấy server")

        val postUrl = Regex("""post_url"\s*:\s*"([^"]+)"""").find(script)?.groupValues?.get(1)?.replace("\\/", "/")
            ?: data

        val fullPlayerUrl = (if (playerUrl.startsWith("http")) playerUrl else "$mainUrl$playerUrl")
        val ajaxUrlWithParams = "$fullPlayerUrl?episode_slug=$episodeSlug&server_id=$server&subsv_id=&post_id=$postId&nonce=$nonce&custom_var="
        
        val ajaxHeaders = headers + mapOf(
            "Accept" to "text/html, */*; q=0.01",
            "X-Requested-With" to "XMLHttpRequest",
            "Referer" to postUrl
        )

        val ajaxRes = app.get(ajaxUrlWithParams, headers = ajaxHeaders, interceptor = killer)
        val responseText = ajaxRes.text
        
        val cleanedText = responseText.replace("\\/ V\\/", "\\/").replace("bizV\\/v", "biz\\/v")

        val parsedResponse = try {
            jacksonObjectMapper().readValue<PlayerPhpResponse>(cleanedText)
        } catch (e: Exception) {
            throw ErrorLoadingException(
                "Lỗi parse JSON từ player.php.\n" +
                "Request: $ajaxUrlWithParams\n" +
                "Original Response: $responseText\n" +
                "Cleaned Response: $cleanedText\n" +
                "Error: ${e.message}" // Đây là dòng 219
            )
        }

        // === START FIX: Cập nhật kiểm tra status ===
        if (!parsedResponse.data.status || parsedResponse.data.sources.isBlank()) {
        // === END FIX ===
            throw ErrorLoadingException(
                "AJAX call to player.php thất bại hoặc không có sources.\n" +
                "Request: $ajaxUrlWithParams\n" +
                "Cleaned Response: $cleanedText"
            )
        }

        val playerHtml = parsedResponse.data.sources
        val iframeSrc = Jsoup.parse(playerHtml).selectFirst("iframe")?.attr("src")
            ?: Jsoup.parse(playerHtml).selectFirst("video > source")?.attr("src")
            ?: throw ErrorLoadingException("Không tìm thấy iframe hoặc video source trong AJAX response")

        // === BƯỚC 2: XỬ LÝ LINK IFRAME (STREAMBLUE) ===
        if (iframeSrc.contains("streamblue.biz")) {
            val streamBlueDoc = app.get(iframeSrc, referer = data).document
        
            val videoId = streamBlueDoc.selectFirst("body")?.attr("data-video")
                ?: throw ErrorLoadingException("Không tìm thấy data-video trên trang StreamBlue")

            val streamBlueApiUrl = "https://streamblue.biz/players/sources?videoId=$videoId"
            
            val postHeaders = mapOf(
                "authority" to "streamblue.biz",
                "accept" to "*/*",
                "origin" to "https://streamblue.biz",
                "referer" to iframeSrc,
                "sec-fetch-mode" to "cors",
                "sec-fetch-site" to "same-origin",
                "x-requested-with" to "XMLHttpRequest",
                "user-agent" to userAgent
            )

            val streamBlueRes = app.post(streamBlueApiUrl, headers = postHeaders).parsed<StreamBlueResponse>()

            if (!streamBlueRes.status || streamBlueRes.message.type != "direct") {
                throw ErrorLoadingException("StreamBlue API call thất bại hoặc không phải 'direct' type")
            }

            val mapper = jacksonObjectMapper()
            val sourceString = streamBlueRes.message.source
            val sources = mapper.readValue<List<StreamBlueSourceItem>>(sourceString)

            sources.forEach { source ->
                val isM3u8 = source.file.contains(".m3u8")
                
                newExtractorLink(
                    source = this.name,
                    name = "${this.name} ${source.label}",
                    url = source.file,
                    type = if (isM3u8) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                ) {
                    this.referer = iframeSrc
                    this.quality = when {
                        source.label.contains("HD", ignoreCase = true) -> Qualities.P720.value
                        source.label.contains("FULLHD", ignoreCase = true) -> Qualities.P1080.value
                        else -> Qualities.Unknown.value
                    }
                }.let { callback(it) }
            }
        } 
        else if (iframeSrc.endsWith(".mp4") || iframeSrc.contains(".mp4?")) {
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
            loadExtractor(iframeSrc, data, subtitleCallback, callback)
        }
        
        return true
    }
}
