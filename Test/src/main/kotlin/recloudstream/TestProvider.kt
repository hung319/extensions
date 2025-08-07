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
 * Phiên bản: 2.0 (Final - Bỏ phân trang, fix deprecated, dùng ExtractorLink trực tiếp)
 * Mô tả: Plugin để xem nội dung từ Redtube, đã được tối ưu theo yêu cầu.
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
    
    // --- BỎ PHÂN TRANG & FIX DEPRECATED WARNING ---
    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {
        // Chỉ tải từ trang chính, không dùng `page`
        val document = app.get(mainUrl).document
        val mainPageVideos = document.select("li.thumbnail-card").mapNotNull {
            it.toSearchResponse()
        }
        
        // Sử dụng `newHomePageResponse` để tránh warning
        return newHomePageResponse(
            list = listOf(HomePageList("Most Recent Videos", mainPageVideos)),
            hasNext = false
        )
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
        val title = document.selectFirst("h1.video_page_title")?.text()?.trim() ?: "Untitled"
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

    // --- DÙNG EXTRACTORLINK TRỰC TIẾP CHO M3U8 ---
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val response = app.get(data)
        
        val mediaDefRegex = Regex(""""mediaDefinitions":(\[.*?\])""")
        val mediaJson = mediaDefRegex.find(response.text)?.groupValues?.get(1) ?: return false

        data class MediaDefinition(
            val format: String?,
            val videoUrl: String?,
            val height: String?
        )
        
        val mediaList = try {
            parseJson<List<MediaDefinition>>(mediaJson)
        } catch (e: Exception) {
            return false
        }

        mediaList.forEach { media ->
            val videoUrl = media.videoUrl
            if (videoUrl != null) {
                val fullUrl = fixUrl(videoUrl)
                val qualityName = media.height?.let { "${it}p" } ?: media.format?.uppercase() ?: "Stream"
                
                // Kiểm tra định dạng và tạo ExtractorLink tương ứng
                if (media.format == "hls") {
                    callback(
                        ExtractorLink(
                            source = this.name,
                            name = "${this.name} - $qualityName",
                            url = fullUrl,
                            referer = data,
                            quality = Qualities.Unknown.value, // Chất lượng sẽ do player xác định
                            type = ExtractorLinkType.M3U8
                        )
                    )
                } else {
                    callback(
                        ExtractorLink(
                            source = this.name,
                            name = "${this.name} - $qualityName",
                            url = fullUrl,
                            referer = data,
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
