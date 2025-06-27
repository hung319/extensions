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
     * Hàm loadLinks đã được sửa để chỉ hiển thị một link DEBUG duy nhất.
     */
    override suspend fun loadLinks(
        dataUrl: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val document = app.get(dataUrl).document

        val mediaDefinitionRegex = """"mediaDefinition"\s*:\s*(\[.+?\])""".toRegex()
        val scriptContent = document.select("script").joinToString { it.data() }
        val mediaJson = mediaDefinitionRegex.find(scriptContent)?.groupValues?.get(1)

        // URL video mẫu để đảm bảo link không bị lỗi
        val placeholderUrl = "https://commondatastorage.googleapis.com/gtv-videos-bucket/sample/BigBuckBunny.mp4"

        if (mediaJson == null) {
            // Nếu không tìm thấy JSON, tạo một link debug báo lỗi.
            callback(ExtractorLink(
                source = this.name,
                name = "DEBUG: Regex FAILED. No mediaDefinition found.",
                url = placeholderUrl,
                referer = mainUrl,
                quality = 1,
                type = ExtractorLinkType.VIDEO
            ))
        } else {
            // Nếu tìm thấy JSON, tạo một link debug chứa nội dung JSON đó.
            callback(ExtractorLink(
                source = this.name,
                name = "DEBUG: JSON Found -> ${mediaJson.take(300)}...", // Lấy 300 ký tự đầu của JSON
                url = placeholderUrl,
                referer = mainUrl,
                quality = 1,
                type = ExtractorLinkType.VIDEO
            ))
            // Đồng thời, vẫn thử lấy link thật. Nếu thành công, bạn sẽ thấy cả link debug và link thật.
            // Nếu thất bại, bạn vẫn có link debug để xem dữ liệu.
            try {
                parseJson<List<MediaDefinition>>(mediaJson).forEach { source ->
                    val videoUrl = source.videoUrl ?: return@forEach
                    val quality = source.height ?: 0
                    callback(
                        ExtractorLink(
                            source = this.name,
                            name = "${this.name} ${quality}p",
                            url = videoUrl,
                            referer = mainUrl,
                            quality = quality,
                            type = if (source.format == "hls") ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                        )
                    )
                }
            } catch (e: Exception) {
                // Nếu parse lỗi, tạo thêm một link debug báo lỗi parse.
                 callback(ExtractorLink(
                    source = this.name,
                    name = "DEBUG: JSON PARSE FAILED -> ${e.message}",
                    url = placeholderUrl,
                    referer = mainUrl,
                    quality = 1,
                    type = ExtractorLinkType.VIDEO
                ))
            }
        }

        return true // Luôn trả về true để đảm bảo link debug được hiển thị
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
