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

    // Header chung để giả dạng trình duyệt
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
     * Hàm `loadLinks` được viết lại để tìm các khối JavaScript cụ thể trong trang watch.
     */
    override suspend fun loadLinks(
        dataUrl: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        sendDebugCallback(callback, "1. Starting analysis on watch page...")
        
        val correctedDataUrl = if (dataUrl.contains("/watch/") && !dataUrl.endsWith("/")) "$dataUrl/" else dataUrl

        val watchHtmlContent = try {
            app.get(correctedDataUrl, referer = mainUrl, headers = browserHeaders).text
        } catch (e: Exception) {
            sendDebugCallback(callback, "2. FAILED to fetch watch page: ${e.message}")
            return true
        }
        sendDebugCallback(callback, "2. OK, fetched watch page content.")

        var foundData = false

        // Cách 1: Tìm `page_params.generalVideoConfig`
        sendDebugCallback(callback, "3. Trying to find 'generalVideoConfig'...")
        val generalConfigRegex = """page_params\.generalVideoConfig\s*=\s*(\{.+?\});""".toRegex()
        var match = generalConfigRegex.find(watchHtmlContent)
        if (match != null) {
            val configJson = match.groupValues[1]
            sendDebugCallback(callback, "3.1 SUCCESS, Found 'generalVideoConfig':")
            sendDebugCallback(callback, configJson.take(300))
            foundData = true
        } else {
            sendDebugCallback(callback, "3.1 FAILED to find 'generalVideoConfig'")
        }
        
        // Cách 2: Tìm `page_params.video_player_setup`
        sendDebugCallback(callback, "4. Trying to find 'video_player_setup'...")
        val playerSetupRegex = """page_params\.video_player_setup\s*=\s*(\{.+?\});""".toRegex()
        match = playerSetupRegex.find(watchHtmlContent)
        if (match != null) {
            val setupJson = match.groupValues[1]
            sendDebugCallback(callback, "4.1 SUCCESS, Found 'video_player_setup':")
            sendDebugCallback(callback, setupJson.take(300))
            foundData = true
        } else {
            sendDebugCallback(callback, "4.1 FAILED to find 'video_player_setup'")
        }

        if (!foundData) {
            sendDebugCallback(callback, "5. ALL METHODS FAILED.")
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
