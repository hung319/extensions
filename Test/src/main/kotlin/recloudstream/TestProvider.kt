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
import com.fasterxml.jackson.databind.ObjectMapper

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

    // --- Models ---
    data class AjaxResponse(val status: Int, val result: String)
    data class ServerResponse(val status: Int, val result: ServerResult?)
    data class ServerResult(val url: String?)

    // MewCloud Extractor Models
    data class MewResponse(val time: Long?, val data: MewData?)
    data class MewData(val tracks: List<MewTrack>?, val sources: List<MewSource>?)
    data class MewTrack(val file: String?, val label: String?, val kind: String?)
    data class MewSource(val url: String?)

    // =========================================================================
    //  1. MEW CLOUD EXTRACTOR (Xử lý link embed cuối cùng)
    // =========================================================================
    inner class MewCloudExtractor : ExtractorApi() {
        override val name = "MewCloud"
        override val mainUrl = "https://mewcdn.online"
        override val requiresReferer = false

        suspend fun getVideos(
            url: String,
            serverName: String,
            subtitleCallback: (SubtitleFile) -> Unit,
            callback: (ExtractorLink) -> Unit
        ) {
            try {
                // CASE 1: plyr.php (Link chứa Base64 M3U8)
                if (url.contains("/player/plyr.php")) {
                    val fragments = url.split("#")
                    if (fragments.size > 1) {
                        val base64String = fragments[1]
                        try {
                            val decodedBytes = Base64.decode(base64String, Base64.DEFAULT)
                            val decodedUrl = String(decodedBytes).trim()

                            if (decodedUrl.startsWith("http")) {
                                // Trả về link M3U8 gốc (AIO)
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
                            }
                        } catch (e: Exception) {}
                    }
                }

                // CASE 2: API save_data.php (Cho link MegaCloud/VidWish dạng cũ)
                val regex = Regex("""/(\d+)/(sub|dub)""")
                val match = regex.find(url) ?: return
                val (id, type) = match.destructured
                val pairId = "$id-$type"
                val apiUrl = "$mainUrl/save_data.php?id=$pairId"
                
                val headers = mapOf(
                    "Origin" to "https://megacloud.bloggy.click",
                    "Referer" to "https://megacloud.bloggy.click/",
                    "User-Agent" to "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/137.0.0.0 Mobile Safari/537.36"
                )

                val response = app.get(apiUrl, headers = headers).parsedSafe<MewResponse>()
                val data = response?.data ?: return

                data.sources?.forEach { source ->
                    val m3u8Url = source.url ?: return@forEach
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

                data.tracks?.forEach { track ->
                    if (track.file != null && track.kind == "captions") {
                        subtitleCallback(SubtitleFile(track.label ?: "English", track.file))
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    // =========================================================================
    //  2. MAIN PROVIDER LOGIC
    // =========================================================================

    private fun Element.toSearchResult(): SearchResponse? {
        val href = this.selectFirst("a")?.attr("href") ?: return null
        val title = this.selectFirst(".name.d-title")?.text() 
                 ?: this.selectFirst(".name")?.text() ?: "Unknown"
        val posterUrl = this.selectFirst("img")?.attr("src")
        val subText = this.selectFirst(".ep-status.sub span")?.text()
        val dubText = this.selectFirst(".ep-status.dub span")?.text()
        return newAnimeSearchResponse(title, fixUrl(href)) {
            this.posterUrl = posterUrl
            if (!subText.isNullOrEmpty()) addQuality("Sub $subText")
            if (!dubText.isNullOrEmpty()) addQuality("Dub $dubText")
        }
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val doc = app.get("$mainUrl/home").document
        val hotest = doc.select(".swiper-slide.item").mapNotNull { element ->
            val title = element.selectFirst(".title.d-title")?.text() ?: return@mapNotNull null
            val href = element.selectFirst("a.btn.play")?.attr("href") ?: return@mapNotNull null
            val bgImage = element.selectFirst(".image div")?.attr("style")?.substringAfter("url('")?.substringBefore("')")
            newAnimeSearchResponse(title, fixUrl(href)) { this.posterUrl = bgImage }
        }
        val recent = doc.select("#recent-update .ani.items .item").mapNotNull { it.toSearchResult() }
        return newHomePageResponse(listOf(HomePageList("Hot", hotest), HomePageList("Recently Updated", recent)), false)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val url = "$mainUrl/filter?keyword=$query"
        val doc = app.get(url).document
        return doc.select("div.ani.items > div.item").mapNotNull { it.toSearchResult() }
    }

    override suspend fun load(url: String): LoadResponse {
        val doc = app.get(url).document
        val title = doc.selectFirst("h1.title.d-title")?.text() ?: "Unknown"
        val description = doc.selectFirst(".synopsis .content")?.text()
        val poster = doc.selectFirst(".binfo .poster img")?.attr("src")
        val ratingText = doc.selectFirst(".meta .rating")?.text()
        val dataId = doc.selectFirst("#watch-main")?.attr("data-id") ?: throw ErrorLoadingException("No ID")
        
        // Lấy danh sách tập
        val ajaxUrl = "$mainUrl/ajax/episode/list/$dataId"
        val json = app.get(ajaxUrl, headers = ajaxHeaders).parsedSafe<AjaxResponse>() ?: throw ErrorLoadingException("Failed fetch episodes")
        val episodesDoc = Jsoup.parse(json.result)
        
        val episodes = episodesDoc.select("ul.ep-range li a").mapNotNull { element ->
            val epName = element.select("span.d-title").text() ?: "Episode ${element.attr("data-num")}"
            val epNum = element.attr("data-num").toFloatOrNull() ?: 1f
            
            // Lấy các tham số cần thiết cho bước loadLinks
            val epIds = element.attr("data-ids") // Cho Server List cũ
            val malId = element.attr("data-mal") // Cho Mapper API
            val timestamp = element.attr("data-timestamp") // Cho Mapper API
            
            if (epIds.isBlank()) return@mapNotNull null
            
            // Gom tất cả vào URL ảo
            val epUrl = "$mainUrl/ajax/server/list?servers=$epIds&mal=$malId&time=$timestamp&ep=${element.attr("data-num")}"
            
            newEpisode(epUrl) {
                this.name = epName
                this.episode = epNum.toInt()
            }
        }
        return newTvSeriesLoadResponse(title, url, TvType.Anime, episodes) {
            this.posterUrl = poster
            this.plot = description
            if (ratingText != null) this.score = Score.from10(ratingText.toDoubleOrNull() ?: 0.0)
        }
    }

    // --- HÀM QUAN TRỌNG NHẤT ---
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val urlObj = android.net.Uri.parse(data)
        val malId = urlObj.getQueryParameter("mal")
        val timestamp = urlObj.getQueryParameter("time")
        val epNum = urlObj.getQueryParameter("ep")
        
        // Danh sách chứa tất cả các Task (Server ID) cần giải mã
        val linkTasks = mutableListOf<Triple<String, String, String>>() // ID, Name, Type

        coroutineScope {
            // 1. Lấy ID từ Anikoto Server List (Nguồn 1)
            val taskAnikoto = async {
                try {
                    val json = app.get(data, headers = ajaxHeaders).parsedSafe<AjaxResponse>()
                    if (json != null) {
                        val doc = Jsoup.parse(json.result)
                        doc.select(".servers .type li").forEach { server ->
                            val linkId = server.attr("data-link-id")
                            val serverName = server.text()
                            val type = server.parent()?.parent()?.attr("data-type") ?: "sub"
                            if (linkId.isNotBlank()) {
                                linkTasks.add(Triple(linkId, serverName, type))
                            }
                        }
                    }
                } catch (e: Exception) { e.printStackTrace() }
            }

            // 2. Lấy ID từ Mapper API (Nguồn 2)
            val taskMapper = async {
                if (!malId.isNullOrBlank() && !timestamp.isNullOrBlank()) {
                    try {
                        val mapperUrl = "https://mapper.mewcdn.online/api/mal/$malId/$epNum/$timestamp"
                        val mapperHeaders = mapOf(
                            "Origin" to mainUrl,
                            "Referer" to "$mainUrl/",
                            "User-Agent" to "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/137.0.0.0 Mobile Safari/537.36"
                        )
                        
                        // Parse JSON động (vì key thay đổi: Kiwi-Stream-360p, etc.)
                        val responseText = app.get(mapperUrl, headers = mapperHeaders).text
                        val mapperJson = ObjectMapper().readTree(responseText)
                        
                        // Duyệt qua các Key (VD: Kiwi-Stream-360p)
                        val fields = mapperJson.fieldNames()
                        while (fields.hasNext()) {
                            val serverKey = fields.next() // VD: Kiwi-Stream-360p
                            val serverNode = mapperJson.get(serverKey)
                            
                            // Check Sub
                            if (serverNode.has("sub")) {
                                val id = serverNode.get("sub").get("url").asText()
                                linkTasks.add(Triple(id, "$serverKey", "sub"))
                            }
                            // Check Dub
                            if (serverNode.has("dub")) {
                                val id = serverNode.get("dub").get("url").asText()
                                linkTasks.add(Triple(id, "$serverKey", "dub"))
                            }
                        }
                    } catch (e: Exception) { e.printStackTrace() }
                }
            }

            // Chờ lấy đủ ID từ 2 nguồn
            awaitAll(taskAnikoto, taskMapper)

            // 3. GIẢI MÃ ID -> URL EMBED (Chạy song song tất cả ID thu được)
            linkTasks.map { (linkId, serverName, type) ->
                async {
                    try {
                        // Gọi chung 1 API giải mã cho tất cả các ID
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
                                    
                                    // Dùng Extractor MewCloud (Xử lý plyr.php + save_data.php)
                                    MewCloudExtractor().getVideos(embedUrl, safeServerName, subtitleCallback, callback)
                                    
                                } else {
                                    // Dùng Extractor mặc định
                                    loadExtractor(embedUrl, safeServerName, subtitleCallback, callback)
                                }
                            }
                        }
                    } catch (e: Exception) {
                        e.printStackTrace()
                    }
                }
            }.awaitAll()
        }

        return true
    }
}
