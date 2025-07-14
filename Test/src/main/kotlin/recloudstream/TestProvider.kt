package recloudstream

import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.net.URI

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
        val newMovies = document.select("div.film_list-wrap div.flw-item").mapNotNull {
            it.toSearchResult()
        }
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
    private fun getEpisodesFromWatchPage(doc: Document?, type: String): List<Episode> {
        return doc?.select("div.ss-list a.ssl-item")?.map {
            val epUrl = it.attr("href")
            val name = "Tập " + it.selectFirst(".ssli-order")?.text()?.trim() + " ($type)"
            Episode(epUrl, name)
        }?.reversed() ?: emptyList()
    }

    override suspend fun load(url: String): LoadResponse? {
        val document = app.get(url).document
        val title = document.selectFirst("h2.film-name")?.text()?.trim() ?: return null
        val poster = document.selectFirst("div.anisc-poster img")?.attr("src")
            ?: document.selectFirst("meta[property=og:image]")?.attr("content")
        val plot = document.selectFirst("div.film-description div.text")?.text()?.trim()
        val tags = document.select("div.anisc-info a.genre").map { it.text() }
        val year = document.select("div.anisc-info span.item-head:contains(Năm:) + span.name")?.text()?.toIntOrNull()

        // Lấy link tới trang xem phim của cả TM và VS
        val dubWatchUrl = document.selectFirst("a.btn-play")?.attr("href")
        val subWatchUrl = document.selectFirst("a.custom-button-sub")?.attr("href")

        var episodes = emptyList<Episode>()

        coroutineScope {
            // Tải song song cả 2 trang để lấy danh sách tập
            val dubEpisodesDeferred = async {
                dubWatchUrl?.let { getEpisodesFromWatchPage(app.get(it).document, "TM") } ?: emptyList()
            }
            val subEpisodesDeferred = async {
                subWatchUrl?.let { getEpisodesFromWatchPage(app.get(it).document, "VS") } ?: emptyList()
            }

            val dubEpisodes = dubEpisodesDeferred.await()
            val subEpisodes = subEpisodesDeferred.await()

            // Chỉ hiển thị danh sách tập của bên nào nhiều hơn, ưu tiên TM nếu bằng nhau
            episodes = if (subEpisodes.size > dubEpisodes.size) subEpisodes else dubEpisodes
        }


        return newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
            this.posterUrl = poster
            this.year = year
            this.plot = plot
            this.tags = tags
        }
    }

    // ============================ LOAD LINKS (VIDEO SOURCES) ============================
    private suspend fun extractLinksFromPage(
        url: String,
        prefix: String,
        callback: (ExtractorLink) -> Unit
    ) {
        try {
            val document = app.get(url, timeout = 10L).document //
            val script = document.select("script")
                .find { it.data().contains("var \$fb =") }?.data() ?: return

            // --- FBO extractor ---
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
                                name = "$prefix - FBO (HD+)",
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
                // Ignore if FBO fails
            }

            // --- Iframe links extractor ---
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
                    val finalName = "$prefix - $serverName"
                    if (link.contains("short.icu")) { // Handle short link
                        val unshortened = app.get(link, allowRedirects = false).headers["location"]
                        if (unshortened != null) {
                            loadExtractor(unshortened, mainUrl, subtitleCallback, callback, name = finalName)
                        }
                    } else {
                        loadExtractor(link, mainUrl, subtitleCallback, callback, name = finalName)
                    }
                }
            }
        } catch (e: Exception) {
            // Fails silently if a server (e.g. VS or TM) doesn't exist
        }
    }


    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        // Lấy path, loại bỏ /sever2/ nếu có
        val path = URI(data).path.removePrefix("/sever2")

        val dubUrl = "$mainUrl$path"
        val subUrl = "$mainUrl/sever2$path"

        // Ping và lấy link từ cả 2 server TM và VS song song
        coroutineScope {
            launch { extractLinksFromPage(dubUrl, "TM", callback) }
            launch { extractLinksFromPage(subUrl, "VS", callback) }
        }

        return true
    }

    // Data class for parsing FBO JSON
    data class FboSource(@JsonProperty("file") val file: String?)
}
