// File: FanxxxProvider.kt
package recloudstream // Thay đổi package name

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType // Import ExtractorLinkType
import com.lagradost.cloudstream3.utils.getQualityFromName
import org.jsoup.nodes.Element

open class FanxxxProvider : MainAPI() {
    override var mainUrl = "https://fanxxx.org"
    override var name = "Fanxxx"
    override val hasMainPage = true
    override var lang = "en"
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(
        TvType.NSFW
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val document = app.get("$mainUrl/page/$page/").document
        val home = document.select("article.post").mapNotNull {
            it.toSearchResult()
        }
        return newHomePageResponse(request.name, home)
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val href = this.selectFirst("a")?.attr("href") ?: return null
        val title = this.selectFirst("h3.post-title span.title")?.text() ?: "No Title"
        val posterUrl = this.selectFirst("div.post-thumbnail img")?.attr("src")

        return newMovieSearchResponse(title, href, TvType.Movie) {
            this.posterUrl = posterUrl
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val searchUrl = "$mainUrl/?s=$query"
        val document = app.get(searchUrl).document

        return document.select("article.post").mapNotNull {
            it.toSearchResult()
        }
    }

    override suspend fun load(url: String): LoadResponse? {
        val document = app.get(url).document

        val title = document.selectFirst("h1.entry-title-single")?.text()?.trim() ?: "No Title"
        val poster = document.selectFirst("meta[property=og:image]")?.attr("content")
        val description = document.selectFirst("div.entry-content-single > p")?.text()
        val downloadPageUrl = document.selectFirst("div.download-link a")?.attr("href")

        return newMovieLoadResponse(title, url, TvType.Movie, downloadPageUrl) {
            this.posterUrl = poster
            this.plot = description
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        // `data` là URL tới hglink.to, sẽ redirect sang davioad.com
        val downloadPageDoc = app.get(data, referer = "$mainUrl/").document
        val downloadPageUrl = downloadPageDoc.location() // Lấy URL cuối cùng sau redirect (davioad.com/e/...)

        val op = downloadPageDoc.selectFirst("input[name=op]")?.attr("value")
        val id = downloadPageDoc.selectFirst("input[name=id]")?.attr("value")
        val rand = downloadPageDoc.selectFirst("input[name=rand]")?.attr("value")

        if (op == null || id == null || rand == null) return false

        val postData = mapOf(
            "op" to op,
            "id" to id,
            "rand" to rand,
            "referer" to "",
            "method_free" to "Free Download",
            "method_premium" to ""
        )

        val finalPageDoc = app.post(
            downloadPageUrl,
            data = postData,
            referer = downloadPageUrl // Gửi referer là trang download
        ).document

        // Tìm link video, ưu tiên source tag
        val videoUrl = finalPageDoc.selectFirst("source")?.attr("src")
            ?: finalPageDoc.selectFirst("a[href*=.mp4], a[href*=.m3u8]")?.attr("href")

        videoUrl?.let {
            val isM3u8 = it.contains(".m3u8")
            
            // **Thêm headers vào link**
            // Dựa trên curl, referer là trang `davioad.com/e/...`
            val streamHeaders = mapOf(
                "Referer" to downloadPageUrl,
                "User-Agent" to "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/137.0.0.0 Mobile Safari/537.36"
            )

            callback.invoke(
                ExtractorLink(
                    source = this.name,
                    name = "Davioad Server",
                    url = it,
                    referer = downloadPageUrl, // Referer chính
                    quality = getQualityFromName(""), // Tự động xác định chất lượng
                    type = if (isM3u8) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO, // Đặt type
                    headers = streamHeaders // Thêm headers
                )
            )
        }

        return true
    }
}
