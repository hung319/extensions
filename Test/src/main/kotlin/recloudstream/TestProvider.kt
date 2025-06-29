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
    
    override suspend fun loadLinks(
        dataUrl: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val videoId = """/watch/(\d+)/""".toRegex().find(dataUrl)?.groupValues?.get(1) ?: return false
        val embedUrl = "$mainUrl/embed/$videoId/"
        val embedHtmlContent = app.get(embedUrl, referer = dataUrl).text
        var intermediateApiUrl: String? = null

        // CÁCH 1: Tìm `mediaDefinition`
        extractJsonArray(embedHtmlContent, "\"mediaDefinition\"")?.let { mediaJson ->
             intermediateApiUrl = try {
                parseJson<List<InitialMedia>>(mediaJson).firstOrNull { it.videoUrl?.contains("/hls/") == true }?.videoUrl
            } catch (e: Exception) { null }
        }

        // CÁCH 2: Tìm `flashvars_...`
        if (intermediateApiUrl == null) {
            val flashvarsRegex = """var\s*flashvars_\d+\s*=\s*(\{.+?\});""".toRegex()
            flashvarsRegex.find(embedHtmlContent)?.groupValues?.get(1)?.let { flashvarsJson ->
                 intermediateApiUrl = try {
                    parseJson<Flashvars>(flashvarsJson).mediaDefinitions?.firstOrNull()?.videoUrl
                } catch (e: Exception) { null }
            }
        }

        // Nếu tìm được URL API, xử lý nó
        if (intermediateApiUrl != null) {
            val correctedApiUrl = intermediateApiUrl.replace("\\/", "/")
            return try {
                val streamApiResponse = app.get(correctedApiUrl, referer = embedUrl).text
                var foundLinks = false
                // Tối ưu hóa: Tạo ExtractorLink trực tiếp vì đã có danh sách chất lượng
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
        
        // CÁCH 3 (Dự phòng cuối cùng): Tìm trực tiếp link M3U8
        val m3u8Regex = """(https?://[^\s"'<>]+\.m3u8[^\s"'<>]*?)""".toRegex()
        return m3u8Regex.findAll(embedHtmlContent).map { it.value }.toList().apmap { m3u8Url ->
            // Sửa lỗi biên dịch: gọi hàm M3u8Helper đúng cách
            M3u8Helper.generateM3u8(
                name = this.name,
                streamUrl = m3u8Url,
                referer = mainUrl,
                source = this.name
            ).forEach(callback)
        }.isNotEmpty()
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
