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
        // Thêm headers vào yêu cầu mạng
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
        // Thêm headers vào yêu cầu mạng
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
        // Thêm headers vào yêu cầu mạng
        val document = app.get(url, headers = headers).document

        val title = document.selectFirst("h1.title span.inlineFree")?.text() 
            ?: document.selectFirst("title")?.text() 
            ?: ""

        val pageText = document.html()
        val poster = Regex("""image_url":"([^"]+)""").find(pageText)?.groupValues?.get(1)
        
        val plot = document.selectFirst("div.video-description-text")?.text()

        val recommendations = document.select("#relatedVideosCenter li.video-list-item").mapNotNull {
             val recTitleElement = it.selectFirst("span.title a") ?: return@mapNotNull null
             val recTitle = recTitleElement.attr("title")
             val recHref = recTitleElement.absUrl("href")
             val recImage = it.selectFirst("img")?.let { img ->
                 img.attr("data-thumbsrc").ifEmpty { img.attr("src") }
             }

             if (recTitle.isNotBlank() && recHref.isNotBlank()) {
                newMovieSearchResponse(name = recTitle, url = recHref, type = TvType.NSFW) {
                    this.posterUrl = recImage
                }
             } else { null }
        }

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
