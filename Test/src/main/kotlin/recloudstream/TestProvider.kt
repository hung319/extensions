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
    override val supportedTypes = setOf(TvType.Cartoon)

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
    
    private fun getEpisodeData(doc: Document?, type: String): List<Pair<String, String>> {
        val selector = if (type == "TM") "div#top-comment div.ss-list a.ssl-item" else "div#new-comment div.ss-list a.ssl-item"
        return doc?.select(selector)
            ?.mapNotNull {
                val epUrl = it.attr("href")
                val orderText = it.selectFirst(".ssli-order")?.text()?.trim()
                if (epUrl.isNullOrBlank() || orderText.isNullOrBlank()) return@mapNotNull null
                Pair(orderText, fixUrl(epUrl))
            } ?: emptyList()
    }

    private data class MergedEpisodeInfo(var dubUrl: String? = null, var subUrl: String? = null)

    override suspend fun load(url: String): LoadResponse? {
        val document = app.get(url).document
        val title = document.selectFirst("h2.film-name")?.text()?.trim() ?: return null
        val poster = fixUrlNull(document.selectFirst("div.anisc-poster img")?.attr("src") ?: document.selectFirst("meta[property=og:image]")?.attr("content"))
        val plot = document.selectFirst("div.film-description div.text")?.text()?.trim()
        val tags = document.select("div.anisc-info a.genre").map { it.text() }
        val year = document.select("div.anisc-info span.item-head:contains(Năm:) + span.name")?.text()?.toIntOrNull()

        val dubWatchUrl = fixUrlNull(document.selectFirst("a.btn-play")?.attr("href"))
        val subWatchUrl = fixUrlNull(document.selectFirst("a.custom-button-sub")?.attr("href"))
        
        val episodes = coroutineScope {
            val dubDataDeferred = async { dubWatchUrl?.let { getEpisodeData(app.get(it).document, "TM") } ?: emptyList() }
            val subDataDeferred = async { subWatchUrl?.let { getEpisodeData(app.get(it).document, "VS") } ?: emptyList() }

            val dubData = dubDataDeferred.await()
            val subData = subDataDeferred.await()

            val mergedMap = mutableMapOf<String, MergedEpisodeInfo>()
            dubData.forEach { (name, epUrl) -> mergedMap.getOrPut(name) { MergedEpisodeInfo() }.dubUrl = epUrl }
            subData.forEach { (name, epUrl) -> mergedMap.getOrPut(name) { MergedEpisodeInfo() }.subUrl = epUrl }

            mergedMap.map { (name, info) ->
                val tag = when {
                    info.dubUrl != null && info.subUrl != null -> "(TM+VS)"
                    info.dubUrl != null -> "(TM)"
                    info.subUrl != null -> "(VS)"
                    else -> ""
                }
                val episodeName = "Tập $name $tag".trim()
                val episodeUrl = info.dubUrl ?: info.subUrl!!
                Episode(episodeUrl, episodeName)
            }.sortedBy { episode -> episode.name?.let { name -> Regex("""\d+""").find(name)?.value?.toIntOrNull() } }
        }

        if (episodes.isEmpty()) {
            return newMovieLoadResponse(title, url, TvType.Cartoon, dubWatchUrl ?: subWatchUrl ?: url) {
                this.posterUrl = poster; this.year = year; this.plot = plot; this.tags = tags
            }
        }
        
        return newTvSeriesLoadResponse(title, url, TvType.Cartoon, episodes) {
            this.posterUrl = poster; this.year = year; this.plot = plot; this.tags = tags
        }
    }

    private suspend fun extractLinksFromPage(url: String, prefix: String, callback: (ExtractorLink) -> Unit) {
        try {
            val document = app.get(url, timeout = 10L).document
            val script = document.select("script").find { it.data().contains("var \$fb =") }?.data() ?: return

            // 1. Lấy tất cả tên server từ nút bấm trên web
            val servers = document.select("a.btn3dsv").associate {
                it.attr("name").uppercase() to it.text() // Chuyển key thành chữ hoa
            }
            
            // 2. Regex tổng quát để tìm tất cả các biến $check...
            val linkRegex = Regex("""var\s*\${'$'}check(\w+)\s*=\s*['"](.*?)['"];""")

            // 3. Quét script và khớp link với tên server
            linkRegex.findAll(script).forEach { match ->
                val id = match.groupValues[1]       // "Fbo", "Link2", "Link7"
                val link = match.groupValues[2]     // URL

                if (link.isNotBlank()) {
                    // Dùng id (đã chuyển thành chữ hoa) để tìm tên server trong map
                    val serverName = servers[id.uppercase()] ?: id
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
            e.printStackTrace()
        }
    }

    override suspend fun loadLinks(data: String, isCasting: Boolean, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit): Boolean {
        val path = try {
            URI(data).path.removePrefix("/sever2")
        } catch (e: Exception) {
            return false
        }
        if (path.isBlank()) return false
        
        val dubUrl = "$mainUrl$path"
        val subUrl = "$mainUrl/sever2$path"

        coroutineScope {
            launch { extractLinksFromPage(dubUrl, "TM", callback) }
            launch { extractLinksFromPage(subUrl, "VS", callback) }
        }

        return true
    }
}
