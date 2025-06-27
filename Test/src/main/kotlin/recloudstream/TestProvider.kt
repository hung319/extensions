package com.lagradost.cloudstream3.providers

import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import com.lagradost.cloudstream3.utils.ExtractorLink
import org.jsoup.nodes.Element

/**
 * Provider để tương tác với YouPorn.
 * Được cập nhật để sửa lỗi biên dịch theo API mới nhất.
 */
class YouPornProvider : MainAPI() {
    // Thông tin cơ bản của provider
    override var name = "YouPorn"
    override var mainUrl = "https://www.youporn.com"
    override var supportedTypes = setOf(TvType.NSFW)
    override val hasMainPage = true

    // Dữ liệu từ JSON để lấy link video
    private data class MediaDefinition(
        @JsonProperty("videoUrl") val videoUrl: String?,
        @JsonProperty("height") val height: Int?,
        @JsonProperty("format") val format: String?,
    )

    /**
     * Hàm định nghĩa các mục trên trang chủ của plugin.
     */
    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = "${request.data}$page"
        val document = app.get(url).document

        // Phân tích các thẻ video và chuyển đổi chúng thành kết quả có thể hiển thị
        val items = document.select("div.video-box").mapNotNull {
            it.toSearchResult()
        }
        
        return newHomePageResponse(request.name, items, hasNext = true)
    }

    /**
     * Hàm tìm kiếm video dựa trên từ khóa.
     */
    override suspend fun search(query: String): List<SearchResponse> {
        val url = "$mainUrl/search/?query=$query"
        val document = app.get(url).document
        
        // Phân tích các thẻ video từ trang kết quả tìm kiếm
        return document.select("div.searchResults div.video-box").mapNotNull {
            it.toSearchResult()
        }
    }

    /**
     * Hàm này chỉ tải siêu dữ liệu (metadata) của video.
     */
    override suspend fun load(url: String): LoadResponse? {
        val document = app.get(url).document

        val title = document.selectFirst("h1.videoTitle")?.text()?.trim() ?: return null
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

        // Regex để tìm đoạn JSON chứa link video trong mã nguồn trang
        val mediaDefinitionRegex = """"mediaDefinition":(\[.*?\])""".toRegex()
        val mediaJson = mediaDefinitionRegex.find(document.html())?.groupValues?.get(1)
        
        var foundLinks = false
        if (mediaJson != null) {
            try {
                // Parse chuỗi JSON và lặp qua các nguồn video
                parseJson<List<MediaDefinition>>(mediaJson).forEach { source ->
                    val videoUrl = source.videoUrl ?: return@forEach
                    val quality = source.height ?: 0

                    // Sử dụng callback để trả về link đã tìm thấy
                    callback(
                        ExtractorLink(
                            source = this.name,
                            name = this.name,
                            url = videoUrl,
                            referer = mainUrl,
                            quality = quality,
                            // isM3u8 đã được thay thế bằng 'type'
                            type = if (source.format == "hls") ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                        )
                    )
                    foundLinks = true
                }
            } catch (e: Exception) {
                // In ra lỗi nếu có vấn đề khi parse JSON
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
        
        // Cập nhật hàm tạo cho MovieSearchResponse theo API mới
        return newMovieSearchResponse(
            name = title,
            url = videoUrl,
            type = TvType.NSFW, // Loại nội dung
            posterUrl = fixUrlNull(posterUrl)
        )
    }
}
