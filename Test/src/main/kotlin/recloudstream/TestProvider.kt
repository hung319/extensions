package recloudstream

import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import org.jsoup.nodes.Element

class Yanhh3dProvider : MainAPI() {
    override var mainUrl = "https://yanhh3d.vip"
    override var name = "YanHH3D"
    override val hasMainPage = true
    override var lang = "vi"
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(
        TvType.Cartoon,
        TvType.Anime,
        TvType.TvSeries
    )

    // ============================ HOMEPAGE ============================
    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val document = app.get(mainUrl).document
        
        // Chỉ lấy mục Phim Mới Cập Nhật
        val newMovies = document.select("div.film_list-wrap div.flw-item").mapNotNull { 
            it.toSearchResult() 
        }

        // Đã loại bỏ mục Phim Đề Cử theo yêu cầu
        return HomePageResponse(listOf(HomePageList("Phim Mới Cập Nhật", newMovies)))
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val title = this.selectFirst("h3.film-name a")?.text()?.trim() ?: return null
        val href = this.selectFirst("a.film-poster-ahref")?.attr("href") ?: return null
        val posterUrl = this.selectFirst("img.film-poster-img")?.attr("data-src")
        val episodeStr = this.selectFirst("div.tick-rate")?.text()?.trim()
        val episodeNum = episodeStr?.let { Regex("""\d+""").find(it)?.value?.toIntOrNull() }

        return newAnimeSearchResponse(title, href, TvType.Anime) {
            this.posterUrl = posterUrl
            addDubStatus(dubExist = true, subExist = true, episodeNum)
        }
    }

    // ============================ SEARCH ============================
    override suspend fun search(query: String): List<SearchResponse> {
        val searchUrl = "$mainUrl/search?keysearch=$query"
        val document = app.get(searchUrl).document

        return document.select("div.film_list-wrap div.flw-item").mapNotNull {
            it.toSearchResult()
        }
    }

    // ============================ LOAD DETAILS ============================
    override suspend fun load(url: String): LoadResponse? {
        // 1. Tải trang thông tin phim để lấy metadata
        val document = app.get(url).document

        val title = document.selectFirst("h2.film-name")?.text()?.trim() ?: return null
        val poster = document.selectFirst("div.anisc-poster img")?.attr("src")
            ?: document.selectFirst("meta[property=og:image]")?.attr("content")
        val plot = document.selectFirst("div.film-description div.text")?.text()?.trim()
        val tags = document.select("div.anisc-info a.genre").map { it.text() }
        val year = document.select("div.anisc-info span.item-head:contains(Năm:) + span.name")?.text()?.toIntOrNull()

        // 2. Lấy link trang xem phim từ nút "Xem Thuyết Minh"
        val watchPageUrl = document.selectFirst("a.btn.btn-play")?.attr("href")
        
        // 3. Tải trang xem phim để lấy danh sách tập đầy đủ
        val episodeDocument = if (watchPageUrl != null) {
            app.get(watchPageUrl).document
        } else {
            // Nếu không có nút xem phim, dùng trang hiện tại (trường hợp phim lẻ)
            document
        }

        val episodesThuyetMinh = episodeDocument.select("div#top-comment div.ss-list a.ssl-item").map {
            val epUrl = it.attr("href")
            val name = "Tập " + it.selectFirst(".ssli-order")?.text()?.trim() + " (TM)"
            Episode(epUrl, name)
        }.reversed()

        val episodesVietSub = episodeDocument.select("div#new-comment div.ss-list a.ssl-item").map {
            val epUrl = it.attr("href")
            val name = "Tập " + it.selectFirst(".ssli-order")?.text()?.trim() + " (VS)"
            Episode(epUrl, name)
        }.reversed()

        return newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodesThuyetMinh + episodesVietSub) {
            this.posterUrl = poster
            this.year = year
            this.plot = plot
            this.tags = tags
        }
    }

    // ============================ LOAD LINKS (VIDEO SOURCES) ============================
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val document = app.get(data).document
        val script = document.select("script").find { it.data().contains("var \$fb =") }?.data()
            ?: return false

        // --- Trích xuất link trực tiếp từ Facebook (FBO) ---
        try {
            val fboJsonRegex = Regex("""source_fbo: (\[.*?\])""")
            val fboMatch = fboJsonRegex.find(script)
            if (fboMatch != null) {
                val fboJson = fboMatch.destructured.component1()
                val fboLinks = parseJson<List<FboSource>>(fboJson)
                fboLinks.firstOrNull()?.file?.let { fileUrl ->
                    callback.invoke(
                        newExtractorLink(
                            source = this.name,
                            name = "FBO (HD+)",
                            url = fileUrl,
                            type = ExtractorLinkType.VIDEO
                        ) {
                            this.referer = mainUrl
                            this.quality = Qualities.P1080.value
                        }
                    )
                }
            }
        } catch (e: Exception) {
            // FBO link not found or parsing error, continue
        }

        // --- Trích xuất các link iframe khác ---
        val linkRegex = Regex("""checkLink(\d+)\s*=\s*["'](.*?)["']""")
        val serverRegex = Regex("""id="sv_LINK(\d+)"\s*name="LINK\d+">(.*?)<""")

        val servers = serverRegex.findAll(document.html()).map {
            val id = it.groupValues[1]
            val name = it.groupValues[2]
            id to name
        }.toMap()

        linkRegex.findAll(script).forEach { match ->
            val (id, link) = match.destructured
            if (link.isNotBlank()) {
                val serverName = servers[id] ?: "Server $id"
                 if(link.contains("short.icu")) { // Handle short link
                    val unshortened = app.get(link, allowRedirects = false).headers["location"]
                    if (unshortened != null) {
                        loadExtractor(unshortened, subtitleCallback, callback)
                    }
                } else {
                     loadExtractor(link, serverName, subtitleCallback, callback)
                }
            }
        }
        return true
    }

    // Data class for parsing FBO JSON
    data class FboSource(@JsonProperty("file") val file: String?)
}
