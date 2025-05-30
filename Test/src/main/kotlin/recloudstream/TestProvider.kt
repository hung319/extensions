// Khai báo package - Đảm bảo tên package khớp với cấu trúc thư mục của bạn
// Ví dụ: nếu plugin của bạn nằm trong .../plugins/MySuperPlugin/
// thì package có thể là com.lagradost.cloudstream3.plugins.mysuperplugin
package com.lagradost.cloudstream3.plugins.exampleplugin // Thay 'exampleplugin' bằng tên thư mục plugin của bạn

// Import các lớp cần thiết từ Cloudstream API
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Document // Thư viện Jsoup để parse HTML

// Định nghĩa loại nội dung chính mà provider này cung cấp (ví dụ: Phim)
// Cloudstream hỗ trợ nhiều loại như TVSeries, Movie, Anime, Live, Others
private const val MAIN_URL = "https://www.scrapingcourse.com/cloudflare-challenge"

class ExampleProvider : MainAPI() { // Kế thừa từ MainAPI
    // Tên sẽ hiển thị trong UI của Cloudstream
    override var name = "Cloudflare Test"
    // URL chính của trang web (ít quan trọng với plugin chỉ có một trang cụ thể)
    override var mainUrl = MAIN_URL
    // Các loại nội dung được hỗ trợ bởi provider này
    override var supportedTypes = setOf(TvType.Others) // Sử dụng 'Others' cho mục đích chung

    // Tắt hỗ trợ tìm kiếm vì chúng ta chỉ tải một trang cụ thể
    override val hasMainPage = true // Chúng ta sẽ tạo một "trang chính" giả để tải nội dung
    override val hasSearch = false

    // Hàm này được gọi để tải "trang chính" của provider
    // Chúng ta sẽ dùng nó để hiển thị một mục duy nhất trỏ đến trang Cloudflare
    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {
        // Tạo một danh sách chứa một mục duy nhất
        // Mục này sẽ đại diện cho trang Cloudflare chúng ta muốn kiểm tra
        val items = listOf(
            // Tạo một SearchResponse đơn giản để hiển thị
            // Data URL sẽ là URL thực tế mà chúng ta muốn tải khi người dùng nhấp vào
            newMovieSearchResponse(
                name = "Test Cloudflare Page", // Tên hiển thị cho mục
                url = MAIN_URL, // URL sẽ được tải khi mục này được chọn
                TvType.Movie // Loại nội dung (chọn Movie cho đơn giản)
            ) {
                // Có thể thêm poster ở đây nếu muốn, nhưng không cần thiết cho thử nghiệm này
                // this.posterUrl = "your_poster_url_here"
            }
        )
        // Trả về một HomePageResponse chứa danh sách các mục
        // "Cloudflare Challenge Test" là tiêu đề của phần này trên trang chính
        return newHomePageResponse("Cloudflare Challenge Test", items)
    }

    // Hàm này được gọi khi người dùng chọn một mục (trong trường hợp này là mục "Test Cloudflare Page")
    // Nó chịu trách nhiệm tải thông tin chi tiết của mục đó
    override suspend fun load(url: String): LoadResponse? {
        // Kiểm tra xem URL có phải là URL chúng ta muốn xử lý không
        if (url != MAIN_URL) return null

        // Sử dụng app.get để tải nội dung từ URL.
        // Cloudstream (và thư viện OkHttp bên dưới) sẽ cố gắng xử lý các thách thức như Cloudflare.
        // Nếu gặp Cloudflare, ứng dụng có thể tự động mở WebView để người dùng giải quyết.
        // Sau khi giải quyết xong, app.get sẽ trả về nội dung trang thực tế.
        logD("Attempting to load URL: $url")
        val document: Document = app.get(url).document // .document để parse HTML bằng Jsoup

        logD("Page content after app.get: ${document.html().take(500)}") // Log một phần HTML

        // Trích xuất tiêu đề H1 từ trang
        val title = document.selectFirst("h1")?.text() ?: "Không tìm thấy tiêu đề H1"
        val bodyText = document.selectFirst("body p")?.text() ?: "Không tìm thấy nội dung đoạn văn."

        logD("Extracted Title: $title")
        logD("Extracted Body: $bodyText")

        // Tạo và trả về một MovieLoadResponse với thông tin đã trích xuất
        // Thông tin này sẽ được hiển thị trên trang chi tiết của mục
        return newMovieLoadResponse(
            name = title, // Tên của "phim" sẽ là tiêu đề H1 của trang
            url = url, // URL gốc
            type = TvType.Movie, // Loại nội dung
            plot = "Nội dung trang: $bodyText\n\nĐây là thử nghiệm tải trang được bảo vệ bởi Cloudflare. Nếu bạn thấy nội dung thực tế của trang, điều đó có nghĩa là Cloudflare đã được vượt qua (có thể thông qua WebView hoặc xử lý tự động)."
        ) {
            // Có thể thêm các thông tin khác ở đây nếu muốn
            // posterUrl = "..."
            // year = 2024
        }
    }
}

// Helper function để ghi log (nếu bạn muốn xem log trong Logcat của Android Studio)
private fun logD(message: String) {
    android.util.Log.d("CloudflareTestPlugin", message)
}
