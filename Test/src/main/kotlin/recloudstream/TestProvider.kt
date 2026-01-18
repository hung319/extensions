package recloudstream

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Element
import org.jsoup.Jsoup

class VlxxProvider : MainAPI() {
    override var name = "Vlxx"
    override var mainUrl = "https://vlxx.xxx" // Giữ nguyên theo yêu cầu cũ
    override var hasMainPage = true
    override var supportedTypes = setOf(TvType.NSFW)
    override var lang = "vi"

    private var baseUrl: String? = null

    // Giữ nguyên hàm getBaseUrl cũ theo yêu cầu
    private suspend fun getBaseUrl(): String {
        if (baseUrl == null) {
            val document = app.get(mainUrl).document
            val realUrl = document.selectFirst("a.button")?.attr("href")?.trimEnd('/') ?: mainUrl
            baseUrl = realUrl
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
        val actors = document.select("div.video-tags div.actress-tag a").map { it.text() }
        
        val recommendations = document.select("div#video-list div.video-item").mapNotNull {
            it.toSearchResult()
        }.filter { it.url != url }
        
        return newMovieLoadResponse(title, url, TvType.NSFW, url) {
            this.posterUrl = posterUrl
            this.plot = plot
            this.tags = tags
            this.actors = actors
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
        val document = app.get(data).document
        val videoId = document.selectFirst("div#video")?.attr("data-id") ?: return false
        
        // Loop qua server 1 và 2
        listOf(1, 2).forEach { serverNum ->
            try {
                // Request body giống curl: vlxx_server=2&id=...&server=...
                val postData = mapOf(
                    "vlxx_server" to "2",
                    "id" to videoId,
                    "server" to serverNum.toString()
                )
                
                val headers = mapOf(
                    "X-Requested-With" to "XMLHttpRequest",
                    "Referer" to data,
                    "Origin" to getBaseUrl() // Thêm Origin cho chắc chắn
                )
                
                val ajaxUrl = "${getBaseUrl()}/ajax.php"
                val ajaxResponse = app.post(ajaxUrl, data = postData, headers = headers).parsed<PlayerResponse>()
                
                val iframeDocument = Jsoup.parse(ajaxResponse.player)
                val iframeSrc = iframeDocument.selectFirst("iframe")?.attr("src")

                if (!iframeSrc.isNullOrBlank()) {
                    // Quan trọng: Iframe request cần Referer là trang video gốc (vlxx.ms/video/...)
                    val iframeHeaders = mapOf(
                        "Referer" to data,
                        "User-Agent" to "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/137.0.0.0 Mobile Safari/537.36"
                    )
                    
                    val finalPlayerPage = app.get(iframeSrc, headers = iframeHeaders).text
                    
                    // Parse JSON từ biến window.$$ops hoặc sources: [...]
                    val sourcesJson = sourcesRegex.find(finalPlayerPage)?.groupValues?.get(1)
                    val sources = AppUtils.tryParseJson<List<VideoSource>>(sourcesJson)
                    
                    sources?.forEach { source ->
                        if (source.file.isNotBlank()) {
                            // Xác định type: Nếu JSON trả về type="hls" hoặc file đuôi .vl/.m3u8 thì là M3U8
                            val isM3u8 = source.type == "hls" || source.file.contains(".m3u8") || source.file.endsWith(".vl")
                            
                            callback.invoke(
                                ExtractorLink(
                                    source = this.name,
                                    name = "${this.name} Server $serverNum",
                                    url = source.file,
                                    referer = iframeSrc, // Referer cho stream thường là domain chứa player
                                    quality = Qualities.Unknown.value,
                                    type = if (isM3u8) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                                )
                            )
                        }
                    }
                }
            } catch (e: Exception) {
                // Bỏ qua lỗi server này, thử server khác
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
        
        // Sử dụng data-original vì site dùng lazyload
        val imgTag = this.selectFirst("img.video-image")
        val posterUrl = imgTag?.attr("data-original") ?: imgTag?.attr("src")

        return newMovieSearchResponse(title, href, TvType.NSFW) {
            this.posterUrl = posterUrl
        }
    }
}
