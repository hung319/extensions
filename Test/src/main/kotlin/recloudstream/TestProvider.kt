// Info: Plugin for phevkl.gg
// Author: Coder
// Date: 2025-07-26
// Version: 4.0 (Definitive fix based on API documentation)

package recloudstream

import android.util.Log
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
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

    private var urlChecked = false

    private suspend fun checkUrlRedirect() {
        if (urlChecked) return
        try {
            val response = app.get(this.mainUrl, allowRedirects = false, timeout = 10)
            if (response.code in 300..399) {
                response.headers["Location"]?.let { newUrl ->
                    if (newUrl.isNotBlank()) {
                        this.mainUrl = newUrl.removeSuffix("/")
                        Log.d(name, "Domain đã được cập nhật thành: ${this.mainUrl}")
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(name, "Không thể kiểm tra redirect. Lỗi: ${e.message}")
        } finally {
            urlChecked = true
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
        checkUrlRedirect()
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
        checkUrlRedirect()
        val searchUrl = "$mainUrl/?s=$query"
        val document = app.get(searchUrl).document
        return document.select("div#video-list div.video-item").mapNotNull {
            it.toSearchResult()
        }
    }

    override suspend fun load(url: String): LoadResponse? {
        checkUrlRedirect()
        val document = app.get(url).document
        val title = document.selectFirst("h1#page-title")?.text()?.trim() ?: return null
        val poster = document.selectFirst("meta[property=og:image]")?.attr("content")
        val description = document.selectFirst("div.video-description")?.text()?.trim()
        val tags = document.select("div.actress-tag a").map { it.text() }
        val recommendations = document.select("div#video-list div.video-item").mapNotNull {
            it.toSearchResult()
        }
        
        return newMovieLoadResponse(title, url, TvType.NSFW, dataUrl = url) {
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
        val doc = app.get(data).document
        val postId = doc.selectFirst("#haun-player")?.attr("data-id") ?: return false
        val ajaxUrl = "$mainUrl/wp-admin/admin-ajax.php"
        var foundLinks = false

        for (server in 1..2) {
            try {
                val response = app.post(
                    ajaxUrl,
                    headers = mapOf(
                        "Referer" to data,
                        "X-Requested-With" to "XMLHttpRequest",
                        "Origin" to mainUrl
                    ),
                    data = mapOf(
                        "action" to "load_server",
                        "id" to postId,
                        "server" to server.toString()
                    )
                ).parsedSafe<AjaxResponse>()

                val iframeSrc = response?.player?.let {
                    Jsoup.parse(it).selectFirst("iframe")?.attr("src")
                } ?: continue

                if (iframeSrc.contains("blogger.com") || iframeSrc.contains("blogspot.com")) {
                    // ==================================================================
                    // THAY ĐỔI THEO YÊU CẦU:
                    // Đưa thẳng link iframe cho player tự xử lý
                    // ==================================================================
                    callback.invoke(
                        ExtractorLink(
                            name = this.name,
                            source = "Server 1", // Tên server
                            url = iframeSrc,               // Link iframe cần giải mã
                            referer = data,                // Trang web chứa iframe
                            quality = Qualities.Unknown.value,
                            type = ExtractorLinkType.VIDEO // Báo cho player biết cần dùng extractor
                        )
                    )
                    foundLinks = true
                } 
                else if (iframeSrc.contains("cnd-videosvn.online")) {
                    // Server 2 giữ nguyên vì đã là link trực tiếp
                    val videoId = iframeSrc.substringAfterLast('/')
                    val m3u8Url = "https://oss1.dlhls.xyz/videos/$videoId/main.m3u8"
                    callback.invoke(
                        ExtractorLink(
                            this.name,
                            "Server 2",
                            m3u8Url,
                            mainUrl,
                            Qualities.Unknown.value,
                            type = ExtractorLinkType.M3U8
                        )
                    )
                    foundLinks = true
                }
            } catch (e: Exception) {
                // Bỏ qua và thử server tiếp theo
            }
        }
        return foundLinks
    }
}
