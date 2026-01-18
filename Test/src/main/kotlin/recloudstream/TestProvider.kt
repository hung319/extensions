package recloudstream

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Element
import org.jsoup.Jsoup

class VlxxProvider : MainAPI() {
    override var name = "Vlxx"
    override var mainUrl = "https://vlxx.xxx"
    override var hasMainPage = true
    override var supportedTypes = setOf(TvType.NSFW)
    override var lang = "vi"

    private val TAG = "VLXX_DEBUG"

    private fun debugLog(msg: String) {
        println("[$TAG] $msg")
    }

    private var baseUrl: String? = null

    private suspend fun getBaseUrl(): String {
        if (baseUrl == null) {
            try {
                val document = app.get(mainUrl).document
                val realUrl = document.selectFirst("a.button")?.attr("href")?.trimEnd('/') ?: mainUrl
                baseUrl = realUrl
                debugLog("Resolved Base URL: $baseUrl")
            } catch (e: Exception) {
                baseUrl = mainUrl
            }
        }
        return baseUrl!!
    }

    private val sourcesRegex = Regex("""sources:\s*(\[\{.*?\}\])""")

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {
        val currentBaseUrl = getBaseUrl()
        val url = if (page == 1) currentBaseUrl else "$currentBaseUrl/new/$page/"
        val document = app.get(url).document
        
        val home = document.select("div.video-item").mapNotNull {
            it.toSearchResult()
        }

        return newHomePageResponse(request.name, home)
    }
    
    override suspend fun search(query: String): List<SearchResponse> {
        val searchUrl = "${getBaseUrl()}/search/$query/"
        val document = app.get(searchUrl).document

        return document.select("div.video-item").mapNotNull {
            it.toSearchResult()
        }
    }

    override suspend fun load(url: String): LoadResponse? {
        debugLog("Loading details for: $url")
        val document = app.get(url).document

        val title = document.selectFirst("h2#page-title")?.text()?.trim() ?: return null
        val posterUrl = document.selectFirst("meta[property=og:image]")?.attr("content")
        val plot = document.selectFirst("div.video-description")?.text()?.trim()
        
        val tags = document.select("div.video-tags div.category-tag a").map { it.text() }
        
        // Đã bỏ phần Actor theo yêu cầu
        
        val recommendations = document.select("div#video-list div.video-item").mapNotNull {
            it.toSearchResult()
        }.filter { it.url != url }
        
        return newMovieLoadResponse(title, url, TvType.NSFW, url) {
            this.posterUrl = posterUrl
            this.plot = plot
            this.tags = tags
            this.recommendations = recommendations
        }
    }

    data class PlayerResponse(val player: String)
    data class VideoSource(val file: String, val type: String?, val label: String?)

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        debugLog("=== START LoadLinks: $data ===")
        
        val document = app.get(data).document
        val videoId = document.selectFirst("div#video")?.attr("data-id")
        
        if (videoId == null) {
            debugLog("ERROR: Cannot find videoId in div#video")
            return false
        }
        debugLog("Found VideoID: $videoId")

        val currentBaseUrl = getBaseUrl() // Lấy URL hiện tại (vd: vlxx.bz)

        listOf(1, 2).forEach { serverNum ->
            debugLog("--- Checking Server $serverNum ---")
            try {
                val postData = mapOf(
                    "vlxx_server" to "2",
                    "id" to videoId,
                    "server" to serverNum.toString()
                )
                
                val headers = mapOf(
                    "X-Requested-With" to "XMLHttpRequest",
                    "Referer" to data,
                    "Origin" to currentBaseUrl
                )
                
                val ajaxUrl = "$currentBaseUrl/ajax.php"
                debugLog("Posting to $ajaxUrl")
                
                // SỬA LỖI: Dùng .text thay vì .parsed để tránh crash khi body rỗng
                val responseText = app.post(ajaxUrl, data = postData, headers = headers).text
                debugLog("Raw Ajax Response: $responseText")

                if (responseText.isBlank()) {
                    debugLog("Server returned empty response. Skipping...")
                    return@forEach
                }
                
                // Thử parse JSON an toàn
                val json = AppUtils.tryParseJson<PlayerResponse>(responseText)
                val iframeHtml = json?.player ?: responseText // Fallback nếu server trả thẳng HTML

                val iframeDocument = Jsoup.parse(iframeHtml)
                val iframeSrc = iframeDocument.selectFirst("iframe")?.attr("src")
                debugLog("Extracted Iframe Src: $iframeSrc")

                if (!iframeSrc.isNullOrBlank()) {
                    val iframeHeaders = mapOf(
                        "Referer" to data,
                        "User-Agent" to "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/137.0.0.0 Mobile Safari/537.36"
                    )
                    
                    val finalPlayerPage = app.get(iframeSrc, headers = iframeHeaders).text
                    
                    val sourcesMatch = sourcesRegex.find(finalPlayerPage)
                    val sourcesJson = sourcesMatch?.groupValues?.get(1)
                    
                    if (sourcesJson != null) {
                        val sources = AppUtils.tryParseJson<List<VideoSource>>(sourcesJson)
                        
                        sources?.forEach { source ->
                            if (source.file.isNotBlank()) {
                                val isM3u8 = source.type == "hls" || source.file.contains(".m3u8") || source.file.endsWith(".vl")
                                val linkType = if (isM3u8) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO

                                val link = newExtractorLink(
                                    source = this.name,
                                    name = "${this.name} Server $serverNum",
                                    url = source.file,
                                    type = linkType
                                ) {
                                    this.referer = iframeSrc
                                    this.quality = Qualities.Unknown.value
                                }
                                
                                callback.invoke(link)
                                debugLog(">>> Added Link: ${source.file}")
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                debugLog("EXCEPTION in Server $serverNum: ${e.message}")
            }
        }
        
        debugLog("=== END LoadLinks ===")
        return true
    }
    
    private suspend fun Element.toSearchResult(): SearchResponse? {
        val linkTag = this.selectFirst("a") ?: return null
        var href = linkTag.attr("href")
        if (href.isBlank()) return null
        
        if (!href.startsWith("http")) {
            href = "${getBaseUrl()}$href"
        }
        
        val title = linkTag.attr("title").trim()
        val imgTag = this.selectFirst("img.video-image")
        val posterUrl = imgTag?.attr("data-original") ?: imgTag?.attr("src")

        return newMovieSearchResponse(title, href, TvType.NSFW) {
            this.posterUrl = posterUrl
        }
    }
}
