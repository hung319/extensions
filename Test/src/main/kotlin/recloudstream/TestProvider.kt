// Bạn có thể cần thay đổi package này cho phù hợp với cấu trúc dự án của mình
package com.lagradost.cloudstream3.plugins.vi

// Thêm thư viện Jsoup để phân tích cú pháp HTML
import org.jsoup.nodes.Element
import java.net.URI 

// Import chính xác và đầy đủ các lớp và hàm cần thiết
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.HomePageResponse
import com.lagradost.cloudstream3.HomePageList
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.MovieLoadResponse
import com.lagradost.cloudstream3.TvSeriesLoadResponse
import com.lagradost.cloudstream3.AnimeSearchResponse
import com.lagradost.cloudstream3.Episode
import com.lagradost.cloudstream3.MainPageRequest
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.fixUrl
import com.lagradost.cloudstream3.newHomePageResponse
import com.lagradost.cloudstream3.newAnimeSearchResponse
import com.lagradost.cloudstream3.newMovieLoadResponse
import com.lagradost.cloudstream3.newTvSeriesLoadResponse
import com.lagradost.cloudstream3.newEpisode

// Định nghĩa lớp chính cho plugin
class Bluphim3Provider : MainAPI() {
    override var mainUrl = "https://bluphim3.com"
    override var name = "Bluphim3"
    override val hasMainPage = true
    override var lang = "vi"
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(
        TvType.Movie,
        TvType.TvSeries,
        TvType.Anime
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse? {
        val document = app.get(mainUrl).document
        val homePageList = mutableListOf<HomePageList>()

        document.select("div.list-films").forEach { block ->
            val title = block.selectFirst("h2.title-box")?.text()?.trim() ?: return@forEach
            val movies = block.select("li.item, li.film-item-ver").mapNotNull {
                it.toSearchResult()
            }
            if (movies.isNotEmpty()) {
                homePageList.add(HomePageList(title, movies))
            }
        }
        return newHomePageResponse(homePageList)
    }

    private fun Element.toSearchResult(): SearchResponse? {
        var title = this.attr("title").trim()
        if (title.isBlank()) {
            title = this.selectFirst("a")?.attr("title")?.trim() ?: ""
        }
        title = title.replace("Xem phim ", "").replace(" online", "")
        if (title.isBlank()) return null

        val href = this.selectFirst("a")?.attr("href")?.let { fixUrl(it) } ?: return null
        val posterUrl = this.selectFirst("img")?.attr("src")?.let { fixUrl(it) }

        return newAnimeSearchResponse(title, href) {
            this.posterUrl = posterUrl
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val searchUrl = "$mainUrl/search?k=$query"
        val document = app.get(searchUrl).document

        return document.select("div.list-films ul li.item").mapNotNull {
            it.toSearchResult()
        }
    }
    
    // CẬP NHẬT: Logic mới để phân biệt phim lẻ và phim bộ
    override suspend fun load(url: String): LoadResponse? {
        val document = app.get(url).document

        val title = document.selectFirst("span.title")?.text()?.trim() ?: return null
        val poster = document.selectFirst("div.poster img")?.attr("src")?.let { fixUrl(it) }
        val year = document.select("div.dinfo dl.col dd").getOrNull(3)?.text()?.trim()?.toIntOrNull()
        val description = document.selectFirst("div.detail div.tab")?.text()?.trim()
        val tags = document.select("dd.theloaidd a").map { it.text() }

        val isAnime = tags.any { it.contains("Hoạt hình", ignoreCase = true) }
        
        val watchUrl = document.selectFirst("a.btn-stream-link")?.attr("href")?.let { fixUrl(it) } ?: url
        val watchDocument = app.get(watchUrl).document
        
        val recommendations = watchDocument.select(".list-films.film-related li.item").mapNotNull { it.toSearchResult() }

        val linkElements = watchDocument.select("div.episodes div.list-episode a")

        val isSeries = linkElements.any { "Tập \\d+".toRegex().find(it.attr("title")) != null }

        return if (isSeries) {
            // Xử lý phim bộ
            val episodes = linkElements.map { element ->
                val originalName = element.attr("title").ifBlank { element.text() }
                val simplifiedName = "Tập \\d+".toRegex().find(originalName)?.value ?: originalName
                newEpisode(fixUrl(element.attr("href"))) {
                    this.name = simplifiedName
                }
            }.reversed()
            
            newTvSeriesLoadResponse(title, url, if (isAnime) TvType.Anime else TvType.TvSeries, episodes) {
                this.posterUrl = poster
                this.year = year
                this.plot = description
                this.tags = tags
                this.recommendations = recommendations
            }
        } else {
            // Xử lý phim lẻ: Tìm server bên thứ 3 (đã hoạt động) và trả về như một phim lẻ
            val movieDataUrl = linkElements.firstOrNull { it.attr("href").contains("sv2=true") }?.attr("href")
                ?: linkElements.firstOrNull { !it.text().contains("Gốc") }?.attr("href")
                ?: watchUrl

            newMovieLoadResponse(title, url, if (isAnime) TvType.Anime else TvType.Movie, fixUrl(movieDataUrl)) {
                this.posterUrl = poster
                this.year = year
                this.plot = description
                this.tags = tags
                this.recommendations = recommendations
            }
        }
    }

    // CẬP NHẬT: Quay lại logic ổn định chỉ hỗ trợ server bên thứ 3
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val episodeDocument = app.get(data).document
        val iframeSrc = episodeDocument.selectFirst("iframe#iframeStream")?.attr("src") ?: return false
        val iframeUrl = fixUrl(iframeSrc)

        // Chặn server gốc vì không ổn định
        if (!iframeUrl.contains("embed3rd")) {
            return false
        }
        
        // Logic cho server bên thứ 3
        val iframe1Doc = app.get(iframeUrl, referer = data).document
        val iframe2Url = iframe1Doc.selectFirst("iframe#embedIframe")?.attr("src") ?: return false
        if (iframe2Url.isBlank()) return false
        
        val iframe2Doc = app.get(iframe2Url, referer = iframeUrl).document
        val playerScript = iframe2Doc.select("script").firstOrNull { 
            it.data().contains("jwplayer") && it.data().contains(".setup") 
        }?.data() ?: return false
        
        val m3u8Url = "\"file\"\\s*:\\s*\"(//[^\"]*?(?:playlist|index)\\.m3u8)\"".toRegex().find(jwPlayerScript)?.groupValues?.get(1)?.let { "https:$it" } ?: return false

        callback(
            ExtractorLink(
                source = this.name,
                name = this.name,
                url = m3u8Url,
                referer = iframe2Url,
                quality = Qualities.Unknown.value,
                type = ExtractorLinkType.M3U8
            )
        )
        
        return true
    }
}
