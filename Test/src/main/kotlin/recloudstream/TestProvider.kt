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

    // Cấu trúc dữ liệu cho các link video
    private data class MediaDefinition(
        @JsonProperty("videoUrl") val videoUrl: String?,
        @JsonProperty("height") val height: Int?,
        @JsonProperty("format") val format: String?,
    )
    
    // Cấu trúc dữ liệu cho phương pháp trích xuất thứ hai (dự phòng)
    private data class PlayerVars(
        @JsonProperty("mediaDefinitions") val mediaDefinitions: List<MediaDefinition>?
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
     * Nâng cấp `loadLinks` để thử nhiều phương pháp trích xuất khác nhau.
     */
    override suspend fun loadLinks(
        dataUrl: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val document = app.get(dataUrl).document
        var foundLinks = false

        // Lấy toàn bộ nội dung của các thẻ script
        val scriptContent = document.select("script").joinToString { it.data() }

        // === CÁCH 1: Tìm "mediaDefinition" ===
        val mediaDefRegex = """"mediaDefinition"\s*:\s*(\[.+?\])""".toRegex()
        mediaDefRegex.find(scriptContent)?.groupValues?.get(1)?.let { mediaJson ->
            try {
                parseJson<List<MediaDefinition>>(mediaJson).forEach { source ->
                    source.videoUrl?.let { url ->
                        callback(createExtractorLink(source.format, url, source.height ?: 0))
                        foundLinks = true
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
        
        // === CÁCH 2 (DỰ PHÒNG): Nếu cách 1 không thành công, tìm "playervars" ===
        if (!foundLinks) {
            val playerVarsRegex = """"playervars":\s*(\{.*?\})""".toRegex()
            playerVarsRegex.find(scriptContent)?.groupValues?.get(1)?.let { playerVarsJson ->
                try {
                    parseJson<PlayerVars>(playerVarsJson).mediaDefinitions?.forEach { source ->
                        source.videoUrl?.let { url ->
                            callback(createExtractorLink(source.format, url, source.height ?: 0))
                            foundLinks = true
                        }
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
        }
        
        return foundLinks
    }
    
    // Hàm tiện ích để tạo ExtractorLink
    private fun createExtractorLink(format: String?, url: String, quality: Int): ExtractorLink {
        return ExtractorLink(
            source = this.name,
            name = "${this.name} ${quality}p",
            url = url,
            referer = mainUrl,
            quality = quality,
            type = if (format == "hls") ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
        )
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
