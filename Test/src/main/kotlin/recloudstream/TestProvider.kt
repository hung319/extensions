package com.lagradost.cloudstream3.plugins.exampleplugin // Hoặc package của bạn

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Document
import android.util.Log

// Giả sử NeedsWebViewException được định nghĩa trong Cloudstream như sau:
// import com.lagradost.cloudstream3.NeedsWebViewException
// Nếu không có sẵn, bạn cần tìm Exception tương đương hoặc cấu hình Cloudstream của bạn hỗ trợ nó.
// Trong nhiều trường hợp, Cloudstream đã có sẵn các Exception như thế này.

private const val MAIN_URL = "https://www.scrapingcourse.com/cloudflare-challenge"
// Một đoạn text đặc trưng có trên trang nội dung THỰC SỰ (sau khi vượt qua Cloudflare)
// của trang https://www.scrapingcourse.com/cloudflare-challenge
private const val ACTUAL_CONTENT_TEXT_INDICATOR = "This is the actual content of the page protected by Cloudflare."

class TestProvider : MainAPI() {
    override var name = "Cloudflare Test"
    override var mainUrl = MAIN_URL
    override var supportedTypes = setOf(TvType.Others)
    override val hasMainPage = true

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {
        val items = listOf(
            MovieSearchResponse(
                name = "Test Cloudflare Page",
                url = MAIN_URL,
                apiName = this.name,
                type = TvType.Movie,
            )
        )
        return HomePageResponse(listOf(HomePageList("Cloudflare Challenge Test", items)))
    }

    override suspend fun load(url: String): LoadResponse? {
        if (url != MAIN_URL) return null

        Log.d("TestProvider", "Bắt đầu tải URL (Lần thử đầu hoặc sau WebView): $url")
        val document: Document = app.get(url).document
        Log.d("TestProvider", "Tiêu đề trang nhận được: ${document.title()}")

        // Kiểm tra xem nội dung thực sự đã được tải hay chưa
        // bằng cách tìm một đoạn text đặc trưng của trang đích (sau khi qua Cloudflare)
        val actualContentElement = document.select("p:contains($ACTUAL_CONTENT_TEXT_INDICATOR)")

        if (actualContentElement.isEmpty()) {
            // Nếu KHÔNG tìm thấy đoạn text đặc trưng của nội dung thực sự,
            // có nghĩa là chúng ta vẫn đang ở trang Cloudflare challenge hoặc một trang trung gian.
            // Yêu cầu mở WebView.
            Log.w("TestProvider", "Không tìm thấy chỉ báo nội dung thực tế. Yêu cầu WebView cho: $url")
            // Ném Exception để báo cho Cloudstream mở WebView
            // Đảm bảo rằng Cloudstream của bạn hỗ trợ xử lý Exception này.
            // Nếu NeedsWebViewException không đúng, bạn cần tìm Exception phù hợp
            // mà phiên bản Cloudstream của bạn sử dụng (ví dụ: CloudflareChallengeException, CaptchaException)
            throw NeedsWebViewException(url)
        } else {
            // Nếu TÌM THẤY đoạn text đặc trưng, có nghĩa là Cloudflare đã được vượt qua
            // và chúng ta đã có nội dung thực sự của trang.
            Log.i("TestProvider", "Đã tìm thấy chỉ báo nội dung thực tế. Trích xuất nội dung...")

            val title = document.selectFirst("h1")?.text() ?: "Không tìm thấy tiêu đề H1 (trang nội dung)"
            // Lấy chính đoạn text đặc trưng đó làm body cho đơn giản, hoặc bạn có thể chọn lọc kỹ hơn
            val bodyText = actualContentElement.first()?.text() ?: "Không tìm thấy nội dung đoạn văn (trang nội dung)."

            Log.d("TestProvider", "Tiêu đề trích xuất (trang nội dung): $title")
            Log.d("TestProvider", "Nội dung body (trang nội dung): $bodyText")

            return MovieLoadResponse(
                name = title,
                url = url,
                apiName = this.name,
                type = TvType.Movie,
                dataUrl = url,
                plot = "Nội dung đã vượt qua Cloudflare: $bodyText",
            )
        }
    }
}

// QUAN TRỌNG:
// Lớp NeedsWebViewException thường là một phần của Cloudstream. Ví dụ:
// package com.lagradost.cloudstream3 // Hoặc một package tiện ích
// class NeedsWebViewException(val url: String, val reason: String = "Page requires WebView interaction") : Exception(reason)
// Bạn không cần định nghĩa lại nếu nó đã tồnTAIN trong Cloudstream bạn dùng.
// Nếu không, bạn sẽ cần một cơ chế tương tự để báo hiệu cho app mở WebView.
