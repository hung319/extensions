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

    /**
     * SỬA LỖI LỚN: Viết lại hoàn toàn logic phân tích HTML để khớp với cấu trúc thực tế.
     * Hàm này sẽ chuyển đổi một khối HTML `div.video-item` thành đối tượng SearchResponse.
     */
    private fun Element.toSearchResponse(): SearchResponse? {
        // Lấy link và tiêu đề từ thẻ <a> bên trong khối thông tin
        val titleElement = this.selectFirst("p.line-clamp-2 a")
        val title = titleElement?.attr("title")?.trim() ?: return null
        val href = titleElement.attr("href")

        // Lấy ảnh poster từ thuộc tính 'data-src' của thẻ img
        var posterUrl = this.selectFirst("img[data-src]")?.attr("data-src")?.trim()

        if (href.isBlank() || posterUrl.isNullOrBlank()) return null
        
        // Xử lý trường hợp URL ảnh bắt đầu bằng "//"
        if (posterUrl.startsWith("//")) {
            posterUrl = "https:$posterUrl"
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

        // Lấy các mục video từ trang chủ, ví dụ: "Recommended", "Creator Videos"
        document.select("div[data-testid=video-list]").forEach { list ->
            val title = list.parent()?.selectFirst("h1, h2")?.text()?.trim() ?: "Homepage"
            val videos = list.select("div[data-testid=video-item]").mapNotNull { element ->
                element.toSearchResponse()
            }
            if (videos.isNotEmpty()) {
                homePageList.add(HomePageList(title, videos))
            }
        }
        
        return HomePageResponse(homePageList.filter { it.list.isNotEmpty() })
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val searchUrl = "$mainUrl/s/${query.replace(" ", "+")}/"
        val document = app.get(searchUrl).document
        // Sử dụng selector chính xác cho trang tìm kiếm
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
        
        // Sửa lại selector cho video đề xuất
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
