// File: RedtubeProvider.kt
// Đã thêm throw Exception để debug nội dung HTML

package recloudstream

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Element
import com.google.gson.Gson

/**
 * Coder's Note:
 * - Provider cho Redtube.
 * - Đã thêm một `throw Exception` trong `getMainPage` để in ra nội dung HTML mà plugin nhận được.
 * - Bạn hãy kiểm tra log lỗi sau khi chạy để xem nội dung HTML đầy đủ.
 */
class RedtubeProvider : MainAPI() {
    override var mainUrl = "https://www.redtube.com"
    override var name = "Redtube"
    override val supportedTypes = setOf(TvType.NSFW)
    override var lang = "en"
    override val hasMainPage = true

    override val mainPage = mainPageOf(
        "$mainUrl/?page=" to "New Videos",
    )

    private fun Element.toSearchResult(): SearchResponse? {
        val linkElement = this.selectFirst("a.video_link") ?: return null
        val title = linkElement.attr("title").trim()
        if (title.isBlank()) return null
        
        val href = fixUrlNull(linkElement.attr("href")) ?: return null
        val posterUrl = fixUrlNull(this.selectFirst("img")?.attr("data-src"))

        return newMovieSearchResponse(title, href, TvType.NSFW) {
            this.posterUrl = posterUrl
        }
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = request.data + page
        val document = app.get(url).document
        
        // DEBUG: Ném ra một exception để in toàn bộ nội dung HTML
        // Tạm thời vô hiệu hóa logic chính để tập trung debug
        throw Exception(document.html())

        /*
        // Logic cũ, tạm thời vô hiệu hóa
        val home = document.select("li.videoblock")
            .mapNotNull { it.toSearchResult() }

        if (home.isEmpty()) {
             // Nếu vẫn không tìm thấy, ném ra lỗi cùng với HTML
             throw Exception("No items found on homepage. HTML content:\n\n${document.html()}")
        }

        return newHomePageResponse(request.name, home)
        */
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val searchUrl = "$mainUrl/?search=$query"
        val document = app.get(searchUrl).document

        return document.select("li.videoblock").mapNotNull {
            it.toSearchResult()
        }
    }

    override suspend fun load(url: String): LoadResponse {
        val document = app.get(url).document

        val title = document.selectFirst("h1.video_title")?.text()?.trim() ?: "Untitled"
        val poster = document.selectFirst("meta[property=og:image]")?.attr("content")
        val description = document.selectFirst("meta[name=description]")?.attr("content")

        return newMovieLoadResponse(title, url, TvType.NSFW, url) {
            this.posterUrl = poster
            this.plot = description
        }
    }

    data class MediaDefinition(
        val quality: String?,
        val videoUrl: String?
    )

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val response = app.get(data)

        val mediaDefinitionRegex = Regex("""mediaDefinition":(\[.*?\])""")
        val matchResult = mediaDefinitionRegex.find(response.text)
        val jsonString = matchResult?.groups?.get(1)?.value ?: return false

        val mediaList = try {
            Gson().fromJson(jsonString, Array<MediaDefinition>::class.java).toList()
        } catch (e: Exception) {
            return false
        }

        mediaList.forEach { media ->
            val videoUrl = media.videoUrl
            val quality = media.quality
            if (!videoUrl.isNullOrBlank() && !quality.isNullOrBlank()) {
                if (videoUrl.contains(".m3u8")) {
                    M3u8Helper.generateM3u8(
                        this.name,
                        videoUrl,
                        mainUrl,
                        headers = mapOf("Referer" to mainUrl)
                    ).forEach(callback)
                } else {
                    callback(
                        ExtractorLink(
                            source = this.name,
                            name = "${this.name} - ${quality}p",
                            url = videoUrl,
                            referer = mainUrl,
                            quality = quality.toIntOrNull() ?: 0,
                            type = ExtractorLinkType.VIDEO
                        )
                    )
                }
            }
        }
        return true
    }
}
