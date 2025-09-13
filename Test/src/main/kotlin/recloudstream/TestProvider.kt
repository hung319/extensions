package recloudstream

import com.lagradost.cloudstream3.*
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
        val url = "$mainUrl/moi-cap-nhat?page=$page"
        val document = app.get(url).document
        val newMovies = document.select("div.film_list-wrap div.flw-item").mapNotNull { it.toSearchResult() }
        // Sửa: Dùng đúng tên tham số `hasNext`
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
        
        val recommendations = document.select("section.block_area_category div.flw-item").mapNotNull { it.toSearchResult() }
        
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
                newEpisode(episodeUrl) { this.name = episodeName }
            // Sửa: Logic sắp xếp an toàn hơn để không còn lỗi
            }.sortedBy { episode ->
                episode.name?.let { Regex("""\d+""").find(it)?.value?.toIntOrNull() } ?: 0
            }
        }

        if (episodes.isEmpty()) {
            return newMovieLoadResponse(title, url, TvType.Cartoon, dubWatchUrl ?: subWatchUrl ?: url) {
                this.posterUrl = poster; this.year = year; this.plot = plot; this.tags = tags
                this.recommendations = recommendations
            }
        }
        
        return newTvSeriesLoadResponse(title, url, TvType.Cartoon, episodes) {
            this.posterUrl = poster; this.year = year; this.plot = plot; this.tags = tags
            this.recommendations = recommendations
        }
    }

    private suspend fun extractLinksFromPage(
        url: String,
        prefix: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        try {
            val document = app.get(url).document
            val scriptData = document.select("script:containsData(checkLink)").html()

            // Dùng selector CSS ổn định nhất để lấy danh sách server
            val servers = document.select("div#list_sv a").associate {
                it.attr("name") to it.text().trim()
            }
            
            val linkRegex = Regex("""var\s*\${'$'}check(\w+)\s*=\s*"([^"]+)"""")

            coroutineScope {
                linkRegex.findAll(scriptData).forEach { match ->
                    launch {
                        try {
                            val serverId = match.groupValues.getOrNull(1) ?: return@launch
                            var link = match.groupValues.getOrNull(2) ?: return@launch
                            
                            // Lấy tên server, nếu không có thì dùng ID làm tên
                            val serverName = servers[serverId] ?: serverId 
                            if (link.isBlank()) return@launch

                            if (link.contains("short.icu")) {
                                link = app.get(link, allowRedirects = false).headers["location"] ?: return@launch
                            }
                            
                            val finalUrl = fixUrl(link)
                            if (finalUrl.isBlank()) return@launch

                            val finalName = "$prefix - $serverName"

                            // =================================================================
                            // LOGIC MỚI: DỰA VÀO CẤU TRÚC URL, GỌN GÀNG VÀ ỔN ĐỊNH HƠN
                            // =================================================================
                            when {
                                // Nhóm 1: Các link cần bóc tách đặc biệt
                                finalUrl.contains("helvid.net") -> {
                                    val helvidDoc = app.get(finalUrl).document
                                    val playerScript = helvidDoc.selectFirst("script:containsData('playerInstance.setup')")?.data()
                                    val videoPath = Regex("""file:\s*"(.*?)"""").find(playerScript ?: "")?.groupValues?.get(1)

                                    if (videoPath?.isNotBlank() == true) {
                                        val videoUrl = "https://helvid.net$videoPath"
                                        callback(
                                            newExtractorLink(this@Yanhh3dProvider.name, finalName, videoUrl, type = ExtractorLinkType.M3U8) {
                                                this.referer = "https://helvid.net/"
                                            }
                                        )
                                    }
                                }
                                finalUrl.contains("/play-fb-v") -> {
                                    val playerDocument = app.get(finalUrl, referer = url).document
                                    val scrapedUrl = playerDocument.selectFirst("#player")?.attr("data-stream-url")
                                        ?: Regex("""var cccc = "([^"]+)""").find(playerDocument.html())?.groupValues?.get(1)

                                    if (scrapedUrl?.isNotBlank() == true) {
                                        loadExtractor(scrapedUrl, mainUrl, subtitleCallback, callback)
                                    }
                                }

                                // Nhóm 2: Các link từ CDN của Facebook
                                finalUrl.contains("fbcdn.") -> {
                                    if (finalUrl.contains(".m3u8")) {
                                        val m3u8Url = finalUrl.replace("/o1/v/t2/f2/m366/", "/stream/m3u8/")
                                        callback(
                                            newExtractorLink(this@Yanhh3dProvider.name, finalName, m3u8Url, type = ExtractorLinkType.M3U8) {
                                                this.referer = mainUrl
                                            }
                                        )
                                    } else {
                                        callback(
                                            newExtractorLink(this@Yanhh3dProvider.name, finalName, finalUrl, type = ExtractorLinkType.VIDEO) {
                                                this.referer = mainUrl
                                            }
                                        )
                                    }
                                }

                                // Nhóm 3: Các trường hợp còn lại
                                else -> {
                                    loadExtractor(finalUrl, mainUrl, subtitleCallback, callback)
                                }
                            }
                        } catch (e: Exception) {
                            e.printStackTrace()
                        }
                    }
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
        // Giữ nguyên cấu trúc gốc xử lý TM/VS
        val path = try {
            URI(data).path.removePrefix("/sever2")
        } catch (e: Exception) {
            return false
        }
        if (path.isBlank()) return false
        
        val dubUrl = "$mainUrl$path"
        val subUrl = "$mainUrl/sever2$path"

        coroutineScope {
            launch { extractLinksFromPage(dubUrl, "TM", subtitleCallback, callback) }
            launch { extractLinksFromPage(subUrl, "VS", subtitleCallback, callback) }
        }
        return true
    }
}
