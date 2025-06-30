package com.lagradost.cloudstream3.providers

import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import org.jsoup.nodes.Element
import java.net.URLEncoder

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

    // Các hàm không thay đổi
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

    /**
     * *** CHỨC NĂNG DEBUG ĐẶC BIỆT ***
     * Hàm này sẽ tạo một link để sao chép mã nguồn HTML của trang mà CloudStream nhận được.
     */
    override suspend fun loadLinks(
        dataUrl: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        // Hàm tiện ích để gửi log debug
        fun sendDebugCallback(message: String) {
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

        val correctedDataUrl = if (dataUrl.contains("/watch/") && !dataUrl.endsWith("/")) "$dataUrl/" else dataUrl
        val videoId = """/watch/(\d+)/""".toRegex().find(correctedDataUrl)?.groupValues?.get(1)

        if (videoId == null) {
            sendDebugCallback("FAILED to get videoId from URL: $correctedDataUrl")
            return true
        }

        try {
            // Cố gắng tải cả trang watch và trang embed để debug
            val watchHtmlContent = app.get(correctedDataUrl, referer = mainUrl, headers = browserHeaders).text
            val encodedWatchHtml = URLEncoder.encode(watchHtmlContent, "UTF-8")
            callback(
                ExtractorLink(this.name, "DEBUG: Chạm để sao chép HTML trang WATCH", "clipboard://$encodedWatchHtml", mainUrl, 1, ExtractorLinkType.VIDEO)
            )

            val embedUrl = "$mainUrl/embed/$videoId/"
            val embedHtmlContent = app.get(embedUrl, referer = correctedDataUrl, headers = browserHeaders).text
            val encodedEmbedHtml = URLEncoder.encode(embedHtmlContent, "UTF-8")
            callback(
                ExtractorLink(this.name, "DEBUG: Chạm để sao chép HTML trang EMBED", "clipboard://$encodedEmbedHtml", mainUrl, 1, ExtractorLinkType.VIDEO)
            )

        } catch (e: Exception) {
            e.printStackTrace()
            sendDebugCallback("FAILED to fetch pages: ${e.message}")
        }
        
        return true // Luôn trả về true để link debug được hiển thị
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
