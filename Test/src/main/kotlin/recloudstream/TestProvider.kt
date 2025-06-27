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
    private data class Flashvars(
        @JsonProperty("mediaDefinitions") val mediaDefinitions: List<InitialMedia>?
    )
    private data class PlayerVars(
        @JsonProperty("mediaDefinitions") val mediaDefinitions: List<InitialMedia>?
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
    
    override suspend fun loadLinks(
        dataUrl: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val document = app.get(dataUrl).document
        val scriptContent = document.select("script").joinToString { it.data() }
        var intermediateApiUrl: String? = null

        sendDebugCallback(callback, "Starting extractor...")

        // CÁCH 1: Tìm `flashvars_...`
        sendDebugCallback(callback, "1. Trying 'flashvars'")
        val flashvarsRegex = """var\s*flashvars_\d+\s*=\s*(\{.+?\});""".toRegex()
        flashvarsRegex.find(scriptContent)?.groupValues?.get(1)?.let { flashvarsJson ->
            sendDebugCallback(callback, "1. OK, 'flashvars' found")
            intermediateApiUrl = parseJson<Flashvars>(flashvarsJson).mediaDefinitions?.firstOrNull()?.videoUrl
        }

        // CÁCH 2: Tìm `mediaDefinition`
        if (intermediateApiUrl == null) {
            sendDebugCallback(callback, "2. Trying 'mediaDefinition'")
            val mediaDefRegex = """"mediaDefinition"\s*:\s*(\[.+?\])""".toRegex()
            mediaDefRegex.find(scriptContent)?.groupValues?.get(1)?.let { mediaJson ->
                sendDebugCallback(callback, "2. OK, 'mediaDefinition' found")
                intermediateApiUrl = parseJson<List<InitialMedia>>(mediaJson).firstOrNull()?.videoUrl
            }
        }
        
        // CÁCH 3: Tìm `playervars`
        if (intermediateApiUrl == null) {
            sendDebugCallback(callback, "3. Trying 'playervars'")
            val playerVarsRegex = """"playervars":\s*(\{.*?\})""".toRegex()
            playerVarsRegex.find(scriptContent)?.groupValues?.get(1)?.let { playerVarsJson ->
                sendDebugCallback(callback, "3. OK, 'playervars' found")
                intermediateApiUrl = parseJson<PlayerVars>(playerVarsJson).mediaDefinitions?.firstOrNull()?.videoUrl
            }
        }

        // Nếu một trong các cách trên tìm được URL API, hãy xử lý nó
        if (intermediateApiUrl != null) {
            sendDebugCallback(callback, "API URL Found: ${intermediateApiUrl!!.take(100)}...")
            try {
                val streamApiResponse = app.get(intermediateApiUrl!!, referer = dataUrl).text
                sendDebugCallback(callback, "API Call OK. Response: ${streamApiResponse.take(100)}...")
                parseJson<List<FinalStreamInfo>>(streamApiResponse).forEach { streamInfo ->
                    streamInfo.videoUrl?.let { M3u8Helper.generateM3u8(name, it, mainUrl).forEach(callback) }
                }
            } catch (e: Exception) {
                sendDebugCallback(callback, "API Call FAILED: ${e.message}")
            }
        } else {
             // CÁCH 4: Tìm trực tiếp link M3U8
            sendDebugCallback(callback, "No API URL. Trying direct M3U8 search")
            val m3u8Regex = """(https?://[^\s"'<>]+\.m3u8[^\s"'<>]*?)""".toRegex()
            val m3u8Links = m3u8Regex.findAll(document.html()).map { it.value }.toList()
            if (m3u8Links.isNotEmpty()) {
                sendDebugCallback(callback, "Found ${m3u8Links.size} direct M3U8 links.")
                m3u8Links.forEach { M3u8Helper.generateM3u8(name, it, mainUrl).forEach(callback) }
            } else {
                sendDebugCallback(callback, "All methods FAILED.")
            }
        }

        return true // Luôn trả về true để hiển thị log
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val href = this.selectFirst("a.video-box-image")?.attr("href") ?: return null
        val videoUrl = fixUrl(href)
        val title = this.selectFirst("a.video-title-text")?.text()?.trim() ?: return null
        val posterUrl = this.selectFirst("img.thumb-image")?.attr("data-src")
        return MovieSearchResponse(name, videoUrl, this@YouPornProvider.name, TvType.NSFW, fixUrlNull(posterUrl), null)
    }
}
