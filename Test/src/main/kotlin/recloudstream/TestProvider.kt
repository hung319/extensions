// Package recloudstream
package recloudstream

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.loadExtractor
import org.jsoup.nodes.Element
import android.util.Base64

class Av123Provider : MainAPI() {
    override var mainUrl = "https://www1.123av.com"
    override var name = "123AV"
    override val hasMainPage = true
    override var lang = "vi"
    override val supportedTypes = setOf(TvType.NSFW)

    override val mainPage = mainPageOf(
        "dm5/new-release" to "Mới phát hành",
        "dm6/recent-update" to "Cập nhật gần đây",
        "dm5/trending" to "Đang thịnh hành"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = "$mainUrl/vi/${request.data}?page=$page"
        val document = app.get(url).document
        val home = document.select("div.box-item-list div.box-item").mapNotNull {
            it.toSearchResult()
        }
        return newHomePageResponse(request.name, home)
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val a = this.selectFirst("a") ?: return null
        val href = fixUrl(a.attr("href"))
        if (href.isBlank()) return null

        val title = this.selectFirst("div.detail a")?.text()?.trim() ?: return null
        val posterUrl = this.selectFirst("div.thumb img")?.attr("data-src")

        return newMovieSearchResponse(title, href, TvType.NSFW) {
            this.posterUrl = posterUrl
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val url = "$mainUrl/vi/search?keyword=$query"
        val document = app.get(url).document

        return document.select("div.box-item-list div.box-item").mapNotNull {
            it.toSearchResult()
        }
    }

    override suspend fun load(url: String): LoadResponse? {
        val document = app.get(url).document

        val title = document.selectFirst("h1")?.text()?.trim() ?: return null
        val poster = document.selectFirst("meta[property=og:image]")?.attr("content")
        val tags = document.select("span.genre a").map { it.text() }
        val description = document.selectFirst("div.description p")?.text()

        val movieId = document.selectFirst("#page-video")
            ?.attr("v-scope")
            ?.substringAfter("Movie({id: ")
            ?.substringBefore(",")
            ?.trim() ?: return null

        return newMovieLoadResponse(title, url, TvType.NSFW, movieId) {
            this.posterUrl = poster
            this.plot = description
            this.tags = tags
        }
    }

    // Hàm giải mã XOR
    private fun xorDecode(input: String): String {
        val key = "MW4gC3v5a1q2E6Z"
        val base64Decoded = Base64.decode(input, Base64.DEFAULT)
        val result = StringBuilder()
        for (i in base64Decoded.indices) {
            result.append((base64Decoded[i].toInt() xor key[i % key.length].code).toChar())
        }
        return result.toString()
    }

    override suspend fun loadLinks(
        data: String, // movieId
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        // 1. Gọi API để lấy URL đã được mã hóa
        val apiUrl = "$mainUrl/vi/ajax/v/$data/videos"
        val res = app.get(apiUrl).parsedSafe<ApiResponse>()

        // 2. Lặp qua từng phần của video
        res?.result?.watch?.apmap { watchItem ->
            val encodedUrl = watchItem.url ?: return@apmap

            // 3. Giải mã URL bằng logic đã tìm thấy
            val decodedPath = xorDecode(encodedUrl)
            val iframeUrl = "https://surrit.store$decodedPath"

            // 4. Sử dụng loadExtractor để lấy link m3u8 cuối cùng
            loadExtractor(iframeUrl, mainUrl, subtitleCallback, callback)
        }

        return true
    }

    // Các lớp dữ liệu để phân tích JSON
    data class WatchItem(val url: String?)
    data class ApiResult(val watch: List<WatchItem>?)
    data class ApiResponse(val result: ApiResult?)
}
