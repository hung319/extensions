// Đảm bảo package này khớp với cấu trúc thư mục plugin của bạn
package com.lagradost.cloudstream3.plugins.exampleplugin // Ví dụ: com.lagradost.cloudstream3.plugins.test

// Các import cần thiết
import com.lagradost.cloudstream3.* // API chính của Cloudstream
import com.lagradost.cloudstream3.utils.* // Các tiện ích (nếu có sử dụng)
import org.jsoup.nodes.Document // Để parse HTML (nếu bạn lấy nội dung từ web)
import android.util.Log // Để ghi log gỡ lỗi

// URL mục tiêu của trang web bạn muốn thử nghiệm
private const val MAIN_URL = "https://www.scrapingcourse.com/cloudflare-challenge"

class TestProvider : MainAPI() {
    // Tên của Provider, sẽ hiển thị trong ứng dụng
    override var name = "Cloudflare Test"

    // URL chính của trang web (có thể không quá quan trọng nếu chỉ test một trang)
    override var mainUrl = MAIN_URL

    // Các loại nội dung mà provider này hỗ trợ (ví dụ: Movies, TVSeries, Anime, Others)
    override var supportedTypes = setOf(TvType.Others)

    // Provider này có trang chính để hiển thị danh sách các mục
    override val hasMainPage = true

    // Hàm được gọi để tải nội dung cho trang chính của provider
    override suspend fun getMainPage(
        page: Int, // Số trang (nếu có phân trang)
        request: MainPageRequest // Thông tin request cho trang chính
    ): HomePageResponse {
        // Tạo một danh sách các mục sẽ hiển thị trên trang chính
        // Ở đây, chúng ta tạo một mục duy nhất để test trang Cloudflare
        val items = listOf(
            // Sử dụng trực tiếp constructor của MovieSearchResponse
            // MovieSearchResponse thường đã được định nghĩa trong Cloudstream core.
            // Bạn cần đảm bảo các tham số bắt buộc (name, url, apiName) được cung cấp.
            MovieSearchResponse(
                name = "Test Cloudflare Page",  // Tên hiển thị cho mục này
                url = MAIN_URL,                 // URL sẽ được truyền vào hàm load() khi mục này được chọn
                apiName = this.name,            // Tên của provider này ( quan trọng cho Cloudstream)
                type = TvType.Movie,            // Phân loại mục này là Movie (hoặc TvType.Others nếu chung chung)
                // Các trường tùy chọn khác có thể thêm vào nếu cần:
                // posterUrl = "https://link.den.anh_poster.jpg",
                // year = 2025,
                // quality = SearchQuality.HD, // Ví dụ về chất lượng
            )
        )

        // Trả về một HomePageResponse, chứa tiêu đề cho nhóm mục và danh sách các mục
        return HomePageResponse(listOf(HomePageList("Cloudflare Challenge Test", items)))
    }

    // Hàm được gọi khi người dùng chọn một mục từ trang chính (hoặc kết quả tìm kiếm)
    // để xem thông tin chi tiết.
    override suspend fun load(url: String): LoadResponse? {
        // Tham số 'url' ở đây là URL được truyền từ MovieSearchResponse (MAIN_URL trong trường hợp này)
        if (url != MAIN_URL) {
            // Nếu URL không phải là URL chúng ta mong đợi, bỏ qua
            return null
        }

        Log.d("TestProvider", "Bắt đầu tải URL: $url")

        // Sử dụng app.get() để Cloudstream thực hiện request.
        // Cloudstream (thông qua OkHttp và các interceptor) sẽ cố gắng xử lý Cloudflare.
        // Nếu cần, nó có thể kích hoạt WebView để người dùng tương tác.
        val document: Document = app.get(url).document // Lấy nội dung HTML và parse bằng Jsoup

        Log.d("TestProvider", "Nội dung trang sau khi app.get (500 ký tự đầu): ${document.html().take(500)}")

        // Trích xuất thông tin từ trang đã tải (ví dụ: tiêu đề, mô tả)
        val title = document.selectFirst("h1")?.text() ?: "Không tìm thấy tiêu đề H1"
        val bodyText = document.selectFirst("body p")?.text() ?: "Không tìm thấy nội dung đoạn văn."

        Log.d("TestProvider", "Tiêu đề trích xuất được: $title")
        Log.d("TestProvider", "Nội dung body trích xuất được: $bodyText")

        // Tạo và trả về một đối tượng LoadResponse (ví dụ: MovieLoadResponse)
        // chứa thông tin chi tiết đã trích xuất.
        return MovieLoadResponse(
            name = title,                   // Tên của nội dung (ví dụ: tên phim/tập)
            url = url,                      // URL gốc của nội dung này (cũng là dataUrl)
            apiName = this.name,            // Tên của provider
            type = TvType.Movie,            // Loại nội dung
            dataUrl = url,                  // URL dữ liệu (thường giống url chính)
            plot = "Nội dung trang: $bodyText\n\nĐây là thử nghiệm tải trang được bảo vệ bởi Cloudflare. Nếu bạn thấy nội dung thực tế của trang, điều đó có nghĩa là Cloudflare đã được vượt qua.",
            // Các trường tùy chọn khác:
            // year = 2025,
            // posterUrl = "https://link.den.anh_poster_chi_tiet.jpg",
            // recommendations = listOf(...) // Danh sách đề xuất (nếu có)
        )
    }

    // Bạn có thể thêm hàm search nếu muốn provider hỗ trợ tìm kiếm:
    // override suspend fun search(query: String): List<SearchResponse> {
    // // Logic tìm kiếm của bạn ở đây
    // return listOf<SearchResponse>()
    // }
}

/*
LƯU Ý QUAN TRỌNG:
Data class `MovieSearchResponse` và `MovieLoadResponse` (cũng như các `SearchResponse`, `LoadResponse` khác)
thường đã được định nghĩa sẵn trong thư viện cốt lõi của Cloudstream (ví dụ: trong package `com.lagradost.cloudstream3`).
Bạn KHÔNG CẦN định nghĩa lại chúng trong file plugin này.
Chỉ cần đảm bảo bạn đã import chúng đúng cách nếu cần thiết (thường thì Kotlin tự động gợi ý import).
Ví dụ về khai báo có thể có trong Cloudstream (bạn không cần thêm vào đây):

data class MovieSearchResponse(
    override val name: String,
    override val url: String,
    override val apiName: String,
    override var type: TvType? = null,
    override var posterUrl: String? = null,
    override var id: Int? = null,
    var year: Int? = null,
    var quality: SearchQuality? = null,
    var posterHeaders: Map<String, String>? = null
) : SearchResponse() // Kế thừa từ SearchResponse hoặc một lớp cơ sở tương tự

data class MovieLoadResponse(
    override val name: String,
    override val url: String,
    override val apiName: String,
    override val type: TvType,
    override val dataUrl: String, // Thường là URL để lấy dữ liệu stream
    var plot: String? = null,
    var year: Int? = null,
    var posterUrl: String? = null,
    var rating: Int? = null, // 0-10000
    var recommendations: List<SearchResponse>? = null,
    var duration: Long? = null, // milliseconds
    var actors: List<ActorData>? = null,
    var tags: List<String>? = null
) : LoadResponse() // Kế thừa từ LoadResponse hoặc một lớp cơ sở tương tự
*/
