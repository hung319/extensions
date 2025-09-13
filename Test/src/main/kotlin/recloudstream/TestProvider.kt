package recloudstream

// Info: Plugin for phevkl.gg
// Author: Coder
// Date: 2025-07-26
// Version: 2.7 (Added Recommendations & Reordered Main Page)

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import android.util.Log
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerialName

class Phevkl : MainAPI() {
    override var mainUrl = "https://phevkl.gg"
    override var name = "Phevkl"
    override val hasMainPage = true
    override var lang = "vi"
    override val hasDownloadSupport = true
    
    override val supportedTypes = setOf(
        TvType.NSFW
    )

    override suspend fun init() {
        try {
            val response = app.get(mainUrl, allowRedirects = false, timeout = 10)
            if (response.code in 300..399) {
                response.headers["Location"]?.let { newUrl ->
                    if (newUrl.isNotBlank()) {
                        mainUrl = newUrl.removeSuffix("/")
                        Log.d(name, "Domain đã được cập nhật thành: $mainUrl")
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(name, "Không thể kiểm tra redirect cho domain. Lỗi: ${e.message}")
        }
    }

    override val mainPage = mainPageOf(
        "$mainUrl/page/%d/" to "Mới cập nhật",
        "$mainUrl/phim-sex-hay/" to "Phim sex hay",
        "$mainUrl/phim-sex-viet-nam/" to "Phim sex Việt Nam",
        "$mainUrl/phim-sex-trung-quoc/" to "Phim sex Trung Quốc",
        "$mainUrl/phim-sex-onlyfans/" to "Sex Onlyfans",
        "$mainUrl/phim-sex-tiktok/" to "Sex Tiktok"
    )

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {
        val url = if (request.data.contains("%d")) {
            request.data.format(page)
        } else {
            if (page == 1) request.data else "${request.data}page/$page/"
        }
        
        val document = app.get(url).document
        val home = document.select("div#video-list div.video-item").mapNotNull {
            it.toSearchResult()
        }
        return newHomePageResponse(request.name, home)
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val link = this.selectFirst("a") ?: return null
        val title = link.attr("title").trim()
        val href = link.attr("href")
        val posterUrl = this.selectFirst("img.video-image")?.let { it.attr("data-src").ifEmpty { it.attr("src") } }

        return if (href.isNotBlank() && title.isNotBlank()) {
            newMovieSearchResponse(title, href, TvType.NSFW) {
                this.posterUrl = posterUrl
            }
        } else {
            null
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val searchUrl = "$mainUrl/?s=$query"
        val document = app.get(searchUrl).document
        return document.select("div#video-list div.video-item").mapNotNull {
            it.toSearchResult()
        }
    }

    override suspend fun load(url: String): LoadResponse? {
        val document = app.get(url).document
        val title = document.selectFirst("h1#page-title")?.text()?.trim() ?: return null
        val poster = document.selectFirst("meta[property=og:image]")?.attr("content")
        val description = document.selectFirst("div.video-description")?.text()?.trim()
        val tags = document.select("div.actress-tag a").map { it.text() }
        
        // Extract recommendations from the "Phim sex liên quan" section
        val recommendations = document.select("div#video-list div.video-item").mapNotNull {
            it.toSearchResult()
        }
        
        return newMovieLoadResponse(title, url, TvType.NSFW, url) {
            this.posterUrl = poster
            this.plot = description
            this.tags = tags
            this.recommendations = recommendations
        }
    }
    
    private data class AjaxResponse(val type: String?, val player: String?)

    @Serializable
private data class BloggerStream(
    @SerialName("play_url") val playUrl: String?,
    @SerialName("format_id") val formatId: Int?
)

@Serializable
private data class BloggerConfig(
    @SerialName("streams") val streams: List<BloggerStream>?
)

override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        // ... (phần code lấy postId vẫn giữ nguyên)
        val doc = app.get(data).document
        val postId = doc.selectFirst("#haun-player")?.attr("data-id") ?: return false
        val ajaxUrl = "$mainUrl/wp-admin/admin-ajax.php"
        var foundLinks = false

        for (server in 1..2) {
            try {
                val response = app.post(
                    // ... (phần post request giữ nguyên)
                ).parsedSafe<AjaxResponse>()

                val iframeSrc = response?.player?.let {
                    Jsoup.parse(it).selectFirst("iframe")?.attr("src")
                } ?: continue

                if (iframeSrc.contains("blogger.com") || iframeSrc.contains("blogspot.com")) {
                    val iframeContent = app.get(iframeSrc, referer = data).text
                    val videoConfigJson = Regex("""var VIDEO_CONFIG = (\{.*?\})""").find(iframeContent)?.groupValues?.get(1)
                    
                    if (videoConfigJson != null) {
                        // SỬA LỖI 2: Thêm "app." vào trước parseJson
                        val config = app.parseJson<BloggerConfig>(videoConfigJson)

                        config.streams?.forEach { stream ->
                            val videoUrl = stream.playUrl ?: return@forEach
                            val quality = when (stream.formatId) {
                                18 -> "SD - 360p"
                                22 -> "HD - 720p"
                                else -> "Unknown"
                            }
                            callback.invoke(
                                ExtractorLink(
                                    name = this.name,
                                    source = "Blogger - $quality",
                                    url = videoUrl,
                                    referer = "https://www.blogger.com/",
                                    quality = Qualities.Unknown.value,
                                    // SỬA LỖI 3: Dùng .VIDEO thay vì .MP4
                                    type = ExtractorLinkType.VIDEO 
                                )
                            )
                            foundLinks = true
                        }
                    }
                } 
                else if (iframeSrc.contains("cnd-videosvn.online")) {
                    // ... (phần code này đã đúng, giữ nguyên)
                }

            } catch (e: Exception) {
                // Ignore and try next server
            }
        }
        return foundLinks
    }
}
