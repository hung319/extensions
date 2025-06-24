// Đặt package của tệp là "recloudstream"
package recloudstream

// Import các thư viện từ package gốc "com.lagradost.cloudstream3"
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import org.jsoup.nodes.Element

/**
 * Đây là lớp chính của plugin.
 */
class AnimeTVNProvider : MainAPI() {
    override var mainUrl = "https://animetvn4.com"
    override var name = "AnimeTVN"
    override val hasMainPage = true
    override var lang = "vi"
    override val hasDownloadSupport = true

    override val supportedTypes = setOf(
        TvType.Anime,
        TvType.AnimeMovie,
        TvType.OVA,
        TvType.Movie,
        TvType.TvSeries,
        TvType.Cartoon
    )

    private val scraperApiUrl = "https://m3u8.h4rs.pp.ua/api/scrape?key=11042006"

    private data class Server(val id: String?, val name: String?, val link: String?)
    private data class ServerListResponse(val success: Boolean?, val links: List<Server>?)
    private data class IframeResponse(val success: Boolean?, val link: String?)
    private data class ScraperResponse(val success: Boolean?, val links: List<String>?)

    // region Main-Page and Search (Không thay đổi)
    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val pages = listOf(
            Triple("$mainUrl/nhom/anime.html", "Anime Mới", TvType.Anime),
            Triple("$mainUrl/bang-xep-hang.html", "Bảng Xếp Hạng", TvType.Anime),
            Triple("$mainUrl/nhom/japanese-drama.html", "Live Action", TvType.TvSeries),
            Triple("$mainUrl/nhom/sieu-nhan.html", "Siêu Nhân", TvType.Cartoon),
            Triple("$mainUrl/nhom/cartoon.html", "Cartoon", TvType.Cartoon)
        )

        val all = coroutineScope {
            pages.map { (url, name, type) ->
                async {
                    try {
                        val pageUrl = "$url?page=$page"
                        val document = app.get(pageUrl).document
                        val home = if (name == "Bảng Xếp Hạng") {
                            document.select("ul.rank-film-list > li.item").mapNotNull { it.toRankingSearchResult(type) }
                        } else {
                            document.select("div.film_item").mapNotNull { it.toSearchResult(type) }
                        }
                        if (home.isNotEmpty()) HomePageList(name, home) else null
                    } catch (e: Exception) { null }
                }
            }.mapNotNull { it.await() }
        }
        
        return HomePageResponse(all, all.isNotEmpty())
    }

    private fun Element.toSearchResult(type: TvType): SearchResponse? {
        val titleElement = this.selectFirst("h3.title a") ?: return null
        val title = titleElement.text()
        val href = titleElement.attr("href")
        val posterUrl = this.selectFirst("img.thumb")?.attr("src")
        return newAnimeSearchResponse(title, href, type) { this.posterUrl = posterUrl }
    }
    
    private fun Element.toRankingSearchResult(type: TvType): SearchResponse? {
        val linkElement = this.selectFirst("a.image") ?: return null
        val href = linkElement.attr("href")
        val title = linkElement.selectFirst("h3.title")?.text() ?: return null
        val posterUrl = linkElement.selectFirst("img.thumb")?.attr("src")
        return newAnimeSearchResponse(title, href, type) { this.posterUrl = posterUrl }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val searchUrl = "$mainUrl/tim-kiem/${query}.html?page=1"
        val document = app.get(searchUrl).document
        return document.select("div.film_item").mapNotNull { it.toSearchResult(TvType.Anime) }
    }
    //endregion

    override suspend fun load(url: String): LoadResponse? {
        val document = app.get(url).document
        val title = document.selectFirst("h2.name-vi")?.text() ?: return null
        val poster = document.selectFirst("div.small_img img")?.attr("src")
        val description = document.selectFirst("div#tab-film-content div.content")?.text()
        val genres = document.select("li.has-color:contains(Thể loại) a").map { it.text() }
        val watchPageUrl = document.selectFirst("a.btn.play-now")?.attr("href")

        val episodes = if (watchPageUrl != null) {
            val watchPageDocument = app.get(watchPageUrl).document
            val episodeElements = watchPageDocument.select("div.eplist a.tapphim")
            data class TempEpisode(val url: String, val epNum: Float)
            
            val tempEpisodes = episodeElements.mapNotNull { ep ->
                val epUrl = ep.attr("href")
                val epText = ep.text().replace("_", ".")
                val epNum = epText.toFloatOrNull()
                if (epNum != null) TempEpisode(epUrl, epNum) else null
            }

            tempEpisodes.distinctBy { it.epNum }
                .sortedByDescending { it.epNum }
                .map { tempEp ->
                    val formattedEpNumber = if (tempEp.epNum == tempEp.epNum.toInt().toFloat()) {
                        tempEp.epNum.toInt().toString()
                    } else {
                        tempEp.epNum.toString()
                    }
                    newEpisode(tempEp.url) { this.name = "Tập $formattedEpNumber"; this.episode = null }
                }
        } else {
            listOf()
        }

        val isLiveAction = genres.any { it.equals("Live Action", ignoreCase = true) || it.equals("Japanese Drama", ignoreCase = true) }
        val isTokusatsuOrCartoon = genres.any { it.equals("Siêu Nhân", ignoreCase = true) || it.equals("Tokusatsu", ignoreCase = true) || it.equals("Cartoon", ignoreCase = true) }

        if (isLiveAction) {
            return if (episodes.size > 1) newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) { this.posterUrl = poster; this.plot = description; this.tags = genres } 
            else newMovieLoadResponse(title, url, TvType.Movie, url) { this.posterUrl = poster; this.plot = description; this.tags = genres }
        }

        if (isTokusatsuOrCartoon) {
            return if (episodes.size > 1) newTvSeriesLoadResponse(title, url, TvType.Cartoon, episodes) { this.posterUrl = poster; this.plot = description; this.tags = genres } 
            else newMovieLoadResponse(title, url, TvType.Cartoon, url) { this.posterUrl = poster; this.plot = description; this.tags = genres }
        }

        return if (episodes.isNotEmpty()) newTvSeriesLoadResponse(title, url, TvType.Anime, episodes) { this.posterUrl = poster; this.plot = description; this.tags = genres } 
        else newMovieLoadResponse(title, url, TvType.AnimeMovie, url) { this.posterUrl = poster; this.plot = description; this.tags = genres }
    }

    /**
     * CẬP NHẬT: Thêm log chi tiết hơn để gỡ lỗi.
     */
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        // Hàm helper để hiển thị log trên UI
        fun log(message: String) {
            callback.invoke(
                ExtractorLink(this.name, message, "#", "", Qualities.Unknown.value, type = ExtractorLinkType.M3U8)
            )
        }

        try {
            log("1. Bắt đầu `loadLinks`")
            log("URL đầu vào: $data")

            // Bước 1: Tải trang xem phim để lấy token
            log("1.1. Chuẩn bị tải trang tập phim...")
            val episodePage = app.get(data).document
            log("1.2. Đã tải trang. Đang tìm CSRF token...")
            val csrfToken = episodePage.selectFirst("meta[name=csrf-token]")?.attr("content")
            if (csrfToken == null) {
                log("1.3. LỖI: Không tìm thấy CSRF Token.")
                return false
            }
            log("1.3. Tìm thấy Token: OK")

            // Bước 2: Lấy epid
            val epid = Regex("-f(\\d+)").find(data)?.groupValues?.get(1)
             if (epid == null) {
                log("2. LỖI: Không tìm thấy EPID.")
                return false
            }
            log("2. EPID: $epid")

            // Bước 3: Lấy danh sách server
            val headers = mapOf(
                "X-Requested-With" to "XMLHttpRequest",
                "X-CSRF-TOKEN" to csrfToken,
                "Referer" to data
            )
            log("3.1. Chuẩn bị gọi API lấy server...")
            val serverListResponse = app.post(
                "$mainUrl/ajax/getExtraLinks",
                data = mapOf("epid" to epid),
                headers = headers
            ).parsedSafe<ServerListResponse>()
            
            val serverList = serverListResponse?.links
            if (serverList.isNullOrEmpty()) {
                log("3.2. LỖI: Không có server nào được trả về.")
                return false
            }
            log("3.2. Tìm thấy ${serverList.size} server.")

            // Lặp qua từng server
            serverList.forEach { server ->
                val serverName = server.name ?: "Unknown Server"
                log("4. Đang xử lý server: $serverName")
                try {
                    val iframeResponse = app.post(
                        "$mainUrl/ajax/getExtraLink",
                        data = mapOf("id" to server.id!!, "link" to server.link!!),
                        headers = headers
                    ).parsedSafe<IframeResponse>()
                    val iframeUrl = iframeResponse?.link
                    
                    if (iframeUrl == null) {
                        log("4.1. LỖI: Không lấy được iframe URL cho $serverName")
                        return@forEach // Thử server tiếp theo
                    }
                    log("4.2. Iframe URL: $iframeUrl")
                    
                    val scraperPayload = mapOf(
                        "url" to iframeUrl,
                        "hasJs" to true,
                        "headers" to mapOf("Referer" to data)
                    )

                    log("4.3. Đang gọi API Scraper...")
                    val scraperResponse = app.post(
                        scraperApiUrl,
                        json = scraperPayload,
                        headers = mapOf("Content-Type" to "application/json")
                    ).parsedSafe<ScraperResponse>()

                    val m3u8Url = scraperResponse?.links?.firstOrNull()
                    if (m3u8Url == null) {
                         log("4.4. LỖI: API Scraper không trả về link m3u8.")
                    } else {
                        log("5. THÀNH CÔNG! Đang gọi callback...")
                        callback.invoke(
                            ExtractorLink(
                                source = this.name,
                                name = "✅ ${server.name}",
                                url = m3u8Url,
                                referer = mainUrl,
                                quality = Qualities.Unknown.value,
                                type = ExtractorLinkType.M3U8
                            )
                        )
                    }
                } catch (e: Exception) {
                    log("LỖI Server $serverName: ${e.message?.take(100)}") // Giới hạn độ dài thông báo lỗi
                }
            }
        } catch (e: Exception) {
            log("LỖI TỔNG THỂ: ${e.message?.take(100)}")
        }
        
        return true
    }
}
