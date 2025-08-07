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
 * Phiên bản: 4.1 (Final - Cập nhật định dạng tên link)
 * Mô tả: Plugin để xem nội dung từ Redtube, phiên bản hoàn thiện.
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

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val response = app.get(data).text
        
        val mediaDefRegex = Regex(""""mediaDefinitions":(\[.*?\])""")
        val initialMediaJson = mediaDefRegex.find(response)?.groupValues?.get(1) ?: return false

        data class InitialMedia(val format: String?, val videoUrl: String?)
        
        val initialMediaList = try {
            parseJson<List<InitialMedia>>(initialMediaJson)
        } catch (e: Exception) {
            return false
        }

        data class FinalVideo(val quality: String?, val videoUrl: String?)

        initialMediaList.apmap { initialMedia ->
            val apiUrl = initialMedia.videoUrl?.let { fixUrl(it) } ?: return@apmap
            
            try {
                val finalVideoList = app.get(apiUrl).parsed<List<FinalVideo>>()
                
                finalVideoList.forEach { finalVideo ->
                    val videoUrl = finalVideo.videoUrl
                    if (videoUrl != null) {
                        val qualityLabel = finalVideo.quality?.let { "${it}p" } ?: "Stream"
                        val qualityInt = finalVideo.quality?.toIntOrNull() ?: Qualities.Unknown.value
                        val formatLabel = initialMedia.format?.uppercase() ?: ""

                        callback(
                            ExtractorLink(
                                source = this.name,
                                // Cập nhật định dạng tên: Redtube + chất lượng + định dạng
                                name = "${this.name} $qualityLabel $formatLabel".trim(),
                                url = videoUrl,
                                referer = data,
                                quality = qualityInt,
                                type = if (initialMedia.format == "hls") ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                            )
                        )
                    }
                }
            } catch (e: Exception) {
                // Lỗi trong môi trường production nên sẽ được bỏ qua nhẹ nhàng
            }
        }

        return true
    }
}
