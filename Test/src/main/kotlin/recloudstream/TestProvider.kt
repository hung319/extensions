package recloudstream

import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.HomePageResponse
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.MainPageRequest
import com.lagradost.cloudstream3.HomePageList
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.newHomePageResponse
import com.lagradost.cloudstream3.newMovieLoadResponse
import com.lagradost.cloudstream3.newMovieSearchResponse
import com.lagradost.cloudstream3.utils.loadExtractor
import org.jsoup.nodes.Element

class JavSubIdnProvider : MainAPI() {
    override var mainUrl = "https://javsubidn.vip"
    override var name = "JavSubIdn"
    override val hasMainPage = true
    override var lang = "id" 
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(
        TvType.NSFW
    )

    // =================================================================
    // CẬP NHẬT: Sửa cảnh báo "deprecated"
    // =================================================================
    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = if (page == 1) mainUrl else "$mainUrl/page/$page"
        val document = app.get(url).document
        val home = document.select("article.thumb-block").mapNotNull { it.toSearchResult() }
        val homePageList = HomePageList(name = "Video Jav Terbaru", list = home)
        val hasNext = document.selectFirst("div.pagination a:contains(Next)") != null
        
        // Sử dụng hàm newHomePageResponse thay vì gọi trực tiếp constructor
        return newHomePageResponse(items = listOf(homePageList), hasNext = hasNext)
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val href = this.selectFirst("a")?.attr("href") ?: return null
        val title = this.selectFirst("header.entry-header span")?.text() ?: "No title"
        val posterUrl = this.selectFirst("img")?.attr("data-src")
        return newMovieSearchResponse(title, href, TvType.NSFW) {
            this.posterUrl = posterUrl
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val searchUrl = "$mainUrl/?s=$query"
        val document = app.get(searchUrl).document
        return document.select("article.thumb-block").mapNotNull { it.toSearchResult() }
    }

    override suspend fun load(url: String): LoadResponse? {
        val document = app.get(url).document
        val title = document.selectFirst("header.entry-header h1")?.text()?.trim() ?: return null
        val poster = document.selectFirst("meta[property='og:image']")?.attr("content")
        val description = document.selectFirst("div.video-description .desc")?.text()?.trim()
        val recommendations = document.select("div.under-video-block article.thumb-block").mapNotNull { it.toSearchResult() }
        return newMovieLoadResponse(title, url, TvType.NSFW, url) {
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
        val document = app.get(data).document
        val urlRegex = Regex("""(https?://[^\'"]+)""")

        document.select("div.box-server a").forEach { element ->
            try {
                val onclickAttribute = element.attr("onclick")
                val serverUrl = urlRegex.find(onclickAttribute)?.value ?: return@forEach
                loadExtractor(serverUrl, data, subtitleCallback, callback)
            } catch (t: Throwable) {
                // Lặng lẽ bỏ qua lỗi của một server và tiếp tục
            }
        }
        
        return true
    }
}
