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
    
    data class MewResponse(val time: Long?, val data: MewData?)
    data class MewData(val tracks: List<MewTrack>?, val sources: List<MewSource>?)
    data class MewTrack(val file: String?, val label: String?, val kind: String?)
    data class MewSource(val url: String?)

    // =========================================================================
    //  1. MEW CLOUD EXTRACTOR (Đã update xử lý plyr.php)
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
                // --- CASE 1: Xử lý link trực tiếp dạng plyr.php#BASE64# ---
                // URL mẫu: https://mewcdn.online/player/plyr.php#aHR0...#?autostart=true
                if (url.contains("/player/plyr.php")) {
                    val fragments = url.split("#")
                    
                    // Chuỗi Base64 thường nằm ở giữa 2 dấu # (index 1)
                    if (fragments.size > 1) {
                        val base64String = fragments[1]
                        try {
                            val decodedBytes = Base64.decode(base64String, Base64.DEFAULT)
                            val decodedUrl = String(decodedBytes)

                            if (decodedUrl.startsWith("http")) {
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
                                return // Tìm thấy thì dừng luôn
                            }
                        } catch (e: Exception) {
                           // Nếu giải mã lỗi thì bỏ qua, chạy tiếp logic dưới (nếu có)
                           // e.printStackTrace()
                        }
                    }
                }

                // --- CASE 2: Xử lý qua API save_data.php (MegaCloud/VidWish) ---
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

                // Video
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

                // Phụ đề
                data.tracks?.forEach { track ->
                    if (track.file != null && track.kind == "captions") {
                        subtitleCallback(
                            SubtitleFile(
                                lang = track.label ?: "English",
                                url = track.file
                            )
                        )
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    // =========================================================================
    //  2. ANIKOTO LOGIC
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
        val newRelease = doc.select("section[data-name='new-release'] .item").mapNotNull { 
            val title = it.selectFirst(".name")?.text() ?: return@mapNotNull null
            val href = it.attr("href")
            newAnimeSearchResponse(title, fixUrl(href)) { this.posterUrl = it.selectFirst("img")?.attr("src") }
        }
        return newHomePageResponse(listOf(HomePageList("Hot", hotest), HomePageList("Recently Updated", recent), HomePageList("New Release", newRelease)), hasNext = false)
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
            val epIds = element.attr("data-ids")
            if (epIds.isBlank()) return@mapNotNull null
            
            val epUrl = "$mainUrl/ajax/server/list?servers=$epIds"
            val isSub = element.attr("data-sub") == "1"
            val isDub = element.attr("data-dub") == "1"
            val typeInfo = if (isSub && isDub) "[Sub/Dub]" else if (isDub) "[Dub]" else ""
            
            newEpisode(epUrl) {
                this.name = if(typeInfo.isNotEmpty()) "$epName $typeInfo" else epName
                this.episode = epNum.toInt()
            }
        }
        return newTvSeriesLoadResponse(title, url, TvType.Anime, episodes) {
            this.posterUrl = poster
            this.plot = description
            if (ratingText != null) this.score = Score.from10(ratingText.toDoubleOrNull() ?: 0.0)
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
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
                                    embedUrl.contains("/player/plyr.php")) { // Thêm điều kiện plyr.php
                                    
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
