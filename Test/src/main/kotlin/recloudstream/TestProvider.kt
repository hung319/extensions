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
        
        // Trích xuất videoId trực tiếp từ URL của trang. Ví dụ: ".../videos/23672/..." -> "23672"
        val videoId = url.split("/")[4]
        
        val poster = document.selectFirst("meta[property=og:image]")?.attr("content")
        val description = document.selectFirst("meta[name=description]")?.attr("content")
        
        val recommendations = document.select("div.related-videos div.item").mapNotNull {
            it.toSearchResult()
        }
        
        // Truyền videoId cho hàm `loadLinks` để nó xây dựng link cuối cùng
        return newMovieLoadResponse(title, url, TvType.NSFW, videoId) {
            this.posterUrl = poster
            this.plot = description
            this.recommendations = recommendations
        }
    }
    
    override suspend fun loadLinks(
        data: String, // `data` bây giờ là videoId (ví dụ: "23672")
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val videoId = data

        // Tính toán thư mục chứa video dựa trên ID
        // Ví dụ: ID 23672 -> thư mục 23000. ID 62 -> thư mục 0.
        val videoFolder = (videoId.toInt() / 1000) * 1000
        
        // Xây dựng URL cuối cùng với tên miền phụ `vid.` và cấu trúc đường dẫn đã biết
        val finalUrl = "https://vid.xporn.tv/videos/$videoFolder/$videoId/$videoId.mp4"

        callback.invoke(
            ExtractorLink(
                source = this.name,
                name = this.name,
                url = finalUrl,
                referer = "$mainUrl/videos/$videoId/", // Gửi referer để đảm bảo link hoạt động
                quality = Qualities.Unknown.value,
                type = ExtractorLinkType.VIDEO // Vẫn để HLS cho linh hoạt
            )
        )
        return true
    }
}
