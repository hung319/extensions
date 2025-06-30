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

    private val browserHeaders = mapOf(
        "User-Agent" to "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/137.0.0.0 Mobile Safari/537.36",
        "Cookie" to "platform=mobile; access=1;"
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
        val document = app.get(url, headers = browserHeaders).document
        val items = document.select("div.video-box").mapNotNull { it.toSearchResult() }
        val hasNext = document.selectFirst("div.paginationWrapper a[rel=next]") != null
        return newHomePageResponse(request.name, items, hasNext = hasNext)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val url = "$mainUrl/search/?query=$query"
        val document = app.get(url, headers = browserHeaders).document
        return document.select("div.searchResults div.video-box").mapNotNull { it.toSearchResult() }
    }

    override suspend fun load(url: String): LoadResponse {
        val correctedUrl = if (url.contains("/watch/") && !url.endsWith("/")) "$url/" else url
        val document = app.get(correctedUrl, headers = browserHeaders).document
        val title = document.selectFirst("h1.videoTitle")?.text()?.trim() ?: "Untitled"
        val poster = document.selectFirst("meta[property=og:image]")?.attr("content")
        val description = document.selectFirst("meta[name=description]")?.attr("content")
        val recommendations = document.select("div#relatedVideosWrapper div.video-box").mapNotNull { it.toSearchResult() }

        return newMovieLoadResponse(title, correctedUrl, TvType.NSFW, correctedUrl) {
            this.posterUrl = poster
            this.plot = description
            this.recommendations = recommendations
        }
    }
    
    // Hàm tiện ích để gửi log debug
    private fun sendDebugCallback(callback: (ExtractorLink) -> Unit, message: String) {
        // *** ĐÃ SỬA: Gọi ExtractorLink với đầy đủ tên tham số ***
        callback(
            ExtractorLink(
                source = this.name,
                name = "DEBUG: $message",
                url = "https://commondatastorage.googleapis.com/gtv-videos-bucket/sample/BigBuckBunny.mp4",
                referer = mainUrl,
                quality = 1,
                type = ExtractorLinkType.VIDEO
            )
        )
    }
    
    // Hàm tiện ích để trích xuất JSON
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
     * Hàm `loadLinks` được viết lại hoàn toàn để thêm log chi tiết.
     */
    override suspend fun loadLinks(
        dataUrl: String, // Đây là link /watch/
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        sendDebugCallback(callback, "1. Starting loadLinks function...")
        
        // Đảm bảo URL có dấu "/" ở cuối
        val correctedDataUrl = if (dataUrl.contains("/watch/") && !dataUrl.endsWith("/")) "$dataUrl/" else dataUrl
        
        val videoId = """/watch/(\d+)/""".toRegex().find(correctedDataUrl)?.groupValues?.get(1)
        if (videoId == null) {
            sendDebugCallback(callback, "1.1 FAILED to get videoId from URL: $correctedDataUrl")
            return true
        }
        sendDebugCallback(callback, "1.1 OK, videoId is: $videoId")
        
        val embedUrl = "$mainUrl/embed/$videoId/"
        sendDebugCallback(callback, "2. Built embed URL: $embedUrl")
        
        val embedHtmlContent = try {
            app.get(embedUrl, referer = correctedDataUrl, headers = browserHeaders).text
        } catch (e: Exception) {
            sendDebugCallback(callback, "2.1 FAILED to fetch embed page: ${e.message}")
            return true
        }
        sendDebugCallback(callback, "2.1 OK, fetched embed page content")

        sendDebugCallback(callback, "3. Trying to extract mediaDefinition JSON...")
        val mediaJson = extractJsonArray(embedHtmlContent, "\"mediaDefinition\"")
        if (mediaJson == null) {
             sendDebugCallback(callback, "3.1 FAILED to find mediaDefinition JSON in embed HTML")
             return true
        }
        sendDebugCallback(callback, "3.1 OK, Found JSON: ${mediaJson.take(100)}...")
        
        sendDebugCallback(callback, "4. Parsing JSON for intermediate API URL...")
        val intermediateApiUrl = try {
            parseJson<List<InitialMedia>>(mediaJson).firstOrNull { it.videoUrl?.contains("/hls/") == true }?.videoUrl
        } catch (e: Exception) {
            sendDebugCallback(callback, "4.1 FAILED to parse JSON: ${e.message}")
            return true
        }
        if (intermediateApiUrl == null) {
            sendDebugCallback(callback, "4.1 FAILED, no HLS videoUrl found in JSON")
            return true
        }
        sendDebugCallback(callback, "4.1 OK, API URL is: ${intermediateApiUrl.take(100)}...")

        val correctedApiUrl = intermediateApiUrl.replace("\\/", "/")
        sendDebugCallback(callback, "5. Corrected API URL: ${correctedApiUrl.take(100)}...")

        try {
            val streamApiResponse = app.get(correctedApiUrl, referer = embedUrl, headers = browserHeaders).text
            sendDebugCallback(callback, "6. API Call OK. Response: ${streamApiResponse.take(100)}...")
            
            val links = parseJson<List<FinalStreamInfo>>(streamApiResponse)
            sendDebugCallback(callback, "7. Parsing final links... Found ${links.size} qualities.")
            links.forEach { streamInfo ->
                streamInfo.videoUrl?.let { M3u8Helper.generateM3u8(name, it, mainUrl, source = this.name).forEach(callback) }
            }
        } catch (e: Exception) {
            sendDebugCallback(callback, "6. FAILED API Call: ${e.message}")
        }
        
        return true 
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
