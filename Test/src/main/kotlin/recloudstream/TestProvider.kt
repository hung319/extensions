// Thêm package theo yêu cầu
package recloudstream

// Import các thư viện cần thiết
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Element

class KuraKura21Provider : MainAPI() {
    override var name = "KuraKura21"
    override var mainUrl = "https://kurakura21.net"
    override var lang = "id"
    override var hasMainPage = true

    override val supportedTypes = setOf(
        TvType.NSFW // Phân loại là nội dung NSFW
    )

    // Sửa lại cách tạo HomePageResponse
    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val document = app.get(mainUrl).document
        val homePageList = document.select("div.gmr-item-modulepost").mapNotNull {
            it.toSearchResult()
        }

        // Trả về danh sách phim cho trang chủ CloudStream
        // Sửa lỗi: HomePageList không cần isHorizontal, nó sẽ hiển thị ngang theo mặc định
        return HomePageResponse(
            listOf(
                HomePageList(
                    name = "Phim Mới Cập Nhật",
                    list = homePageList
                )
            )
        )
    }

    // Hàm tiện ích chuyển đổi HTML sang SearchResponse
    private fun Element.toSearchResult(): SearchResponse? {
        val href = this.selectFirst("a")?.attr("href") ?: return null
        val title = this.selectFirst("h2.entry-title a")?.text() ?: "Không có tiêu đề"
        val posterUrl = this.selectFirst("img")?.attr("data-src")

        // Sửa lỗi: Dùng 'newMovieSearchResponse' thay vì 'newNsfwSearchResponse'
        return newMovieSearchResponse(
            name = title,
            url = href,
            type = TvType.NSFW // Chỉ định loại nội dung ở đây
        ) {
            this.posterUrl = posterUrl
        }
    }

    // Hàm tìm kiếm
    override suspend fun search(query: String): List<SearchResponse> {
        val searchUrl = "$mainUrl/?s=$query"
        val document = app.get(searchUrl).document

        return document.select("article.item-infinite").mapNotNull {
            val href = it.selectFirst("a")?.attr("href") ?: return@mapNotNull null
            val title = it.selectFirst("h2.entry-title a")?.text() ?: "Không có tiêu đề"
            val posterUrl = it.selectFirst("img")?.attr("src")

            // Sửa lỗi: Dùng 'newMovieSearchResponse'
            newMovieSearchResponse(
                name = title,
                url = href,
                type = TvType.NSFW
            ) {
                this.posterUrl = posterUrl
            }
        }
    }

    // Hàm load để lấy thông tin chi tiết
    override suspend fun load(url: String): LoadResponse? {
        val document = app.get(url).document
        
        val title = document.selectFirst("h1.entry-title")?.text()?.trim() ?: "Không có tiêu đề"
        val poster = document.selectFirst("div.gmr-movie-data img")?.attr("src")
        val description = document.selectFirst("div.entry-content")?.text()?.trim()
        val tags = document.select("div.gmr-moviedata a[rel=tag]").map { it.text() }
        val postId = document.body().className().substringAfter("postid-").substringBefore(" ")

        // Sửa lỗi: Dùng 'newMovieLoadResponse' thay vì 'newNsfwLoadResponse'
        return newMovieLoadResponse(
            name = title,
            url = url,
            type = TvType.NSFW, // Chỉ định loại nội dung ở đây
            dataUrl = postId 
        ) {
            this.posterUrl = poster
            this.plot = description
            this.tags = tags
        }
    }

    // Hàm loadLinks sử dụng API và loadExtractor (không thay đổi logic)
    override suspend fun loadLinks(
        data: String, // postId
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val postId = data
        val ajaxUrl = "$mainUrl/wp-admin/admin-ajax.php"
        
        // Lấy link cho 2 server
        (1..2).apmap { tabIndex -> // Sử dụng apmap để chạy song song, tăng tốc độ
            try {
                val tabId = "p$tabIndex"
                val postData = mapOf(
                    "action" to "muvipro_player_content",
                    "tab" to tabId,
                    "post_id" to postId
                )

                val playerContent = app.post(
                    url = ajaxUrl,
                    data = postData,
                    referer = "$mainUrl/" // Referer chung
                ).document

                val iframeSrc = playerContent.selectFirst("iframe")?.attr("src")
                if (iframeSrc != null) {
                    loadExtractor(iframeSrc, subtitleCallback, callback)
                }
            } catch (e: Exception) {
                // Bỏ qua lỗi nếu một trong các server không hoạt động
            }
        }

        return true
    }
}
