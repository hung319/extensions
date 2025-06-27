package com.lagradost.cloudstream3.providers

import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import org.jsoup.nodes.Element

class YouPornProvider : MainAPI() {
    override var name = "YouPorn"
    override var mainUrl = "https://www.youporn.com"
    override var supportedTypes = setOf(TvType.NSFW)
    override val hasMainPage = true

    private data class MediaDefinition(
        @JsonProperty("videoUrl") val videoUrl: String?,
        @JsonProperty("height") val height: Int?,
        @JsonProperty("format") val format: String?,
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

        val items = document.select("div.video-box").mapNotNull {
            it.toSearchResult()
        }
        
        val hasNext = document.selectFirst("div.paginationWrapper a[rel=next]") != null
        return newHomePageResponse(request.name, items, hasNext = hasNext)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val url = "$mainUrl/search/?query=$query"
        val document = app.get(url).document
        
        return document.select("div.searchResults div.video-box").mapNotNull {
            it.toSearchResult()
        }
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
     * Hàm loadLinks đã được thêm các callback DEBUG để hiển thị trạng thái.
     */
    override suspend fun loadLinks(
        dataUrl: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val document = app.get(dataUrl).document
        
        // Regex linh hoạt hơn để tìm JSON
        val mediaDefinitionRegex = """"mediaDefinition"\s*:\s*(\[.+?\])""".toRegex()
        var foundLinks = false

        // Tìm JSON trong tất cả các thẻ script
        val scriptContent = document.select("script").joinToString { it.data() }
        val mediaJson = mediaDefinitionRegex.find(scriptContent)?.groupValues?.get(1)

        if (mediaJson == null) {
            // DEBUG: Thông báo nếu Regex không tìm thấy gì
            callback(ExtractorLink(this.name, "DEBUG: Regex FAILED to find mediaDefinition", "#", "", -1, ExtractorLinkType.VIDEO))
            return false
        }
        
        // DEBUG: Thông báo Regex đã thành công và hiển thị một phần JSON
        callback(ExtractorLink(this.name, "DEBUG: Regex OK, JSON: ${mediaJson.take(150)}...", "#", "", -1, ExtractorLinkType.VIDEO))

        try {
            val sources = parseJson<List<MediaDefinition>>(mediaJson)
            if (sources.isEmpty()) {
                // DEBUG: Thông báo nếu parse JSON thành công nhưng không có link nào
                 callback(ExtractorLink(this.name, "DEBUG: JSON Parsed but list is empty", "#", "", -1, ExtractorLinkType.VIDEO))
            }

            sources.forEach { source ->
                val videoUrl = source.videoUrl ?: return@forEach
                val quality = source.height ?: 0

                callback(
                    ExtractorLink(
                        source = this.name,
                        name = "${this.name} ${quality}p", // Tên link hiển thị chất lượng
                        url = videoUrl,
                        referer = mainUrl,
                        quality = quality,
                        type = if (source.format == "hls") ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                    )
                )
                foundLinks = true
            }
        } catch (e: Exception) {
            e.printStackTrace()
            // DEBUG: Thông báo nếu parse JSON thất bại
            callback(ExtractorLink(this.name, "DEBUG: JSON Parse FAILED - ${e.message?.take(100)}", "#", "", -1, ExtractorLinkType.VIDEO))
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
