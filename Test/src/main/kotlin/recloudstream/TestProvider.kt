package com.lagradost.cloudstream3.providers // Thêm package ở đầu file

import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import com.lagradost.cloudstream3.utils.ExtractorLink
import org.jsoup.nodes.Element

/**
 * Provider để tương tác với YouPorn.
 * Lớp này chứa các hàm chính: mainPage, loadPage, search, và load.
 */
class YouPornProvider : MainAPI() {
    // Thông tin cơ bản của provider
    override var name = "YouPorn"
    override var mainUrl = "https://www.youporn.com"
    override var supportedTypes = setOf(TvType.NSFW)
    override val hasMainPage = true

    // Dữ liệu từ JSON để lấy link video
    private data class MediaDefinition(
        @JsonProperty("videoUrl") val videoUrl: String,
        @JsonProperty("height") val height: Int,
        @JsonProperty("format") val format: String,
    )

    /**
     * Hàm định nghĩa các mục trên trang chủ của plugin.
     * CloudStream sẽ gọi hàm `loadPage` tương ứng khi người dùng chọn một mục.
     */
    override val mainPage = mainPageOf(
        "$mainUrl/?page=" to "Recommended",
        "$mainUrl/top_rated/?page=" to "Top Rated",
        "$mainUrl/most_viewed/?page=" to "Most Viewed",
        "$mainUrl/most_favorited/?page=" to "Most Favorited",
        "$mainUrl/browse/time/?page=" to "Newest Videos"
    )

    /**
     * Hàm tải danh sách video cho các mục trên trang chủ.
     */
    override suspend fun loadPage(page: HomePageList): HomePageResponse {
        val document = app.get(page.data).document
        
        // Phân tích các thẻ video và chuyển đổi chúng thành kết quả có thể hiển thị
        val items = document.select("div.video-box").mapNotNull {
            it.toSearchResult()
        }
        
        return newHomePageResponse(
            page.name,
            items,
            hasNext = document.selectFirst("div.paginationWrapper li.next") != null
        )
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
     * Hàm lấy thông tin chi tiết và các liên kết để phát video.
     * Đây là nơi xử lý `load+loadlinks.html`.
     */
    override suspend fun load(url: String): LoadResponse? {
        val document = app.get(url).document

        // Trích xuất các thông tin cơ bản
        val title = document.selectFirst("h1.videoTitle")?.text()?.trim() ?: return null
        val poster = document.selectFirst("meta[property=og:image]")?.attr("content")
        val description = document.selectFirst("meta[name=description]")?.attr("content")
        val recommendations = document.select("div#relatedVideosWrapper div.video-box").mapNotNull { it.toSearchResult() }
        
        // Regex để tìm đoạn JSON chứa link video trong mã nguồn trang
        val mediaDefinitionRegex = """"mediaDefinition":(\[.*?\])""".toRegex()
        val mediaJson = mediaDefinitionRegex.find(document.html())?.groupValues?.get(1)

        val extractorLinks = arrayListOf<ExtractorLink>()
        
        if (mediaJson != null) {
            try {
                // Parse chuỗi JSON và lặp qua các nguồn video
                parseJson<List<MediaDefinition>>(mediaJson).forEach { source ->
                    extractorLinks.add(
                        ExtractorLink(
                            name = "YouPorn - ${source.height}p",
                            url = source.videoUrl,
                            referer = "$mainUrl/",
                            quality = source.height,
                            isM3u8 = source.format == "hls"
                        )
                    )
                }
            } catch (e: Exception) {
                // Ghi log nếu có lỗi xảy ra
                logError(e)
            }
        }
        
        return newMovieLoadResponse(title, url, TvType.NSFW, url) {
            this.posterUrl = poster
            this.plot = description
            this.recommendations = recommendations
            // Thêm các link đã trích xuất để CloudStream có thể phát
            addLinks(extractorLinks.sortedBy { -it.quality }) // Sắp xếp chất lượng từ cao đến thấp
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

        return newMovieSearchResponse(name = title, url = videoUrl, TvType.NSFW, fixUrlNull(posterUrl)) {
            val durationText = this@toSearchResult.selectFirst("div.video-duration")?.text()?.trim()
            if (durationText != null) {
                this.duration = durationText.toMinutes()
            }
        }
    }
    
    /**
     * Hàm tiện ích để chuyển đổi chuỗi thời gian (vd: "19:33") thành phút.
     */
    private fun String.toMinutes(): Int {
        return this.split(':').let { parts ->
            when (parts.size) {
                2 -> (parts[0].toIntOrNull() ?: 0) * 60 + (parts[1].toIntOrNull() ?: 0)
                1 -> parts[0].toIntOrNull() ?: 0
                else -> 0
            }
        } / 60
    }
}
