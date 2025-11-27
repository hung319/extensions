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

    // --- JSON Models ---
    data class AjaxResponse(val status: Int, val result: String)
    data class ServerResponse(val status: Int, val result: ServerResult?)
    data class ServerResult(val url: String?)

    // --- CÁC HÀM CƠ BẢN GIỮ NGUYÊN ---
    private fun Element.toSearchResult(): SearchResponse? {
        val href = this.selectFirst("a")?.attr("href") ?: return null
        val title = this.selectFirst(".name.d-title")?.text() 
                 ?: this.selectFirst(".name")?.text() ?: "Unknown"
        val posterUrl = this.selectFirst("img")?.attr("src")
        return newAnimeSearchResponse(title, fixUrl(href)) {
            this.posterUrl = posterUrl
        }
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
        val poster = doc.selectFirst(".binfo .poster img")?.attr("src")
        val dataId = doc.selectFirst("#watch-main")?.attr("data-id") ?: throw ErrorLoadingException("No ID")
        
        val ajaxUrl = "$mainUrl/ajax/episode/list/$dataId"
        val json = app.get(ajaxUrl, headers = ajaxHeaders).parsedSafe<AjaxResponse>() 
            ?: throw ErrorLoadingException("Failed to fetch episodes")
        val epDoc = Jsoup.parse(json.result)
        
        val episodes = epDoc.select("ul.ep-range li a").mapNotNull { 
            val epIds = it.attr("data-ids")
            if(epIds.isBlank()) return@mapNotNull null
            newEpisode("$mainUrl/ajax/server/list?servers=$epIds") {
                this.name = "Ep ${it.attr("data-num")}"
                this.episode = it.attr("data-num").toIntOrNull()
            }
        }
        return newTvSeriesLoadResponse(title, url, TvType.Anime, episodes) {
            this.posterUrl = poster
        }
    }

    // --- HÀM LOADLINKS CÓ LOG DEBUG ---
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val logs = StringBuffer() // Nơi lưu log để in ra màn hình
        logs.append("--- START DEBUG ---\n")

        val json = app.get(data, headers = ajaxHeaders).parsedSafe<AjaxResponse>() ?: return false
        val doc = Jsoup.parse(json.result)

        val tasks = doc.select(".servers .type li").mapNotNull { server ->
            val linkId = server.attr("data-link-id")
            if (linkId.isBlank()) return@mapNotNull null
            val serverName = server.text()
            Triple(linkId, serverName, "sub")
        }

        coroutineScope {
            tasks.map { (linkId, serverName, type) ->
                async {
                    try {
                        // 1. Gọi API lấy link embed
                        val resolveUrl = "$mainUrl/ajax/server?get=$linkId"
                        val responseText = app.get(resolveUrl, headers = ajaxHeaders).text
                        
                        if (!responseText.trim().startsWith("<")) {
                            val linkJson = AppUtils.parseJson<ServerResponse>(responseText)
                            val embedUrl = linkJson.result?.url
                            
                            if (!embedUrl.isNullOrBlank()) {
                                logs.append("[$serverName] Embed: $embedUrl\n")

                                // 2. Xử lý Link plyr.php
                                if (embedUrl.contains("/player/plyr.php")) {
                                    // Tách chuỗi theo dấu #
                                    val fragments = embedUrl.split("#")
                                    logs.append("[$serverName] Fragments: ${fragments.size}\n")

                                    // Lấy phần tử thứ 2 (index 1) làm Base64
                                    if (fragments.size > 1) {
                                        val base64String = fragments[1]
                                        try {
                                            val decodedBytes = Base64.decode(base64String, Base64.DEFAULT)
                                            val decodedUrl = String(decodedBytes).trim()
                                            
                                            logs.append("[$serverName] Decoded: $decodedUrl\n")

                                            if (decodedUrl.startsWith("http")) {
                                                // Trả về link trực tiếp (AIO)
                                                callback(
                                                    newExtractorLink(
                                                        source = serverName,
                                                        name = serverName,
                                                        url = decodedUrl,
                                                        type = ExtractorLinkType.M3U8
                                                    ) {
                                                        this.referer = "https://mewcdn.online/"
                                                        this.quality = Qualities.Unknown.value
                                                    }
                                                )
                                                logs.append("[$serverName] -> OK (Callback Called)\n")
                                            }
                                        } catch (e: Exception) {
                                            logs.append("[$serverName] Decode Err: ${e.message}\n")
                                        }
                                    }
                                } else {
                                    // Link thường (Vidstream/Megacloud cũ)
                                    // loadExtractor(embedUrl, serverName, subtitleCallback, callback)
                                    logs.append("[$serverName] Skip normal extractor for debug\n")
                                }
                            }
                        }
                    } catch (e: Exception) {
                        logs.append("Error: ${e.message}\n")
                    }
                }
            }.awaitAll()
        }

        // --- QUAN TRỌNG: IN LOG RA MÀN HÌNH ---
        // Sau khi chạy xong, bạn hãy đọc nội dung lỗi màu đỏ này
        // Nếu thấy dòng "Decoded: https://..." mà vẫn không play được, thì do Player.
        throw ErrorLoadingException(logs.toString())
        
        // return true
    }
}
