package com.lagradost.cloudstream3.plugins.exampleplugin // Hoặc package của bạn

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Document

private const val MAIN_URL = "https://www.scrapingcourse.com/cloudflare-challenge"

class TestProvider : MainAPI() { // Hoặc tên lớp Provider của bạn
    override var name = "Cloudflare Test"
    override var mainUrl = MAIN_URL
    override var supportedTypes = setOf(TvType.Others)

    override val hasMainPage = true
    // XÓA BỎ DÒNG NÀY: override val hasSearch = false
    // Việc có hàm search() hay không sẽ quyết định khả năng tìm kiếm.
    // Nếu bạn không muốn có tìm kiếm, chỉ cần không triển khai (override) hàm search().

    // Nếu bạn không cần chức năng tìm kiếm, bạn có thể bỏ qua việc triển khai hàm search.
    // Nếu bạn CÓ cần tìm kiếm, bạn sẽ override nó như sau:
    // override suspend fun search(query: String): List<SearchResponse> {
    //     // Logic tìm kiếm của bạn ở đây
    //     return listOf()
    // }

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {
        val items = listOf(
            newMovieSearchResponse(
                name = "Test Cloudflare Page",
                url = MAIN_URL, // URL này sẽ được truyền vào hàm load() khi item được chọn
                TvType.Movie
            ) {
                // this.posterUrl = "your_poster_url_here"
            }
        )
        return newHomePageResponse("Cloudflare Challenge Test", items)
    }

    override suspend fun load(url: String): LoadResponse? { // `url` ở đây chính là `dataUrl`
        if (url != MAIN_URL) return null

        logD("Attempting to load URL: $url")
        val document: Document = app.get(url).document
        logD("Page content after app.get: ${document.html().take(500)}")

        val title = document.selectFirst("h1")?.text() ?: "Không tìm thấy tiêu đề H1"
        val bodyText = document.selectFirst("body p")?.text() ?: "Không tìm thấy nội dung đoạn văn."
        logD("Extracted Title: $title")
        logD("Extracted Body: $bodyText")

        // THAY ĐỔI Ở ĐÂY:
        return newMovieLoadResponse(
            name = title,
            dataUrl = url, // Sử dụng `dataUrl` thay cho `url` và truyền `url` của trang vào đây
            type = TvType.Movie
        ) {
            // Thuộc tính 'plot' (hoặc tương đương là 'description') thường được gán trong khối lambda này
            this.plot = "Nội dung trang: $bodyText\n\nĐây là thử nghiệm tải trang được bảo vệ bởi Cloudflare. Nếu bạn thấy nội dung thực tế của trang, điều đó có nghĩa là Cloudflare đã được vượt qua (có thể thông qua WebView hoặc xử lý tự động)."
            // Bạn cũng có thể gán các thuộc tính khác ở đây nếu cần:
            // this.posterUrl = "..."
            // this.year = 2024
            // this.recommendations = ... // etc.
        }
    }
}

private fun logD(message: String) {
    android.util.Log.d("CloudflareTestPlugin", message)
}
