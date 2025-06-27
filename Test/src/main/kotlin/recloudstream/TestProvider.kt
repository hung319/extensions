package com.lagradost.cloudstream3.providers

import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.M3u8Helper
import org.jsoup.nodes.Element

class YouPornProvider : MainAPI() {
    override var name = "YouPorn"
    override var mainUrl = "https://www.youporn.com"
    override var supportedTypes = setOf(TvType.NSFW)
    override val hasMainPage = true

    // Cấu trúc data class để parse JSON từ bước 1
    private data class InitialMedia(
        @JsonProperty("videoUrl") val videoUrl: String?,
    )

    // Cấu trúc data class để parse JSON response cuối cùng (từ cURL của bạn)
    private data class FinalStreamInfo(
        @JsonProperty("format") val format: String?,
        @JsonProperty("videoUrl") val videoUrl: String?,
        @JsonProperty("quality") val quality: String?,
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

    /**
     * Hàm `loadLinks` đã được viết lại hoàn toàn để mô phỏng quy trình 2 bước.
     */
    override suspend fun loadLinks(
        dataUrl: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val document = app.get(dataUrl).document
        val scriptContent = document.select("script").joinToString { it.data() }
        var foundLinks = false

        // Bước 1: Trích xuất URL API trung gian từ JSON trong mã nguồn
        val mediaDefinitionRegex = """"mediaDefinition"\s*:\s*(\[.+?\])""".toRegex()
        val intermediateApiUrl = mediaDefinitionRegex.find(scriptContent)?.groupValues?.get(1)?.let { mediaJson ->
            parseJson<List<InitialMedia>>(mediaJson).firstOrNull()?.videoUrl
        } ?: return false // Nếu không tìm thấy URL API, dừng lại

        // Bước 2: Gọi đến URL API đó để lấy JSON chứa các link stream cuối cùng
        val streamApiResponse = app.get(intermediateApiUrl, referer = dataUrl).text
        
        try {
            // Bước 3: Parse JSON response cuối cùng
            parseJson<List<FinalStreamInfo>>(streamApiResponse).forEach { streamInfo ->
                val finalStreamUrl = streamInfo.videoUrl ?: return@forEach
                val quality = streamInfo.quality?.toIntOrNull() ?: 0

                // Bước 4: Dùng M3u8Helper để xử lý link HLS và tạo ExtractorLink
                M3u8Helper.generateM3u8(
                    name = "${this.name} ${quality}p",
                    streamUrl = finalStreamUrl,
                    referer = mainUrl
                ).forEach { link ->
                    callback(link)
                    foundLinks = true
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }

        return foundLinks
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
