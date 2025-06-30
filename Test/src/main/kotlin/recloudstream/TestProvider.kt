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

    // Hàm tiện ích để phân tích một video item
    private fun Element.toSearchResponse(): SearchResponse? {
        val titleElement = this.selectFirst("a.thumb") ?: return null
        val href = titleElement.attr("href")
        val title = titleElement.attr("title").trim()
        var posterUrl = this.selectFirst("img.cover")?.attr("data-src")

        if (href.isBlank() || title.isBlank() || posterUrl.isNullOrBlank()) {
            return null
        }

        if (posterUrl.startsWith("//")) {
            posterUrl = "https:$posterUrl"
        }

        return MovieSearchResponse(
            name = title,
            url = fixUrl(href),
            apiName = this@SpankbangProvider.name,
            type = TvType.Movie,
            posterUrl = posterUrl,
        )
    }

    /**
     * SỬA LỖI #2: Thêm hàm parsePage để xử lý phân trang
     * Hàm này sẽ được gọi bởi cả `getMainPage` và `load`
     */
    private suspend fun parsePage(url: String): MovieSearchResponse {
        val document = app.get(url).document
        val list = document.select("div.video-list > div.video-item").mapNotNull {
            it.toSearchResponse()
        }
        
        // Tìm link của trang tiếp theo
        val nextUrl = document.selectFirst("li.next > a")?.attr("href")
        
        return newMovieSearchResponse(document.title(), list) {
            this.nextUrl = nextUrl?.let { fixUrl(it) }
        }
    }

    /**
     * SỬA LỖI #1: Cấu trúc lại trang chủ và thêm layoutType
     */
    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val homePageList = mutableListOf<HomePageList>()

        val sections = listOf(
            Pair("Trending Videos", "/trending_videos/"),
            Pair("New Videos", "/new_videos/"),
            Pair("Popular", "/most_popular/")
        )

        sections.forEach { (sectionName, sectionUrl) ->
            try {
                // Chỉ tải trang đầu tiên cho trang chủ
                val response = parsePage(mainUrl + sectionUrl)
                if (response.list.isNotEmpty()) {
                    homePageList.add(
                        HomePageList(
                            sectionName,
                            response.list,
                            // Thêm layoutType để hiển thị dạng lưới
                            layoutType = HomePageListLayout.Grid,
                            // Thêm url để CloudStream biết cách tải trang tiếp theo
                            url = response.nextUrl 
                        )
                    )
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
        
        return HomePageResponse(homePageList, hasNext = true)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val searchUrl = "$mainUrl/s/${query.replace(" ", "+")}/"
        return parsePage(searchUrl).list
    }
    
    /**
     * SỬA LỖI #2: Cập nhật hàm `load` để xử lý cả trang danh sách và trang chi tiết
     */
    override suspend fun load(url: String): LoadResponse {
        // Kiểm tra xem URL là trang video chi tiết hay trang danh sách
        if (url.contains("/video/")) {
            // Đây là trang video chi tiết, lấy link stream
            val document = app.get(url).document
            val title = document.selectFirst("h1.main_content_title")?.text()?.trim() ?: "Video"
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
        } else {
            // Đây là trang danh sách (category/search), tải danh sách video và phân trang
            return parsePage(url)
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
