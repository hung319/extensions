package recloudstream

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.* // Sử dụng wildcard import
import org.json.JSONObject
import org.jsoup.nodes.Element
// SỬA LỖI #2: Thêm các import cần thiết cho coroutine
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.awaitAll

class SpankbangProvider : MainAPI() {
    override var mainUrl = "https://spankbang.party"
    override var name = "SpankBang"
    override var lang = "en"
    override val hasMainPage = true
    override val supportedTypes = setOf(TvType.NSFW)

    private fun Element.toSearchResponse(): SearchResponse? {
        // SỬA: Cập nhật selector cho linkElement, thẻ <a> không còn class 'thumb'
        val linkElement = this.selectFirst("a[href]") ?: return null
        val href = linkElement.attr("href")
        
        // Selector cho title và poster vẫn hoạt động vì chúng nằm trong linkElement
        // và img vẫn có class 'lazyload'
        val title = linkElement.selectFirst("img")?.attr("alt")?.trim() ?: return null
        var posterUrl = this.selectFirst("img.cover, img.lazyload")?.attr("data-src")

        if (href.isBlank() || posterUrl.isNullOrBlank()) return null

        if (posterUrl.startsWith("//")) {
            posterUrl = "https:$posterUrl"
        }

        return newMovieSearchResponse(name = title, url = fixUrl(href)) {
            this.posterUrl = posterUrl
        }
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val sections = listOf(
            Pair("Trending Videos", "/trending_videos/"),
            Pair("New Videos", "/new_videos/"),
            Pair("Popular", "/most_popular/")
        )
        
        val homePageList = coroutineScope {
            sections.map { (sectionName, sectionUrl) ->
                // SỬA LỖI #1: Chỉ định rõ kiểu dữ liệu trả về cho async
                async<HomePageList?> {
                    try {
                        val document = app.get(mainUrl + sectionUrl).document
                        // SỬA: Cập nhật selector cho video item để khớp với cấu trúc mới
                        val videos = document.select("div[data-testid=\"video-item\"]").mapNotNull {
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
            }.awaitAll().filterNotNull() // Bây giờ awaitAll() sẽ được nhận dạng
        }

        return newHomePageResponse(homePageList)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val searchUrl = "$mainUrl/s/${query.replace(" ", "+")}/"
        val document = app.get(searchUrl).document
        
        // SỬA: Cập nhật selector cho kết quả tìm kiếm để khớp HTML mới
        // Sử dụng data-testid để tăng độ ổn định
        return document.select("div[data-testid=\"search-result\"] div[data-testid=\"video-item\"]").mapNotNull {
            it.toSearchResponse()
        }
    }
    
    override suspend fun load(url: String): LoadResponse {
        val document = app.get(url).document
        val title = document.selectFirst("h1.main_content_title")?.text()?.trim()
            ?: "Video"
        val poster = document.selectFirst("meta[property=og:image]")?.attr("content")
        val description = document.selectFirst("meta[name=description]")?.attr("content")
        
        // SỬA (Proactive): Cập nhật selector cho recommendations
        val recommendations = document.select("div.similar div[data-testid=\"video-item\"]").mapNotNull {
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

        // ## BẮT ĐẦU THAY ĐỔI (M3U8) ##
        streamData.optJSONArray("m3u8")?.let { arr ->
            for (i in 0 until arr.length()) {
                val m3u8Url = arr.getString(i)
                
                // 1. Gọi newExtractorLink
                val link = newExtractorLink(
                    source = this.name,
                    name = "HLS (Auto)",
                    url = m3u8Url,
                    type = ExtractorLinkType.M3U8
                ) {
                    // 2. Đặt thuộc tính trong initializer
                    this.referer = mainUrl
                    this.quality = Qualities.Unknown.value
                }
                // 3. Gọi callback
                callback.invoke(link)
            }
        }
        // ## KẾT THÚC THAY ĐỔI (M3U8) ##
        
        val qualities = listOf("1080p", "720p", "480p", "240p")
        // .forEach là an toàn ở đây vì nó không chạy trong lambda non-suspend
        qualities.forEach { quality -> 
            streamData.optJSONArray(quality)?.let { arr ->
                if (arr.length() > 0) {
                    val mp4Url = arr.getString(0)

                    // ## BẮT ĐẦU THAY ĐỔI (MP4) ##
                    // 1. Gọi newExtractorLink
                    val link = newExtractorLink(
                        source = this.name,
                        name = "MP4 $quality",
                        url = mp4Url,
                        type = ExtractorLinkType.VIDEO
                    ) {
                        // 2. Đặt thuộc tính trong initializer
                        this.referer = mainUrl
                        this.quality = quality.replace("p", "").toIntOrNull() ?: Qualities.Unknown.value
                    }
                    // 3. Gọi callback
                    callback.invoke(link)
                    // ## KẾT THÚC THAY ĐỔI (MP4) ##
                }
            }
        }
        return true
    }
}
