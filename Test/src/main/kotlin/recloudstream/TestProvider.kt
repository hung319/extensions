// Đặt package của tệp là "recloudstream"
package recloudstream

// Import các thư viện cần thiết
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

    // Các lớp data class để parse JSON
    private data class Server(val id: String?, val name: String?, val link: String?)
    private data class ServerListResponse(val success: Boolean?, val links: List<Server>?)
    private data class IframeResponse(val success: Boolean?, val link: String?)

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
     * CẬP NHẬT: Dùng link iframe làm link stream để debug.
     */
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        // Bước 1: Tải trang xem phim để lấy token và epid
        val episodePage = app.get(data, headers = mapOf(
            "User-Agent" to "Mozilla/5.0 (Linux; Android 10) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/107.0.0.0 Mobile Safari/537.36"
        )).document
        val csrfToken = episodePage.selectFirst("meta[name=csrf-token]")?.attr("content") ?: return false
        val epid = Regex("-f(\\d+)").find(data)?.groupValues?.get(1) ?: return false

        // Bước 2: Lấy danh sách server
        val headers = mapOf(
            "X-Requested-With" to "XMLHttpRequest",
            "X-CSRF-TOKEN" to csrfToken,
            "Referer" to data
        )
        val serverList = app.post(
            "$mainUrl/ajax/getExtraLinks",
            data = mapOf("epid" to epid),
            headers = headers
        ).parsedSafe<ServerListResponse>()?.links

        // Bước 3: Lặp qua từng server để lấy link iframe và gửi cho callback
        serverList?.forEach { server ->
            try {
                val iframeUrl = app.post(
                    "$mainUrl/ajax/getExtraLink",
                    data = mapOf("id" to server.id!!, "link" to server.link!!),
                    headers = headers
                ).parsedSafe<IframeResponse>()?.link ?: return@forEach

                // Gửi trực tiếp link iframe cho callback
                callback.invoke(
                    ExtractorLink(
                        source = this.name,
                        name = server.name ?: this.name,
                        url = iframeUrl,
                        referer = mainUrl, // Referer là trang chứa iframe
                        quality = Qualities.Unknown.value,
                        type = ExtractorLinkType.M3U8 // Đặt type là Iframe
                    )
                )
            } catch (e: Exception) {
                // Bỏ qua nếu có lỗi
            }
        }
        return true
    }
}
