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

    // ================= SỬA LỖI Ở ĐÂY =================
    // Đã dọn dẹp hàm này để chỉ sử dụng các thuộc tính có sẵn
    private fun Element.toSearchResult(): SearchResponse? {
        val title = this.selectFirst("strong.title")?.text()?.trim() ?: return null
        val href = this.selectFirst("a")?.attr("href") ?: return null
        val posterUrl = this.selectFirst("img.thumb")?.attr("src")

        return newMovieSearchResponse(title, href, TvType.NSFW) {
            this.posterUrl = posterUrl
            // Không thêm thông tin phụ vì không có thuộc tính hỗ trợ
        }
    }
    // =================================================

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
        
        val script = document.select("script").find { it.data().contains("flashvars") }?.data()

        if (script != null) {
            val videoUrlRegex = Regex("""'video_url'\s*:\s*'function/0/(.+?\.mp4/)'""")
            val videoUrlMatch = videoUrlRegex.find(script)
            val finalVideoUrl = videoUrlMatch?.groups?.get(1)?.value

            if (finalVideoUrl != null) {
                return newMovieLoadResponse(title, url, TvType.NSFW, finalVideoUrl) {
                    this.posterUrl = poster
                    this.plot = description
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
        callback.invoke(
            ExtractorLink(
                source = this.name,
                name = this.name,
                url = data,
                referer = mainUrl,
                quality = Qualities.Unknown.value,
                type = ExtractorLinkType.VIDEO 
            )
        )
        return true
    }
}
