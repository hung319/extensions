// Tên file: PerfectGirlsProvider.kt
package recloudstream

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Element

class PerfectGirlsProvider : MainAPI() {
    override var mainUrl = "https://www.perfectgirls.xxx"
    override var name = "PerfectGirls"
    override val hasMainPage = true
    override var lang = "en"
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(
        TvType.NSFW
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = if (page > 1) "$mainUrl/$page/" else mainUrl
        val document = app.get(url).document
        
        val homePageList = ArrayList<HomePageList>()
        val mainVideos = document.select("div.list_video_wrapper div.thumb-bl-video")
            .mapNotNull { it.toSearchResult() }

        if (mainVideos.isNotEmpty()) {
            homePageList.add(HomePageList("New Videos", mainVideos, hasNext = true))
        }

        return HomePageResponse(homePageList)
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val aTag = this.selectFirst("a") ?: return null
        val title = aTag.attr("title")
        if (title.isBlank()) return null
        
        val href = fixUrl(aTag.attr("href").replace(" ", ""))
        val posterUrl = fixUrlNull(this.selectFirst("img")?.attr("data-original"))

        return newMovieSearchResponse(title, href, TvType.NSFW) {
            this.posterUrl = posterUrl
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val searchUrl = "$mainUrl/search/$query/"
        val document = app.get(searchUrl).document

        return document.select("div.list_video_wrapper div.thumb-bl-video").mapNotNull {
            it.toSearchResult()
        }
    }

    /**
     * THAY ĐỔI: Hàm load bây giờ chỉ lấy thông tin video, không lấy link.
     * Nó sẽ truyền URL của trang cho hàm `loadLinks`.
     */
    override suspend fun load(url: String): LoadResponse? {
        val document = app.get(url).document

        val title = document.selectFirst("div.video-info h1")?.text()?.trim()
            ?: document.selectFirst("title")?.text()?.substringBefore("-")?.trim()
            ?: return null

        val poster = fixUrlNull(document.selectFirst("meta[property=og:image]")?.attr("content"))
        val plot = document.selectFirst("meta[name=description]")?.attr("content")
        val tags = document.select("ul.video-tags li a").map { it.text() }
        
        val recommendations = document.select("#custom_list_videos_custom_related_videos div.thumb-bl-video")
            .mapNotNull { it.toSearchResult() }

        // Thay vì truyền danh sách link, ta truyền chính `url` của trang này cho loadLinks.
        return newMovieLoadResponse(title, url, TvType.NSFW, url) {
            this.posterUrl = poster
            this.plot = plot
            this.tags = tags
            this.recommendations = recommendations
        }
    }

    /**
     * THAY ĐỔI: Hàm loadLinks giờ sẽ là nơi duy nhất chịu trách nhiệm lấy link video.
     * Tham số `data` chính là `url` được truyền từ hàm `load`.
     */
    override suspend fun loadLinks(
        data: String, // `data` bây giờ là URL của trang xem video, ví dụ: "https://www.perfectgirls.xxx/video/496343/"
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val document = app.get(data).document
        
        document.select("video#my-video source").forEach {
            val videoUrl = fixUrl(it.attr("src").replace(" ", ""))
            val quality = it.attr("title")
            if (videoUrl.isNotBlank() && quality != "Auto") {
                callback.invoke(
                    ExtractorLink(
                        source = this.name,
                        name = "${this.name} - $quality",
                        url = videoUrl,
                        referer = mainUrl, // Luôn dùng mainUrl làm referer
                        quality = getQualityFromName(quality),
                        type = if (videoUrl.contains(".m3u8")) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                    )
                )
            }
        }
        return true
    }
}
