package recloudstream

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Element

class KuraKura21Provider : MainAPI() {
    override var name = "KuraKura21"
    override var mainUrl = "https://kurakura21.net"
    override var lang = "id"
    override var hasMainPage = true

    override val supportedTypes = setOf(
        TvType.NSFW
    )

    // CẬP NHẬT: Đổi tên danh sách thành "RECENT POST"
    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val document = app.get(mainUrl).document
        val homePageList = document.select("div.gmr-item-modulepost").mapNotNull {
            it.toSearchResult()
        }
        
        return HomePageResponse(
            listOf(
                HomePageList(
                    name = "RECENT POST", // Đã đổi tên
                    list = homePageList
                )
            )
        )
    }
    
    // Hàm tiện ích để chuyển đổi một phần tử HTML thành SearchResponse
    private fun Element.toSearchResult(): SearchResponse? {
        val href = this.selectFirst("a")?.attr("href") ?: return null
        val title = this.selectFirst("h2.entry-title a")?.text() ?: "Không có tiêu đề"
        // Sử dụng data-src cho lazy-loading
        val posterUrl = this.selectFirst("img")?.attr("data-src")

        return newMovieSearchResponse(
            name = title,
            url = href,
            type = TvType.NSFW
        ) {
            this.posterUrl = posterUrl
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val searchUrl = "$mainUrl/?s=$query"
        val document = app.get(searchUrl).document

        return document.select("article.item-infinite").mapNotNull {
            val href = it.selectFirst("a")?.attr("href") ?: return@mapNotNull null
            val title = it.selectFirst("h2.entry-title a")?.text() ?: "Không có tiêu đề"
            // Trang tìm kiếm dùng 'src' thay vì 'data-src'
            val posterUrl = it.selectFirst("img")?.attr("src")

            newMovieSearchResponse(
                name = title,
                url = href,
                type = TvType.NSFW
            ) {
                this.posterUrl = posterUrl
            }
        }
    }

    // CẬP NHẬT: Sửa lỗi lấy poster và thêm danh sách phim đề cử
    override suspend fun load(url: String): LoadResponse? {
        val document = app.get(url).document
        
        val title = document.selectFirst("h1.entry-title")?.text()?.trim() ?: "Không có tiêu đề"
        // SỬA LỖI: Lấy poster từ thuộc tính 'data-src'
        val poster = document.selectFirst("div.gmr-movie-data img")?.attr("data-src")
        val description = document.selectFirst("div.entry-content")?.text()?.trim()
        val tags = document.select("div.gmr-moviedata a[rel=tag]").map { it.text() }
        val postId = document.body().className().substringAfter("postid-").substringBefore(" ")

        // THÊM MỚI: Lấy danh sách phim liên quan (recommendations)
        val recommendations = document.select("div.gmr-related-title ~ div.gmr-box-content article").mapNotNull {
            val recHref = it.selectFirst("a")?.attr("href") ?: return@mapNotNull null
            val recTitle = it.selectFirst("h2.entry-title a")?.text() ?: "N/A"
            val recPoster = it.selectFirst("img")?.attr("data-src")

            newMovieSearchResponse(recTitle, recHref, TvType.NSFW) {
                this.posterUrl = recPoster
            }
        }

        return newMovieLoadResponse(
            name = title,
            url = url,
            type = TvType.NSFW,
            dataUrl = postId 
        ) {
            this.posterUrl = poster
            this.plot = description
            this.tags = tags
            this.recommendations = recommendations // Thêm danh sách đề cử vào response
        }
    }

    override suspend fun loadLinks(
        data: String, 
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val postId = data
        val ajaxUrl = "$mainUrl/wp-admin/admin-ajax.php"
        
        (1..2).toList().apmap { tabIndex ->
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
                    referer = "$mainUrl/"
                ).document

                playerContent.select("iframe").firstOrNull()?.attr("src")?.let { iframeSrc ->
                    loadExtractor(iframeSrc, subtitleCallback, callback)
                }
            } catch (e: Exception) {
                // Bỏ qua lỗi
            }
        }
        return true
    }
}
