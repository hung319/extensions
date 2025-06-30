package recloudstream

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.json.JSONObject
import org.jsoup.nodes.Element
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope

class SpankbangProvider : MainAPI() {
    override var mainUrl = "https://spankbang.party"
    override var name = "SpankBang"
    override var lang = "en"
    override val hasMainPage = true
    override val supportedTypes = setOf(TvType.NSFW)

    /**
     * SỬA CẢNH BÁO #1: Sử dụng hàm `newMovieSearchResponse` thay vì constructor cũ.
     */
    private fun Element.toSearchResponse(): SearchResponse? {
        val linkElement = this.selectFirst("a.thumb") ?: return null
        val href = linkElement.attr("href")
        val title = linkElement.selectFirst("img")?.attr("alt")?.trim() ?: return null
        var posterUrl = this.selectFirst("img.cover, img.lazyload")?.attr("data-src")

        if (href.isBlank() || title.isBlank() || posterUrl.isNullOrBlank()) {
            return null
        }

        if (posterUrl.startsWith("//")) {
            posterUrl = "https:$posterUrl"
        }

        return newMovieSearchResponse( // <-- Đã thay đổi
            name = title,
            url = fixUrl(href),
            // apiName = this@SpankbangProvider.name,  // apiName được tự động thêm
            type = TvType.Movie,
            posterUrl = posterUrl,
        )
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val sections = listOf(
            Pair("Trending Videos", "/trending_videos/"),
            Pair("New Videos", "/new_videos/"),
            Pair("Popular", "/most_popular/")
        )

        /**
         * SỬA CẢNH BÁO #2: Thay thế `apmap` bằng `coroutineScope` và `async`/`awaitAll`
         * để xử lý bất đồng bộ một cách an toàn và hiện đại.
         */
        val items = coroutineScope {
            sections.map { (sectionName, sectionUrl) ->
                async {
                    try {
                        val document = app.get(mainUrl + sectionUrl).document
                        val videos = document.select("div.video-item").mapNotNull {
                            it.toSearchResponse()
                        }
                        if (videos.isNotEmpty()) {
                            HomePageList(sectionName, videos)
                        } else {
                            null
                        }
                    } catch (e: Exception) {
                        e.printStackTrace()
                        null
                    }
                }
            }.awaitAll().filterNotNull()
        }
        
        /**
         * SỬA CẢNH BÁO #3: Sử dụng hàm `newHomePageResponse` thay cho constructor cũ.
         */
        return newHomePageResponse(items) // <-- Đã thay đổi
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val searchUrl = "$mainUrl/s/${query.replace(" ", "+")}/"
        val document = app.get(searchUrl).document
        return document.select("div.main_results div.video-item").mapNotNull {
            it.toSearchResponse()
        }
    }
    
    override suspend fun load(url: String): LoadResponse {
        val document = app.get(url).document
        val title = document.selectFirst("h1.main_content_title")?.text()?.trim()
            ?: "Video"
        val poster = document.selectFirst("meta[property=og:image]")?.attr("content")
        val description = document.selectFirst("meta[name=description]")?.attr("content")
        
        val recommendations = document.select("div.similar div.video-item").mapNotNull {
            it.toSearchResponse()
        }

        return newMovieLoadResponse(
            name = title,
            url = url,
            dataUrl = url,
            type = TvType.NSFW,
        ) {
            this.posterUrl = poster
            this.plot = description
            this.recommendations = recommendations
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val document = app.get(data).text
        val streamDataRegex = Regex("""var stream_data = (\{.*?\});""")
        val matchResult = streamDataRegex.find(document) ?: return false

        val streamDataJson = matchResult.groupValues[1]
        val streamData = JSONObject(streamDataJson)

        streamData.optJSONArray("m3u8")?.let { arr ->
            for (i in 0 until arr.length()) {
                val m3u8Url = arr.getString(i)
                callback(
                    ExtractorLink(
                        source = this.name,
                        name = "HLS (Auto)",
                        url = m3u8Url,
                        referer = mainUrl,
                        quality = Qualities.Unknown.value,
                        type = ExtractorLinkType.M3U8
                    )
                )
            }
        }
        
        val qualities = listOf("1080p", "720p", "480p", "240p")
        qualities.forEach { quality ->
            streamData.optJSONArray(quality)?.let { arr ->
                if (arr.length() > 0) {
                    val mp4Url = arr.getString(0)
                    callback(
                        ExtractorLink(
                            source = this.name,
                            name = "MP4 $quality",
                            url = mp4Url,
                            referer = mainUrl,
                            quality = quality.replace("p", "").toIntOrNull() ?: Qualities.Unknown.value,
                            type = ExtractorLinkType.VIDEO
                        )
                    )
                }
            }
        }
        return true
    }
}
