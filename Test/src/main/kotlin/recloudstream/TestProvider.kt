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
import com.lagradost.cloudstream3.newMovieLoadResponse
import com.lagradost.cloudstream3.newMovieSearchResponse
import com.lagradost.cloudstream3.utils.loadExtractor
import org.jsoup.nodes.Element
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.async

// Đã loại bỏ import không cần thiết và gây lỗi
// import com.lagradost.cloudstream3.extractors.Smoothpre

class JavSubIdnProvider : MainAPI() {
    override var mainUrl = "https://javsubidn.vip"
    override var name = "JavSubIdn"
    override val hasMainPage = true
    override var lang = "id" 
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(
        TvType.NSFW
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = if (page == 1) mainUrl else "$mainUrl/page/$page"
        val document = app.get(url).document
        val home = document.select("article.thumb-block").mapNotNull { it.toSearchResult() }
        val homePageList = HomePageList(name = "Video Jav Terbaru", list = home)
        val hasNext = document.selectFirst("div.pagination a:contains(Next)") != null
        return HomePageResponse(items = listOf(homePageList), hasNext = hasNext)
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

    // =================================================================
    // CẬP NHẬT HÀM LOADLINKS (PHIÊN BẢN AN TOÀN HƠN)
    // =================================================================
    override suspend fun loadLinks(
        data: String, 
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val document = app.get(data).document

        coroutineScope {
            document.select("div.box-server a").map { element ->
                async {
                    try {
                        var serverUrl = element.attr("onclick").substringAfter("'").substringBefore("'")
                        
                        // Nếu là link smoothpre, thử đổi /v/ thành /e/.
                        // Đây là "mẹo" phổ biến để sửa link embed.
                        if (serverUrl.contains("smoothpre.com", true)) {
                            serverUrl = serverUrl.replace("/v/", "/e/")
                        }

                        // Luôn sử dụng hàm loadExtractor chung để tương thích với mọi phiên bản CloudStream.
                        loadExtractor(serverUrl, data, subtitleCallback, callback)
                        
                    } catch (e: Exception) {
                        // Bỏ qua lỗi nếu một server nào đó không hoạt động
                    }
                }
            }
        }
        
        return true
    }
}
