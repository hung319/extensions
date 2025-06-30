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
        // Selector chính xác hơn để lấy link và tiêu đề
        val titleElement = this.selectFirst("a.thumb") ?: return null
        val href = titleElement.attr("href")
        val title = titleElement.attr("title").ifBlank { titleElement.text() }.trim()

        // Selector cho ảnh poster
        var posterUrl = this.selectFirst("img.cover, img.lazyload")?.attr("data-src")

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
     * SỬA LỖI: Đơn giản hóa hàm getMainPage để chỉ tải các mục tĩnh.
     * Bỏ qua phân trang tại đây để tránh lỗi.
     */
    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val items = mutableListOf<HomePageList>()
        val sections = listOf(
            "Trending Videos" to "/trending_videos/",
            "New Videos" to "/new_videos/",
            "Popular" to "/most_popular/"
        )

        // Tải và phân tích dữ liệu cho từng mục
        for ((sectionName, sectionUrl) in sections) {
            try {
                val document = app.get(mainUrl + sectionUrl).document
                val videoList = document.select("div.video-item").mapNotNull {
                    it.toSearchResponse()
                }
                if (videoList.isNotEmpty()) {
                    // SỬA LỖI: Sử dụng constructor HomePageList đơn giản, không có các tham số không được hỗ trợ
                    items.add(HomePageList(sectionName, videoList))
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
        
        return HomePageResponse(items)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val searchUrl = "$mainUrl/s/${query.replace(" ", "+")}/"
        val document = app.get(searchUrl).document
        // Selector này cần phải chính xác cho trang tìm kiếm
        return document.select("div.main_results div.video-item").mapNotNull {
            it.toSearchResponse()
        }
    }
    
    /**
     * SỬA LỖI: Hàm load chỉ xử lý một loại response duy nhất là LoadResponse.
     * Không còn logic kiểm tra và trả về MovieSearchResponse gây lỗi type mismatch.
     */
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
