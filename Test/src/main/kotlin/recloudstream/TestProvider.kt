package recloudstream

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Element

class KingBokep : MainAPI() {
    override var mainUrl = "https://kingbokep.tv"
    override var name = "KingBokep"
    override val hasMainPage = true
    override var lang = "id"
    override val supportedTypes = setOf(TvType.NSFW)

    override val mainPage = mainPageOf(
        "$mainUrl/" to "Home",
        "$mainUrl/category/indonesia/" to "Bokep Indo",
        "$mainUrl/category/viral/" to "Indo Viral",
        "$mainUrl/category/jilbab/" to "Jilbab",
        "$mainUrl/category/scandal/" to "Scandal"
    )

    private fun Element.toSearchResult(): SearchResponse? {
        val link = this.selectFirst("a.group") ?: return null
        val href = fixUrl(link.attr("href"))
        val title = this.selectFirst(".video-card-title")?.text()?.trim() ?: return null
        
        val imgTag = this.selectFirst("img")
        // Ưu tiên data-src (lazy load) trước, nếu không có mới lấy src
        val posterUrl = fixUrl(imgTag?.attr("data-src") ?: imgTag?.attr("src"))
        
        val duration = this.selectFirst(".video-card-badge .text-xs")?.text()?.trim()

        return newMovieSearchResponse(title, href, TvType.NSFW) {
            this.posterUrl = posterUrl
            addDuration(duration)
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val url = "$mainUrl/search/?keyword=$query"
        return app.get(url).document.select("li.video-card").mapNotNull {
            it.toSearchResult()
        }
    }

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {
        val url = if (page == 1) {
            request.data
        } else {
            "${request.data.removeSuffix("/")}/page/$page/"
        }

        val document = app.get(url).document
        val home = document.select("li.video-card").mapNotNull {
            it.toSearchResult()
        }
        return newHomePageResponse(request.name, home)
    }

    override suspend fun load(url: String): LoadResponse {
        val document = app.get(url).document

        val title = document.selectFirst("h1")?.text()?.trim() ?: "Unknown"
        val date = document.selectFirst("span:contains(Tanggal:)")?.nextSibling()?.toString()?.trim()
        val duration = document.selectFirst("span[data-pagefind-meta=duration]")?.text()
        val description = document.select("meta[name=description]").attr("content")
        
        val poster = document.selectFirst("video#bokep-player")?.attr("poster") 
            ?: document.select("meta[property=og:image]").attr("content")

        val recommendations = document.select("li.video-card").mapNotNull {
            it.toSearchResult()
        }
        
        val tags = document.select("meta[name=keywords]").attr("content")
            .split(",").map { it.trim() }

        return newMovieLoadResponse(title, url, TvType.NSFW, url) {
            this.posterUrl = poster
            this.plot = description
            this.tags = tags
            this.year = date?.takeLast(4)?.toIntOrNull()
            this.recommendations = recommendations
            addDuration(duration)
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val document = app.get(data).document
        
        // Lấy link playlist từ attribute data-playlist của thẻ video
        val videoTag = document.selectFirst("#bokep-player")
        val playlistUrl = videoTag?.attr("data-playlist")

        if (!playlistUrl.isNullOrBlank()) {
            callback.invoke(
                newExtractorLink(
                    source = name,
                    name = name,
                    url = fixUrl(playlistUrl),
                    type = ExtractorLinkType.M3U8
                ) {
                    referer = mainUrl
                    quality = Qualities.Unknown.value
                    // Đã bỏ isM3u8 = true theo yêu cầu
                }
            )
            return true
        }
        return false
    }
}
