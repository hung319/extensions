// Bấm vào "Copy" ở góc trên bên phải để sao chép mã
package recloudstream

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Element

class XpornTvProvider : MainAPI() {
    override var mainUrl = "https://www.xporn.tv"
    override var name = "Xporn.tv"
    override val hasMainPage = true
    override var lang = "en"
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(
        TvType.NSFW
    )

    override val mainPage = mainPageOf(
        "$mainUrl/latest-updates/" to "Latest Videos",
        "$mainUrl/most-popular/" to "Most Popular",
        "$mainUrl/top-rated/" to "Top Rated",
    )

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {
        val url = if (page > 1) "${request.data}$page" else request.data
        val document = app.get(url).document
        
        val home = document.select("div.list-videos div.item").mapNotNull {
            it.toSearchResult()
        }
        return newHomePageResponse(request.name, home)
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val title = this.selectFirst("strong.title")?.text()?.trim() ?: return null
        val href = this.selectFirst("a")?.attr("href") ?: return null
        val posterUrl = this.selectFirst("img.thumb")?.attr("src")

        return newMovieSearchResponse(title, href, TvType.NSFW) {
            this.posterUrl = posterUrl
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val searchResponse = app.get("$mainUrl/search/$query/").document
        return searchResponse.select("div.list-videos div.item").mapNotNull {
            it.toSearchResult()
        }
    }

    override suspend fun load(url: String): LoadResponse {
        val document = app.get(url).document

        val title = document.selectFirst("h1")?.text()?.trim()
            ?: document.selectFirst("meta[property=og:title]")?.attr("content")
            ?: "Video"
        
        // Trích xuất videoId trực tiếp từ URL của trang
        val videoId = url.split("/")[4]
        
        val poster = document.selectFirst("meta[property=og:image]")?.attr("content")
        val description = document.selectFirst("meta[name=description]")?.attr("content")
        
        val recommendations = document.select("div.related-videos div.item").mapNotNull {
            it.toSearchResult()
        }
        
        // Truyền videoId cho hàm `loadLinks` để sử dụng
        return newMovieLoadResponse(title, url, TvType.NSFW, videoId) {
            this.posterUrl = poster
            this.plot = description
            this.recommendations = recommendations
        }
    }
    
    override suspend fun loadLinks(
        data: String, // `data` bây giờ là videoId (ví dụ: "182086")
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val videoId = data

        // Lấy nội dung trang để tìm script chứa thông tin cần thiết
        val document = app.get("$mainUrl/videos/$videoId/").document
        
        val scriptText = document.select("script").find { it.data().contains("flashvars") }?.data()
            ?: throw ErrorLoadingException("Không thể tìm thấy script chứa thông tin video")

        // Trích xuất mã hash động và các thông tin khác từ `flashvars`
        val videoUrlMatch = Regex("""video_url:\s*'.*?/get_file/(\d+)/([^/]+)/""").find(scriptText)
            ?: throw ErrorLoadingException("Không thể trích xuất thông tin cần thiết từ video_url")
        
        val quality = videoUrlMatch.groupValues[1]
        val hash = videoUrlMatch.groupValues[2]
        
        val rnd = Regex("""rnd:\s*'([^']+)""").find(scriptText)?.groupValues?.get(1)
            ?: System.currentTimeMillis()

        // Xây dựng URL API cuối cùng, hợp lệ
        val videoFolder = (videoId.toInt() / 1000) * 1000
        val finalUrl = "$mainUrl/get_file/$quality/$hash/$videoFolder/$videoId/$videoId.mp4/?rnd=$rnd"

        callback.invoke(
            ExtractorLink(
                source = this.name,
                name = this.name,
                url = finalUrl,
                referer = "$mainUrl/videos/$videoId/", // Referer là trang chứa video
                quality = Qualities.Unknown.value,
                type = ExtractorLinkType.M3U8 // Trang này dùng M3U8 trá hình
            )
        )
        return true
    }
}
