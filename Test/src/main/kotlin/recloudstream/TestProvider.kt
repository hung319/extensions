package recloudstream

import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.SearchQuality
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
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
        TvType.Cartoon
    )

    private fun toSearchQuality(qualityString: String?): SearchQuality? {
        return when {
            qualityString == null -> null
            qualityString.contains("4K") || qualityString.contains("UHD") -> SearchQuality.FourK
            qualityString.contains("1080") || qualityString.contains("FullHD") -> SearchQuality.HD
            qualityString.contains("720") -> SearchQuality.HD
            qualityString.contains("HD") -> SearchQuality.HD
            else -> null
        }
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = "$mainUrl/moi-cap-nhat?page=$page"
        val document = app.get(url).document
        val newMovies = document.select("div.film_list-wrap div.flw-item").mapNotNull { it.toSearchResult() }
        return HomePageResponse(listOf(HomePageList("Phim Mới Cập Nhật", newMovies)), hasNext = newMovies.isNotEmpty())
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val titleElement = this.selectFirst("h3.film-name a") ?: return null
        val title = titleElement.text().trim()
        val href = fixUrl(titleElement.attr("href"))
        val posterUrl = fixUrlNull(this.selectFirst("img.film-poster-img")?.attr("data-src"))
        val episodeStr = this.selectFirst("div.tick-rate")?.text()?.trim()
        val episodeNum = episodeStr?.let { Regex("""\d+""").find(it)?.value?.toIntOrNull() }
        val qualityText = this.selectFirst(".tick-dub")?.text()?.trim()

        return newAnimeSearchResponse(title, href, TvType.Cartoon) {
            this.posterUrl = posterUrl
            this.quality = toSearchQuality(qualityText)
            addDubStatus(dubExist = true, subExist = true, episodeNum)
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val searchUrl = "$mainUrl/search?keysearch=$query"
        val document = app.get(searchUrl).document
        return document.select("div.film_list-wrap div.flw-item").mapNotNull { it.toSearchResult() }
    }

    private fun getEpisodesFromDoc(doc: Document?): List<Episode> {
        return doc?.select("div#top-comment div.ss-list a.ssl-item, div#new-comment div.ss-list a.ssl-item")
            ?.distinctBy { it.selectFirst(".ssli-order")?.text() }
            ?.map {
                val epUrl = fixUrl(it.attr("href"))
                val name = "Tập " + it.selectFirst(".ssli-order")?.text()?.trim()
                Episode(epUrl, name)
            }?.reversed() ?: emptyList()
    }

    override suspend fun load(url: String): LoadResponse? {
        val document = app.get(url).document
        val title = document.selectFirst("h2.film-name")?.text()?.trim() ?: return null
        val poster = fixUrlNull(document.selectFirst("div.anisc-poster img")?.attr("src") ?: document.selectFirst("meta[property=og:image]")?.attr("content"))
        val plot = document.selectFirst("div.film-description div.text")?.text()?.trim()
        val tags = document.select("div.anisc-info a.genre").map { it.text() }
        val year = document.select("div.anisc-info span.item-head:contains(Năm:) + span.name")?.text()?.toIntOrNull()

        val dubWatchUrl = fixUrlNull(document.selectFirst("a.btn-play")?.attr("href"))
        val subWatchUrl = fixUrlNull(document.selectFirst("a.custom-button-sub")?.attr("href"))

        var episodes: List<Episode> = emptyList()

        coroutineScope {
            val dubEpisodesDeferred = async { dubWatchUrl?.let { getEpisodesFromDoc(app.get(it).document) } ?: emptyList() }
            val subEpisodesDeferred = async { subWatchUrl?.let { getEpisodesFromDoc(app.get(it).document) } ?: emptyList() }
            val dubEpisodes = dubEpisodesDeferred.await()
            val subEpisodes = subEpisodesDeferred.await()
            episodes = if (subEpisodes.size > dubEpisodes.size) subEpisodes else dubEpisodes
        }

        if (episodes.isEmpty()) {
            return newMovieLoadResponse(title, url, TvType.Cartoon, dubWatchUrl ?: subWatchUrl ?: url) {
                this.posterUrl = poster
                this.year = year
                this.plot = plot
                this.tags = tags
            }
        }

        return newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
            this.posterUrl = poster
            this.year = year
            this.plot = plot
            this.tags = tags
        }
    }

    private suspend fun extractLinksFromPage(url: String, prefix: String, callback: (ExtractorLink) -> Unit) {
        try {
            val document = app.get(url, timeout = 10L).document
            val script = document.select("script").find { it.data().contains("var \$fb =") }?.data() ?: return

            try {
                // Sửa: Regex linh hoạt hơn với khoảng trắng
                val fboJsonRegex = Regex("""source_fbo:\s*(\[.*?\])""")
                val fboMatch = fboJsonRegex.find(script)
                if (fboMatch != null) {
                    val fboJson = fboMatch.destructured.component1()
                    val fboLinks = parseJson<List<FboSource>>(fboJson)
                    fboLinks.firstOrNull()?.file?.let { fileUrl ->
                        callback.invoke(
                            newExtractorLink(this.name, "$prefix - FBO (HD+)", fileUrl, type = ExtractorLinkType.VIDEO) {
                                this.referer = mainUrl
                                this.quality = Qualities.P1080.value
                            }
                        )
                    }
                }
            } catch (e: Exception) {
                // Sửa: Dùng throw Exception để hiện lỗi chi tiết khi gỡ lỗi
                throw e
            }

            val linkRegex = Regex("""checkLink(\d+)\s*=\s*["'](.*?)["']""")
            val serverRegex = Regex("""id="sv_LINK(\d+)"\s*name="LINK\d+">(.*?)<""")
            val servers = serverRegex.findAll(document.html()).associate { it.groupValues[1] to it.groupValues[2] }

            linkRegex.findAll(script).forEach { match ->
                val (id, link) = match.destructured
                if (link.isNotBlank()) {
                    val serverName = servers[id] ?: "Server $id"
                    val finalName = "$prefix - $serverName"
                    val tempLink = if (link.contains("short.icu")) {
                        app.get(link, allowRedirects = false).headers["location"]
                    } else {
                        link
                    }
                    if (tempLink != null) {
                        val absoluteLink = fixUrl(tempLink)
                        callback(
                            newExtractorLink(this.name, finalName, absoluteLink, type = ExtractorLinkType.VIDEO) {
                                this.referer = mainUrl
                            }
                        )
                    }
                }
            }
        } catch (e: Exception) {
            throw e // Hiển thị lỗi ra ngoài nếu toàn bộ quá trình thất bại
        }
    }

    override suspend fun loadLinks(data: String, isCasting: Boolean, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit): Boolean {
        val path = if (data.startsWith(mainUrl)) {
            URI(data).path.removePrefix("/sever2")
        } else {
            return false
        }
        
        val dubUrl = "$mainUrl$path"
        val subUrl = "$mainUrl/sever2$path"

        coroutineScope {
            launch { extractLinksFromPage(dubUrl, "TM", callback) }
            launch { extractLinksFromPage(subUrl, "VS", callback) }
        }

        return true
    }

    data class FboSource(@JsonProperty("file") val file: String?)
}
