package recloudstream

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.utils.AppUtils.tryParseJson
import org.jsoup.nodes.Element

class FullxcinemaProvider : MainAPI() {
    override var mainUrl = "https://fullxcinema.com"
    override var name = "Fullxcinema"
    override val hasMainPage = true
    override var lang = "en"
    override val hasDownloadSupport = true
    
    override val supportedTypes = setOf(
        TvType.NSFW
    )

    // Hàm xử lý chung để lấy thông tin phim từ thẻ HTML <article>
    private fun Element.toSearchResponse(): SearchResponse? {
        // Lấy link bài viết
        val href = this.selectFirst("a")?.attr("href") ?: return null
        
        // Lấy tiêu đề: Dựa vào HTML <header class="entry-header"><span>Title</span></header>
        val title = this.selectFirst("header.entry-header span")?.text() ?: return null
        
        // Lấy ảnh: Dựa vào HTML <img class="video-main-thumb" src="...">
        // Lưu ý: HTML dùng 'src', không phải 'data-src'
        val posterUrl = this.selectFirst("div.post-thumbnail img")?.attr("src")
            ?: this.selectFirst("img")?.attr("src")

        return newMovieSearchResponse(title, href, TvType.NSFW) {
            this.posterUrl = posterUrl
            // Có thể lấy thêm thời lượng nếu muốn
            // addDuration(this.selectFirst("span.duration")?.text())
        }
    }

    override suspend fun getMainPage(
        page: Int,
        request : MainPageRequest
    ): HomePageResponse {
        // Cấu trúc phân trang: /page/2/
        val document = app.get("$mainUrl/page/$page/").document
        
        // Selector lấy danh sách video: <article class="loop-video thumb-block ...">
        val home = document.select("article.loop-video.thumb-block").mapNotNull {
            it.toSearchResponse()
        }
        
        return newHomePageResponse(
            list = HomePageList(
                name = "Latest Movies",
                list = home,
                isHorizontalImages = true // Video thường là 16:9
            ), 
            hasNext = true
        )
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val searchUrl = "$mainUrl/?s=$query"
        val document = app.get(searchUrl).document
        
        return document.select("article.loop-video.thumb-block").mapNotNull {
            it.toSearchResponse()
        }
    }

    override suspend fun load(url: String): LoadResponse {
        val document = app.get(url).document

        // Tiêu đề trang chi tiết
        val title = document.selectFirst("h1.entry-title")?.text()?.trim()
            ?: document.selectFirst("h1.site-title")?.text()?.trim()
            ?: "Unknown Title"

        // Poster & Description
        val poster = document.selectFirst("meta[property='og:image']")?.attr("content")
        val description = document.selectFirst("div.video-description div.desc.more")?.text()?.trim()
        val tags = document.select("div.tags-list a[rel='tag']").map { it.text() }

        // Lấy link Iframe
        // Dựa trên theme Retrotube, iframe thường nằm trong .responsive-player hoặc .video-embed
        val iframeUrl = document.selectFirst("div.responsive-player iframe")?.attr("src")
            ?: document.selectFirst("div.video-embed iframe")?.attr("src")
            ?: document.selectFirst("iframe[src*='magsrv']")?.attr("src") // Fallback nếu có
            ?: throw ErrorLoadingException("No video iframe found")

        // Lấy danh sách phim đề xuất bên dưới
        val recommendations = document.select("div.under-video-block article.loop-video.thumb-block").mapNotNull {
            it.toSearchResponse()
        }

        return newMovieLoadResponse(
            name = title,
            url = url,
            type = TvType.NSFW,
            dataUrl = iframeUrl
        ) {
            this.posterUrl = poster
            this.plot = description
            this.tags = tags
            this.recommendations = recommendations
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        // Load nội dung của Iframe
        val iframeDocument = app.get(data).document
        
        // Cố gắng tìm thẻ <video> hoặc source bên trong iframe
        val videoUrl = iframeDocument.selectFirst("video source")?.attr("src")
            ?: iframeDocument.selectFirst("video")?.attr("src")
            ?: return false

        val linkType = if (videoUrl.contains(".m3u8")) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
        
        // --- PHẦN ĐÃ SỬA ---
        // Chuyển referer và quality vào trong block lambda {} ở cuối
        val link = newExtractorLink(
            source = this.name,
            name = this.name,
            url = videoUrl,
            type = linkType // type là tham số, để ở ngoài
        ) {
            this.referer = "$mainUrl/"
            this.quality = getQualityFromName("")
        }
        // -------------------

        callback.invoke(link)
        return true
    }
}
