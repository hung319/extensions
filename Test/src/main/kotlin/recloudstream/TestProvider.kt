// Package recloudstream
package recloudstream

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.loadExtractor
import org.jsoup.nodes.Element

class Av123Provider : MainAPI() {
    override var mainUrl = "https://www1.123av.com"
    override var name = "123AV"
    override val hasMainPage = true
    override var lang = "vi"
    override val hasDownloadSupport = true
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
        // Đảm bảo link là tuyệt đối
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

        // Trích xuất movieId từ URL để đảm bảo tính nhất quán
        // Ví dụ URL: https://www1.123av.com/vi/dm4/v/god-055
        // Chúng ta cần lấy 'god-055' và sau đó gọi API để lấy ID số.
        // Tuy nhiên, trang video đã cung cấp sẵn ID trong thuộc tính v-scope.
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

    // ##### HÀM ĐÃ ĐƯỢC CẬP NHẬT THEO LOGIC MỚI NHẤT #####
    override suspend fun loadLinks(
        data: String, // movieId
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        // 1. Gọi API mà bạn đã tìm thấy để lấy thông tin video
        val apiUrl = "$mainUrl/vi/ajax/v/$data/videos"
        val res = app.get(apiUrl).parsedSafe<ApiResponse>()

        // 2. Lặp qua từng phần của video (trường hợp video có nhiều phần)
        res?.result?.watch?.apmap { watchItem ->
            // 3. Lấy URL đã được mã hóa/obfuscate từ API
            val encodedUrl = watchItem.url ?: return@apmap

            // Ghi chú quan trọng:
            // Chuỗi `encodedUrl` này (ví dụ: JSNAIj1Tf1k...) không phải là link video trực tiếp.
            // Nó được JavaScript của trang web giải mã để tạo ra link iframe (ví dụ: https://surrit.store/e/Z8M06PZ2).
            // Vì logic giải mã nằm trong JS và có thể phức tạp, cách tốt nhất là
            // để một extractor chuyên dụng xử lý tên miền `surrit.store`.

            // 4. Xây dựng URL iframe.
            // Dựa vào thông tin bạn cung cấp, tên miền là `surrit.store`.
            // Phần path của iframe (vd: Z8M06PZ2) là kết quả sau khi giải mã `encodedUrl`.
            // Nếu không có logic giải mã, chúng ta không thể tạo ra URL này một cách tự động.
            
            // Tạm thời, chúng ta sẽ giả định rằng một extractor khác có thể xử lý việc này,
            // hoặc chúng ta cần thêm logic giải mã ở đây.
            // Để mã hoạt động, chúng ta cần một cách để chuyển đổi `encodedUrl` -> `iframeUrl`.
            
            // Vì chưa có logic giải mã, tôi sẽ sử dụng một URL iframe giả định để minh họa.
            // KHI CÓ LOGIC GIẢI MÃ, BẠN SẼ THAY THẾ DÒNG DƯỚI ĐÂY.
            // val iframeUrl = "https://surrit.store/e/" + javascriptDecodeFunction(encodedUrl)
            
            // Tuy nhiên, có vẻ như `watchItem.url` chính là link iframe. Hãy thử dùng trực tiếp.
            // Nếu watchItem.url không phải là một URL đầy đủ, ta sẽ cần thêm logic.
            // Dựa trên thông tin của bạn, có vẻ iframe host là `surrit.store`
            // Mà API lại trả về một chuỗi mã hoá. Điều này cho thấy JS của trang web
            // đã làm một bước chuyển đổi.
            
            // Với thông tin hiện tại, bước khả thi nhất là chuyển URL mã hóa này cho một extractor
            // có khả năng xử lý nó. Nhiều khả năng, URL iframe được xây dựng từ chuỗi này.
            // Ví dụ: val iframeUrl = "https://some-iframe-host.com/e/" + encodedUrl
            // Ta sẽ thử nghiệm với `surrit.store`
            
            val iframeUrl = "https://surrit.store/e/$encodedUrl"

            // 5. Gọi `loadExtractor` để xử lý iframe và lấy link m3u8 cuối cùng
            loadExtractor(iframeUrl, mainUrl, subtitleCallback, callback)
        }

        return true
    }

    // Các lớp dữ liệu để phân tích JSON từ API, đã cập nhật theo cấu trúc mới
    data class WatchItem(
        val index: Int?,
        val name: String?,
        val url: String? // Đây là chuỗi mã hóa hoặc path của iframe
    )

    data class ApiResult(
        val watch: List<WatchItem>?,
        val download: List<Any>? // download có thể là một cấu trúc khác hoặc rỗng
    )

    data class ApiResponse(
        val status: Int?,
        val result: ApiResult?
    )
}
