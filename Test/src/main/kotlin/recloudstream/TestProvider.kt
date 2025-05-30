package com.lagradost.cloudstream3.plugins.exampleplugin // Or your actual package

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Document

private const val MAIN_URL = "https://www.scrapingcourse.com/cloudflare-challenge"

class TestProvider : MainAPI() {
    override var name = "Cloudflare Test"
    override var mainUrl = MAIN_URL
    override var supportedTypes = setOf(TvType.Others)

    override val hasMainPage = true
    // No 'hasSearch' override needed if you don't implement search

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {
        val items = listOf(
            newMovieSearchResponse(
                name = "Test Cloudflare Page",
                url = MAIN_URL, // FIX: Explicitly named the 'url' parameter
                TvType.Movie
            ) {
                // this.posterUrl = "your_poster_url_here"
            }
        )
        return newHomePageResponse("Cloudflare Challenge Test", items)
    }

    override suspend fun load(url: String): LoadResponse? {
        if (url != MAIN_URL) return null

        logD("Attempting to load URL: $url")
        val document: Document = app.get(url).document
        logD("Page content after app.get: ${document.html().take(500)}")

        val title = document.selectFirst("h1")?.text() ?: "Không tìm thấy tiêu đề H1"
        val bodyText = document.selectFirst("body p")?.text() ?: "Không tìm thấy nội dung đoạn văn."
        logD("Extracted Title: $title")
        logD("Extracted Body: $bodyText")

        return newMovieLoadResponse(
            name = title,
            dataUrl = url,
            type = TvType.Movie
        ) {
            this.plot = "Nội dung trang: $bodyText\n\nĐây là thử nghiệm tải trang được bảo vệ bởi Cloudflare. Nếu bạn thấy nội dung thực tế của trang, điều đó có nghĩa là Cloudflare đã được vượt qua (có thể thông qua WebView hoặc xử lý tự động)."
        }
    }
}

private fun logD(message: String) {
    android.util.Log.d("CloudflareTestPlugin", message)
}
