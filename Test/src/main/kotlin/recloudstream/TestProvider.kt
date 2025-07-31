// Tên file: PerfectGirlsProvider.kt
// Package đã được cập nhật
package recloudstream 

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Element

/**
 * Lớp chính của Provider, kế thừa từ MainAPI.
 */
class PerfectGirlsProvider : MainAPI() {
    // Ghi đè các thuộc tính cần thiết của MainAPI
    override var mainUrl = "https://www.perfectgirls.xxx"
    override var name = "PerfectGirls"
    override val hasMainPage = true
    override var lang = "en"
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(
        TvType.NSFW
    )

    /**
     * Hàm lấy dữ liệu cho trang chủ.
     */
    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        // Trang chủ chỉ có một mục là "New Videos"
        val document = app.get("$mainUrl/").document
        val homePageList = ArrayList<HomePageList>()

        val mainVideos = document.select("div.list_video_wrapper div.thumb-bl-video")
            .mapNotNull { it.toSearchResult() }

        if (mainVideos.isNotEmpty()) {
            homePageList.add(HomePageList("New Videos", mainVideos))
        }

        return HomePageResponse(homePageList)
    }

    /**
     * Hàm helper để chuyển đổi một phần tử HTML (Element) thành đối tượng SearchResponse.
     * Có thể tái sử dụng cho trang chủ, tìm kiếm, và video liên quan.
     */
    private fun Element.toSearchResult(): SearchResponse? {
        val aTag = this.selectFirst("a") ?: return null
        val title = aTag.attr("title")
        if (title.isBlank()) return null
        
        val href = fixUrl(aTag.attr("href"))
        val posterUrl = fixUrlNull(this.selectFirst("img")?.attr("data-original"))

        return newMovieSearchResponse(title, href, TvType.Movie) {
            this.posterUrl = posterUrl
        }
    }

    /**
     * Hàm tìm kiếm video.
     */
    override suspend fun search(query: String): List<SearchResponse> {
        val searchUrl = "$mainUrl/search/$query/"
        val document = app.get(searchUrl).document

        return document.select("div.list_video_wrapper div.thumb-bl-video").mapNotNull {
            it.toSearchResult()
        }
    }

    /**
     * Hàm load chi tiết của một video.
     * Cũng sẽ trích xuất link video (loadLinks) tại đây để tránh gọi mạng 2 lần.
     */
    override suspend fun load(url: String): LoadResponse? {
        val document = app.get(url).document

        val title = document.selectFirst("div.video-info h1")?.text()?.trim()
            ?: document.selectFirst("title")?.text()?.substringBefore("-")?.trim()
            ?: return null

        val poster = fixUrlNull(document.selectFirst("meta[property=og:image]")?.attr("content"))
        val plot = document.selectFirst("meta[name=description]")?.attr("content")

        val tags = document.select("ul.video-tags li a").map { it.text() }
        
        // Trích xuất video sources
        val sources = document.select("video#my-video source").mapNotNull {
            val videoUrl = fixUrl(it.attr("src"))
            val quality = it.attr("title") // "360p", "480p", "720p", "Auto"
            
            // Cập nhật: Sử dụng ExtractorLinkType.M3U8 thay cho isM3u8
            ExtractorLink(
                source = this.name,
                name = "${this.name} - $quality",
                url = videoUrl,
                referer = "$mainUrl/",
                quality = getQualityFromName(quality),
                type = if (videoUrl.contains(".m3u8")) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
            )
        }.filter { it.url.isNotBlank() && it.name.contains("Auto").not() } // Lọc bỏ link rỗng và chất lượng "Auto"

        val recommendations = document.select("#custom_list_videos_custom_related_videos div.thumb-bl-video")
            .mapNotNull { it.toSearchResult() }

        return newMovieLoadResponse(title, url, TvType.Movie, sources) {
            this.posterUrl = poster
            this.plot = plot
            this.tags = tags
            this.recommendations = recommendations
        }
    }

    /**
     * Hàm loadLinks không cần thiết vì đã xử lý trong `load`.
     * Tuy nhiên, để tuân thủ kiến trúc của MainAPI, ta vẫn override nó.
     * CloudStream sẽ ưu tiên các link được cung cấp trong `load` hơn.
     */
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        // Đã được xử lý trong `load`, hàm này chỉ để dự phòng.
        val document = app.get(data).document
        document.select("video#my-video source").forEach {
            val videoUrl = fixUrl(it.attr("src"))
            val quality = it.attr("title")
            if (videoUrl.isNotBlank() && quality != "Auto") {
                // Cập nhật: Sử dụng ExtractorLinkType.M3U8 thay cho isM3u8
                callback.invoke(
                    ExtractorLink(
                        source = this.name,
                        name = "${this.name} - $quality",
                        url = videoUrl,
                        referer = "$mainUrl/",
                        quality = getQualityFromName(quality),
                        type = if (videoUrl.contains(".m3u8")) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                    )
                )
            }
        }
        return true
    }
}
