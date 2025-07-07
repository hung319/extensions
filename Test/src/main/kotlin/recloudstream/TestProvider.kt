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
        
        val videoId = url.split("/")[4]
        
        val poster = document.selectFirst("meta[property=og:image]")?.attr("content")
        val description = document.selectFirst("meta[name=description]")?.attr("content")
        
        val recommendations = document.select("div.related-videos div.item").mapNotNull {
            it.toSearchResult()
        }
        
        return newMovieLoadResponse(title, url, TvType.NSFW, videoId) {
            this.posterUrl = poster
            this.plot = description
            this.recommendations = recommendations
        }
    }
    
    override suspend fun loadLinks(
        data: String, // `data` là videoId
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val videoId = data
        val videoIdInt = videoId.toIntOrNull() ?: return false

        val subdomain: String
        val pathSegment: String

        // ================== LOGIC THÔNG MINH Ở ĐÂY ==================
        // Dựa vào ID để chọn đúng cấu trúc server và đường dẫn
        if (videoIdInt >= 290000) {
            // Cấu trúc cho video mới
            subdomain = "vid2"
            pathSegment = "videos1X"
        } else {
            // Cấu trúc cho video cũ
            subdomain = "vid"
            pathSegment = "videos"
        }
        // =========================================================

        val videoFolder = (videoIdInt / 1000) * 1000
        
        val finalUrl = "https://$subdomain.xporn.tv/$pathSegment/$videoFolder/$videoId/$videoId.mp4"

        callback.invoke(
            ExtractorLink(
                source = this.name,
                name = this.name,
                url = finalUrl,
                referer = "$mainUrl/videos/$videoId/",
                quality = Qualities.Unknown.value,
                type = ExtractorLinkType.VIDEO 
            )
        )
        return true
    }
}
