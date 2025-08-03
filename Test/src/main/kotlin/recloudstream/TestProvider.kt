// File: RedtubeProvider.kt
// Tương thích với Cloudstream Toggle

package recloudstream

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Element
import com.lagradost.cloudstream3.network.CloudflareKiller
import com.google.gson.Gson

/**
 * Coder's Note:
 * - Provider cho Redtube, đã được cập nhật để tương thích với Cloudstream Toggle.
 * - Sử dụng `ExtractorLinkType.VIDEO` cho các luồng MP4 trực tiếp theo tài liệu mới.
 * - `M3u8Helper` sẽ tự động xử lý các luồng HLS (`.m3u8`).
 */
class RedtubeProvider : MainAPI() {
    // Thông tin cơ bản của Provider
    override var mainUrl = "https://www.redtube.com"
    override var name = "Redtube"
    override val supportedTypes = setOf(TvType.NSFW)
    override var lang = "en"

    override val mainPage = mainPageOf(
        "$mainUrl/?page=" to "New Videos",
    )

    override val interceptor = CloudflareKiller()

    /**
     * Hàm phân tích cú pháp một video item từ HTML
     */
    private fun Element.toSearchResult(): SearchResponse? {
        val title = this.selectFirst("a.video_link")?.attr("title")?.trim() ?: return null
        val href = fixUrlNull(this.selectFirst("a.video_link")?.attr("href")) ?: return null
        val posterUrl = fixUrlNull(this.selectFirst("img")?.attr("data-src"))

        return newMovieSearchResponse(title, href, TvType.NSFW) {
            this.posterUrl = posterUrl
        }
    }

    /**
     * Tải trang chính
     */
    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = request.data + page
        val document = app.get(url, interceptor = interceptor).document
        val home = document.select("ul#video_shelf_main_content > li.videoblock, ul.videos.search-video-thumbs > li.videoblock")
            .mapNotNull { it.toSearchResult() }

        return newHomePageResponse(request.name, home)
    }

    /**
     * Thực hiện tìm kiếm
     */
    override suspend fun search(query: String): List<SearchResponse> {
        val searchUrl = "$mainUrl/?search=$query"
        val document = app.get(searchUrl, interceptor = interceptor).document

        return document.select("ul.videos.search-video-thumbs > li.videoblock").mapNotNull {
            it.toSearchResult()
        }
    }

    /**
     * Tải thông tin chi tiết của một video
     */
    override suspend fun load(url: String): LoadResponse {
        val document = app.get(url, interceptor = interceptor).document

        val title = document.selectFirst("h1.video_title")?.text()?.trim() ?: "Untitled"
        val poster = document.selectFirst("meta[property=og:image]")?.attr("content")
        val description = document.selectFirst("meta[name=description]")?.attr("content")

        return newMovieLoadResponse(title, url, TvType.NSFW, url) {
            this.posterUrl = poster
            this.plot = description
        }
    }

    // Data class để parse JSON mediaDefinition
    data class MediaDefinition(
        val quality: String?,
        val videoUrl: String?
    )

    /**
     * Trích xuất các link stream video
     */
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val response = app.get(data, interceptor = interceptor)

        // Regex để tìm và trích xuất mảng JSON `mediaDefinition`
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
                    // Xử lý link MP4 trực tiếp
                    callback(
                        ExtractorLink(
                            source = this.name,
                            name = "${this.name} - ${quality}p",
                            url = videoUrl,
                            referer = mainUrl,
                            quality = quality.toIntOrNull() ?: 0,
                            type = ExtractorLinkType.VIDEO // <--- ĐÃ THAY ĐỔI
                        )
                    )
                }
            }
        }

        return true
    }
}
