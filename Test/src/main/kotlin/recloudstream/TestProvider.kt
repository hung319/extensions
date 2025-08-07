package recloudstream

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import org.jsoup.nodes.Element
import java.net.URLDecoder

class RedtubeProvider : MainAPI() {
    override var mainUrl = "https://www.redtube.com"
    override var name = "Redtube"
    override val hasMainPage = true
    override var lang = "en"
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(
        TvType.NSFW
    )

    // Hàm bóc tách dữ liệu từ một item video
    private fun Element.toSearchResponse(): MovieSearchResponse? {
        val linkElement = this.selectFirst("a.video_title_link") ?: return null
        val title = linkElement.attr("title")
        val href = fixUrl(linkElement.attr("href"))
        val posterUrl = this.selectFirst("img.video_thumb_img")?.attr("data-src")

        // Thời lượng bị bỏ qua hoàn toàn ở màn hình này
        return newMovieSearchResponse(title, href, TvType.Movie) {
            this.posterUrl = posterUrl
        }
    }
    
    // --- TRANG CHỦ ---
    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {
        val document = app.get(mainUrl).document
        val allPages = ArrayList<HomePageList>()

        document.select("div.section_title_wrapper").forEach { section ->
            val title = section.selectFirst("h2.section_title")?.text()?.trim() ?: "Unknown Category"
            val videoContainer = section.nextElementSibling()
            val videos = videoContainer?.select("div.video_item_container")?.mapNotNull {
                it.toSearchResponse()
            }
            if (!videos.isNullOrEmpty()) {
                allPages.add(HomePageList(title, videos))
            }
        }

        return HomePageResponse(allPages)
    }

    // --- TÌM KIẾM ---
    override suspend fun search(query: String): List<SearchResponse> {
        val searchUrl = "$mainUrl/redtube/$query"
        val document = app.get(searchUrl).document
        return document.select("div.video_item_container").mapNotNull {
            it.toSearchResponse()
        }
    }

    // --- TẢI THÔNG TIN CHI TIẾT VIDEO ---
    override suspend fun load(url: String): LoadResponse? {
        val document = app.get(url).document
        val title = document.selectFirst("h1.video_title")?.text()?.trim()
            ?: document.selectFirst("title")?.text()?.trim()
            ?: "Untitled"
        val poster = document.selectFirst("meta[property='og:image']")?.attr("content")
        // Mô tả trên trang web thường có chứa thời lượng, nên người dùng vẫn xem được ở đây
        val description = document.selectFirst("div.video_description_text")?.text()?.trim()
        val recommendations = document.select("div.video_item_container_related").mapNotNull {
            it.toSearchResponse()
        }

        return newMovieLoadResponse(title, url, TvType.Movie, url) {
            this.posterUrl = poster
            this.plot = description
            this.recommendations = recommendations
        }
    }

    // --- TẢI LINK STREAM ---
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val response = app.get(data)
        val flashvarsRegex = Regex("""mediaDefinition:\s*'([^']+)""")
        val encodedMediaDef = flashvarsRegex.find(response.text)?.groupValues?.get(1) ?: return false
        val decodedJson = URLDecoder.decode(encodedMediaDef, "UTF-8")
        
        data class MediaQuality(
            val quality: String,
            val videoUrl: String
        )
        
        val qualities = try {
            parseJson<List<MediaQuality>>(decodedJson)
        } catch (e: Exception) {
            return false
        }

        qualities.forEach { media ->
            val videoUrl = media.videoUrl
            if (videoUrl.isNotBlank()) {
                if (videoUrl.contains(".m3u8")) {
                    M3u8Helper.generateM3u8(
                        this.name,
                        videoUrl,
                        mainUrl
                    ).forEach(callback)
                } else {
                    callback(
                        ExtractorLink(
                            source = this.name,
                            name = "${this.name} - ${media.quality}p",
                            url = videoUrl,
                            referer = mainUrl,
                            quality = media.quality.toIntOrNull() ?: Qualities.Unknown.value,
                            type = ExtractorLinkType.VIDEO
                        )
                    )
                }
            }
        }

        return true
    }
}
