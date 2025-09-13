package recloudstream

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
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

    // ... (Các hàm không thay đổi vẫn được giữ nguyên) ...
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
                val episodeData = info.dubUrl ?: info.subUrl!! 
                newEpisode(episodeData) { this.name = episodeName }
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


    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val isSubServer = data.contains("/sever2/")
        val (dubUrl, subUrl) = if (isSubServer) {
            data.replace("/sever2/", "/") to data
        } else {
            data to data.replace(mainUrl, "$mainUrl/sever2")
        }

        coroutineScope {
            launch { processPageLinks(dubUrl, "TM", subtitleCallback, callback) }
            launch { processPageLinks(subUrl, "VS", subtitleCallback, callback) }
        }
        return true
    }

    private suspend fun processPageLinks(
        pageUrl: String,
        prefix: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        runCatching {
            val document = app.get(pageUrl, timeout = 10L).document
            val scriptContent = document.select("script").firstOrNull {
                val d = it.data()
                d.contains("\$checkFbo") || d.contains("\$checkLink")
            }?.data() ?: return@runCatching

            val serverNames = document.select("a.btn3dsv").associate {
                it.attr("name").uppercase() to it.text()
            }
            
            val linkRegex = Regex("""var\s*\$(check(?:Fbo|Link\d{1,2}))\s*=\s*['"](.*?)['"];""")
            linkRegex.findAll(scriptContent).forEach { match ->
                val varName = match.groupValues[1]
                val link = match.groupValues[2]
                if (link.isBlank()) return@forEach

                val serverId = varName.removePrefix("check").uppercase()
                val serverDisplayName = serverNames[serverId] ?: serverId
                val finalName = "$prefix - $serverDisplayName"
                
                routeLinkBasedOnUrl(link, finalName, subtitleCallback, callback)
            }
        }.onFailure { it.printStackTrace() }
    }

    private suspend fun routeLinkBasedOnUrl(
        url: String,
        name: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val blockedDomains = listOf("short.icu", "streamc.xyz", "freeplayervideo.com", "abysscdn.com")
        if (blockedDomains.any { it in url }) {
            return
        }

        when {
            "yanhh3d.vip/play-fb-v" in url -> handleIframePlayer(url, name, callback)
            "fbcdn.cloud/" in url -> handleFbCdnLink(url, name, callback)
            "short-cdn.ink/video/" in url -> handleShortCdnLink(url, name, callback)
            "helvid.net/" in url -> handleHelvidLink(url, name, callback)
            "dailymotion.com/" in url -> handleDailymotion(url, name, subtitleCallback, callback)
            else -> handleDirectLink(url, name, callback)
        }
    }

    private fun getLinkType(url: String): ExtractorLinkType {
        return if (url.contains(".m3u8")) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
    }

    private suspend fun handleDirectLink(url: String, name: String, callback: (ExtractorLink) -> Unit) {
        if (url.isBlank()) return
        callback(
            newExtractorLink(this.name, name, url, type = getLinkType(url)) {
                this.referer = mainUrl
            }
        )
    }

    private suspend fun handleIframePlayer(url: String, name: String, callback: (ExtractorLink) -> Unit) {
        runCatching {
            val iframeDoc = app.get(url).document
            val script = iframeDoc.selectFirst("script:containsData(eval), script:containsData(var cccc)")?.data() ?: return@runCatching
            
            // Ưu tiên tìm 'var cccc', nếu không có mới tìm trong 'eval'
            val videoUrl = Regex("""var\s*cccc\s*=\s*'([^']+)';""").find(script)?.groupValues?.get(1)
                ?: Regex("""'file':"([^"]+)"""").find(script)?.groupValues?.get(1)?.replace("\\/", "/")

            if (!videoUrl.isNullOrBlank()) {
                callback(
                    newExtractorLink(this.name, name, videoUrl, type = getLinkType(videoUrl)) {
                        this.referer = url
                    }
                )
            }
        }.onFailure { it.printStackTrace() }
    }


    private suspend fun handleFbCdnLink(url: String, name: String, callback: (ExtractorLink) -> Unit) {
        val streamUrl = when {
            url.contains("fbcdn.cloud/video/") -> "$url/master.m3u8"
            url.contains("/o1/v/t2/f2/m366/") -> url.replace("/o1/v/t2/f2/m366/", "/stream/m3u8/")
            else -> url
        }
        if (!streamUrl.contains(".m3u8")) return
        callback(
            newExtractorLink(this.name, name, streamUrl, type = ExtractorLinkType.M3U8) {
                this.referer = mainUrl
            }
        )
    }

    private suspend fun handleShortCdnLink(url: String, name: String, callback: (ExtractorLink) -> Unit) {
        val streamUrl = "$url/master.m3u8"
        callback(
            newExtractorLink(this.name, name, streamUrl, type = ExtractorLinkType.M3U8) {
                this.referer = mainUrl
            }
        )
    }
    
    private suspend fun handleHelvidLink(url: String, name: String, callback: (ExtractorLink) -> Unit) {
        runCatching {
            val helvidDoc = app.get(url).document
            val script = helvidDoc.selectFirst("script:containsData(const sources)")?.data() ?: return@runCatching
            val obfuscatedUrl = Regex("""'file':\s*'([^']*)'""").find(script)?.groupValues?.get(1)
            if (!obfuscatedUrl.isNullOrBlank()) {
                val realUrl = obfuscatedUrl.replace("https_//", "https://")
                callback(
                    newExtractorLink(this.name, name, realUrl, type = ExtractorLinkType.M3U8) {
                        this.referer = url
                    }
                )
            }
        }.onFailure { it.printStackTrace() }
    }

    private suspend fun handleDailymotion(
        url: String,
        name: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val dailymotionLinks = mutableListOf<ExtractorLink>()
        loadExtractor(url, mainUrl, subtitleCallback) { link ->
            dailymotionLinks.add(link)
        }

        if (dailymotionLinks.isNotEmpty()) {
            // Ưu tiên 1: Tìm link có tên "Dailymotion" (thường là link tổng hợp/auto)
            val preferredLink = dailymotionLinks.find { it.name.equals("Dailymotion", ignoreCase = true) }
            
            // Ưu tiên 2: Nếu không có link tổng, tìm link có chất lượng cao nhất
            val bestQualityLink = dailymotionLinks.maxByOrNull { it.quality }

            val chosenLink = preferredLink ?: bestQualityLink ?: dailymotionLinks.first()
            
            // Ghi đè tên của link được chọn bằng tên từ website
            chosenLink.name = name
            callback(chosenLink)
        }
    }
}
