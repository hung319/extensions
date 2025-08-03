package recloudstream

import com.fasterxml.jackson.annotation.JsonProperty
import org.jsoup.nodes.Element
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.utils.AppUtils.parseJson

class Redtube : MainAPI() {
    override var name = "Redtube"
    override var mainUrl = "https://www.redtube.com"
    override var lang = "en"
    // Thêm hasMainPage
    override val hasMainPage = true
    override var supportedTypes = setOf(TvType.NSFW)

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {
        val document = app.get("$mainUrl/?page=$page").document
        val home = document.select("div.video_bloc").mapNotNull {
            it.toSearchResult()
        }
        return newHomePageResponse(
            list = HomePageList(
                name = "Latest Videos",
                list = home
            ),
            hasNext = true
        )
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val title = this.selectFirst("a.video_title")?.text() ?: return null
        val href = fixUrl(this.selectFirst("a")?.attr("href") ?: return null)
        val posterUrl = this.selectFirst("img.video_thumb")?.attr("data-src")

        return newMovieSearchResponse(title, href, TvType.Movie) {
            this.posterUrl = posterUrl
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val searchResponse = app.get("$mainUrl/?search=$query").document
        return searchResponse.select("div.video_bloc").mapNotNull {
            it.toSearchResult()
        }
    }

    // Hàm load bây giờ chỉ lấy thông tin cơ bản của phim
    override suspend fun load(url: String): LoadResponse? {
        val document = app.get(url).document
        val title = document.selectFirst("h1.video_title")?.text()?.trim() ?: return null
        val poster = document.selectFirst("meta[property=og:image]")?.attr("content")
        val plot = document.selectFirst("meta[property=og:description]")?.attr("content")

        return newMovieLoadResponse(title, url, TvType.Movie, url) {
            this.posterUrl = poster
            this.plot = plot
        }
    }

    // Logic trích xuất link được chuyển vào hàm loadLinks
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val document = app.get(data).document
        val script = document.select("script").find { it.data().contains("mediaDefinition") }?.data()
            ?: return false

        val mediaDefinitionJson = script.substringAfter("mediaDefinition: [").substringBefore("]")
        val sources = parseJson<List<VideoSource>>(mediaDefinitionJson)

        sources.forEach { source ->
            val quality = source.quality
            val videoUrl = source.videoUrl
            if (quality != null && videoUrl != null) {
                callback(
                    ExtractorLink(
                        name,
                        "$name $quality",
                        videoUrl,
                        referer = mainUrl,
                        quality = quality.toIntOrNull() ?: 0,
                        // Sử dụng ExtractorLinkType.M3U8 thay cho isM3u8
                        type = if (videoUrl.contains(".m3u8")) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO,
                    )
                )
            }
        }
        return true
    }

    data class VideoSource(
        @JsonProperty("quality") val quality: String?,
        @JsonProperty("videoUrl") val videoUrl: String?
    )
}
