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
    
    // *** THAY ĐỔI QUAN TRỌNG: Tạo một bộ header chung để giả dạng trình duyệt ***
    private val browserHeaders = mapOf(
        "User-Agent" to "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/137.0.0.0 Mobile Safari/537.36",
        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,image/apng,*/*;q=0.8,application/signed-exchange;v=b3;q=0.7",
        "Accept-Language" to "vi",
        "sec-ch-ua" to "\"Chromium\";v=\"137\", \"Not/A)Brand\";v=\"24\"",
        "sec-ch-ua-mobile" to "?1",
        "sec-ch-ua-platform" to "\"Android\"",
        // Cookie được giữ ở mức tối giản để đảm bảo tính ổn định, platform=mobile là quan trọng nhất
        "Cookie" to "platform=mobile; access=1"
    )

    override val mainPage = mainPageOf(
        "/?page=" to "Recommended",
        "/top_rated/?page=" to "Top Rated",
        "/most_viewed/?page=" to "Most Viewed",
        "/most_favorited/?page=" to "Most Favorited",
        "/browse/time/?page=" to "Newest Videos"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = "$mainUrl${request.data}$page"
        // Sử dụng header mới
        val document = app.get(url, headers = browserHeaders).document
        val items = document.select("div.video-box").mapNotNull { it.toSearchResult() }
        val hasNext = document.selectFirst("div.paginationWrapper a[rel=next]") != null
        return newHomePageResponse(request.name, items, hasNext = hasNext)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val url = "$mainUrl/search/?query=$query"
        // Sử dụng header mới
        val document = app.get(url, headers = browserHeaders).document
        return document.select("div.searchResults div.video-box").mapNotNull { it.toSearchResult() }
    }

    override suspend fun load(url: String): LoadResponse {
        val document = app.get(url, headers = browserHeaders).document
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

    override suspend fun loadLinks(
        dataUrl: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        // Đảm bảo URL có dấu "/" ở cuối
        val correctedDataUrl = if (dataUrl.contains("/watch/") && !dataUrl.endsWith("/")) "$dataUrl/" else dataUrl
        val videoId = """/watch/(\d+)/""".toRegex().find(correctedDataUrl)?.groupValues?.get(1) ?: return false
        val embedUrl = "$mainUrl/embed/$videoId/"
        
        // Bước 1: Tải trang embed với header đã được thêm vào
        val embedHtmlContent = app.get(embedUrl, referer = correctedDataUrl, headers = browserHeaders).text
        var intermediateApiUrl: String? = null

        // Bước 2: Trích xuất JSON chứa `mediaDefinition`
        val mediaJson = extractJsonArray(embedHtmlContent, "\"mediaDefinition\"") ?: return false

        // Bước 3: Parse JSON để lấy URL API trung gian
        intermediateApiUrl = try {
            parseJson<List<InitialMedia>>(mediaJson).firstOrNull { it.videoUrl?.contains("/hls/") == true }?.videoUrl
        } catch (e: Exception) {
            null
        } ?: return false
        
        // Bước 4: Dọn dẹp URL và gọi API để lấy link cuối cùng
        val correctedApiUrl = intermediateApiUrl.replace("\\/", "/")
        
        return try {
            // Sử dụng header mới cho cả cuộc gọi API
            val streamApiResponse = app.get(correctedApiUrl, referer = embedUrl, headers = browserHeaders).text
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
        var videoUrl = fixUrl(href)
        if (videoUrl.contains("/watch/") && !videoUrl.endsWith("/")) {
            videoUrl += "/"
        }
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
