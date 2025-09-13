package com.lagradost.cloudstream3

import com.lagradost.cloudstream3.utils.*
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.net.URI
import com.lagradost.cloudstream3.CommonActivity.showToast
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import android.widget.Toast

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
        withContext(Dispatchers.Main) {
            CommonActivity.activity?.let { activity ->
                showToast(activity, "Free Repo From SIX [H4RS]\nTelegram/Discord: hung319", Toast.LENGTH_LONG)
            }
        }
        val url = if (page == 1) mainUrl else "$mainUrl/moi-cap-nhat?page=$page"
        val document = app.get(url).document
        val newMovies = document.select("div.film_list-wrap div.flw-item").mapNotNull { it.toSearchResult() }
        return newHomePageResponse(listOf(HomePageList("Phim Mới Cập Nhật", newMovies)), hasNext = newMovies.isNotEmpty())
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
        val selector = if (type == "TM") "#top-comment .ss-list a.ssl-item" else "#new-comment .ss-list a.ssl-item"
        return doc?.select(selector)
            ?.mapNotNull {
                val epUrl = it.attr("href")
                val orderText = it.selectFirst(".ssli-order")?.text()?.trim()
                if (epUrl.isBlank() || orderText.isNullOrBlank()) return@mapNotNull null
                Pair(orderText, fixUrl(epUrl))
            } ?: emptyList()
    }

    private data class MergedEpisodeInfo(var dubUrl: String? = null, var subUrl: String? = null)

    override suspend fun load(url: String): LoadResponse? {
        val document = app.get(url).document
        val title = document.selectFirst("h2.film-name a")?.text()?.trim() ?: return null
        val poster = fixUrlNull(document.selectFirst(".anis-poster img")?.attr("src") ?: document.selectFirst("meta[property=og:image]")?.attr("content"))
        val plot = document.selectFirst("div.film-description div.text")?.text()?.trim()
        val tags = document.select(".anisc-info .item-list:contains(Thể loại) a.name").map { it.text() }
        val year = document.selectFirst(".anisc-info .item-list:contains(Năm:) .name")?.text()?.toIntOrNull()

        // logic cũ để lấy danh sách tập, hiện tại trang đã đổi sang load trực tiếp không qua trang chi tiết nữa
        val firstEpisodeUrl = document.selectFirst(".btn-play")?.attr("href")
            ?: document.selectFirst("a.ssl-item")?.attr("href")
            ?: url

        val episodeDocument = app.get(firstEpisodeUrl).document
        val dubData = getEpisodeData(episodeDocument, "TM")
        val subData = getEpisodeData(episodeDocument, "VS")

        val mergedMap = mutableMapOf<String, MergedEpisodeInfo>()
        dubData.forEach { (name, epUrl) -> mergedMap.getOrPut(name) { MergedEpisodeInfo() }.dubUrl = epUrl }
        subData.forEach { (name, epUrl) -> mergedMap.getOrPut(name) { MergedEpisodeInfo() }.subUrl = epUrl }

        val episodes = mergedMap.map { (name, info) ->
            val tag = when {
                info.dubUrl != null && info.subUrl != null -> "(TM+VS)"
                info.dubUrl != null -> "(TM)"
                info.subUrl != null -> "(VS)"
                else -> ""
            }
            val episodeName = "Tập $name $tag".trim()
            // Ưu tiên URL thuyết minh nếu có
            val episodeUrl = info.dubUrl ?: info.subUrl!!
            newEpisode(episodeUrl) { this.name = episodeName }
        }.sortedBy { episode ->
            // Sắp xếp an toàn hơn, xử lý cả trường hợp gộp tập "1-5"
            episode.name?.let { Regex("""\d+""").find(it)?.value?.toIntOrNull() } ?: 0
        }

        val recommendations = document.select("section.block_area_category div.flw-item").mapNotNull { it.toSearchResult() }

        return newTvSeriesLoadResponse(title, url, TvType.Cartoon, episodes) {
            this.posterUrl = poster; this.year = year; this.plot = plot; this.tags = tags
            this.recommendations = recommendations
        }
    }

    private suspend fun extractVideoLinks(
        url: String,
        prefix: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        try {
            val document = app.get(url, timeout = 20L).document
            val scriptContent = document.select("script").find {
                it.data().contains("\$checkLink") || it.data().contains("\$checkFbo")
            }?.data() ?: return

            // 1. Extract all potential links from script into a map
            val linksMap = mutableMapOf<String, String>()
            Regex("""var\s*\${'$'}check(\w+)\s*=\s*['"](.*?)['"];""").findAll(scriptContent).forEach {
                val id = it.groupValues[1].uppercase()
                val link = it.groupValues[2]
                if (link.isNotBlank()) linksMap[id] = link
            }
            // Extract FBO link specifically
            Regex("""source_fbo:\s*\[\{"file":"(.*?)"\}\]""").find(scriptContent)?.let {
                linksMap["FBO"] = it.groupValues[1]
            }

            // 2. Iterate through server buttons to process links
            document.select("div.ps__-list a.btn3dsv").forEach { serverElement ->
                try {
                    val serverId = serverElement.attr("name").uppercase()
                    val serverName = serverElement.text()
                    var link = linksMap[serverId] ?: return@forEach

                    val finalName = "$prefix - $serverName"

                    if (link.contains("short.icu")) {
                        link = app.get(link, allowRedirects = false).headers["location"] ?: return@forEach
                    }

                    when (serverName.uppercase()) {
                        "HD+" -> { // FBO direct link
                            callback(
                                ExtractorLink(
                                    this.name,
                                    finalName,
                                    link,
                                    "",
                                    Qualities.Unknown.value,
                                    type = ExtractorLinkType.VIDEO
                                )
                            )
                        }
                        "HD" -> { // Custom play-fb-v7 logic
                             if (link.contains("/play-fb-v")) {
                                val playerDoc = app.get(link, referer = url).document
                                var finalUrl = playerDoc.selectFirst("#player")?.attr("data-stream-url")
                                if (finalUrl.isNullOrBlank()) {
                                    finalUrl = Regex("""var cccc = "([^"]+)""").find(playerDoc.html())?.groupValues?.get(1)
                                }
                                if (!finalUrl.isNullOrBlank()) {
                                   loadExtractor(finalUrl, mainUrl, subtitleCallback, callback)
                                }
                            }
                        }
                        "1080", "4K" -> { // M3U8 Stream link
                            val m3u8Url = link.replace("/o1/v/t2/f2/m366/", "/stream/m3u8/").substringBefore("?")
                             callback(
                                ExtractorLink(
                                    this.name,
                                    finalName,
                                    m3u8Url,
                                    mainUrl,
                                    Qualities.Unknown.value,
                                    type = ExtractorLinkType.M3U8
                                )
                            )
                        }
                        "LINK8" -> { // Helvid extractor
                            if(link.contains("helvid.net")) {
                                val helvidPage = app.get(link).text
                                val m3u8Path = Regex("""file:\s*"(.*?)"""").find(helvidPage)?.groupValues?.get(1)
                                if (m3u8Path != null) {
                                    val baseUrl = URI(link).let { "${it.scheme}://${it.host}" }
                                    val m3u8Link = if (m3u8Path.startsWith("http")) m3u8Path else baseUrl + m3u8Path
                                    callback(
                                        ExtractorLink(
                                            this.name,
                                            finalName,
                                            m3u8Link,
                                            baseUrl,
                                            Qualities.P1080.value,
                                            type = ExtractorLinkType.M3U8
                                        )
                                    )
                                }
                            }
                        }
                        else -> { // Default handler for Dailymotion, etc.
                            loadExtractor(link, mainUrl, subtitleCallback, callback)
                        }
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        // Path logic is different now, sometimes it contains /sever2, sometimes not.
        // We can build both URLs and check which one is valid or just try both.
        val path = try {
            URI(data).path.replace("/sever2", "")
        } catch (e: Exception) {
            return false
        }
        if (path.isBlank()) return false
        
        val dubUrl = "$mainUrl$path"
        val subUrl = "$mainUrl/sever2$path"

        coroutineScope {
            // Check both TM and VS links concurrently
            launch { extractVideoLinks(dubUrl, "TM", subtitleCallback, callback) }
            launch { extractVideoLinks(subUrl, "VS", subtitleCallback, callback) }
        }
        return true
    }
}
