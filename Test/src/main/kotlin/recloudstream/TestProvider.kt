package recloudstream

/*
* @CloudstreamProvider: BokepIndoProvider
* @Version: 2.6
* @Author: Coder
* @Language: id
* @TvType: Nsfw
* @Url: https://bokepindoh.monster
* @Info: Provider for Bokepindoh.monster. Fixed DoodStream extraction logic.
*/

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Element
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import java.net.URI

class BokepIndoProvider : MainAPI() {
    override var name = "BokepIndo"
    override var mainUrl = "https://bokepindoh.monster"
    override var supportedTypes = setOf(TvType.NSFW)
    override var lang = "id"
    override var hasMainPage = true
    override var hasDownloadSupport = true

    override val mainPage = mainPageOf(
        mainUrl to "Latest",
        "$mainUrl/category/bokep-indo/" to "Bokep Indo",
        "$mainUrl/category/bokep-viral/" to "Bokep Viral",
        "$mainUrl/category/bokep-jav/" to "Bokep JAV"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = if (page == 1) request.data else "${request.data.removeSuffix("/")}/page/$page/"
        val document = app.get(url).document
        val homePageList = document.select("article.loop-video.thumb-block").mapNotNull { it.toSearchResponse() }
        return newHomePageResponse(HomePageList(request.name, homePageList), hasNext = homePageList.isNotEmpty())
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val document = app.get("$mainUrl/?s=${query.replace(" ", "+")}").document
        return document.select("article.loop-video.thumb-block").mapNotNull { it.toSearchResponse() }
    }

    override suspend fun load(url: String): LoadResponse {
        val document = app.get(url).document
        val title = document.selectFirst("h1.entry-title")?.text() ?: "No Title"
        val poster = document.selectFirst("meta[property='og:image']")?.attr("content")
        val description = document.selectFirst("div.video-description .desc")?.text()
        val tags = document.select("div.tags-list a.label").map { it.text() }
        val recommendations = document.select("div.under-video-block article.loop-video.thumb-block").mapNotNull { it.toSearchResponse() }

        return newMovieLoadResponse(title, url, TvType.NSFW, url) {
            this.posterUrl = poster
            this.plot = description
            this.tags = tags
            this.recommendations = recommendations
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val document = app.get(data).document
        val servers = mutableListOf<Pair<String, String>>()

        val scriptContent = document.select("script").firstOrNull { it.data().contains("wpst_ajax_var") }?.data() ?: ""
        val embedUrlRegex = Regex("""["']embed_url["']:\s*["']<iframe src=\\"(.*?)\\"""")
        val videoUrlRegex = Regex("""["']video_url["']:\s*["']<iframe.*?src=\\"(.*?)\\"""")

        embedUrlRegex.find(scriptContent)?.groupValues?.get(1)?.let { servers.add("Server Ori" to it) }
        videoUrlRegex.find(scriptContent)?.groupValues?.get(1)?.let { servers.add("Server Dood" to it) }

        if (servers.isEmpty()) {
             document.select("div.responsive-player iframe").forEach { element ->
                val url = element.attr("src")
                val host = (URI(url).host ?: url).removePrefix("www.")
                servers.add(host to url)
            }
        }

        if (servers.isEmpty()) return false

        coroutineScope {
            servers.map { (serverName, serverUrl) ->
                async {
                    if (serverUrl.contains("dood") || serverUrl.contains("dsvplay")) {
                        extractDoodStreamLink(serverUrl, serverName, callback)
                    } else {
                        extractLuluBasedServer(serverUrl, serverName, callback)
                    }
                }
            }.awaitAll()
        }
        return true
    }
    
    private suspend fun extractLuluBasedServer(url: String, sourceName: String, callback: (ExtractorLink) -> Unit) {
        try {
            val doc = app.get(url, referer = mainUrl).text
            if (doc.contains("eval(function(p,a,c,k,e,d)")) {
                val packerRegex = Regex("""eval\(function\(p,a,c,k,e,d\).*?'(.*?)\',(\d+),(\d+),'(.*?)'\.split""")
                val match = packerRegex.find(doc)
                if (match != null) {
                    val (p, a, c, k) = match.destructured
                    var packedCode = p
                    val radix = a.toInt()
                    var count = c.toInt()
                    val dictionary = k.split("|")
                    fun toBase(num: Int, base: Int): String {
                        val baseChars = "0123456789abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ"
                        if (num < 0) return ""
                        var n = num
                        var result = ""
                        while (n > 0) {
                            result = baseChars[n % base] + result
                            n /= base
                        }
                        return result.ifEmpty { "0" }
                    }
                    while (count-- > 0) {
                        val key = toBase(count, radix)
                        val value = if (dictionary.getOrNull(count)?.isNotEmpty() == true) dictionary[count] else key
                        packedCode = packedCode.replace(Regex("\\b$key\\b"), value)
                    }
                    val fileRegex = Regex("""sources:\s*\[\s*\{\s*.*?file['"]\s*:\s*['"]([^'"]+)""")
                    fileRegex.find(packedCode)?.groupValues?.get(1)?.let { videoUrl ->
                        callback(ExtractorLink(sourceName, "Server Ori", videoUrl, url, Qualities.Unknown.value, type = ExtractorLinkType.VIDEO))
                        return
                    }
                }
            }
            val m3u8Regex = Regex("""sources:\s*\[\{file:"([^"]+)""")
            m3u8Regex.find(doc)?.groupValues?.get(1)?.let { m3u8Url ->
                if (m3u8Url.contains(".m3u8")) {
                     callback(ExtractorLink(sourceName, "LuluStream", m3u8Url, url, Qualities.Unknown.value, type = ExtractorLinkType.M3U8))
                }
            }
        } catch (e: Exception) { /* Bỏ qua lỗi */ }
    }
    
    // ĐÃ SỬA: Hàm xử lý DoodStream hoàn chỉnh
    private suspend fun extractDoodStreamLink(url: String, sourceName: String, callback: (ExtractorLink) -> Unit) {
        try {
            val doc = app.get(url, referer = mainUrl).document.html()

            // Bước 1: Trích xuất MD5 path và token từ script của trang iframe
            val md5Path = Regex("""/pass_md5/([^'"]+)""").find(doc)?.value ?: return
            val token = Regex("""makePlay\(\)\s*\{.*?token=([^&'"]+)""").find(doc)?.groupValues?.get(1) ?: return
            
            val baseUrl = url.substringBefore("/e/")
            val md5Url = "$baseUrl$md5Path"
            
            // Thêm Header để request trông giống trình duyệt thật hơn
            val headers = mapOf("Referer" to url)

            // Bước 2: Gọi API pass_md5 để lấy phần đầu của link video
            val videoUrlBase = app.get(md5Url, headers = headers).text
            
            // Bước 3: Tái tạo lại logic của hàm makePlay()
            val randomChars = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789"
            val randomString = (1..10).map { randomChars.random() }.joinToString("")
            val expiry = System.currentTimeMillis()
            
            // Bước 4: Ghép lại thành URL hoàn chỉnh
            val finalUrl = "$videoUrlBase$randomString?token=$token&expiry=$expiry"

            callback(
                ExtractorLink(
                    source = sourceName,
                    name = "DoodStream",
                    url = finalUrl,
                    referer = baseUrl,
                    quality = Qualities.Unknown.value,
                    type = ExtractorLinkType.VIDEO
                )
            )
        } catch (e: Exception) { 
            // e.printStackTrace() // Bật dòng này nếu cần debug
        }
    }

    private fun Element.toSearchResponse(): SearchResponse? {
        val linkTag = this.selectFirst("a") ?: return null
        val href = fixUrl(linkTag.attr("href"))
        if (href.isBlank()) return null
        val title = linkTag.selectFirst("header.entry-header span")?.text() ?: return null
        val posterUrl = fixUrlNull(linkTag.selectFirst("div.post-thumbnail-container img")?.attr("data-src"))
        return newMovieSearchResponse(title, href) { this.posterUrl = posterUrl }
    }
}
