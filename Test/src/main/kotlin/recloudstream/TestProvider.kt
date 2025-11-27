package recloudstream

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import android.util.Base64 

class AnikotoProvider : MainAPI() {
    override var mainUrl = "https://anikoto.tv"
    override var name = "Anikoto"
    override val hasMainPage = true
    override var lang = "en"
    override val supportedTypes = setOf(TvType.Anime, TvType.Movie, TvType.OVA)

    private val ajaxHeaders = mapOf(
        "X-Requested-With" to "XMLHttpRequest",
        "Referer" to "$mainUrl/"
    )

    data class AjaxResponse(val status: Int, val result: String)
    data class ServerResponse(val status: Int, val result: ServerResult?)
    data class ServerResult(val url: String?)
    data class MewResponse(val time: Long?, val data: MewData?)
    data class MewData(val tracks: List<MewTrack>?, val sources: List<MewSource>?)
    data class MewTrack(val file: String?, val label: String?, val kind: String?)
    data class MewSource(val url: String?)

    // =========================================================================
    //  1. MEW CLOUD EXTRACTOR (Kèm Log Debug)
    // =========================================================================
    inner class MewCloudExtractor : ExtractorApi() {
        override val name = "MewCloud"
        override val mainUrl = "https://mewcdn.online"
        override val requiresReferer = false

        suspend fun getVideos(
            url: String,
            serverName: String,
            subtitleCallback: (SubtitleFile) -> Unit,
            callback: (ExtractorLink) -> Unit,
            logs: StringBuffer // Thêm tham số để ghi log
        ) {
            logs.append("\n--- MewCloud: $serverName ---\nINPUT: $url\n")
            
            try {
                // --- CASE 1: Link trực tiếp dạng plyr.php ---
                if (url.contains("/player/plyr.php")) {
                    logs.append(">> Detect: PLYR.PHP Direct Link\n")
                    val fragments = url.split("#")
                    logs.append(">> Split Size: ${fragments.size}\n")
                    
                    if (fragments.size > 1) {
                        val base64String = fragments[1]
                        logs.append(">> Base64 Raw: $base64String\n")
                        
                        try {
                            // Thử decode
                            val decodedBytes = Base64.decode(base64String, Base64.DEFAULT)
                            val decodedUrl = String(decodedBytes)
                            logs.append(">> Decoded URL: $decodedUrl\n")

                            if (decodedUrl.startsWith("http")) {
                                logs.append(">> ✅ URL Valid! Returning ExtractorLink...\n")
                                callback(
                                    newExtractorLink(
                                        source = serverName,
                                        name = serverName,
                                        url = decodedUrl,
                                        type = ExtractorLinkType.M3U8
                                    ) {
                                        this.referer = url
                                        this.quality = Qualities.Unknown.value
                                    }
                                )
                                return
                            } else {
                                logs.append(">> ⚠️ Decoded URL does not start with http\n")
                            }
                        } catch (e: Exception) {
                            logs.append(">> ❌ Base64 Decode Error: ${e.message}\n")
                        }
                    } else {
                        logs.append(">> ⚠️ URL does not contain hash fragment (#)\n")
                    }
                    // Không return ở đây để lỡ nó fail thì chạy thử logic dưới (dù ít khả năng)
                }

                // --- CASE 2: API save_data.php ---
                logs.append(">> Fallback to save_data.php logic...\n")
                val regex = Regex("""/(\d+)/(sub|dub)""")
                val match = regex.find(url)
                
                if (match == null) {
                    logs.append(">> ❌ Regex ID not found in URL\n")
                    return
                }
                
                val (id, type) = match.destructured
                val pairId = "$id-$type"
                val apiUrl = "$mainUrl/save_data.php?id=$pairId"
                logs.append(">> API URL: $apiUrl\n")
                
                val headers = mapOf(
                    "Origin" to "https://megacloud.bloggy.click",
                    "Referer" to "https://megacloud.bloggy.click/",
                    "User-Agent" to "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/137.0.0.0 Mobile Safari/537.36"
                )

                val textRes = app.get(apiUrl, headers = headers).text
                logs.append(">> API Response: $textRes\n") // Log nội dung trả về

                val response = AppUtils.parseJson<MewResponse>(textRes)
                val data = response.data ?: run {
                    logs.append(">> ⚠️ Data null in response\n")
                    return
                }

                data.sources?.forEach { source ->
                    val m3u8Url = source.url ?: return@forEach
                    logs.append(">> ✅ Found Source: $m3u8Url\n")
                    callback(
                        newExtractorLink(
                            source = serverName,
                            name = serverName,
                            url = m3u8Url,
                            type = ExtractorLinkType.M3U8
                        ) {
                            this.referer = "https://megacloud.bloggy.click/"
                            this.quality = Qualities.Unknown.value
                        }
                    )
                }
                
            } catch (e: Exception) {
                logs.append(">> ☠️ Exception in getVideos: ${e.message}\n")
                e.printStackTrace()
            }
        }
    }

    // =========================================================================
    //  2. ANIKOTO LOGIC
    // =========================================================================
    
    // (Copy lại các hàm toSearchResult, getMainPage, search, load y hệt code cũ)
    private fun Element.toSearchResult(): SearchResponse? {
        val href = this.selectFirst("a")?.attr("href") ?: return null
        val title = this.selectFirst(".name.d-title")?.text() ?: this.selectFirst(".name")?.text() ?: "Unknown"
        val posterUrl = this.selectFirst("img")?.attr("src")
        return newAnimeSearchResponse(title, fixUrl(href)) { this.posterUrl = posterUrl }
    }
    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val doc = app.get("$mainUrl/home").document
        val recent = doc.select("#recent-update .ani.items .item").mapNotNull { it.toSearchResult() }
        return newHomePageResponse(listOf(HomePageList("Recent", recent)), false)
    }
    override suspend fun search(query: String): List<SearchResponse> {
        val url = "$mainUrl/filter?keyword=$query"
        val doc = app.get(url).document
        return doc.select("div.ani.items > div.item").mapNotNull { it.toSearchResult() }
    }
    override suspend fun load(url: String): LoadResponse {
        val doc = app.get(url).document
        val title = doc.selectFirst("h1.title.d-title")?.text() ?: "Unknown"
        val dataId = doc.selectFirst("#watch-main")?.attr("data-id") ?: throw ErrorLoadingException("No ID")
        val ajaxUrl = "$mainUrl/ajax/episode/list/$dataId"
        val json = app.get(ajaxUrl, headers = ajaxHeaders).parsedSafe<AjaxResponse>()
        val epDoc = Jsoup.parse(json?.result ?: "")
        val episodes = epDoc.select("ul.ep-range li a").mapNotNull { 
            val epIds = it.attr("data-ids")
            if(epIds.isBlank()) return@mapNotNull null
            newEpisode("$mainUrl/ajax/server/list?servers=$epIds") {
                this.name = "Ep ${it.attr("data-num")}"
                this.episode = it.attr("data-num").toIntOrNull()
            }
        }
        return newTvSeriesLoadResponse(title, url, TvType.Anime, episodes)
    }

    // --- LOAD LINKS VỚI LOGGING ---
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val logs = StringBuffer() // Thread-safe string builder
        logs.append("=== START LOAD LINKS ===\n")

        val json = app.get(data, headers = ajaxHeaders).parsedSafe<AjaxResponse>() ?: return false
        val doc = Jsoup.parse(json.result)

        val tasks = doc.select(".servers .type li").mapNotNull { server ->
            val linkId = server.attr("data-link-id")
            if (linkId.isBlank()) return@mapNotNull null
            val serverName = server.text()
            val type = server.parent()?.parent()?.attr("data-type") ?: "sub"
            Triple(linkId, serverName, type)
        }

        coroutineScope {
            tasks.map { (linkId, serverName, type) ->
                async {
                    try {
                        val resolveUrl = "$mainUrl/ajax/server?get=$linkId"
                        val responseText = app.get(resolveUrl, headers = ajaxHeaders).text
                        
                        if (!responseText.trim().startsWith("<")) {
                            val linkJson = AppUtils.parseJson<ServerResponse>(responseText)
                            val embedUrl = linkJson.result?.url
                            
                            if (!embedUrl.isNullOrBlank()) {
                                val safeServerName = "$serverName ($type)"
                                
                                if (embedUrl.contains("megacloud.bloggy.click") || 
                                    embedUrl.contains("vidwish.live") ||
                                    embedUrl.contains("mewcdn.online") ||
                                    embedUrl.contains("/player/plyr.php")) {
                                    
                                    // Gọi Extractor và truyền logs vào để ghi lại
                                    MewCloudExtractor().getVideos(embedUrl, safeServerName, subtitleCallback, callback, logs)
                                    
                                } else {
                                    logs.append("Normal Extractor: $embedUrl\n")
                                    loadExtractor(embedUrl, safeServerName, subtitleCallback, callback)
                                }
                            }
                        }
                    } catch (e: Exception) {
                         logs.append("Error in task: ${e.message}\n")
                    }
                }
            }.awaitAll()
        }
        
        // --- THROW LOG RA MÀN HÌNH ĐỂ DEBUG ---
        throw ErrorLoadingException(logs.toString())
        
        // return true // <-- Bỏ comment dòng này khi chạy thật
    }
}
