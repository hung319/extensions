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

    // Data class để parse JSON chứa link API trung gian
    private data class InitialMedia(
        @JsonProperty("videoUrl") val videoUrl: String?,
    )
    
    // Data class để parse JSON chứa link video cuối cùng
    private data class FinalStreamInfo(
        @JsonProperty("videoUrl") val videoUrl: String?,
        @JsonProperty("quality") val quality: String?,
    )

    private val browserHeaders = mapOf(
        "User-Agent" to "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/137.0.0.0 Mobile Safari/537.36",
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
        // Đảm bảo URL có dấu "/" ở cuối
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
     * Hàm `loadLinks` cuối cùng, với logic chuẩn xác nhất.
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
        
        // Bước 2: Tải trang embed với header chuẩn
        val embedHtmlContent = app.get(embedUrl, referer = dataUrl, headers = browserHeaders).text
        var intermediateApiUrl: String? = null

        // Bước 3: Dùng Regex để tìm TẤT CẢ các đoạn mediaDefinition
        val mediaDefRegex = """"mediaDefinition"\s*:\s*(\[.*?\])""".toRegex()
        val allMatches = mediaDefRegex.findAll(embedHtmlContent)

        // Bước 4: Lặp qua tất cả các kết quả để tìm cái hợp lệ
        for (match in allMatches) {
            val mediaJson = match.groupValues[1]
            val hlsSource = try {
                parseJson<List<InitialMedia>>(mediaJson).firstOrNull { it.videoUrl?.contains("/hls/") == true }
            } catch (e: Exception) {
                null
            }

            // Nếu tìm thấy một nguồn HLS hợp lệ, lấy URL và dừng vòng lặp
            if (hlsSource?.videoUrl != null) {
                intermediateApiUrl = hlsSource.videoUrl
                break
            }
        }

        if (intermediateApiUrl == null) return false
        
        // Bước 5: Dọn dẹp URL và gọi API để lấy link cuối cùng
        val correctedApiUrl = intermediateApiUrl.replace("\\/", "/")
        
        return try {
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

        // Đảm bảo URL có dấu "/" ở cuối ngay khi tạo
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
