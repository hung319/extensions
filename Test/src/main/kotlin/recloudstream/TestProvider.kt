package recloudstream

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope

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

    // --- GIỮ NGUYÊN CÁC HÀM KHÁC (search, load, mainPage) ---
    // (Copy lại phần search, load, mainPage từ code trước để code gọn)
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
    // ---------------------------------------------------------

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        // Buffer để lưu log, thread-safe
        val debugLogs = StringBuffer()
        debugLogs.append("--- BẮT ĐẦU QUÉT ---\n")

        val json = app.get(data, headers = ajaxHeaders, timeout = 15L).parsedSafe<AjaxResponse>() 
            ?: run { debugLogs.append("❌ Lỗi: Không lấy được Server List HTML\n"); return false }
        
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
                    val logPrefix = "[$serverName-$type]"
                    try {
                        // debugLogs.append("$logPrefix 1. Đang gọi API resolve...\n")
                        val resolveUrl = "$mainUrl/ajax/server?get=$linkId"
                        val responseText = app.get(resolveUrl, headers = ajaxHeaders, timeout = 10L).text
                        
                        if (!responseText.trim().startsWith("<")) {
                            val linkJson = AppUtils.parseJson<ServerResponse>(responseText)
                            val embedUrl = linkJson.result?.url
                            
                            if (!embedUrl.isNullOrBlank()) {
                                debugLogs.append("$logPrefix ✅ API OK: $embedUrl\n")
                                
                                val safeServerName = "$serverName ($type)"
                                
                                // Gọi Extractor và bắt callback để log
                                var extractorFound = false
                                loadExtractor(embedUrl, safeServerName, subtitleCallback) { link ->
                                    extractorFound = true
                                    debugLogs.append("$logPrefix 🎉 EXTRACTOR SUCCESS: ${link.name} -> ${link.url}\n")
                                    callback(link)
                                }
                                
                                // Lưu ý: Nếu loadExtractor thất bại, nó thường không gọi callback,
                                // nên ta không log được dòng SUCCESS.
                            } else {
                                debugLogs.append("$logPrefix ⚠️ API trả về URL rỗng\n")
                            }
                        } else {
                            debugLogs.append("$logPrefix ❌ API trả về HTML (Lỗi)\n")
                        }
                    } catch (e: Exception) {
                        debugLogs.append("$logPrefix ☠️ Lỗi Exception: ${e.message}\n")
                    }
                }
            }.awaitAll()
        }

        // IN TOÀN BỘ LOG RA MÀN HÌNH
        throw ErrorLoadingException(debugLogs.toString())

        // return true // <-- Khi nào chạy thật thì bỏ throw ở trên và mở comment dòng này
    }
}
