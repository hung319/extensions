package com.lagradost.cloudstream3.plugins.exampleplugin // Ensure this matches your package structure

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Document
import android.util.Log // Import for logging

private const val MAIN_URL = "https://www.scrapingcourse.com/cloudflare-challenge"

class TestProvider : MainAPI() { // Your class name
    override var name = "Cloudflare Test"
    override var mainUrl = MAIN_URL
    override var supportedTypes = setOf(TvType.Others)

    override val hasMainPage = true
    // As before, 'hasSearch' is not needed.
    // Implement 'suspend fun search(query: String): List<SearchResponse> { ... }' if you need search.

    // This is the function where the error at line 49 occurs
    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {
        val items = listOf(
            // THE ERROR IS IN THIS CALL: Ensure 'url = MAIN_URL' is present
            newMovieSearchResponse(
                name = "Test Cloudflare Page",
                url = MAIN_URL, // <<< THIS IS THE REQUIRED PARAMETER
                TvType.Movie
            ) {
                // You can add posterUrl here if needed, e.g.:
                // this.posterUrl = "your_poster_image_url_here"
            }
        )
        return newHomePageResponse("Cloudflare Challenge Test", items)
    }

    override suspend fun load(url: String): LoadResponse? {
        // The 'url' parameter here is the 'dataUrl' for the content
        if (url != MAIN_URL) return null

        Log.d("CloudflareTestPlugin", "Attempting to load URL: $url")
        // Use app.get() to fetch the document. Cloudstream handles Cloudflare.
        val document: Document = app.get(url).document
        Log.d("CloudflareTestPlugin", "Page content after app.get: ${document.html().take(500)}")

        val title = document.selectFirst("h1")?.text() ?: "Không tìm thấy tiêu đề H1"
        val bodyText = document.selectFirst("body p")?.text() ?: "Không tìm thấy nội dung đoạn văn."
        Log.d("CloudflareTestPlugin", "Extracted Title: $title")
        Log.d("CloudflareTestPlugin", "Extracted Body: $bodyText")

        return newMovieLoadResponse(
            name = title,
            dataUrl = url, // This 'url' is correctly referred to as 'dataUrl' here
            type = TvType.Movie
        ) {
            this.plot = "Nội dung trang: $bodyText\n\nĐây là thử nghiệm tải trang được bảo vệ bởi Cloudflare. Nếu bạn thấy nội dung thực tế của trang, điều đó có nghĩa là Cloudflare đã được vượt qua (có thể thông qua WebView hoặc xử lý tự động)."
            // Add other details if needed:
            // this.year = 2025
            // this.posterUrl = "your_poster_image_url_here"
        }
    }
}

// Removed duplicate logD function, using android.util.Log directly in 'load'
