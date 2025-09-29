package recloudstream

/*
* @CloudstreamProvider: BokepIndoProvider
* @Version: 2.1
* @Author: Coder
* @Language: id
* @TvType: Nsfw
* @Url: https://bokepindoh.monster
* @Info: Provider for Bokepindoh.monster with multiple homepage categories.
*/

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Element

class BokepIndoProvider : MainAPI() {
    override var name = "BokepIndo"
    override var mainUrl = "https://bokepindoh.monster"
    override var supportedTypes = setOf(TvType.NSFW)
    override var hasMainPage = true
    override var hasDownloadSupport = true

    // ## 1. Thêm các danh sách (grid) cho trang chính ##
    // Sử dụng `mainPageOf` để định nghĩa các mục sẽ hiển thị trên homepage.
    // Mỗi mục là một cặp (URL, Tên hiển thị).
    override val mainPage = mainPageOf(
        mainUrl to "Mới Nhất",
        "$mainUrl/category/bokep-indo/" to "Bokep Indo",
        "$mainUrl/category/bokep-viral/" to "Bokep Viral",
        "$mainUrl/category/bokep-jav/" to "Bokep JAV"
    )

    // ## 2. Cập nhật getMainPage để xử lý nhiều danh sách ##
    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        // `request.data` sẽ chứa URL tương ứng với mục được chọn (VD: https://.../category/bokep-indo/)
        val url = if (page == 1) {
            request.data
        } else {
            // Xử lý phân trang cho cả trang chủ và các trang thể loại
            "${request.data.removeSuffix("/")}/page/$page/"
        }
        val document = app.get(url).document

        val homePageList = document.select("article.loop-video.thumb-block").mapNotNull {
            it.toSearchResponse()
        }

        // `request.name` sẽ là tên bạn đã định nghĩa ở `mainPageOf`
        return newHomePageResponse(
            HomePageList(request.name, homePageList),
            hasNext = true
        )
    }
    
    override suspend fun search(query: String): List<SearchResponse> {
        val searchUrl = "$mainUrl/?s=${query.replace(" ", "+")}"
        val document = app.get(searchUrl).document
        
        return document.select("article.loop-video.thumb-block").mapNotNull {
            it.toSearchResponse()
        }
    }

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
    
    // ## 3. Cập nhật loadLinks để dùng constructor ExtractorLink chuẩn ##
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val mainDocument = app.get(data).document
        val iframeSrc = mainDocument.selectFirst("div.responsive-player iframe")?.attr("src")
            ?: return false 

        val iframeHtmlContent = app.get(iframeSrc).text

        val m3u8Regex = Regex("""sources:\s*\[\{file:"([^"]+master\.m3u8[^"]+)"""")
        val match = m3u8Regex.find(iframeHtmlContent)
        val m3u8Url = match?.groups?.get(1)?.value

        if (m3u8Url != null) {
            // Sử dụng constructor chuẩn với `type = ExtractorLinkType.M3U8`
            callback(
                ExtractorLink(
                    source = this.name,
                    name = "LuluStream",
                    url = m3u8Url,
                    referer = iframeSrc,
                    quality = Qualities.Unknown.value,
                    type = ExtractorLinkType.M3U8 // Thay thế cho isM3u8 = true
                )
            )
            return true
        }

        return false
    }
    
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
