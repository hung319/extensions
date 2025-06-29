package com.lagradost.cloudstream3.providers

import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.M3u8Helper
import org.jsoup.nodes.Element

class YouPornProvider : MainAPI() {
    override var name = "YouPorn"
    override var mainUrl = "https://www.youporn.com"
    override var supportedTypes = setOf(TvType.NSFW)
    override val hasMainPage = true

    // --- Data Classes ---
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
        return null
    }

    /**
     * Hàm `loadLinks` cuối cùng, với logic thêm header để giả dạng trình duyệt.
     */
    override suspend fun loadLinks(
        dataUrl: String, // Đây là link /watch/
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        // Bước 1: Lấy ID và xây dựng URL của trang embed
        val videoId = """/watch/(\d+)/""".toRegex().find(dataUrl)?.groupValues?.get(1) ?: return false
        val embedUrl = "$mainUrl/embed/$videoId/"
        
        // *** THAY ĐỔI QUAN TRỌNG: Thêm các header để giả dạng trình duyệt mobile ***
        val headers = mapOf(
            "User-Agent" to "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/137.0.0.0 Mobile Safari/537.36",
            "Cookie" to "platform=mobile"
        )
        
        // Bước 2: Tải trang embed với header đã được thêm vào
        val embedHtmlContent = app.get(embedUrl, referer = dataUrl, headers = headers).text
        var intermediateApiUrl: String? = null

        // Bước 3: Trích xuất JSON chứa `mediaDefinition`
        val mediaJson = extractJsonArray(embedHtmlContent, "\"mediaDefinition\"") ?: return false

        // Bước 4: Parse JSON để lấy URL API trung gian
        intermediateApiUrl = try {
            parseJson<List<InitialMedia>>(mediaJson).firstOrNull { it.videoUrl?.contains("/hls/") == true }?.videoUrl
        } catch (e: Exception) {
            null
        } ?: return false
        
        // Bước 5: Dọn dẹp URL và gọi API để lấy link cuối cùng
        val correctedApiUrl = intermediateApiUrl.replace("\\/", "/")
        
        return try {
            val streamApiResponse = app.get(correctedApiUrl, referer = embedUrl, headers = headers).text
            var foundLinks = false
            parseJson<List<FinalStreamInfo>>(streamApiResponse).forEach { streamInfo ->
                val finalStreamUrl = streamInfo.videoUrl ?: return@forEach
                val qualityInt = streamInfo.quality?.toIntOrNull()
                callback(
                    ExtractorLink(
                        source = this.name,
                        name = "${this.name} ${streamInfo.quality}p",
                        url = finalStreamUrl,
                        referer = mainUrl,
                        quality = qualityInt ?: 0,
                        type = ExtractorLinkType.M3U8
                    )
                )
                foundLinks = true
            }
            foundLinks
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
