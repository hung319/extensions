package recloudstream

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import org.jsoup.nodes.Element
import java.net.URLDecoder

/**
 * --- METADATA ---
 * Tên plugin: Redtube Provider
 * Tác giả: Coder (AI)
 * Phiên bản: 1.6 (Cập nhật loadLinks)
 * Mô tả: Plugin để xem nội dung từ Redtube, sử dụng selector đã được xác nhận.
 * Ngôn ngữ: en (Tiếng Anh)
 */
class RedtubeProvider : MainAPI() {
    override var mainUrl = "https://www.redtube.com"
    override var name = "Redtube"
    override val hasMainPage = true
    override var lang = "en"
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(
        TvType.NSFW
    )

    private fun Element.toSearchResponse(): MovieSearchResponse? {
        val linkElement = this.selectFirst("a.video-title-text") ?: return null
        val title = linkElement.attr("title")
        val href = fixUrl(linkElement.attr("href"))
        val posterUrl = this.selectFirst("img.js_thumbImageTag")?.attr("data-src")

        return newMovieSearchResponse(title, href, TvType.Movie) {
            this.posterUrl = posterUrl
        }
    }
    
    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {
        val document = app.get("$mainUrl/?page=$page").document
        val mainPageVideos = document.select("li.thumbnail-card").mapNotNull {
            it.toSearchResponse()
        }
        
        if (mainPageVideos.isEmpty()) return HomePageResponse(emptyList())
        
        return HomePageResponse(listOf(HomePageList("Page $page", mainPageVideos)))
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val searchUrl = "$mainUrl/redtube/$query"
        val document = app.get(searchUrl).document
        return document.select("li.thumbnail-card").mapNotNull {
            it.toSearchResponse()
        }
    }

    override suspend fun load(url: String): LoadResponse? {
        val document = app.get(url).document
        val title = document.selectFirst("h1.video_page_title")?.text()?.trim()
            ?: "Untitled"
        val poster = document.selectFirst("meta[property='og:image']")?.attr("content")
        val description = document.selectFirst("meta[name='description']")?.attr("content")
        val recommendations = document.select("li.thumbnail-card").mapNotNull {
            it.toSearchResponse()
        }

        return newMovieLoadResponse(title, url, TvType.Movie, url) {
            this.posterUrl = poster
            this.plot = description
            this.recommendations = recommendations
        }
    }

    // --- CẬP NHẬT HÀM LOADLINKS ---
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val response = app.get(data)
        
        // Regex mới để tìm mảng JSON "mediaDefinitions"
        val mediaDefRegex = Regex(""""mediaDefinitions":(\[.*?\])""")
        val mediaJson = mediaDefRegex.find(response.text)?.groupValues?.get(1) ?: return false

        // Data class mới để parse cấu trúc JSON
        data class MediaDefinition(
            val format: String?,
            val videoUrl: String?,
            val height: String? // Chất lượng có thể lấy từ height
        )
        
        val mediaList = try {
            parseJson<List<MediaDefinition>>(mediaJson)
        } catch (e: Exception) {
            e.printStackTrace()
            return false
        }

        mediaList.forEach { media ->
            val videoUrl = media.videoUrl
            if (videoUrl != null) {
                // Link trong JSON là link tương đối, cần thêm domain vào
                val fullUrl = fixUrl(videoUrl)
                val qualityName = media.height?.let { "${it}p" } ?: media.format ?: "Default"

                if (media.format == "hls") {
                    M3u8Helper.generateM3u8(
                        this.name,
                        fullUrl,
                        mainUrl,
                        headers = mapOf("Referer" to data) // Thêm referer của trang watch
                    ).forEach(callback)
                } else {
                    callback(
                        ExtractorLink(
                            source = this.name,
                            name = "${this.name} - $qualityName",
                            url = fullUrl,
                            referer = data, // Referer là trang watch
                            quality = media.height?.toIntOrNull() ?: Qualities.Unknown.value,
                            type = ExtractorLinkType.VIDEO
                        )
                    )
                }
            }
        }

        return true
    }
}
