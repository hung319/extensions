package com.lagradost.cloudstream3.providers

import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import org.jsoup.nodes.Element

/**
 * Provider để tương tác với YouPorn.
 * Được cập nhật để sử dụng đúng cấu trúc API mới nhất.
 */
class YouPornProvider : MainAPI() {
    override var name = "YouPorn"
    override var mainUrl = "https://www.youporn.com"
    override var supportedTypes = setOf(TvType.NSFW)
    override val hasMainPage = true

    private data class MediaDefinition(
        @JsonProperty("videoUrl") val videoUrl: String?,
        @JsonProperty("height") val height: Int?,
        @JsonProperty("format") val format: String?,
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        // Xử lý phân trang: trang 1 không có tham số ?page=
        val url = if (page > 1) "${request.data}$page" else request.data.replace("?page=", "")
        val document = app.get(url).document

        val items = document.select("div.video-box").mapNotNull {
            it.toSearchResult()
        }
        
        // Kiểm tra xem có nút "Next" hay không để xác định có trang tiếp theo
        val hasNext = document.selectFirst("div.paginationWrapper li.next a") != null
        return newHomePageResponse(request.name, items, hasNext = hasNext)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val url = "$mainUrl/search/?query=$query"
        val document = app.get(url).document
        
        return document.select("div.searchResults div.video-box").mapNotNull {
            it.toSearchResult()
        }
    }

    /**
     * Hàm này chỉ tải siêu dữ liệu (metadata) của video.
     */
    override suspend fun load(url: String): LoadResponse {
        val document = app.get(url).document

        val title = document.selectFirst("h1.videoTitle")?.text()?.trim() ?: "Untitled"
        val poster = document.selectFirst("meta[property=og:image]")?.attr("content")
        val description = document.selectFirst("meta[name=description]")?.attr("content")
        val recommendations = document.select("div#relatedVideosWrapper div.video-box").mapNotNull { it.toSearchResult() }

        return newMovieLoadResponse(title, url, TvType.NSFW, url) {
            this.posterUrl = poster
            this.plot = description
            this.recommendations = recommendations
        }
    }

    /**
     * Hàm này tải các liên kết (stream links) để phát video.
     */
    override suspend fun loadLinks(
        dataUrl: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val document = app.get(dataUrl).document

        val mediaDefinitionRegex = """"mediaDefinition":(\[.*?\])""".toRegex()
        val mediaJson = mediaDefinitionRegex.find(document.html())?.groupValues?.get(1)
        
        var foundLinks = false
        if (mediaJson != null) {
            try {
                parseJson<List<MediaDefinition>>(mediaJson).forEach { source ->
                    val videoUrl = source.videoUrl ?: return@forEach
                    val quality = source.height ?: 0

                    // *** ĐÃ CẬP NHẬT THEO CẤU TRÚC MỚI BẠN CUNG CẤP ***
                    callback(
                        ExtractorLink(
                            source = this.name,
                            name = this.name,
                            url = videoUrl,
                            referer = mainUrl,
                            quality = quality,
                            type = if (source.format == "hls") ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                        )
                    )
                    foundLinks = true
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
        return foundLinks
    }

    /**
     * Hàm tiện ích để chuyển đổi một khối HTML thành đối tượng SearchResponse.
     */
    private fun Element.toSearchResult(): SearchResponse? {
        val href = this.selectFirst("a.video-box-image")?.attr("href") ?: return null
        val videoUrl = fixUrl(href)

        val title = this.selectFirst("a.video-title-text")?.text()?.trim() ?: return null
        val posterUrl = this.selectFirst("img.thumb-image")?.attr("data-src")
        
        // Sửa lại cách gọi hàm tạo cho đúng với API
        return MovieSearchResponse(
            name = title,
            url = videoUrl,
            apiName = this@YouPornProvider.name,
            type = TvType.NSFW,
            posterUrl = fixUrlNull(posterUrl),
            year = null
        )
    }
}
