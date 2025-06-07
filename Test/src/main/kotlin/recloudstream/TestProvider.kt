// Bắt buộc phải có ở đầu mỗi tệp plugin
package com.recloudstream.extractors.pornhub

// Import các lớp cần thiết từ app của CloudStream
import com.lagradost.cloudstream3.HomePageResponse // <--- THÊM DÒNG NÀY
import com.lagradost.cloudstream3.LoadResponse // <--- THÊM DÒNG NÀY
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MainPageRequest
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.newHomePageResponse
import com.lagradost.cloudstream3.newMovieLoadResponse // <--- THÊM DÒNG NÀY
import com.lagradost.cloudstream3.newMovieSearchResponse
import com.lagradost.cloudstream3.utils.ExtractorLink

// Tạo một lớp Provider kế thừa từ MainAPI
class PornhubProvider : MainAPI() {
    // Metadata cho plugin của bạn
    override var name = "Pornhub"
    override var mainUrl = "https://www.pornhub.com" 
    override var lang = "en"
    override var hasMainPage = true
    override val supportedTypes = setOf(TvType.NSFW)

    /**
     * Hàm này chịu trách nhiệm tải và phân tích cú pháp trang chính.
     */
    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse { // Lỗi đã được sửa
        val document = app.get("$mainUrl/").document
        
        val home = document.select("li.pcVideoListItem").mapNotNull {
            val titleElement = it.selectFirst("span.title a")
            val title = titleElement?.attr("title") ?: ""
            val href = titleElement?.attr("href")?.let { link -> mainUrl + link } ?: return@mapNotNull null
            val image = it.selectFirst("img")?.let { img -> 
                img.attr("data-src").ifEmpty { img.attr("src") }
            }

            newMovieSearchResponse(
                name = title,
                url = href,
                type = TvType.NSFW 
            ) {
                this.posterUrl = image
            }
        }
        
        return newHomePageResponse("Recommended", home)
    }

    /**
     * Hàm này xử lý các yêu cầu tìm kiếm.
     */
    override suspend fun search(query: String): List<SearchResponse> {
        val searchUrl = "$mainUrl/video/search?search=$query"
        val document = app.get(searchUrl).document
        
        return document.select("li.pcVideoListItem").mapNotNull {
            val titleElement = it.selectFirst("span.title a")
            val title = titleElement?.attr("title") ?: ""
            val href = titleElement?.attr("href")?.let { link -> mainUrl + link } ?: return@mapNotNull null
            val image = it.selectFirst("img")?.let { img ->
                img.attr("data-src").ifEmpty { img.attr("src") }
            }

            newMovieSearchResponse(name = title, url = href, type = TvType.NSFW) {
                this.posterUrl = image
            }
        }
    }

    /**
     * Hàm này tải chi tiết của một bộ phim hoặc chương trình truyền hình cụ thể.
     */
    override suspend fun load(url: String): LoadResponse { // Lỗi đã được sửa
        val document = app.get(url).document
        val title = document.selectFirst("h1.title span.inlineFree")?.text() 
            ?: document.selectFirst("title")?.text() 
            ?: ""
        val poster = document.selectFirst("div.video-element-wrapper.image-wrapper.video-image-display.cover img")?.attr("src")
        val description = document.selectFirst("div.video-description-text")?.text()
        val recommendations = document.select("li.pcVideoListItem.related-video-list").mapNotNull {
             val recTitleElement = it.selectFirst("span.title a")
             val recTitle = recTitleElement?.attr("title") ?: ""
             val recHref = recTitleElement?.attr("href")?.let { link -> mainUrl + link } ?: return@mapNotNull null
             val recImage = it.selectFirst("img")?.let { img ->
                 img.attr("data-src").ifEmpty { img.attr("src") }
             }

             newMovieSearchResponse(name = recTitle, url = recHref, type = TvType.NSFW) {
                 this.posterUrl = recImage
             }
        }

        // Lỗi đã được sửa
        return newMovieLoadResponse(
            name = title,
            url = url,
            type = TvType.NSFW,
            dataUrl = url 
        ) {
            this.posterUrl = poster // Lỗi đã được sửa
            this.plot = description // Lỗi đã được sửa
            this.recommendations = recommendations // Lỗi đã được sửa
        }
    }

    /**
     * Hàm này (hiện đang bỏ qua) sẽ chịu trách nhiệm tìm liên kết video trực tiếp.
     */
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        throw NotImplementedError("loadLinks is not yet implemented.")
        return true
    }
}
