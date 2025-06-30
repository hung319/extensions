package recloudstream

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.json.JSONObject
import org.jsoup.nodes.Element

class SpankbangProvider : MainAPI() {
    override var mainUrl = "https://spankbang.party"
    override var name = "SpankBang"
    override var lang = "en"
    override val hasMainPage = true
    override val supportedTypes = setOf(TvType.NSFW)

    private fun Element.toSearchResponse(): SearchResponse? {
        val linkElement = this.selectFirst("a.thumb") ?: return null
        val href = linkElement.attr("href")
        val title = linkElement.attr("title").trim()
        val posterUrl = this.selectFirst("img.cover")?.attr("data-src")

        if (href.isBlank() || title.isBlank() || posterUrl.isNullOrBlank()) {
            return null
        }

        return MovieSearchResponse(
            name = title,
            url = fixUrl(href),
            apiName = this@SpankbangProvider.name,
            type = TvType.Movie,
            posterUrl = posterUrl
        )
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val document = app.get(mainUrl).document
        val homePageList = mutableListOf<HomePageList>()

        val mainSections = mapOf(
            "Trending Videos" to "/trending_videos/",
            "New Videos" to "/new_videos/",
            "Popular Videos" to "/most_popular/"
        )

        mainSections.forEach { (sectionName, sectionUrl) ->
            try {
                val sectionDocument = app.get(mainUrl + sectionUrl).document
                val videoList = sectionDocument.select("div.video-item").mapNotNull {
                    it.toSearchResponse()
                }
                if (videoList.isNotEmpty()) {
                    homePageList.add(HomePageList(sectionName, videoList))
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }

        return HomePageResponse(homePageList)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val searchUrl = "$mainUrl/s/${query.replace(" ", "+")}/"
        val document = app.get(searchUrl).document
        return document.select("div.video-item").mapNotNull {
            it.toSearchResponse()
        }
    }
    
    // SỬA LỖI: Sửa tên tham số `url` thành `dataUrl` để khớp với API của bạn
    override suspend fun load(url: String): LoadResponse {
        val document = app.get(url).document
        val title = document.selectFirst("h1.main_content_title")?.text()?.trim()
            ?: document.selectFirst("meta[property=og:title]")?.attr("content")?.trim()
            ?: "Video"
        val poster = document.selectFirst("meta[property=og:image]")?.attr("content")
        val description = document.selectFirst("meta[name=description]")?.attr("content")
        val recommendations = document.select("div.similar > div.video-list > div.video-item").mapNotNull {
            it.toSearchResponse()
        }

        return newMovieLoadResponse(
            name = title,
            dataUrl = url, // <-- THAY ĐỔI Ở ĐÂY
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
        val response = app.get(data)
        val document = response.text

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
