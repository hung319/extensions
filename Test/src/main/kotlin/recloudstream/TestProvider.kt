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
        
        val poster = document.selectFirst("meta[property=og:image]")?.attr("content")
        val description = document.selectFirst("meta[name=description]")?.attr("content")
        
        val recommendations = document.select("div.related-videos div.item").mapNotNull {
            it.toSearchResult()
        }
        
        return newMovieLoadResponse(title, url, TvType.NSFW, url) {
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
        val document = app.get(data).document
        
        val script = document.select("script").find { it.data().contains("flashvars") }?.data()
            ?: throw ErrorLoadingException("Không tìm thấy script chứa thông tin video")

        val videoUrlRegex = Regex("""video_url:\s*'.*?(https?://[^']+)""")
        val videoUrl = videoUrlRegex.find(script)?.groups?.get(1)?.value
            ?: throw ErrorLoadingException("Không thể trích xuất link video từ script")

        callback.invoke(
            ExtractorLink(
                source = this.name,
                name = this.name,
                url = videoUrl,
                referer = data,
                quality = Qualities.Unknown.value,
                // ================== SỬA LỖI Ở ĐÂY ==================
                // Đổi type thành HLS để trình phát xử lý đúng luồng m3u8
                type = ExtractorLinkType.M3U8
                // ==================================================
            )
        )
        return true
    }
}
