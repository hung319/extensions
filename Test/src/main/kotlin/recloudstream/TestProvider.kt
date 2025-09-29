package recloudstream

/*
* @CloudstreamProvider: BokepIndoProvider
* @Version: 2.2
* @Author: Coder
* @Language: id
* @TvType: Nsfw
* @Url: https://bokepindoh.monster
* @Info: Provider for Bokepindoh.monster with multiple homepage categories and multi-server support.
*/

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Element
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope

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
        val url = if (page == 1) {
            request.data
        } else {
            "${request.data.removeSuffix("/")}/page/$page/"
        }
        val document = app.get(url).document

        val homePageList = document.select("article.loop-video.thumb-block").mapNotNull {
            it.toSearchResponse()
        }
        
        // Cải tiến: Kiểm tra nếu không có kết quả thì không hiển thị trang tiếp theo
        return newHomePageResponse(
            HomePageList(request.name, homePageList),
            hasNext = homePageList.isNotEmpty()
        )
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val searchUrl = "$mainUrl/?s=${query.replace(" ", "+")}"
        val document = app.get(searchUrl).document

        return document.select("article.loop-video.thumb-block").mapNotNull {
            it.toSearchResponse()
        }
    }

    override suspend fun load(url: String): LoadResponse {
        val document = app.get(url).document

        val title = document.selectFirst("h1.entry-title")?.text() ?: "Không có tiêu đề"
        val poster = document.selectFirst("meta[property='og:image']")?.attr("content")
        val description = document.selectFirst("div.video-description .desc")?.text()
        val tags = document.select("div.tags-list a[class=label]").map { it.text() }
        val recommendations = document.select("div.under-video-block article.loop-video.thumb-block").mapNotNull {
            it.toSearchResponse()
        }

        return newMovieLoadResponse(title, url, TvType.NSFW, url) {
            this.posterUrl = poster
            this.plot = description
            this.tags = tags
            this.recommendations = recommendations
        }
    }

    // ## Cập nhật loadLinks để xử lý nhiều server ##
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val document = app.get(data).document
        val servers = mutableListOf<Pair<String, String>>()

        // Trích xuất iframe từ biến JavaScript `wpst_ajax_var`
        val scriptContent = document.select("script").firstOrNull { it.data().contains("wpst_ajax_var") }?.data() ?: ""
        
        // Regex để lấy URL từ các key `embed_url` và `video_url`
        val embedUrlRegex = Regex("""["']embed_url["']:\s*["']<iframe src=\\"(.*?)\\"""")
        val videoUrlRegex = Regex("""["']video_url["']:\s*["']<iframe.*?src=\\"(.*?)\\"""")

        embedUrlRegex.find(scriptContent)?.groupValues?.get(1)?.let {
            servers.add("Server Ori" to it)
        }
        videoUrlRegex.find(scriptContent)?.groupValues?.get(1)?.let {
            servers.add("Server Dood" to it)
        }
        
        // Nếu không tìm thấy server nào từ script, thử fallback về logic cũ
        if (servers.isEmpty()) {
             document.selectFirst("div.responsive-player iframe")?.attr("src")?.let {
                servers.add("Server" to it)
            }
        }
        
        if (servers.isEmpty()) return false

        // Xử lý song song các server để tăng tốc độ
        coroutineScope {
            servers.map { (serverName, serverUrl) ->
                async {
                    when {
                        // Nhận diện DoodStream qua tên miền
                        serverUrl.contains("dsvplay.com") || serverUrl.contains("dood") -> {
                            extractDoodStreamLink(serverUrl, serverName, callback)
                        }
                        // Server còn lại mặc định là LuluStream
                        else -> {
                            extractLuluStreamLink(serverUrl, serverName, callback)
                        }
                    }
                }
            }.awaitAll()
        }

        return true
    }

    // Hàm riêng để xử lý link LuluStream (Server Ori)
    private suspend fun extractLuluStreamLink(url: String, sourceName: String, callback: (ExtractorLink) -> Unit) {
        try {
            val iframeHtmlContent = app.get(url, referer = mainUrl).text
            val m3u8Regex = Regex("""sources:\s*\[\{file:"([^"]+master\.m3u8[^"]+)"""")
            m3u8Regex.find(iframeHtmlContent)?.groupValues?.get(1)?.let { m3u8Url ->
                callback(
                    ExtractorLink(
                        source = sourceName,
                        name = "LuluStream",
                        url = m3u8Url,
                        referer = url,
                        quality = Qualities.Unknown.value,
                        type = ExtractorLinkType.M3U8
                    )
                )
            }
        } catch (e: Exception) {
            // Lỗi khi lấy link, bỏ qua
        }
    }

    // Hàm riêng để xử lý link DoodStream
    private suspend fun extractDoodStreamLink(url: String, sourceName: String, callback: (ExtractorLink) -> Unit) {
        try {
            val doc = app.get(url, referer = mainUrl).text
            // DoodStream dùng một API pass_md5 để lấy link thật
            val md5UrlPath = Regex("""/pass_md5/([^'"]+)""").find(doc)?.value ?: return
            
            // Lấy base URL từ URL của iframe
            val baseUrl = url.substringBefore("/e/")

            val finalUrlContent = app.get(
                "$baseUrl$md5UrlPath",
                referer = url
            ).text

            // Link video nằm trong response của API pass_md5
            if (finalUrlContent.isNotBlank()) {
                 callback(
                    ExtractorLink(
                        source = sourceName,
                        name = "DoodStream",
                        url = finalUrlContent,
                        referer = baseUrl,
                        quality = Qualities.Unknown.value,
                        type = ExtractorLinkType.VIDEO // DoodStream thường trả về MP4
                    )
                )
            }
        } catch (e: Exception) {
             // Lỗi khi lấy link, bỏ qua
        }
    }

    private fun Element.toSearchResponse(): SearchResponse? {
        val linkTag = this.selectFirst("a") ?: return null
        val href = fixUrl(linkTag.attr("href"))
        if (href.isBlank()) return null

        val title = linkTag.selectFirst("header.entry-header span")?.text() ?: return null
        val posterUrl = fixUrlNull(linkTag.selectFirst("div.post-thumbnail-container img")?.attr("data-src"))

        return newMovieSearchResponse(title, href) {
            this.posterUrl = posterUrl
        }
    }
}
