package com.recloudstream.extractors.pornhub

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink

class PornhubProvider : MainAPI() {
    override var name = "Pornhub"
    override var mainUrl = "https://www.pornhub.com"
    override var lang = "en"
    override var hasMainPage = true
    override val supportedTypes = setOf(TvType.NSFW)

    // Biến chứa User-Agent của một trình duyệt máy tính thông thường
    private val userAgent = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/114.0.0.0 Safari/537.36"

    // Hàm tạo header để tái sử dụng
    private val headers get() = mapOf("User-Agent" to userAgent)

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {
        val document = app.get("$mainUrl/", headers = headers).document
        
        val home = document.select("li.pcVideoListItem").mapNotNull {
            val titleElement = it.selectFirst("span.title a")
            val title = titleElement?.attr("title") ?: ""
            val href = titleElement?.absUrl("href") ?: return@mapNotNull null
            val image = it.selectFirst("img")?.let { img -> 
                img.attr("data-src").ifEmpty { img.attr("src") }
            }

            newMovieSearchResponse(name = title, url = href, type = TvType.NSFW) {
                this.posterUrl = image
            }
        }
        
        return newHomePageResponse("Recommended", home)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val searchUrl = "$mainUrl/video/search?search=$query"
        val document = app.get(searchUrl, headers = headers).document
        
        return document.select("li.pcVideoListItem").mapNotNull {
            val titleElement = it.selectFirst("span.title a")
            val title = titleElement?.attr("title") ?: ""
            val href = titleElement?.absUrl("href") ?: return@mapNotNull null
            val image = it.selectFirst("img")?.let { img ->
                img.attr("data-src").ifEmpty { img.attr("src") }
            }

            newMovieSearchResponse(name = title, url = href, type = TvType.NSFW) {
                this.posterUrl = image
            }
        }
    }

    override suspend fun load(url: String): LoadResponse {
        val document = app.get(url, headers = headers).document

        val title = document.selectFirst("h1.title span.inlineFree")?.text() 
            ?: document.selectFirst("title")?.text() 
            ?: ""

        val pageText = document.html()
        val poster = Regex("""image_url":"([^"]+)""").find(pageText)?.groupValues?.get(1)
        
        val plot = document.selectFirst("div.video-description-text")?.text()

        // ======================= PHẦN SỬA LỖI QUAN TRỌNG =======================
        // Dùng selector chính xác dựa trên HTML bạn cung cấp
        val recommendations = document.select("ul#recommendedVideos > li").mapNotNull { item ->
            val linkElement = item.selectFirst("a.thumbnailTitle")
            
            // Lấy link, nếu không có thì bỏ qua video này
            val href = linkElement?.absUrl("href") ?: return@mapNotNull null
            
            // Lấy tiêu đề từ link, nếu không có thì lấy từ alt của ảnh
            val recTitle = linkElement.text().trim().ifBlank {
                item.selectFirst("img.videoThumb")?.attr("alt")?.trim()
            } ?: return@mapNotNull null

            // Lấy ảnh thumbnail
            val image = item.selectFirst("img.videoThumb")?.attr("src")

            // Tạo đối tượng SearchResponse
            newMovieSearchResponse(name = recTitle, url = href, type = TvType.NSFW) {
                this.posterUrl = image
            }
        }
        // =====================================================================

        return newMovieLoadResponse(name = title, url = url, type = TvType.NSFW, dataUrl = url) {
            this.posterUrl = poster
            this.plot = plot
            this.recommendations = recommendations
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        throw NotImplementedError("loadLinks is not yet implemented.")
    }
}
