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

    // --- Data Classes để parse các loại JSON khác nhau ---
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

    /**
     * Hàm `loadLinks` cuối cùng, với Regex được cải tiến.
     */
    override suspend fun loadLinks(
        dataUrl: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val document = app.get(dataUrl).document
        val htmlContent = document.html()

        var intermediateApiUrl: String? = null

        // Tạo một danh sách các biểu thức chính quy để thử lần lượt
        // Regex mới (ưu tiên cao nhất) có thể xử lý cả dấu ":" và "="
        val regexes = listOf(
            """['"]mediaDefinition['"]\s*[:=]\s*(\[.*?\])""".toRegex(),
            """var\s*flashvars_\d+\s*=\s*(\{.+?\});""".toRegex()
        )

        for (regex in regexes) {
            val match = regex.find(htmlContent)?.groupValues?.get(1) ?: continue
            
            // Thử parse JSON theo cấu trúc tương ứng
            intermediateApiUrl = try {
                 parseJson<List<InitialMedia>>(match).firstOrNull()?.videoUrl
            } catch (e: Exception) {
                try {
                    parseJson<Flashvars>(match).mediaDefinitions?.firstOrNull()?.videoUrl
                } catch (e2: Exception) {
                    null
                }
            }

            if (intermediateApiUrl != null) break
        }
        
        // Nếu một trong các cách trên tìm được URL API, hãy xử lý nó
        if (intermediateApiUrl != null) {
            return try {
                val streamApiResponse = app.get(intermediateApiUrl!!, referer = dataUrl).text
                parseJson<List<FinalStreamInfo>>(streamApiResponse).mapNotNull { it.videoUrl }.apmap { streamUrl ->
                    M3u8Helper.generateM3u8(
                        name,
                        streamUrl,
                        mainUrl
                    ).forEach(callback)
                }.isNotEmpty()
            } catch (e: Exception) {
                e.printStackTrace()
                false
            }
        }
        
        return false // Trả về false nếu tất cả các phương pháp đều thất bại
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
