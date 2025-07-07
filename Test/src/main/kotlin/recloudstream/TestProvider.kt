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
        // Thêm số trang vào URL, bắt đầu từ trang 1
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
        // Đảm bảo lấy đúng ảnh thumbnail, tránh ảnh gif
        val posterUrl = this.selectFirst("img.thumb")?.attr("src")
        val durationText = this.selectFirst("div.wrap > div:last-child")?.text()?.trim()

        return newMovieSearchResponse(title, href, TvType.NSFW) {
            this.posterUrl = posterUrl
            // Sửa lỗi: Sử dụng "quality" thay cho "otherVimeStuff" để hiển thị thời lượng
            if (durationText != null) {
                this.quality = SearchQuality.Custom(durationText)
            }
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
        val tags = document.select("div.item:contains(Tags) a").map { it.text() }
        
        // Tìm đoạn script chứa thông tin video
        val script = document.select("script").find { it.data().contains("flashvars") }?.data()

        if (script != null) {
            // Dùng regex để trích xuất 'video_url' từ trong đối tượng javascript 'flashvars'
            val videoUrlRegex = Regex("""'video_url'\s*:\s*'function/0/(.+?\.mp4/)'""")
            val videoUrlMatch = videoUrlRegex.find(script)
            val finalVideoUrl = videoUrlMatch?.groups?.get(1)?.value

            if (finalVideoUrl != null) {
                return newMovieLoadResponse(title, url, TvType.NSFW, finalVideoUrl) {
                    this.posterUrl = poster
                    this.plot = description
                    this.tags = tags
                }
            }
        }
        
        throw ErrorLoadingException("Không thể tìm thấy link video")
    }
    
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        // 'data' ở đây chính là URL của video đã được trả về từ hàm load()
        callback.invoke(
            ExtractorLink(
                source = this.name,
                name = this.name,
                url = data,
                referer = mainUrl,
                quality = Qualities.Unknown.value,
                // Đã cập nhật type, vì link là file .mp4 trực tiếp
                type = ExtractorLinkType.VIDEO 
            )
        )
        return true
    }
}
