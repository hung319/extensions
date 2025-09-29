package recloudstream

/*
* @CloudstreamProvider: BokepIndoProvider
* @Version: 1.3
* @Author: Coder
* @Language: id
* @TvType: Nsfw
* @Url: https://bokepindoh.monster
* @Info: Provider for Bokepindoh.monster
*/

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Element

// Định nghĩa provider, kế thừa từ MainAPI của CloudStream
class BokepIndoProvider : MainAPI() {
    // ## 1. Thiết lập Provider cơ bản ##
    override var name = "BokepIndo"
    override var mainUrl = "https://bokepindoh.monster"
    override var supportedTypes = setOf(TvType.NSFW)
    override var hasMainPage = true
    override var hasDownloadSupport = true

    // ## 2. Hàm tải trang chính (Homepage) - Đã cập nhật thành getMainPage ##
    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        // Xử lý phân trang, trang 1 không cần /page/1
        val url = if (page == 1) mainUrl else "$mainUrl/page/$page/"
        val document = app.get(url).document

        // Tìm tất cả các video cards trên trang
        val homePageList = document.select("article.loop-video.thumb-block").mapNotNull {
            it.toSearchResponse() // Sử dụng hàm helper để chuyển đổi Element thành SearchResponse
        }

        // Tạo và trả về một danh sách cho homepage
        // `hasNext` = true để cho phép cuộn vô tận (load more)
        return newHomePageResponse(
            HomePageList(request.name, homePageList), // Sử dụng request.name để lấy tên mặc định
            hasNext = true
        )
    }

    // ## 3. Hàm tìm kiếm ##
    override suspend fun search(query: String): List<SearchResponse> {
        val searchUrl = "$mainUrl/?s=${query.replace(" ", "+")}"
        val document = app.get(searchUrl).document
        
        // Logic parse kết quả tìm kiếm giống hệt trang chủ
        return document.select("article.loop-video.thumb-block").mapNotNull {
            it.toSearchResponse()
        }
    }

    // ## 4. Hàm tải chi tiết (Metadata) ##
    override suspend fun load(url: String): LoadResponse {
        val document = app.get(url).document

        val title = document.selectFirst("h1.entry-title")?.text() ?: "Không có tiêu đề"
        val poster = document.selectFirst("meta[property='og:image']")?.attr("content")
        val description = document.selectFirst("div.video-description .desc")?.text()
        val tags = document.select("div.tags-list a[class=label]").map { it.text() }
        val recommendations = document.select("div.under-video-block article.loop-video.thumb-block").mapNotNull {
            it.toSearchResponse()
        }

        return newMovieLoadResponse(title, url, TvType.NSFW, url) {
            this.posterUrl = poster
            this.plot = description
            this.tags = tags
            this.recommendations = recommendations
        }
    }

    // ## 5. Hàm tải link stream ##
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val document = app.get(data).document
        
        val iframeSrc = document.selectFirst("div.responsive-player iframe")?.attr("src")
            ?: return false 

        return loadExtractor(iframeSrc, data, subtitleCallback, callback)
    }

    // ## 6. Hàm Helper để parse Video Card ##
    private fun Element.toSearchResponse(): SearchResponse? {
        val linkTag = this.selectFirst("a") ?: return null
        val href = fixUrl(linkTag.attr("href"))
        if (href.isBlank()) return null
        
        val title = linkTag.selectFirst("header.entry-header span")?.text() ?: return null
        val posterUrl = fixUrlNull(linkTag.selectFirst("div.post-thumbnail-container img")?.attr("data-src"))

        return newMovieSearchResponse(title, href) {
            this.posterUrl = posterUrl
        }
    }
}
