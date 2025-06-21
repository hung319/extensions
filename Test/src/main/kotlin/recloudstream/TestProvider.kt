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
    // Ghi đè các thuộc tính cơ bản của plugin
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

        val episodeElements = watchDocument.select("div.episodes div.list-episode a")

        val isSeries = episodeElements.any { "Tập \\d+".toRegex().find(it.attr("title")) != null }

        return if (isSeries) {
            val episodes = episodeElements.map { element ->
                val originalName = element.attr("title").ifBlank { element.text() }
                val simplifiedName = "Tập \\d+".toRegex().find(originalName)?.value ?: originalName

                newEpisode(fixUrl(element.attr("href"))) {
                    this.name = simplifiedName
                    this.description = element.text()
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
            // Với phim lẻ, chỉ hiển thị nút play, không hiển thị danh sách server
            // dataUrl sẽ được xử lý trong loadLinks để chọn server phù hợp
            newMovieLoadResponse(title, url, if (isAnime) TvType.Anime else TvType.Movie, url) {
                this.posterUrl = poster
                this.year = year
                this.plot = description
                this.tags = tags
                this.recommendations = recommendations
            }
        }
    }

    // CẬP NHẬT LỚN: Viết lại loadLinks để thử nghiệm logic lấy token
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        // `data` là URL gốc của phim (vd: /phim/abc-123)
        // Cần vào trang xem phim để lấy danh sách server
        val document = app.get(data).document
        val watchUrl = document.selectFirst("a.btn-stream-link")?.attr("href")?.let { fixUrl(it) } ?: data
        val watchDocument = app.get(watchUrl).document
        val serverElements = watchDocument.select("div.episodes div.list-episode a")

        // Duyệt qua từng server và thử lấy link
        var success = false
        serverElements.apmap { server ->
            try {
                val serverUrl = fixUrl(server.attr("href"))
                val serverName = server.text()
                
                // Logic cho Server Gốc
                if (serverName.contains("Gốc")) {
                    val embedDoc = app.get(serverUrl, referer = watchUrl).document
                    val scriptContent = embedDoc.select("script").firstOrNull { it.data().contains("var videoId") }?.data() ?: return@apmap
                    val videoId = "var videoId = '(.*?)'".toRegex().find(scriptContent)?.groupValues?.get(1) ?: return@apmap
                    val cdn = "var cdn = '(.*?)'".toRegex().find(scriptContent)?.groupValues?.get(1) ?: return@apmap

                    val tokenData = app.post(
                        "https://moviking.childish2x2.fun/geturl",
                        headers = mapOf(
                            "X-Requested-With" to "XMLHttpRequest",
                            "Referer" to serverUrl
                        ),
                        // Thử gửi request mà không cần body phức tạp
                    ).text

                    val streamingUrl = "$cdn/streaming?id=$videoId&$tokenData"
                    val finalStreamPage = app.get(streamingUrl, referer = serverUrl).document
                    val playerScript = finalStreamPage.select("script").firstOrNull { it.data().contains("var url =") }?.data() ?: return@apmap
                    val finalM3u8Url = "var url = '(.*?)'".toRegex().find(playerScript)?.groupValues?.get(1) ?: return@apmap
                    
                    callback(ExtractorLink(source = serverName, name = name, url = finalM3u8Url, referer = streamingUrl, quality = Qualities.P1080.value, type = ExtractorLinkType.M3U8))
                    success = true
                } 
                // Logic cho Server thứ 3
                else {
                    val iframe1Doc = app.get(serverUrl, referer = watchUrl).document
                    val iframe2Url = iframe1Doc.selectFirst("iframe#embedIframe")?.attr("src") ?: return@apmap
                    if (iframe2Url.isBlank()) return@apmap
                    
                    val iframe2Doc = app.get(iframe2Url, referer = serverUrl).document
                    val playerScript = iframe2Doc.select("script").firstOrNull { it.data().contains("var url =") }?.data() ?: return@apmap
                    val finalM3u8Url = "var url = '(.*?)'".toRegex().find(playerScript)?.groupValues?.get(1) ?: return@apmap
                    
                    callback(ExtractorLink(source = serverName, name = name, url = finalM3u8Url, referer = iframe2Url, quality = Qualities.Unknown.value, type = ExtractorLinkType.M3U8))
                    success = true
                }
            } catch (e: Exception) {
                // Bỏ qua lỗi và thử server tiếp theo
            }
        }
        return success
    }
}
