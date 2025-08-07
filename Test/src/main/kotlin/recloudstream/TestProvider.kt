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
 * Phiên bản: 3.0 (Logic loadLinks 2-bước & fix TvType)
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

        // Cập nhật TvType thành NSFW
        return newMovieSearchResponse(title, href, TvType.NSFW) {
            this.posterUrl = posterUrl
        }
    }
    
    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {
        val document = app.get(mainUrl).document
        val mainPageVideos = document.select("li.thumbnail-card").mapNotNull {
            it.toSearchResponse()
        }
        
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

        // Cập nhật TvType thành NSFW
        return newMovieLoadResponse(title, url, TvType.NSFW, url) {
            this.posterUrl = poster
            this.plot = description
            this.recommendations = recommendations
        }
    }

    // --- VIẾT LẠI HOÀN TOÀN HÀM LOADLINKS ---
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val response = app.get(data).text
        
        // Bước 1: Lấy danh sách các API endpoint từ trang watch
        val mediaDefRegex = Regex(""""mediaDefinitions":(\[.*?\])""")
        val initialMediaJson = mediaDefRegex.find(response)?.groupValues?.get(1) ?: return false

        data class InitialMedia(val format: String?, val videoUrl: String?)
        
        val initialMediaList = try {
            parseJson<List<InitialMedia>>(initialMediaJson)
        } catch (e: Exception) {
            return false
        }

        // Data class cho kết quả JSON từ API endpoint
        data class FinalVideo(val quality: String?, val videoUrl: String?)

        // Bước 2: Lặp qua từng API endpoint (HLS, MP4) để lấy link video cuối cùng
        initialMediaList.apmap { initialMedia -> // Dùng apmap để gọi API song song
            val apiUrl = initialMedia.videoUrl?.let { fixUrl(it) } ?: return@apmap
            
            try {
                // Gọi API và parse JSON trả về
                val finalVideoList = app.get(apiUrl).parsed<List<FinalVideo>>()
                
                finalVideoList.forEach { finalVideo ->
                    val videoUrl = finalVideo.videoUrl
                    if (videoUrl != null) {
                        val qualityName = finalVideo.quality?.let { "${it}p" } ?: "Stream"
                        val qualityInt = finalVideo.quality?.toIntOrNull() ?: Qualities.Unknown.value

                        callback(
                            ExtractorLink(
                                source = this.name,
                                name = "${this.name} - $qualityName",
                                url = videoUrl,
                                referer = data, // Referer là trang watch ban đầu
                                quality = qualityInt,
                                // Xác định type dựa trên format của API endpoint ban đầu
                                type = if (initialMedia.format == "hls") ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                            )
                        )
                    }
                }
            } catch (e: Exception) {
                // Bỏ qua nếu có lỗi khi gọi một trong các API
            }
        }

        return true
    }
}
