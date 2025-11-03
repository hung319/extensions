// Save this file as HHDRagonProvider.kt
package recloudstream

import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.network.CloudflareKiller
import com.lagradost.cloudstream3.utils.*
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import com.lagradost.cloudstream3.CommonActivity.showToast
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import android.widget.Toast
// Import cho cả 2 hàm newExtractorLink (suspend và non-suspend)
import com.lagradost.cloudstream3.utils.newExtractorLink

// Data class cho AJAX request tới player.php (cURL 1)
private data class PlayerResponse(
    @JsonProperty("data") val data: PlayerData?,
    @JsonProperty("status") val status: Boolean
)
private data class PlayerData(
    // "sources" là một chuỗi HTML chứa iframe
    @JsonProperty("sources") val sources: String?
)

// Data class cho streamblue.biz (cURL 2)
private data class StreamBlueResponse(
    @JsonProperty("message") val message: StreamBlueMessage?
)
private data class StreamBlueMessage(
    // "source" là một chuỗi JSON
    @JsonProperty("source") val source: String?
)
private data class StreamBlueSource(
    @JsonProperty("file") val file: String,
    @JsonProperty("label") val label: String?,
    @JsonProperty("type") val type: String?
)

// =========================================================================
// 1. DÙNG HHDRagonProvider
// =========================================================================
class HHDRagonProvider : MainAPI() {
    override var mainUrl = "https://hhtq.lat"
    override var name = "HHTQ" // Tên có thể vẫn giữ là HHTQ
    override var lang = "vi"
    override val hasMainPage = true
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(
        TvType.Cartoon,
        TvType.Anime
    )

    private val killer = CloudflareKiller()
    private val userAgent = "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/127.0.0.0 Mobile Safari/536.36"
    private val headers = mapOf("User-Agent" to userAgent)

    override val mainPage = mainPageOf(
        "/" to "Mới Cập Nhật",
        "/the-loai/hh3d" to "HH3D",
        "/the-loai/hh2d" to "HH2D",
    )
    
    private fun Element.toSearchResponse(): SearchResponse {
        val linkTag = this.selectFirst("a")!!
        val href = fixUrl(linkTag.attr("href"))
        val title = linkTag.attr("title")
        val posterUrl = fixUrlNull(this.selectFirst("img")?.attr("data-src"))
        val type = TvType.Cartoon 

        return newAnimeSearchResponse(title, href, type) { this.posterUrl = posterUrl }
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        withContext(Dispatchers.Main) {
            CommonActivity.activity?.let { activity ->
                showToast(activity, "Provider cho HHTQ.lat", Toast.LENGTH_LONG)
            }
        }
        
        val url = if (page == 1) {
            "$mainUrl${request.data}"
        } else {
            "$mainUrl${request.data.removeSuffix("/")}/page/$page/"
        }
        
        val document = app.get(url, headers = headers, interceptor = killer).document
        val home = document.select("div.halim_box div.item").map { it.toSearchResponse() }
        val hasNext = document.selectFirst("a.next.page-numbers") != null && home.isNotEmpty()

        return newHomePageResponse(
            list = HomePageList(name = request.name, list = home),
            hasNext = hasNext
        )
    }
    
    override suspend fun search(query: String): List<SearchResponse> {
        val url = "$mainUrl/tim-kiem/?keyword=${query}"
        val document = app.get(url, headers = headers, interceptor = killer).document
        return document.select("div.halim_box div.item").map { it.toSearchResponse() }
    }

    override suspend fun load(url: String): LoadResponse {
        val document = app.get(url, headers = headers, interceptor = killer).document
        
        val title = document.selectFirst("h1.entry-title")?.text()?.trim() ?: "No Title"
        val posterUrl = fixUrlNull(document.selectFirst("div.film-poster img")?.attr("data-src"))
        val plot = document.selectFirst("div.film-content")?.text()?.trim()
        val tags = document.select("li.halim-tags a").map { it.text() }
        val year = document.select("ul.filminfo li a[href*='/year/']").firstOrNull()?.text()?.toIntOrNull()
        val tvType = TvType.Cartoon
        
        val episodes = document.select("div.episode-lists a.episode-item").map {
            newEpisode(fixUrl(it.attr("href"))) {
                this.name = "Tập ${it.text()}"
            }
        }.reversed()
        
        val recommendations = document.select("div.related-movie div.item").mapNotNull { it.toSearchResponse() }
        
        return newTvSeriesLoadResponse(title, url, tvType, episodes) {
            this.posterUrl = posterUrl
            this.plot = plot
            this.tags = tags
            this.year = year
            this.recommendations = recommendations
        }
    }

    // Biểu thức Regex để tìm nonce và post_id từ script
    private val nonceRegex = Regex(""""nonce":"?([a-zA-Z0-9]+)"?""")
    private val postIdRegex = Regex(""""post_id":"?(\d+)"?""")

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val document = app.get(data, headers = headers, referer = mainUrl, interceptor = killer).document
        val scriptData = document.select("script").map { it.data() }.firstOrNull { it.contains("halim_cfg") }
            ?: throw ErrorLoadingException("Không tìm thấy halim_cfg script")

        val nonce = nonceRegex.find(scriptData)?.groupValues?.get(1)
            ?: throw ErrorLoadingException("Không tìm thấy nonce")
        val postId = postIdRegex.find(scriptData)?.groupValues?.get(1)
            ?: throw ErrorLoadingException("Không tìm thấy post_id")

        val servers = document.select("li.server-item")

        coroutineScope {
            servers.forEach { server ->
                val serverName = server.selectFirst("a.server-name")?.text()?.trim() ?: "Server"
                val episodeSlug = server.attr("data-episode-slug")
                val serverId = server.attr("data-server-id")

                if (episodeSlug.isBlank() || serverId.isBlank()) return@forEach

                launch {
                    try {
                        val playerAjaxUrl = "$mainUrl/halimmovies/player.php?episode_slug=$episodeSlug&server_id=$serverId&subsv_id=&post_id=$postId&nonce=$nonce&custom_var="
                        val ajaxHeaders = headers + mapOf(
                            "Accept" to "text/html, */*; q=0.01",
                            "X-Requested-With" to "XMLHttpRequest",
                            "Referer" to data
                        )
                        val ajaxRes = app.get(playerAjaxUrl, headers = ajaxHeaders, interceptor = killer).parsed<PlayerResponse>()
                        if (ajaxRes.status != true || ajaxRes.data?.sources.isNullOrBlank()) {
                            return@launch
                        }

                        val iframeDoc = Jsoup.parse(ajaxRes.data.sources)
                        val embedUrl = iframeDoc.selectFirst("iframe")?.attr("src") ?: return@launch
                        
                        // --- Logic inline cho streamblue.biz ---
                        if (embedUrl.contains("streamblue.biz")) {
                            val streamblueReferer = "https://streamblue.biz/"
                            
                            val embedDoc = app.get(embedUrl, headers = headers + mapOf("Referer" to data)).document
                            val videoId = Regex("""videoId\s*:\s*['"]?(\d+)['"]?""").find(embedDoc.html())?.groupValues?.get(1)
                                ?: return@launch
                            
                            val postUrl = "https://streamblue.biz/players/sources?videoId=$videoId"
                            val postHeaders = headers + mapOf(
                                "X-Requested-With" to "XMLHttpRequest",
                                "Referer" to embedUrl,
                                "Origin" to streamblueReferer
                            )
                            val sourceRes = app.post(postUrl, headers = postHeaders).parsed<StreamBlueResponse>()
                            val sourceString = sourceRes.message?.source ?: return@launch

                            val sourceList = jacksonObjectMapper().readValue<List<StreamBlueSource>>(sourceString)

                            sourceList.forEach { source ->
                                val qualityLabel = source.label ?: "HD"
                                val linkType = if (source.type == "video/mp4") ExtractorLinkType.VIDEO else ExtractorLinkType.M3U8
                                
                                // Dùng suspend fun (Đúng)
                                callback(
                                    newExtractorLink(
                                        source = "$name ($serverName)",
                                        name = "StreamBlue $qualityLabel",
                                        url = source.file,
                                        type = linkType // 2. Dùng ExtractorLinkType
                                    ) { 
                                        this.quality = qualityLabel.toQualityValue()
                                        this.referer = streamblueReferer
                                        // 2. ĐÃ BỎ isM3u8
                                    }
                                )
                            }
                        } 
                        // --- Kết thúc logic streamblue.biz ---
                        
                        else {
                            // =========================================================================
                            // 3. SỬA LỖI FALLBACK
                            // Dùng .copy() để tạo link mới với tên đã sửa
                            // Đây là cách duy nhất đúng vì:
                            // - link.name là 'val' (Lỗi 'val cannot be reassigned')
                            // - Chúng ta đang ở trong callback không-suspend (Lỗi 'Suspension functions...')
                            // =========================================================================
                            loadExtractor(embedUrl, data, subtitleCallback) { link ->
                                callback(
                                    link.copy( // <-- SỬ DỤNG .copy()
                                        name = "$serverName: ${link.name}" // Chỉ thay đổi tên
                                    )
                                )
                            }
                        }
                    } catch (e: Exception) {
                        e.printStackTrace()
                    }
                }
            }
        }
        
        return true
    }

    // Helper để chuyển "720p" -> 720
    private fun String.toQualityValue(): Int {
        return Regex("""(\d+)""").find(this)?.groupValues?.get(1)?.toIntOrNull()
            ?: Qualities.Unknown.value
    }
}
