package recloudstream

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import android.util.Base64
import android.widget.Toast
import com.fasterxml.jackson.databind.ObjectMapper

class AnikotoProvider : MainAPI() {
    override var mainUrl = "https://anikoto.tv"
    override var name = "Anikoto"
    override val hasMainPage = true
    override var lang = "en"
    override val supportedTypes = setOf(TvType.Anime, TvType.Movie, TvType.OVA)

    private val userAgent = "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/137.0.0.0 Mobile Safari/537.36"

    private fun getBaseHeaders(referer: String = "$mainUrl/") = mapOf(
        "Accept-Language" to "vi-VN,vi;q=0.9",
        "Sec-Ch-Ua" to "\"Chromium\";v=\"137\", \"Not/A)Brand\";v=\"24\"",
        "Sec-Ch-Ua-Mobile" to "?1",
        "Sec-Ch-Ua-Platform" to "\"Android\"",
        "Sec-Fetch-Site" to "same-origin",
        "Sec-Fetch-User" to "?1",
        "Upgrade-Insecure-Requests" to "1",
        "User-Agent" to userAgent,
        "Referer" to referer
    )

    // --- Models ---
    data class AjaxResponse(val status: Int, val result: String)
    data class ServerResponse(val status: Int, val result: ServerResult?)
    data class ServerResult(val url: String?)
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

        suspend fun getVideos(url: String, serverName: String, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit) {
            try {
                if (url.contains("/player/plyr.php")) {
                    val fragments = url.split("#")
                    if (fragments.size > 1) {
                        try {
                            val decodedBytes = Base64.decode(fragments[1], Base64.DEFAULT)
                            val decodedUrl = String(decodedBytes).trim()
                            if (decodedUrl.startsWith("http")) {
                                callback(newExtractorLink(serverName, serverName, decodedUrl, ExtractorLinkType.M3U8) {
                                    this.referer = url
                                    this.quality = Qualities.Unknown.value
                                })
                                return
                            }
                        } catch (e: Exception) {}
                    }
                }

                val regex = Regex("""/(\d+)/(sub|dub)""")
                val match = regex.find(url) ?: return
                val (id, type) = match.destructured
                val apiUrl = "$mainUrl/save_data.php?id=$id-$type"
                
                val headers = mapOf(
                    "Authority" to "mewcdn.online",
                    "Accept" to "application/json, text/javascript, */*; q=0.01",
                    "Origin" to "https://megacloud.bloggy.click",
                    "Referer" to "https://megacloud.bloggy.click/",
                    "User-Agent" to userAgent
                )

                val response = app.get(apiUrl, headers = headers).parsedSafe<MewResponse>()?.data ?: return

                response.sources?.forEach { source ->
                    source.url?.let { m3u8 ->
                        callback(newExtractorLink(serverName, serverName, m3u8, ExtractorLinkType.M3U8) {
                            this.referer = "https://megacloud.bloggy.click/"
                            this.quality = Qualities.Unknown.value
                        })
                    }
                }

                response.tracks?.filter { it.kind == "captions" && !it.file.isNullOrBlank() }
                    ?.distinctBy { it.file }
                    ?.forEach { track ->
                        subtitleCallback(newSubtitleFile(track.label ?: "English", track.file!!) {
                            this.headers = headers
                        })
                    }
            } catch (e: Exception) { e.printStackTrace() }
        }
    }

    // =========================================================================
    //  2. MEGAPLAY EXTRACTOR (FIXED SUSPEND ERROR)
    // =========================================================================
    inner class MegaplayExtractor : ExtractorApi() {
        override val name = "Megaplay"
        override val mainUrl = "https://megaplay.buzz"
        override val requiresReferer = false

        suspend fun getVideos(url: String, serverName: String, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit) {
            try {
                // 1. Headers để stream video
                val streamHeaders = mapOf(
                    "Referer" to "https://megaplay.buzz/",
                    "User-Agent" to userAgent,
                    "Sec-Ch-Ua" to "\"Chromium\";v=\"137\", \"Not/A)Brand\";v=\"24\"",
                    "Sec-Ch-Ua-Mobile" to "?1",
                    "Sec-Ch-Ua-Platform" to "\"Android\""
                )

                // 2. Tải HTML trang embed
                val doc = app.get(url, headers = mapOf("Referer" to "https://anikoto.tv/", "User-Agent" to userAgent)).document
                
                val playerDiv = doc.selectFirst("#megaplay-player")
                val dataId = playerDiv?.attr("data-id") ?: return

                // 3. Gọi API getSources
                val apiUrl = "$mainUrl/stream/getSources?id=$dataId"
                val apiHeaders = mapOf(
                    "X-Requested-With" to "XMLHttpRequest",
                    "Referer" to url, 
                    "User-Agent" to userAgent,
                    "Accept" to "application/json, text/javascript, */*; q=0.01"
                )

                val textResponse = app.get(apiUrl, headers = apiHeaders).text
                
                // 4. Parse JSON
                val mapper = ObjectMapper()
                val jsonNode = mapper.readTree(textResponse)
                val sourcesNode = jsonNode.get("sources")

                // [FIXED] Thêm từ khóa suspend để gọi được newExtractorLink
                suspend fun addLink(file: String) {
                    callback(newExtractorLink(serverName, serverName, file, ExtractorLinkType.M3U8) {
                        this.headers = streamHeaders 
                        this.quality = Qualities.Unknown.value
                    })
                }

                // Xử lý Sources
                if (sourcesNode != null) {
                    if (sourcesNode.isArray) {
                        // [FIXED] Dùng vòng lặp for thay vì forEach để hỗ trợ suspend function
                        for (source in sourcesNode) {
                            val file = source.get("file")?.asText()
                            if (!file.isNullOrBlank()) {
                                addLink(file)
                            }
                        }
                    } else if (sourcesNode.isObject) {
                        val file = sourcesNode.get("file")?.asText()
                        if (!file.isNullOrBlank()) {
                            addLink(file)
                        }
                    }
                } else if (jsonNode.get("encrypted")?.asBoolean() == true) {
                    // Fallback
                    loadExtractor(url, serverName, subtitleCallback, callback)
                }

                // Xử lý Subtitles
                val tracksNode = jsonNode.get("tracks")
                if (tracksNode != null && tracksNode.isArray) {
                    for (track in tracksNode) {
                        val kind = track.get("kind")?.asText()
                        val file = track.get("file")?.asText()
                        val label = track.get("label")?.asText() ?: "English"
                        
                        if (kind == "captions" && !file.isNullOrBlank()) {
                            subtitleCallback(newSubtitleFile(label, file))
                        }
                    }
                }

            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    // =========================================================================
    //  MAIN LOGIC
    // =========================================================================

    override val mainPage = mainPageOf(
        "$mainUrl/ajax/home/widget/updated-all" to "Recently Updated",
        "$mainUrl/new-release" to "New Added",
        "$mainUrl/most-viewed" to "Most Popular",
        "$mainUrl/status/currently-airing" to "Ongoing",
        "$mainUrl/status/finished-airing" to "Completed"
    )

    private fun Element.toSearchResult(): SearchResponse? {
        val href = this.selectFirst("a")?.attr("href") ?: return null
        val title = this.selectFirst(".name.d-title")?.text() 
                 ?: this.selectFirst(".name")?.text() 
                 ?: this.selectFirst(".d-title")?.text() ?: "Unknown"
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
        if (page == 1) {
            withContext(Dispatchers.Main) {
                try { 
                    CommonActivity.activity?.let { 
                        Toast.makeText(it, "Free Repo From H4RS", Toast.LENGTH_LONG).show() 
                    } 
                } catch (e: Exception) {}
            }
        }

        var url = request.data
        if (page > 1) {
            val separator = if (url.contains("?")) "&" else "?"
            url = "$url${separator}page=$page"
        }

        val isAjax = url.contains("/ajax/")
        val headers = getBaseHeaders(referer = request.data).toMutableMap()
        
        if (isAjax) {
            headers["Accept"] = "application/json, text/javascript, */*; q=0.01"
            headers["X-Requested-With"] = "XMLHttpRequest"
            headers["Sec-Fetch-Mode"] = "cors"
            headers["Sec-Fetch-Dest"] = "empty"
        } else {
            headers["Accept"] = "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8"
            headers["Sec-Fetch-Mode"] = "navigate"
            headers["Sec-Fetch-Dest"] = "document"
        }

        val doc = if (isAjax) {
            val jsonText = app.get(url, headers = headers).text
            val jsonResponse = parseJson<AjaxResponse>(jsonText)
            Jsoup.parse(jsonResponse.result)
        } else {
            app.get(url, headers = headers).document
        }

        val homeList = doc.select(".ani.items .item, .item").mapNotNull { it.toSearchResult() }

        return newHomePageResponse(
            list = HomePageList(request.name, homeList, isHorizontalImages = false), 
            hasNext = homeList.isNotEmpty()
        )
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val url = "$mainUrl/filter?keyword=$query"
        val doc = app.get(url, headers = getBaseHeaders()).document
        return doc.select("div.ani.items > div.item").mapNotNull { it.toSearchResult() }
    }

    override suspend fun load(url: String): LoadResponse {
        val doc = app.get(url, headers = getBaseHeaders(referer = url)).document
        val title = doc.selectFirst("h1.title.d-title")?.text() ?: "Unknown"
        val description = doc.selectFirst(".synopsis .content")?.text()
        val poster = doc.selectFirst(".binfo .poster img")?.attr("src")
        val ratingText = doc.selectFirst(".meta .rating")?.text()
        val dataId = doc.selectFirst("#watch-main")?.attr("data-id") ?: throw ErrorLoadingException("No ID")
        
        val typeTag = when {
            doc.select(".meta .sub").isNotEmpty() && doc.select(".meta .dub").isNotEmpty() -> "[Sub/Dub]"
            doc.select(".meta .sub").isNotEmpty() -> "[Sub]"
            doc.select(".meta .dub").isNotEmpty() -> "[Dub]"
            else -> ""
        }

        val ajaxUrl = "$mainUrl/ajax/episode/list/$dataId"
        val ajaxHeaders = getBaseHeaders(referer = url) + mapOf("X-Requested-With" to "XMLHttpRequest")
        
        val json = app.get(ajaxUrl, headers = ajaxHeaders).parsedSafe<AjaxResponse>() 
            ?: throw ErrorLoadingException("Failed to fetch episodes")
        
        val episodes = Jsoup.parse(json.result).select("ul.ep-range li a").mapNotNull { element ->
            val epNumStr = element.attr("data-num")
            val epIds = element.attr("data-ids")
            if (epIds.isBlank()) return@mapNotNull null
            val epName = element.select("span.d-title").text().takeIf { it.isNotBlank() } ?: "Episode $epNumStr"
            
            val epUrl = "$mainUrl/ajax/server/list?servers=$epIds&mal=${element.attr("data-mal")}&time=${element.attr("data-timestamp")}&ep=$epNumStr"
            
            newEpisode(epUrl) {
                this.name = "$epName $typeTag".trim()
                this.episode = epNumStr.toFloatOrNull()?.toInt()
            }
        }
        
        val recommendations = doc.select(".w-side-section .item").mapNotNull { it.toSearchResult() }

        return newTvSeriesLoadResponse(title, url, TvType.Anime, episodes) {
            this.posterUrl = poster
            this.plot = description
            this.score = Score.from10(ratingText?.toDoubleOrNull() ?: 0.0)
            this.recommendations = recommendations
        }
    }

    override suspend fun loadLinks(data: String, isCasting: Boolean, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit): Boolean {
        val urlObj = android.net.Uri.parse(data)
        val malId = urlObj.getQueryParameter("mal")
        val timestamp = urlObj.getQueryParameter("time")
        val epNum = urlObj.getQueryParameter("ep")
        
        val linkTasks = mutableListOf<Triple<String, String, String>>() 
        val ajaxHeaders = getBaseHeaders() + mapOf("X-Requested-With" to "XMLHttpRequest")

        coroutineScope {
            val taskAnikoto = async {
                try {
                    app.get(data, headers = ajaxHeaders).parsedSafe<AjaxResponse>()?.result?.let { html ->
                        Jsoup.parse(html).select(".servers .type li").forEach { server ->
                            val linkId = server.attr("data-link-id")
                            if (linkId.isNotBlank()) {
                                linkTasks.add(Triple(linkId, server.text(), server.parent()?.parent()?.attr("data-type") ?: "sub"))
                            }
                        }
                    }
                } catch (e: Exception) {}
            }

            val taskMapper = async {
                if (!malId.isNullOrBlank() && !timestamp.isNullOrBlank()) {
                    try {
                        val mapperUrl = "https://mapper.mewcdn.online/api/mal/$malId/$epNum/$timestamp"
                        val json = app.get(mapperUrl, headers = mapOf("Origin" to mainUrl, "Referer" to "$mainUrl/")).text
                        val mapperJson = ObjectMapper().readTree(json)
                        mapperJson.fieldNames().forEach { key ->
                            val node = mapperJson.get(key)
                            if (node.has("sub")) linkTasks.add(Triple(node.get("sub").get("url").asText(), key, "sub"))
                            if (node.has("dub")) linkTasks.add(Triple(node.get("dub").get("url").asText(), key, "dub"))
                        }
                    } catch (e: Exception) {}
                }
            }
            awaitAll(taskAnikoto, taskMapper)

            linkTasks.map { (linkId, serverName, type) ->
                async {
                    try {
                        val resolveUrl = "$mainUrl/ajax/server?get=$linkId"
                        val responseText = app.get(resolveUrl, headers = ajaxHeaders).text
                        if (!responseText.trim().startsWith("<")) {
                            val embedUrl = parseJson<ServerResponse>(responseText).result?.url
                            if (!embedUrl.isNullOrBlank()) {
                                val safeName = "$serverName ($type)"
                                
                                // Logic định tuyến Extractor
                                if (embedUrl.contains("megaplay.buzz")) {
                                    MegaplayExtractor().getVideos(embedUrl, safeName, subtitleCallback, callback)
                                } else if (embedUrl.contains("mewcdn") || embedUrl.contains("megacloud") || embedUrl.contains("plyr.php")) {
                                    MewCloudExtractor().getVideos(embedUrl, safeName, subtitleCallback, callback)
                                } else {
                                    loadExtractor(embedUrl, safeName, subtitleCallback, callback)
                                }
                            }
                        }
                    } catch (e: Exception) {}
                }
            }.awaitAll()
        }
        return true
    }
}
