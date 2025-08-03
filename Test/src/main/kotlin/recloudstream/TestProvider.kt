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
    override val hasMainPage = true
    override var supportedTypes = setOf(TvType.NSFW)

    // Đã cập nhật selector để tìm các video
    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {
        val document = app.get("$mainUrl/?page=$page").document
        // Thay đổi selector từ "div.video_bloc" thành "div.video_item_wrapper"
        val home = document.select("div.video_item_wrapper").mapNotNull {
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

    // Cập nhật lại toàn bộ logic trích xuất thông tin cho mỗi video
    private fun Element.toSearchResult(): SearchResponse? {
        // Selector mới cho link và tiêu đề
        val linkElement = this.selectFirst("a.video_link") ?: return null
        val href = fixUrl(linkElement.attr("href"))
        // Selector mới cho ảnh thumbnail, ưu tiên lấy ảnh chất lượng cao từ 'data-thumb_url'
        val posterUrl = this.selectFirst("img.video_thumb")?.attr("data-thumb_url")
        val title = this.selectFirst("span.video_title")?.text() ?: return null


        return newMovieSearchResponse(title, href, TvType.Movie) {
            this.posterUrl = posterUrl
        }
    }

    // Đã cập nhật selector để tìm kiếm
    override suspend fun search(query: String): List<SearchResponse> {
        val searchResponse = app.get("$mainUrl/?search=$query").document
        // Thay đổi selector từ "div.video_bloc" thành "div.video_item_wrapper"
        return searchResponse.select("div.video_item_wrapper").mapNotNull {
            it.toSearchResult()
        }
    }

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

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val document = app.get(data).document
        val script = document.select("script").find { it.data().contains("mediaDefinition") }?.data()
            ?: return false

        // Logic trích xuất link này có vẻ vẫn ổn, giữ nguyên
        val mediaDefinitionJson = script.substringAfter("mediaDefinition: [").substringBefore("]")
        // Sửa lỗi parseJson nếu nó không nhận được một mảng JSON hợp lệ
        val validJson = if (mediaDefinitionJson.endsWith(",")) mediaDefinitionJson.dropLast(1) else mediaDefinitionJson
        val sources = parseJson<List<VideoSource>>("[$validJson]")

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
