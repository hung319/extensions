package recloudstream

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Element
import org.jsoup.Jsoup
import java.net.URI

class VlxxProvider : MainAPI() {
    override var name = "Vlxx"
    override var mainUrl = "https://vlxx.xxx" // Link gốc (sẽ tự redirect)
    override var hasMainPage = true
    override var supportedTypes = setOf(TvType.NSFW)
    override var lang = "vi"

    private val TAG = "VLXX_DEBUG"

    private fun debugLog(msg: String) {
        println("[$TAG] $msg")
    }

    private var baseUrl: String? = null

    // Logic mới: Request vào mainUrl -> Lấy final URL (đã redirect)
    private suspend fun getBaseUrl(): String {
        if (baseUrl == null) {
            try {
                // app.get tự động follow redirect
                val response = app.get(mainUrl)
                val finalUrl = response.url.trimEnd('/')
                
                debugLog("Redirect Check: $mainUrl -> $finalUrl")
                baseUrl = finalUrl
            } catch (e: Exception) {
                debugLog("Redirect Check Failed: ${e.message}")
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
        val document = app.get(url).document

        val title = document.selectFirst("h2#page-title")?.text()?.trim() ?: return null
        val posterUrl = document.selectFirst("meta[property=og:image]")?.attr("content")
        val plot = document.selectFirst("div.video-description")?.text()?.trim()
        val tags = document.select("div.video-tags div.category-tag a").map { it.text() }
        
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
            debugLog("ERROR: Cannot find videoId")
            return false
        }

        // 1. Lấy Domain hiện tại (sau khi đã redirect)
        val currentBaseUrl = getBaseUrl()
        val targetAjaxUrl = "$currentBaseUrl/ajax.php"
        
        // 2. Tự động lấy Host từ URL (ví dụ: vlxx.ms hay vlxx.bz) để nhét vào Header Authority
        val currentHost = try {
            URI(currentBaseUrl).host
        } catch (e: Exception) {
            "vlxx.ms" // Fallback
        }
        
        debugLog("Using Host: $currentHost | AjaxUrl: $targetAjaxUrl")

        listOf(1, 2).forEach { serverNum ->
            try {
                val postData = mapOf(
                    "vlxx_server" to "2",
                    "id" to videoId,
                    "server" to serverNum.toString()
                )
                
                // Headers động theo domain hiện tại
                val headers = mapOf(
                    "Authority" to currentHost, // Động
                    "Accept" to "*/*",
                    "Accept-Language" to "vi-VN,vi;q=0.9",
                    "Content-Type" to "application/x-www-form-urlencoded; charset=UTF-8",
                    "Origin" to currentBaseUrl, // Động
                    "Referer" to data,
                    "Sec-Ch-Ua" to "\"Chromium\";v=\"137\", \"Not/A)Brand\";v=\"24\"",
                    "Sec-Ch-Ua-Mobile" to "?1",
                    "Sec-Ch-Ua-Platform" to "\"Android\"",
                    "Sec-Fetch-Dest" to "empty",
                    "Sec-Fetch-Mode" to "cors",
                    "Sec-Fetch-Site" to "same-origin",
                    "User-Agent" to "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/137.0.0.0 Mobile Safari/537.36",
                    "X-Requested-With" to "XMLHttpRequest"
                )
                
                val responseText = app.post(targetAjaxUrl, data = postData, headers = headers).text
                
                if (responseText.isBlank()) return@forEach

                val json = AppUtils.tryParseJson<PlayerResponse>(responseText)
                val iframeHtml = json?.player ?: responseText 
                
                val iframeDocument = Jsoup.parse(iframeHtml)
                val iframeSrc = iframeDocument.selectFirst("iframe")?.attr("src")

                if (!iframeSrc.isNullOrBlank()) {
                    val iframeHeaders = mapOf(
                        "Referer" to data,
                        "User-Agent" to "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/137.0.0.0 Mobile Safari/537.36",
                        "Upgrade-Insecure-Requests" to "1",
                        "Sec-Fetch-Dest" to "iframe",
                        "Sec-Fetch-Site" to "cross-site",
                        "Sec-Fetch-Mode" to "navigate"
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
                debugLog("Error server $serverNum: ${e.message}")
            }
        }
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
