package com.recloudstream.extractors.pornhub

import com.lagradost.cloudstream3.HomePageResponse
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MainPageRequest
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.newHomePageResponse
import com.lagradost.cloudstream3.newMovieLoadResponse
import com.lagradost.cloudstream3.newMovieSearchResponse
import com.lagradost.cloudstream3.utils.ExtractorLink

class PornhubProvider : MainAPI() {
    override var name = "Pornhub"
    override var mainUrl = "https://www.pornhub.com"
    override var lang = "en"
    override var hasMainPage = true
    override val supportedTypes = setOf(TvType.NSFW)

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {
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

    override suspend fun load(url: String): LoadResponse {
        val document = app.get(url).document

        val title = document.selectFirst("h1.title span.inlineFree")?.text() 
            ?: document.selectFirst("title")?.text() 
            ?: ""

        // Lấy toàn bộ mã HTML của trang
        val pageText = document.html()
        // Tìm URL poster bằng cách sử dụng biểu thức chính quy (Regex) 
        // để tìm "image_url" trong biến javascript "flashvars"
        val poster = Regex("""image_url":"([^"]+)""").find(pageText)?.groupValues?.get(1)
        
        val description = document.selectFirst("div.video-description-text")?.text()

        // Sử dụng selector chính xác hơn để lấy các video trong khu vực "relatedVideos"
        val recommendations = document.select("#relatedVideos .pcVideoListItem").mapNotNull {
             val recTitleElement = it.selectFirst("span.title a")
             // Bỏ qua nếu không tìm thấy tiêu đề hoặc link
             if (recTitleElement == null) return@mapNotNull null

             val recTitle = recTitleElement.attr("title")
             val recHref = recTitleElement.attr("href").let { link -> mainUrl + link }

             val recImage = it.selectFirst("img")?.let { img ->
                 img.attr("data-thumbsrc").ifEmpty { img.attr("src") }
             }

             newMovieSearchResponse(name = recTitle, url = recHref, type = TvType.NSFW) {
                 this.posterUrl = recImage
             }
        }

        return newMovieLoadResponse(
            name = title,
            url = url,
            type = TvType.NSFW,
            dataUrl = url 
        ) {
            // Gán poster và recommendations đã được sửa
            this.posterUrl = poster
            this.plot = description
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
        return true
    }
}
