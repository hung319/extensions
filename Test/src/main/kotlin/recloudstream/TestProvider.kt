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

    private data class PlayerVars(
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
     * Hàm `loadLinks` cuối cùng, xử lý tất cả các trường hợp đã biết.
     */
    override suspend fun loadLinks(
        dataUrl: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val document = app.get(dataUrl).document
        val scriptContent = document.select("script").joinToString { it.data() }

        // Cố gắng tìm URL API trung gian bằng nhiều phương pháp
        var intermediateApiUrl: String? = null

        // CÁCH 1: Tìm `flashvars_...` (phát hiện mới nhất)
        val flashvarsRegex = """var\s*flashvars_\d+\s*=\s*(\{.+?\});""".toRegex()
        flashvarsRegex.find(scriptContent)?.groupValues?.get(1)?.let { flashvarsJson ->
            intermediateApiUrl = parseJson<Flashvars>(flashvarsJson).mediaDefinitions?.firstOrNull()?.videoUrl
        }

        // CÁCH 2: Nếu cách 1 thất bại, tìm `mediaDefinition`
        if (intermediateApiUrl == null) {
            val mediaDefRegex = """"mediaDefinition"\s*:\s*(\[.+?\])""".toRegex()
            mediaDefRegex.find(scriptContent)?.groupValues?.get(1)?.let { mediaJson ->
                intermediateApiUrl = parseJson<List<InitialMedia>>(mediaJson).firstOrNull()?.videoUrl
            }
        }
        
        // CÁCH 3: Nếu cách 2 thất bại, tìm `playervars`
        if (intermediateApiUrl == null) {
            val playerVarsRegex = """"playervars":\s*(\{.*?\})""".toRegex()
            playerVarsRegex.find(scriptContent)?.groupValues?.get(1)?.let { playerVarsJson ->
                intermediateApiUrl = parseJson<PlayerVars>(playerVarsJson).mediaDefinitions?.firstOrNull()?.videoUrl
            }
        }

        // Nếu một trong các cách trên tìm được URL API, hãy xử lý nó
        if (intermediateApiUrl != null) {
            return try {
                val streamApiResponse = app.get(intermediateApiUrl!!, referer = dataUrl).text
                parseJson<List<FinalStreamInfo>>(streamApiResponse).mapNotNull { it.videoUrl }.apmap {  streamUrl ->
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
        
        // CÁCH 4 (Dự phòng cuối cùng): Tìm trực tiếp link M3U8 trong toàn bộ trang
        val m3u8Regex = """(https?://[^\s"'<>]+\.m3u8[^\s"'<>]*?)""".toRegex()
        return m3u8Regex.findAll(document.html()).map { it.value }.toList().apmap { m3u8Url ->
            M3u8Helper.generateM3u8(
                name,
                m3u8Url,
                mainUrl
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
