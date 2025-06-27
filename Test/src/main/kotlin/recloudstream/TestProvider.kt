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

    /**
     * Sửa đổi cách định nghĩa trang chủ để xử lý phân trang một cách an toàn hơn.
     * Mỗi mục sẽ chứa một đường dẫn tương đối với tham số page.
     */
    override val mainPage = mainPageOf(
        "/?page=" to "Recommended",
        "/top_rated/?page=" to "Top Rated",
        "/most_viewed/?page=" to "Most Viewed",
        "/most_favorited/?page=" to "Most Favorited",
        "/browse/time/?page=" to "Newest Videos"
    )

    /**
     * Sửa đổi hàm getMainPage để xây dựng URL một cách chính xác.
     * Đây là nơi đã xảy ra lỗi ở phiên bản trước.
     */
    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        // Xây dựng URL hoàn chỉnh và an toàn
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

    override suspend fun loadLinks(
        dataUrl: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val document = app.get(dataUrl).document

        val mediaDefinitionRegex = """"mediaDefinition":(\[.*?\])""".toRegex()
        val mediaJson = mediaDefinitionRegex.find(document.html())?.groupValues?.get(1)
        
        var foundLinks = false
        if (mediaJson != null) {
            try {
                parseJson<List<MediaDefinition>>(mediaJson).forEach { source ->
                    val videoUrl = source.videoUrl ?: return@forEach
                    val quality = source.height ?: 0

                    callback(
                        ExtractorLink(
                            source = this.name,
                            name = this.name,
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
            }
        }
        return foundLinks
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val href = this.selectFirst("a.video-box-image")?.attr("href") ?: return null
        val videoUrl = fixUrl(href)

        val title = this.selectFirst("a.video-title-text")?.text()?.trim() ?: return null
        val posterUrl = this.selectFirst("img.thumb-image")?.attr("data-src")
        
        // Sử dụng hàm tạo với các tham số theo đúng vị trí
        return MovieSearchResponse(
            title,
            videoUrl,
            this@YouPornProvider.name,
            TvType.NSFW,
            fixUrlNull(posterUrl),
            null
        )
    }
}
