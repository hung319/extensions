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
 * Phiên bản: 3.1 (Thêm Log & Exception để Debug)
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

        return newMovieLoadResponse(title, url, TvType.NSFW, url) {
            this.posterUrl = poster
            this.plot = description
            this.recommendations = recommendations
        }
    }

    // --- CẬP NHẬT: THÊM LOG VÀ EXCEPTION ---
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        try {
            println("RedtubeDebug: Bắt đầu loadLinks cho url: $data")
            val response = app.get(data).text
            
            val mediaDefRegex = Regex(""""mediaDefinitions":(\[.*?\])""")
            val initialMediaJson = mediaDefRegex.find(response)?.groupValues?.get(1)
            
            if (initialMediaJson == null) {
                throw Exception("RedtubeDebug: Không tìm thấy 'mediaDefinitions' trong HTML. Regex thất bại.")
            }
            println("RedtubeDebug: Đã tìm thấy mediaDefinitions JSON: $initialMediaJson")

            data class InitialMedia(val format: String?, val videoUrl: String?)
            val initialMediaList = parseJson<List<InitialMedia>>(initialMediaJson)
            println("RedtubeDebug: Đã parse được ${initialMediaList.size} API endpoints.")

            data class FinalVideo(val quality: String?, val videoUrl: String?)
            var linksFound = 0

            initialMediaList.apmap { initialMedia ->
                val apiUrl = initialMedia.videoUrl?.let { fixUrl(it) }
                if (apiUrl == null) {
                    println("RedtubeDebug: Bỏ qua vì apiUrl rỗng.")
                    return@apmap
                }
                println("RedtubeDebug: Đang gọi API: $apiUrl")
                
                val apiResponse = app.get(apiUrl).text
                println("RedtubeDebug: Phản hồi từ API: $apiResponse")

                val finalVideoList = parseJson<List<FinalVideo>>(apiResponse)
                
                finalVideoList.forEach { finalVideo ->
                    val videoUrl = finalVideo.videoUrl
                    if (videoUrl != null) {
                        linksFound++
                        val qualityName = finalVideo.quality?.let { "${it}p" } ?: "Stream"
                        val qualityInt = finalVideo.quality?.toIntOrNull() ?: Qualities.Unknown.value

                        callback(
                            ExtractorLink(
                                source = this.name,
                                name = "${this.name} - $qualityName",
                                url = videoUrl,
                                referer = data,
                                quality = qualityInt,
                                type = if (initialMedia.format == "hls") ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                            )
                        )
                    }
                }
            }

            println("RedtubeDebug: Đã tìm thấy tổng cộng $linksFound links.")
            if (linksFound == 0) {
                throw Exception("RedtubeDebug: Không tìm thấy link video nào sau khi xử lý tất cả API.")
            }

        } catch (e: Exception) {
            // Ném ra một exception mới với thông tin lỗi đầy đủ để hiển thị trong Logcat
            throw Exception("Lỗi trong RedtubeProvider: ${e.message}", e)
        }
        return true
    }
}
