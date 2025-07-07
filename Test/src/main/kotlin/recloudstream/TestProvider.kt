package recloudstream

import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.HomePageResponse
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.MainPageRequest
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
    
    // Đã thay đổi mã ngôn ngữ thành Indonesia
    override var lang = "id" 
    override val hasDownloadSupport = true

    // Đã thay đổi loại TVType thành NSFW
    override val supportedTypes = setOf(
        TvType.NSFW
    )

    // Lấy danh sách phim từ trang chủ
    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = if (page == 1) mainUrl else "$mainUrl/page/$page"
        val document = app.get(url).document

        val home = document.select("article.thumb-block").mapNotNull {
            it.toSearchResult()
        }
        
        return newHomePageResponse(request.name, home)
    }

    // Hàm chuyển đổi một phần tử HTML thành đối tượng SearchResponse
    private fun Element.toSearchResult(): SearchResponse? {
        val href = this.selectFirst("a")?.attr("href") ?: return null
        val title = this.selectFirst("header.entry-header span")?.text() ?: "No title"
        val posterUrl = this.selectFirst("img")?.attr("data-src")

        // Sử dụng TvType.NSFW
        return newMovieSearchResponse(title, href, TvType.NSFW) {
            this.posterUrl = posterUrl
        }
    }

    // Chức năng tìm kiếm
    override suspend fun search(query: String): List<SearchResponse> {
        val searchUrl = "$mainUrl/?s=$query"
        val document = app.get(searchUrl).document

        return document.select("article.thumb-block").mapNotNull {
            it.toSearchResult()
        }
    }

    // Tải thông tin chi tiết của một phim
    override suspend fun load(url: String): LoadResponse? {
        val document = app.get(url).document

        val title = document.selectFirst("header.entry-header h1")?.text()?.trim() ?: return null
        val poster = document.selectFirst("meta[property='og:image']")?.attr("content")
        val description = document.selectFirst("div.video-description .desc")?.text()?.trim()
        
        val recommendations = document.select("div.under-video-block article.thumb-block").mapNotNull {
            it.toSearchResult()
        }
        
        // Sử dụng TvType.NSFW
        return newMovieLoadResponse(title, url, TvType.NSFW, url) {
            this.posterUrl = poster
            this.plot = description
            this.recommendations = recommendations
        }
    }

    // Tải các link xem phim (server S1, S2,...)
    override suspend fun loadLinks(
        data: String, 
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val document = app.get(data).document

        document.select("div.box-server a").forEach { element ->
            val serverUrl = element.attr("onclick").substringAfter("'").substringBefore("'")
            loadExtractor(serverUrl, data, subtitleCallback, callback)
        }
        
        return true
    }
}
