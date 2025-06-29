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

    // --- Data Class để parse JSON response cuối cùng ---
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
     * Hàm `loadLinks` được viết lại hoàn toàn để thêm log chi tiết.
     */
    override suspend fun loadLinks(
        dataUrl: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val document = app.get(dataUrl).document
        val htmlContent = document.html()

        // Bước 1: Dùng Regex để tìm URL API trung gian
        val apiRegex = """"(https://www\.youporn\.com/media/hls/\?s=[^"]+)"""".toRegex()
        val intermediateApiUrl = apiRegex.find(htmlContent)?.groupValues?.get(1)

        if (intermediateApiUrl == null) {
            sendDebugCallback(callback, "Regex FAILED to find API URL in HTML")
            return true // Trả về true để hiển thị log
        }
        
        // Log 1: Hiển thị URL gốc lấy từ HTML
        sendDebugCallback(callback, "1. Raw URL from HTML: ${intermediateApiUrl.take(150)}...")
        
        // Sửa lỗi các ký tự `\/`
        val correctedApiUrl = intermediateApiUrl.replace("\\/", "/")
        
        // Log 2: Hiển thị URL đã được sửa lỗi
        sendDebugCallback(callback, "2. Corrected URL: ${correctedApiUrl.take(150)}...")

        // Bước 2: Gọi đến URL API đã được sửa lỗi
        try {
            val streamApiResponse = app.get(correctedApiUrl, referer = dataUrl).text
            
            // Log 3: Hiển thị response từ API
            sendDebugCallback(callback, "3. API Response OK: ${streamApiResponse.take(150)}...")
            
            var foundLinks = false
            // Bước 3: Parse JSON response và tạo link
            parseJson<List<FinalStreamInfo>>(streamApiResponse).forEach { streamInfo ->
                val finalStreamUrl = streamInfo.videoUrl ?: return@forEach
                val quality = streamInfo.quality
                
                M3u8Helper.generateM3u8(
                    name = "${this.name} ${quality}p",
                    streamUrl = finalStreamUrl,
                    referer = mainUrl,
                    source = this.name
                ).forEach { link ->
                    callback(link)
                    foundLinks = true
                }
            }
            if (!foundLinks) {
                sendDebugCallback(callback, "4. JSON Parsed but no links were generated")
            }
        } catch (e: Exception) {
            e.printStackTrace()
            // Log 4: Hiển thị lỗi nếu gọi API thất bại
            sendDebugCallback(callback, "3. API Call FAILED: ${e.message}")
        }
        
        return true // Luôn trả về true để đảm bảo tất cả các log được hiển thị
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
