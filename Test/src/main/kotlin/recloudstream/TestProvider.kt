package com.example.vlxx

// Sửa các import từ 'com.lagacy.Cloudstream.API' sang 'com.lagradost.cloudstream3'
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.search.SearchResponse
import com.lagradost.cloudstream3.HomePageList
import com.lagradost.cloudstream3.MovieLoadResponse
import com.lagradost.cloudstream3.network.CloudstreamHttp
// import com.lagradost.cloudstream3.network.CloudstreamHttp.get // Không cần thiết vì đã có CloudstreamHttp.get
// import com.lagradost.cloudstream3.network.CloudstreamHttp.post // Không cần thiết
import com.lagradost.cloudstream3.utils.AppUtils.tryParseJson // Có thể không cần dùng nếu chưa xử lý JSON
import org.jsoup.Jsoup
import org.jsoup.nodes.Element

class VlxxProvider : MainAPI() { // Kế thừa MainAPI

    override val name = "VLXX.BZ" // Tên hiển thị của plugin
    override val mainUrl = "https://vlxx.bz" // URL chính của trang web
    override val supportedTypes = setOf(TvType.XXX) // Loại nội dung được hỗ trợ

    // Hàm này sẽ tải trang chủ hoặc các trang danh sách khác
    override fun getMainPage(page: Int, category: String?): HomePageList { // Đã sửa tham số thứ 2 thành 'category: String?'
        val url = if (page == 1) mainUrl else "$mainUrl/new/$page/"
        val doc = Jsoup.parse(CloudstreamHttp.get(url).text) // Tải và phân tích HTML

        val videoList = doc.select("div#video-list div.video-item").mapNotNull { element -> // Chọn tất cả các mục video
            val link = element.selectFirst("a") ?: return@mapNotNull null // Lấy thẻ <a> đầu tiên
            val title = link.attr("title").ifEmpty { link.selectFirst(".video-name a")?.text() } ?: return@mapNotNull null // Lấy tiêu đề từ 'title' hoặc từ text của thẻ <a> trong .video-name
            val href = link.attr("href") // Lấy đường dẫn của video
            val posterUrl = element.selectFirst("img.video-image.lazyload")?.attr("data-original") ?: return@mapNotNull null // Lấy URL ảnh bìa từ 'data-original'

            SearchResponse(
                name = title,
                url = fixUrl(href), // Đảm bảo URL đầy đủ
                posterUrl = fixUrl(posterUrl), // Đảm bảo URL đầy đủ
                type = TvType.XXX
            )
        }
        
        // Kiểm tra xem có trang tiếp theo không để hỗ trợ phân trang
        val hasNext = doc.select("div.pagenavi a[title='Next Page']").isNotEmpty() || doc.select("div.pagenavi a[data-page='${page + 1}']").isNotEmpty()

        return HomePageList(videoList, hasNext)
    }

    // Hàm này sẽ xử lý tìm kiếm
    override fun search(query: String, page: Int, type: TvType): HomePageList {
        // VLXX có vẻ không hỗ trợ phân trang cho tìm kiếm trực tiếp trên URL với số trang.
        // Tuy nhiên, nếu có, bạn có thể điều chỉnh lại. Hiện tại chỉ xử lý trang đầu tiên.
        val searchUrl = "$mainUrl/search/${query.replace(" ", "-")}/" // URL tìm kiếm
        val doc = Jsoup.parse(CloudstreamHttp.get(searchUrl).text) // Tải và phân tích HTML

        val videoList = doc.select("div#video-list div.video-item").mapNotNull { element -> // Chọn tất cả các mục video
            val link = element.selectFirst("a") ?: return@mapNotNull null // Lấy thẻ <a> đầu tiên
            val title = link.attr("title").ifEmpty { link.selectFirst(".video-name a")?.text() } ?: return@mapNotNull null // Lấy tiêu đề
            val href = link.attr("href") // Lấy đường dẫn
            val posterUrl = element.selectFirst("img.video-image.lazyload")?.attr("data-original") ?: return@mapNotNull null // Lấy URL ảnh bìa

            SearchResponse(
                name = title,
                url = fixUrl(href), // Đảm bảo URL đầy đủ
                posterUrl = fixUrl(posterUrl), // Đảm bảo URL đầy đủ
                type = TvType.XXX
            )
        }
        // Vì tìm kiếm không có phân trang rõ ràng, giả định chỉ có một trang kết quả.
        return HomePageList(videoList, false) // Không có trang tiếp theo cho tìm kiếm đơn giản này
    }

    // Hàm này sẽ tải thông tin chi tiết của một video
    override fun load(url: String): MovieLoadResponse {
        val doc = Jsoup.parse(CloudstreamHttp.get(url).text) // Tải và phân tích HTML của trang chi tiết

        val title = doc.selectFirst("h2#page-title")?.text() ?: "Không có tiêu đề" // Lấy tiêu đề
        val plot = doc.selectFirst("div.video-description")?.text() ?: "Không có mô tả" // Lấy mô tả
        val posterUrl = doc.selectFirst("meta[property='og:image']")?.attr("content") ?: "" // Lấy URL ảnh bìa từ thẻ meta

        val videoId = doc.selectFirst("div#video")?.attr("data-id") ?: "" // Lấy ID video để tạo URL server

        val servers = mutableListOf<MovieLoadResponse.Episode>() // Dùng Episode để đại diện cho các server

        // Thêm Server #1
        if (videoId.isNotEmpty()) {
            val server1Url = "$mainUrl/server1.html?vid=$videoId" // Tạo URL cho server 1
            servers.add(
                MovieLoadResponse.Episode(
                    dataUrl = server1Url, // Sử dụng dataUrl để Cloudstream biết cần tải thêm
                    name = "Server #1"
                )
            )
        }

        // Thêm Server #2
        if (videoId.isNotEmpty()) {
            val server2Url = "$mainUrl/server2.html?vid=$videoId" // Tạo URL cho server 2
            servers.add(
                MovieLoadResponse.Episode(
                    dataUrl = server2Url,
                    name = "Server #2"
                )
            )
        }
        
        return MovieLoadResponse(
            name = title,
            url = url,
            posterUrl = fixUrl(posterUrl),
            plot = plot,
            episodes = servers // Các server được coi như các 'episode' cho video này
        )
    }

    // Hàm trợ giúp để đảm bảo URL luôn là tuyệt đối
    private fun fixUrl(url: String): String {
        return if (url.startsWith("http")) url else "$mainUrl$url"
    }
}
