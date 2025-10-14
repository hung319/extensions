// File: FanxxxProvider.kt
package recloudstream

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
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

    // SỬA LỖI: Cập nhật selector để lấy đúng title và poster
    private fun Element.toSearchResult(): SearchResponse? {
        val href = this.selectFirst("a")?.attr("href") ?: return null
        // SỬA: Lấy text từ thẻ 'a' thay vì 'span' không tồn tại
        val title = this.selectFirst("h3.post-title a")?.text() ?: "No Title"
        // SỬA: Lấy ảnh từ 'data-src' do trang web dùng lazy loading
        val posterUrl = this.selectFirst("div.post-thumbnail img")?.attr("data-src")

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

        // Giữ nguyên selector này vì nó đã đúng
        val title = document.selectFirst("h1.entry-title-single")?.text()?.trim() ?: "No Title"
        val poster = document.selectFirst("meta[property=og:image]")?.attr("content")
        val description = document.selectFirst("div.entry-content-single > p")?.text()
        
        // SỬA LỖI: Dùng selector ổn định hơn để tìm đúng link download
        val downloadPageUrl = document.selectFirst("a[href*=\"hglink.to\"]")?.attr("href")

        // Nếu không tìm thấy link download, sẽ không thể xem phim
        if (downloadPageUrl == null) return null

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
        // Logic phần này vẫn giữ nguyên vì nó đã xử lý đúng trang download
        val downloadPageDoc = app.get(data, referer = "$mainUrl/").document
        val downloadPageUrl = downloadPageDoc.location() 

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
            referer = downloadPageUrl
        ).document

        val videoUrl = finalPageDoc.selectFirst("source")?.attr("src")
            ?: finalPageDoc.selectFirst("a[href*=.mp4], a[href*=.m3u8]")?.attr("href")

        videoUrl?.let {
            val isM3u8 = it.contains(".m3u8")
            
            val streamHeaders = mapOf(
                "Referer" to downloadPageUrl,
                "User-Agent" to "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/137.0.0.0 Mobile Safari/537.36"
            )

            callback.invoke(
                ExtractorLink(
                    source = this.name,
                    name = "Davioad Server",
                    url = it,
                    referer = downloadPageUrl,
                    quality = getQualityFromName(""),
                    type = if (isM3u8) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO,
                    headers = streamHeaders
                )
            )
        }

        return true
    }
}
