package com.lagradost.cloudstream3.providers

import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.M3u8Helper
import org.jsoup.nodes.Element

class YouPornProvider : MainAPI() {
    override var name = "YouPorn"
    override var mainUrl = "https://www.youporn.com"
    override var supportedTypes = setOf(TvType.NSFW)
    override val hasMainPage = true

    // --- Data Classes để parse JSON ---
    private data class InitialMedia(
        @JsonProperty("videoUrl") val videoUrl: String?,
    )
    private data class FinalStreamInfo(
        @JsonProperty("videoUrl") val videoUrl: String?,
        @JsonProperty("quality") val quality: String?,
    )

    // --- Các hàm không thay đổi ---
    override val mainPage = mainPageOf(
        "/?page=" to "Recommended",
        "/top_rated/?page=" to "Top Rated",
        "/most_viewed/?page=" to "Most Viewed",
        "/most_favorited/?page=" to "Most Favorited",
        "/browse/time/?page=" to "Newest Videos"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = "$mainUrl${request.data}$page"
        val document = app.get(url).document
        val items = document.select("div.video-box").mapNotNull { it.toSearchResult() }
        val hasNext = document.selectFirst("div.paginationWrapper a[rel=next]") != null
        return newHomePageResponse(request.name, items, hasNext = hasNext)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val url = "$mainUrl/search/?query=$query"
        val document = app.get(url).document
        return document.select("div.searchResults div.video-box").mapNotNull { it.toSearchResult() }
    }

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
     * Hàm tiện ích dùng để trích xuất một mảng JSON một cách an toàn.
     */
    private fun extractJsonArray(htmlContent: String, key: String): String? {
        val keyIndex = htmlContent.indexOf(key)
        if (keyIndex == -1) return null

        val startIndex = htmlContent.indexOf('[', keyIndex)
        if (startIndex == -1) return null

        var bracketCount = 1
        for (i in (startIndex + 1) until htmlContent.length) {
            when (htmlContent[i]) {
                '[' -> bracketCount++
                ']' -> bracketCount--
            }
            if (bracketCount == 0) {
                return htmlContent.substring(startIndex, i + 1)
            }
        }
        return null // Không tìm thấy dấu ngoặc đóng tương ứng
    }

    /**
     * Hàm `loadLinks` được viết lại để sử dụng trang /embed/
     */
    override suspend fun loadLinks(
        dataUrl: String, // Đây là link /watch/
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        // Bước 1: Trích xuất ID video từ URL xem phim (/watch/...)
        val videoId = """/watch/(\d+)/""".toRegex().find(dataUrl)?.groupValues?.get(1) ?: return false

        // Bước 2: Xây dựng URL của trang embed
        val embedUrl = "$mainUrl/embed/$videoId/"

        // Bước 3: Tải trang embed và lấy mã nguồn HTML
        val embedHtmlContent = app.get(embedUrl, referer = dataUrl).text

        // Bước 4: Trích xuất JSON chứa `mediaDefinition` từ trang embed
        val mediaJson = extractJsonArray(embedHtmlContent, "\"mediaDefinition\"") ?: return false

        // Bước 5: Parse JSON để lấy URL API trung gian
        val intermediateApiUrl = try {
            parseJson<List<InitialMedia>>(mediaJson).firstOrNull { it.videoUrl?.contains("/hls/") == true }?.videoUrl
        } catch (e: Exception) {
            e.printStackTrace()
            null
        } ?: return false
        
        // Bước 6: Dọn dẹp URL và gọi API để lấy link cuối cùng
        val correctedApiUrl = intermediateApiUrl.replace("\\/", "/")

        return try {
            val streamApiResponse = app.get(correctedApiUrl, referer = embedUrl).text
            parseJson<List<FinalStreamInfo>>(streamApiResponse).mapNotNull { it.videoUrl }.apmap { streamUrl ->
                M3u8Helper.generateM3u8(
                    name,
                    streamUrl,
                    mainUrl
                ).forEach(callback)
            }.isNotEmpty()
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val href = this.selectFirst("a.video-box-image")?.attr("href") ?: return null
        val videoUrl = fixUrl(href)
        val title = this.selectFirst("a.video-title-text")?.text()?.trim() ?: return null
        val posterUrl = this.selectFirst("img.thumb-image")?.attr("data-src")
        
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
