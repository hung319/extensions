// Đảm bảo package này khớp với cấu trúc thư mục của bạn
package com.lagradost.cloudstream3.plugins.exampleplugin

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Document
import android.util.Log // Import Log để ghi log

// URL mục tiêu để thử nghiệm
private const val MAIN_URL = "https://www.scrapingcourse.com/cloudflare-challenge"

class TestProvider : MainAPI() { // Tên lớp Provider của bạn
    override var name = "Cloudflare Test"
    override var mainUrl = MAIN_URL
    override var supportedTypes = setOf(TvType.Others) // Loại nội dung hỗ trợ

    override val hasMainPage = true // Provider này có trang chính

    // Hàm getMainPage: được gọi để tải các mục trên trang chính của provider
    // LỖI CỦA BẠN NẰM Ở TRONG HÀM NÀY, TẠI DÒNG GỌI `newMovieSearchResponse`
    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {
        val items = listOf(
            // DÒNG GÂY LỖI (HOẶC QUANH ĐÂY - DÒNG ~55):
            // Đảm bảo rằng `url = MAIN_URL,` được viết chính xác và không bị thiếu.
            newMovieSearchResponse(
                name = "Test Cloudflare Page", // Tên hiển thị của mục
                url = MAIN_URL,               // <<< DÒNG NÀY RẤT QUAN TRỌNG VÀ PHẢI CÓ
                TvType.Movie                  // Loại của mục (ở đây là Movie cho đơn giản)
            ) {
                // Bạn có thể thêm posterUrl ở đây nếu muốn, ví dụ:
                // this.posterUrl = "https://your.image.url/poster.jpg"
            }
        )
        // Trả về một HomePageResponse với tiêu đề và danh sách các mục
        return newHomePageResponse("Cloudflare Challenge Test", items)
    }

    // Hàm load: được gọi khi người dùng chọn một mục để xem chi tiết
    override suspend fun load(url: String): LoadResponse? {
        // Tham số 'url' ở đây chính là 'dataUrl' mà `newMovieSearchResponse` đã cung cấp
        if (url != MAIN_URL) return null // Chỉ xử lý URL của chúng ta

        Log.d("CloudflareTestPlugin", "Bắt đầu tải URL: $url")

        // Sử dụng app.get() để lấy nội dung trang.
        // Cloudstream sẽ cố gắng xử lý các vấn đề như Cloudflare (có thể mở WebView nếu cần).
        val document: Document = app.get(url).document
        Log.d("CloudflareTestPlugin", "Nội dung trang sau khi app.get (500 ký tự đầu): ${document.html().take(500)}")

        // Trích xuất thông tin từ trang
        val title = document.selectFirst("h1")?.text() ?: "Không tìm thấy tiêu đề H1"
        val bodyText = document.selectFirst("body p")?.text() ?: "Không tìm thấy nội dung đoạn văn."
        Log.d("CloudflareTestPlugin", "Tiêu đề trích xuất được: $title")
        Log.d("CloudflareTestPlugin", "Nội dung body trích xuất được: $bodyText")

        // Trả về thông tin chi tiết của mục
        return newMovieLoadResponse(
            name = title,               // Tên của nội dung (lấy từ H1)
            dataUrl = url,              // URL gốc của nội dung này
            type = TvType.Movie,        // Loại nội dung
        ) {
            // Gán nội dung mô tả (plot)
            this.plot = "Nội dung trang: $bodyText\n\nĐây là thử nghiệm tải trang được bảo vệ bởi Cloudflare. Nếu bạn thấy nội dung thực tế của trang, điều đó có nghĩa là Cloudflare đã được vượt qua (có thể thông qua WebView hoặc xử lý tự động)."
            // Bạn có thể thêm các thông tin khác ở đây:
            // this.year = 2025 // Năm phát hành
            // this.posterUrl = "https://your.image.url/detail_poster.jpg" // Poster cho trang chi tiết
        }
    }
}
