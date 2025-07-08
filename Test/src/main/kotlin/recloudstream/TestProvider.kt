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
    
    // --> THÊM MỚI: Báo cho CloudStream biết plugin này có trang chủ
    override var hasMainPage = true

    // --> CẬP NHẬT: Thay đổi loại nội dung thành NSFW
    override val supportedTypes = setOf(
        TvType.NSFW
    )

    // Hàm lấy danh sách phim từ trang chủ
    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val document = app.get(mainUrl).document
        val homePageList = document.select("div.gmr-item-modulepost").mapNotNull {
            it.toSearchResult()
        }

        return newHomePageResponse(
            list = listOf(
                HomePageList(
                    name = "Phim Mới Cập Nhật",
                    list = homePageList,
                    isHorizontal = true
                )
            ),
            hasNext = false
        )
    }
    
    // Hàm tiện ích chuyển đổi HTML sang SearchResponse
    private fun Element.toSearchResult(): SearchResponse? {
        val href = this.selectFirst("a")?.attr("href") ?: return null
        val title = this.selectFirst("h2.entry-title a")?.text() ?: "Không có tiêu đề"
        val posterUrl = this.selectFirst("img")?.attr("data-src")

        // --> CẬP NHẬT: Sử dụng newNsfwSearchResponse
        return newNsfwSearchResponse(
            name = title,
            url = href
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

            // --> CẬP NHẬT: Sử dụng newNsfwSearchResponse
            newNsfwSearchResponse(
                name = title,
                url = href
            ) {
                this.posterUrl = posterUrl
            }
        }
    }

    // Hàm load để lấy thêm Post ID
    override suspend fun load(url: String): LoadResponse? {
        val document = app.get(url).document
        
        val title = document.selectFirst("h1.entry-title")?.text()?.trim() ?: "Không có tiêu đề"
        val poster = document.selectFirst("div.gmr-movie-data img")?.attr("src")
        val description = document.selectFirst("div.entry-content")?.text()?.trim()
        val tags = document.select("div.gmr-moviedata a[rel=tag]").map { it.text() }
        val postId = document.body().className().substringAfter("postid-").substringBefore(" ")

        // --> CẬP NHẬT: Sử dụng newNsfwLoadResponse
        return newNsfwLoadResponse(
            name = title,
            url = url,
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
        
        (1..2).forEach { tabIndex ->
            val tabId = "p$tabIndex"
            val postData = mapOf(
                "action" to "muvipro_player_content",
                "tab" to tabId,
                "post_id" to postId
            )

            val playerContent = app.post(
                url = ajaxUrl,
                data = postData,
                referer = "$mainUrl/"
            ).document

            val iframeSrc = playerContent.selectFirst("iframe")?.attr("src")
            if (iframeSrc != null) {
                loadExtractor(iframeSrc, subtitleCallback, callback)
            }
        }

        return true
    }
}
