// File: RedtubeProvider.kt
// Cập nhật selector chính xác và tích hợp cơ chế debug thông minh

package recloudstream

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Element
import com.google.gson.Gson

/**
 * Coder's Note:
 * - Provider cho Redtube.
 * - Đã cập nhật lại CSS selector chính xác nhất dựa trên phân tích HTML.
 * - Tích hợp cơ chế debug: Nếu không tìm thấy video, plugin sẽ báo lỗi và đính kèm nội dung HTML để kiểm tra.
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
        
        // SỬA LỖI: Selector chính xác và cụ thể nhất cho trang chính.
        val home = document.select("div#video_shelf_main_content li.videoblock")
            .mapNotNull { it.toSearchResult() }

        // DEBUG THÔNG MINH: Nếu không tìm thấy item nào, báo lỗi và in ra HTML.
        // Điều này giúp xác định vấn đề nếu layout trang web thay đổi hoặc bạn nhận được trang captcha.
        if (home.isEmpty()) {
            throw Exception("Không tìm thấy video nào trên trang chính. Nội dung HTML nhận được:\n\n${document.html()}")
        }

        return newHomePageResponse(request.name, home)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val searchUrl = "$mainUrl/?search=$query"
        val document = app.get(searchUrl).document

        // SỬA LỖI: Selector chính xác và cụ thể nhất cho trang tìm kiếm.
        val results = document.select("ul.search-video-thumbs li.videoblock").mapNotNull {
            it.toSearchResult()
        }

        // Tùy chọn: Bạn có thể thêm cơ chế debug tương tự ở đây nếu cần.
        // if (results.isEmpty()) {
        //     throw Exception("Tìm kiếm cho '$query' không có kết quả. Nội dung HTML nhận được:\n\n${document.html()}")
        // }

        return results
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
                    M3u8Helper.generateM3u8(this.name, videoUrl, mainUrl, headers = mapOf("Referer" to mainUrl)).forEach(callback)
                } else {
                    callback(ExtractorLink(this.name, "${this.name} - ${quality}p", videoUrl, mainUrl, quality.toIntOrNull() ?: 0, type = ExtractorLinkType.VIDEO))
                }
            }
        }
        return true
    }
}
