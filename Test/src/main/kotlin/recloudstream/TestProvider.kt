package com.lagradost.cloudstream3.providers

import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import org.jsoup.nodes.Element

/**
 * Provider để tương tác với YouPorn.
 * Lớp này chứa các hàm chính: getMainPage, search, và load.
 */
class YouPornProvider : MainAPI() {
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
    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse? {
        val url = "${request.data}$page"
        val document = app.get(url).document

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
        
        return document.select("div.searchResults div.video-box").mapNotNull {
            it.toSearchResult()
        }
    }

    /**
     * Hàm lấy thông tin chi tiết và các liên kết để phát video.
     */
    override suspend fun load(url: String): LoadResponse? {
        val document = app.get(url).document

        val title = document.selectFirst("h1.videoTitle")?.text()?.trim() ?: return null
        val poster = document.selectFirst("meta[property=og:image]")?.attr("content")
        val description = document.selectFirst("meta[name=description]")?.attr("content")
        val recommendations = document.select("div#relatedVideosWrapper div.video-box").mapNotNull { it.toSearchResult() }
        
        val mediaDefinitionRegex = """"mediaDefinition":(\[.*?\])""".toRegex()
        val mediaJson = mediaDefinitionRegex.find(document.html())?.groupValues?.get(1)

        return newMovieLoadResponse(title, url, TvType.NSFW, url) {
            this.posterUrl = poster
            this.plot = description
            this.recommendations = recommendations

            if (mediaJson != null) {
                try {
                    parseJson<List<MediaDefinition>>(mediaJson).forEach { source ->
                        val videoUrl = source.videoUrl ?: return@forEach
                        val quality = source.height ?: 0

                        // *** ĐÃ SỬA ĐỔI THEO YÊU CẦU CỦA BẠN ***
                        // Xác định loại link (HLS hay MP4) bằng ExtractorLinkType
                        val type = if (source.format == "hls") {
                            ExtractorLinkType.M3U8
                        } else {
                            ExtractorLinkType.VIDEO
                        }

                        addExtractor(
                            ExtractorLink(
                                source = this@YouPornProvider.name,
                                name = this@YouPornProvider.name,
                                url = videoUrl,
                                referer = mainUrl,
                                quality = quality,
                                type = type // Sử dụng enum thay cho isM3u8
                            )
                        )
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
        }
    }

    /**
     * Hàm tiện ích để chuyển đổi một khối HTML thành đối tượng SearchResponse.
     */
    private fun Element.toSearchResult(): SearchResponse? {
        val href = this.selectFirst("a.video-box-image")?.attr("href") ?: return null
        val videoUrl = fixUrl(href)

        val title = this.selectFirst("a.video-title-text")?.text()?.trim() ?: return null
        val posterUrl = this.selectFirst("img.thumb-image")?.attr("data-src")
        
        return newMovieSearchResponse(name = title, url = videoUrl, tvType = TvType.NSFW) {
            this.posterUrl = fixUrlNull(posterUrl)
        }
    }
}
