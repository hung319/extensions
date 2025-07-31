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
    
    // SỬA LỖI 1: Thay đổi supportedTypes thành Movie để khớp với kết quả trả về.
    override val supportedTypes = setOf(
        TvType.Movie 
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val document = app.get("$mainUrl/").document
        val homePageList = ArrayList<HomePageList>()

        val mainVideos = document.select("div.list_video_wrapper div.thumb-bl-video")
            .mapNotNull { it.toSearchResult() }

        if (mainVideos.isNotEmpty()) {
            homePageList.add(HomePageList("New Videos", mainVideos))
        }

        return HomePageResponse(homePageList)
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val aTag = this.selectFirst("a") ?: return null
        val title = aTag.attr("title")
        if (title.isBlank()) return null
        
        // SỬA LỖI 2 (Phòng ngừa): Loại bỏ khoảng trắng khỏi URL ngay cả ở đây.
        val href = fixUrl(aTag.attr("href").replace(" ", ""))
        val posterUrl = fixUrlNull(this.selectFirst("img")?.attr("data-original"))

        return newMovieSearchResponse(title, href, TvType.Movie) {
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

    override suspend fun load(url: String): LoadResponse? {
        val document = app.get(url).document

        val title = document.selectFirst("div.video-info h1")?.text()?.trim()
            ?: document.selectFirst("title")?.text()?.substringBefore("-")?.trim()
            ?: return null

        val poster = fixUrlNull(document.selectFirst("meta[property=og:image]")?.attr("content"))
        val plot = document.selectFirst("meta[name=description]")?.attr("content")

        val tags = document.select("ul.video-tags li a").map { it.text() }
        
        val sources = document.select("video#my-video source").mapNotNull {
            // SỬA LỖI 2: Loại bỏ tất cả khoảng trắng khỏi URL.
            val videoUrl = fixUrl(it.attr("src").replace(" ", ""))
            val quality = it.attr("title")
            
            ExtractorLink(
                source = this.name,
                name = "${this.name} - $quality",
                url = videoUrl,
                referer = "$mainUrl/",
                quality = getQualityFromName(quality),
                type = if (videoUrl.contains(".m3u8")) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
            )
        }.filter { it.url.isNotBlank() && it.name.contains("Auto").not() }

        val recommendations = document.select("#custom_list_videos_custom_related_videos div.thumb-bl-video")
            .mapNotNull { it.toSearchResult() }

        return newMovieLoadResponse(title, url, TvType.Movie, sources) {
            this.posterUrl = poster
            this.plot = plot
            // SỬA LỖI 1: Thêm tag NSFW để ứng dụng nhận diện đúng.
            this.tags = tags + "NSFW" 
            this.recommendations = recommendations
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val document = app.get(data).document
        document.select("video#my-video source").forEach {
            // SỬA LỖI 2: Loại bỏ tất cả khoảng trắng khỏi URL.
            val videoUrl = fixUrl(it.attr("src").replace(" ", ""))
            val quality = it.attr("title")
            if (videoUrl.isNotBlank() && quality != "Auto") {
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
