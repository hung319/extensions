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
    
    // Header chung để giả dạng trình duyệt
    private val browserHeaders = mapOf(
        "User-Agent" to "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/137.0.0.0 Mobile Safari/537.36",
        "Cookie" to "platform=mobile; access=1"
    )

    // Hàm tiện ích để gửi log debug
    private fun sendDebugCallback(callback: (ExtractorLink) -> Unit, message: String) {
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
    
    /**
     * Hàm `loadLinks` được viết lại để tìm trực tiếp chuỗi URL chứa 'media/hls/'
     */
    override suspend fun loadLinks(
        dataUrl: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        sendDebugCallback(callback, "1. Starting extractor...")

        val videoId = """/watch/(\d+)/""".toRegex().find(dataUrl)?.groupValues?.get(1)
        if (videoId == null) {
            sendDebugCallback(callback, "1.1 FAILED to get videoId from URL: $dataUrl")
            return true
        }
        sendDebugCallback(callback, "1.1 OK, videoId is: $videoId")
        
        val embedUrl = "$mainUrl/embed/$videoId/"
        sendDebugCallback(callback, "2. Built embed URL: $embedUrl")
        
        val embedHtmlContent = try {
            app.get(embedUrl, referer = dataUrl, headers = browserHeaders).text
        } catch (e: Exception) {
            sendDebugCallback(callback, "2.1 FAILED to fetch embed page: ${e.message}")
            return true
        }
        sendDebugCallback(callback, "2.1 OK, fetched embed page content. Searching for URL...")

        // *** THAY ĐỔI CHÍNH: Dùng Regex để tìm trực tiếp URL chứa /media/hls/ ***
        val apiRegex = """"(https://www\.youporn\.com/media/hls/\?s=[^"]+)"""".toRegex()
        val match = apiRegex.find(embedHtmlContent)
        
        if (match != null) {
            val foundUrl = match.groupValues[1]
            sendDebugCallback(callback, "3. SUCCESS, Found URL:")
            sendDebugCallback(callback, foundUrl.take(300))
        } else {
            sendDebugCallback(callback, "3. FAILED to find any URL containing '/media/hls/'")
        }
        
        return true // Luôn trả về true để hiển thị log
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
