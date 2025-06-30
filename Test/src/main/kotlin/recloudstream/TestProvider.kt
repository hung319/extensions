package recloudstream

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.json.JSONObject

// Lớp Provider chính, kế thừa từ MainAPI
class SpankbangProvider : MainAPI() {
    // Thông tin cơ bản về provider
    override var mainUrl = "https://spankbang.party"
    override var name = "SpankBang"
    override var lang = "en"
    override val hasMainPage = true
    override val supportedTypes = setOf(TvType.NSFW)

    // SỬA LỖI: Cấu trúc lại MainPage để tải dữ liệu đúng cách
    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val items = mutableListOf<HomePageList>()
        val sections = listOf(
            "Trending Videos" to "/trending_videos/",
            "New Videos" to "/new_videos/",
            "Popular Videos" to "/most_popular/"
        )

        // Tải và phân tích dữ liệu cho từng mục trên trang chủ
        sections.forEach { (sectionName, sectionUrl) ->
            try {
                val document = app.get(mainUrl + sectionUrl).document
                val videoList = document.select("div.video-item").mapNotNull { element ->
                    val link = element.selectFirst("a.thumb")
                    val href = link?.attr("href") ?: return@mapNotNull null
                    val title = link.attr("title").trim()
                    val posterUrl = element.selectFirst("img.cover")?.attr("data-src")

                    MovieSearchResponse(
                        name = title,
                        url = fixUrl(href),
                        apiName = this.name,
                        type = TvType.Movie,
                        posterUrl = posterUrl
                    )
                }
                if (videoList.isNotEmpty()) {
                    items.add(HomePageList(sectionName, videoList))
                }
            } catch (e: Exception) {
                // Bỏ qua nếu có lỗi tải một mục
                e.printStackTrace()
            }
        }

        return HomePageResponse(items)
    }

    // Hàm phân tích HTML để lấy danh sách phim (dùng cho tìm kiếm)
    private fun parseSearch(html: String): List<SearchResponse> {
        val document = app.parseHTML(html)
        return document.select("div.video-item").mapNotNull {
            val link = it.selectFirst("a.thumb") ?: return@mapNotNull null
            val href = link.attr("href")
            val title = link.attr("title").trim()
            val posterUrl = it.selectFirst("img.cover")?.attr("data-src")
            MovieSearchResponse(
                title,
                fixUrl(href),
                this.name,
                TvType.Movie,
                posterUrl,
            )
        }
    }

    // Hàm xử lý tìm kiếm
    override suspend fun search(query: String): List<SearchResponse> {
        val searchUrl = "$mainUrl/s/${query.replace(" ", "+")}/"
        val response = app.get(searchUrl)
        return parseSearch(response.text)
    }
    
    // SỬA LỖI: Implement hàm load một cách đầy đủ
    override suspend fun load(url: String): LoadResponse {
        val document = app.get(url).document
        val title = document.selectFirst("h1.main_content_title")?.text()?.trim()
            ?: document.selectFirst("meta[property=og:title]")?.attr("content")?.trim()
            ?: "Video"
        val poster = document.selectFirst("meta[property=og:image]")?.attr("content")
        val description = document.selectFirst("meta[name=description]")?.attr("content")
        val recommendations = document.select("div.video-item").mapNotNull {
            val link = it.selectFirst("a.thumb") ?: return@mapNotNull null
            val href = link.attr("href")
            val recTitle = link.attr("title").trim()
            val recPosterUrl = it.selectFirst("img.cover")?.attr("data-src")
            MovieSearchResponse(
                recTitle,
                fixUrl(href),
                this.name,
                TvType.Movie,
                recPosterUrl,
            )
        }

        return newMovieLoadResponse(
            name = title,
            url = url,
            type = TvType.NSFW,
            posterUrl = poster,
            plot = description,
            recommendations = recommendations
        )
    }


    // ⭐ HÀM CẬP NHẬT: Sử dụng cấu trúc ExtractorLink mới và sửa lỗi type mismatch
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

        // SỬA LỖI: Duyệt mảng JSON đúng cách để tránh type mismatch
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
