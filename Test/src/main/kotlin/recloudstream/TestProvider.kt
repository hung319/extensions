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
import android.net.Uri

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
    //  1. MEW CLOUD EXTRACTOR (Chỉ giữ lại cái này)
    // =========================================================================
    inner class MewCloudExtractor : ExtractorApi() {
        override val name = "MewCloud"
        override val mainUrl = "https://mewcdn.online"
        override val requiresReferer = false

        suspend fun getVideos(url: String, serverName: String, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit) {
            try {
                // CASE 1: Plyr Base64
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

                // CASE 2: API save_data.php
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
        // [TOAST]
        if (page == 1) {
            withContext(Dispatchers.Main) {
                try { 
                    CommonActivity.activity?.let { 
                        Toast.makeText(it, "Free Repo From H4RS", Toast.LENGTH_LONG).show() 
                    } 
                } catch (e: Exception) {}
            }
        }

        // Chỉ lấy URL gốc, không xử lý page > 1
        val url = request.data
        val isAjax = url.contains("/ajax/")
        
        val headers = getBaseHeaders(referer = request.data).toMutableMap()
        if (isAjax) {
            headers["Accept"] = "application/json, text/javascript, */*; q=0.01"
            headers["X-Requested-With"] = "XMLHttpRequest"
        } else {
            headers["Accept"] = "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8"
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
            hasNext = false // Disable Pagination
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
            
            // Xây dựng URL đơn giản, bỏ các tham số timestamp/mal không cần thiết cho native load
            val epUrl = "$mainUrl/ajax/server/list?servers=$epIds"
            
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
        // [CLEANED] Bỏ hoàn toàn logic Mapper bên thứ 3 và các tham số phức tạp
        val linkTasks = mutableListOf<Triple<String, String, String>>() 
        val ajaxHeaders = getBaseHeaders() + mapOf("X-Requested-With" to "XMLHttpRequest")

        // 1. Chỉ gọi 1 nguồn duy nhất: Anikoto Server List
        try {
            app.get(data, headers = ajaxHeaders).parsedSafe<AjaxResponse>()?.result?.let { html ->
                Jsoup.parse(html).select(".servers .type li").forEach { server ->
                    val linkId = server.attr("data-link-id")
                    if (linkId.isNotBlank()) {
                        linkTasks.add(Triple(linkId, server.text(), server.parent()?.parent()?.attr("data-type") ?: "sub"))
                    }
                }
            }
        } catch (e: Exception) { e.printStackTrace() }

        // 2. Resolve Link song song
        coroutineScope {
            linkTasks.map { (linkId, serverName, type) ->
                async {
                    try {
                        val resolveUrl = "$mainUrl/ajax/server?get=$linkId"
                        val responseText = app.get(resolveUrl, headers = ajaxHeaders).text
                        
                        if (!responseText.trim().startsWith("<")) {
                            val embedUrl = parseJson<ServerResponse>(responseText).result?.url
                            
                            if (!embedUrl.isNullOrBlank()) {
                                val safeName = "$serverName ($type)"
                                
                                // [FILTER] Chặn tuyệt đối Megaplay và Kiwistream
                                if (embedUrl.contains("megaplay") || embedUrl.contains("kiwi")) {
                                    // Do nothing (Skip)
                                } 
                                // Chỉ xử lý MewCloud/MegaCloud hoặc Native
                                else if (embedUrl.contains("mewcdn") || embedUrl.contains("megacloud") || embedUrl.contains("plyr.php")) {
                                    MewCloudExtractor().getVideos(embedUrl, safeName, subtitleCallback, callback)
                                } else {
                                    loadExtractor(embedUrl, safeName, subtitleCallback, callback)
                                }
                            }
                        }
                    } catch (e: Exception) { e.printStackTrace() }
                }
            }.awaitAll()
        }
        return true
    }
}
