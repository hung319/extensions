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

    // --- JSON Models ---
    data class AjaxResponse(val status: Int, val result: String)
    data class ServerResponse(val status: Int, val result: ServerResult?)
    data class ServerResult(val url: String?)
    
    // MewCloud Models
    data class MewResponse(val time: Long?, val data: MewData?)
    data class MewData(val tracks: List<MewTrack>?, val sources: List<MewSource>?)
    data class MewTrack(val file: String?, val label: String?, val kind: String?)
    data class MewSource(val url: String?)

    // =========================================================================
    //  1. MEW CLOUD EXTRACTOR
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
                // CASE 1: Link trực tiếp dạng plyr.php#BASE64#
                if (url.contains("/player/plyr.php")) {
                    val fragments = url.split("#")
                    if (fragments.size > 1) {
                        val base64String = fragments[1]
                        try {
                            val decodedBytes = Base64.decode(base64String, Base64.DEFAULT)
                            val decodedUrl = String(decodedBytes).trim()

                            if (decodedUrl.startsWith("http")) {
                                // Trả về 1 link AIO duy nhất
                                callback(
                                    newExtractorLink(
                                        source = serverName,
                                        name = serverName,
                                        url = decodedUrl,
                                        type = ExtractorLinkType.M3U8
                                    ) {
                                        this.referer = url
                                        this.quality = Qualities.Unknown.value // Auto quality
                                    }
                                )
                                return
                            }
                        } catch (e: Exception) {}
                    }
                }

                // CASE 2: API save_data.php (Backup)
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
                 ?: this.selectFirst(".name")?.text() 
                 ?: this.selectFirst(".d-title")?.text()
                 ?: "Unknown"
        
        val posterUrl = this.selectFirst("img")?.attr("src")
        
        val subText = this.selectFirst(".ep-status.sub span")?.text()
        val dubText = this.selectFirst(".ep-status.dub span")?.text()
        val epTotal = this.selectFirst(".ep-status.total span")?.text()

        return newAnimeSearchResponse(title, fixUrl(href)) {
            this.posterUrl = posterUrl
            if (!subText.isNullOrEmpty()) addQuality("Sub $subText")
            if (!dubText.isNullOrEmpty()) addQuality("Dub $dubText")
            if (!epTotal.isNullOrEmpty()) addQuality("Total $epTotal")
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
        val newRelease = doc.select("section[data-name='new-release'] .item").mapNotNull { it.toSearchResult() }
        val newAdded = doc.select("section[data-name='new-added'] .item").mapNotNull { it.toSearchResult() }
        val completed = doc.select("section[data-name='completed'] .item").mapNotNull { it.toSearchResult() }
        val topDay = doc.select("#top-anime .tab-content[data-name='day'] .item").mapNotNull { it.toSearchResult() }

        return newHomePageResponse(
            listOf(
                HomePageList("Hot", hotest),
                HomePageList("Recently Updated", recent),
                HomePageList("New Release", newRelease),
                HomePageList("Newly Added", newAdded),
                HomePageList("Just Completed", completed),
                HomePageList("Top Anime (Day)", topDay)
            ), 
            hasNext = false
        )
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
        
        val ajaxUrl = "$mainUrl/ajax/episode/list/$dataId"
        val json = app.get(ajaxUrl, headers = ajaxHeaders).parsedSafe<AjaxResponse>() ?: throw ErrorLoadingException("Failed to fetch episodes JSON")
        val episodesDoc = Jsoup.parse(json.result)
        
        val episodes = episodesDoc.select("ul.ep-range li a").mapNotNull { element ->
            val epName = element.select("span.d-title").text() ?: "Episode ${element.attr("data-num")}"
            val epNum = element.attr("data-num").toFloatOrNull() ?: 1f
            
            // Lấy ID và Metadata để truyền vào LoadLinks
            val epIds = element.attr("data-ids")
            val malId = element.attr("data-mal")
            val timestamp = element.attr("data-timestamp")
            
            if (epIds.isBlank()) return@mapNotNull null
            
            // Gom tất cả data cần thiết vào URL
            val epUrl = "$mainUrl/ajax/server/list?servers=$epIds&mal=$malId&time=$timestamp&ep=${element.attr("data-num")}"
            
            newEpisode(epUrl) {
                this.name = if(epName.contains("Episode")) epName else "Ep ${element.attr("data-num")} - $epName"
                this.episode = epNum.toInt()
            }
        }
        
        val recommendations = doc.select(".w-side-section .item, #recent-update .item").mapNotNull { 
            it.toSearchResult() 
        }

        return newTvSeriesLoadResponse(title, url, TvType.Anime, episodes) {
            this.posterUrl = poster
            this.plot = description
            if (ratingText != null) this.score = Score.from10(ratingText.toDoubleOrNull() ?: 0.0)
            this.recommendations = recommendations
        }
    }

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
        
        // Danh sách ID cần giải mã (ID, ServerName, Type)
        val linkTasks = mutableListOf<Triple<String, String, String>>() 

        coroutineScope {
            // 1. Lấy ID từ Server List mặc định
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

            // 2. Lấy ID từ Mapper API (Khôi phục logic này để lấy Kiwi)
            val taskMapper = async {
                if (!malId.isNullOrBlank() && !timestamp.isNullOrBlank()) {
                    try {
                        val mapperUrl = "https://mapper.mewcdn.online/api/mal/$malId/$epNum/$timestamp"
                        val mapperHeaders = mapOf(
                            "Origin" to mainUrl,
                            "Referer" to "$mainUrl/",
                            "User-Agent" to "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/137.0.0.0 Mobile Safari/537.36"
                        )
                        val responseText = app.get(mapperUrl, headers = mapperHeaders).text
                        // Parse JSON động để lấy ID của Kiwi
                        val mapperJson = ObjectMapper().readTree(responseText)
                        val fields = mapperJson.fieldNames()
                        while (fields.hasNext()) {
                            val serverKey = fields.next() // VD: Kiwi-Stream-360p
                            val serverNode = mapperJson.get(serverKey)
                            
                            if (serverNode.has("sub")) {
                                val id = serverNode.get("sub").get("url").asText()
                                linkTasks.add(Triple(id, "$serverKey", "sub"))
                            }
                            if (serverNode.has("dub")) {
                                val id = serverNode.get("dub").get("url").asText()
                                linkTasks.add(Triple(id, "$serverKey", "dub"))
                            }
                        }
                    } catch (e: Exception) { e.printStackTrace() }
                }
            }

            awaitAll(taskAnikoto, taskMapper)

            // 3. Giải mã tất cả ID (Bao gồm cả ID từ Anikoto và ID từ Mapper)
            linkTasks.map { (linkId, serverName, type) ->
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
                                    
                                    MewCloudExtractor().getVideos(embedUrl, safeServerName, subtitleCallback, callback)
                                    
                                } else {
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
