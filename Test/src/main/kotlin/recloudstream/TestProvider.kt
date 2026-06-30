package recloudstream

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import com.lagradost.cloudstream3.CommonActivity.showToast
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import android.util.Base64
import okhttp3.Interceptor
import okhttp3.Response
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.ResponseBody.Companion.asResponseBody

class Yanhh3dProvider : MainAPI() {
    override var mainUrl = "https://yanhh3d.me" 
    override var name = "YanHH3D"
    override val hasMainPage = true
    override var lang = "vi"
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(TvType.Cartoon)

    private val mutex = Mutex()
    private var cachedBaseUrl: String? = null

    private suspend fun getBaseUrl(): String {
        return mutex.withLock {
            if (cachedBaseUrl != null) return@withLock cachedBaseUrl!!

            try {
                val response = app.get(mainUrl)
                val document = response.document
                val html = document.html()

                var activeDomain = document.selectFirst("span#linkText span")?.text()?.trim()

                if (activeDomain.isNullOrBlank() || !activeDomain.startsWith("http")) {
                    val domainNow = Regex("""domainNow:\s*"([^"]+)"""").find(html)?.groupValues?.get(1)
                    val scheme = Regex("""scheme:\s*"([^"]+)"""").find(html)?.groupValues?.get(1) ?: "https://"
                    if (!domainNow.isNullOrBlank()) {
                        activeDomain = "$scheme$domainNow"
                    }
                }

                if (!activeDomain.isNullOrBlank()) {
                    cachedBaseUrl = activeDomain.trimEnd('/')
                } else {
                    cachedBaseUrl = mainUrl
                }
            } catch (e: Exception) {
                e.printStackTrace()
                cachedBaseUrl = mainUrl.trimEnd('/') 
            }

            cachedBaseUrl!!
        }
    }

    private fun toSearchQuality(qualityString: String?): SearchQuality? {
        return when {
            qualityString == null -> null
            qualityString.contains("4K", true) || qualityString.contains("UHD", true) -> SearchQuality.FourK
            qualityString.contains("1080") || qualityString.contains("FullHD", true) -> SearchQuality.HD
            qualityString.contains("720") -> SearchQuality.HD
            qualityString.contains("HD", true) -> SearchQuality.HD
            else -> null
        }
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val currentBaseUrl = getBaseUrl()

        if (page == 1) {
            withContext(Dispatchers.Main) {
                CommonActivity.activity?.let { activity ->
                    showToast(activity, "Free Repo From H4RS", 1)
                }
            }
        }

        val sections = listOf(
            Pair("Mới Cập Nhật", "$currentBaseUrl/moi-cap-nhat?page=$page"),
            Pair("Hoạt Hình 3D", "$currentBaseUrl/hoat-hinh-3d?page=$page"),
            Pair("Hoạt Hình 4K", "$currentBaseUrl/hoat-hinh-4k?page=$page"),
            Pair("Hoạt Hình 2D", "$currentBaseUrl/hoat-hinh-2d?page=$page"),
            Pair("Đã Hoàn Thành", "$currentBaseUrl/hoan-thanh?page=$page")
        )

        val items = coroutineScope {
            sections.map { (name, url) ->
                async {
                    runCatching {
                        val document = app.get(url).document
                        val movies = document.select("div.film_list-wrap div.flw-item").mapNotNull { it.toSearchResult(currentBaseUrl) }
                        if (movies.isNotEmpty()) HomePageList(name, movies) else null
                    }.getOrNull()
                }
            }.awaitAll().filterNotNull()
        }

        return newHomePageResponse(items, hasNext = items.isNotEmpty())
    }

    private fun Element.toSearchResult(currentBaseUrl: String): SearchResponse? {
        val titleElement = this.selectFirst("h3.film-name a") ?: return null
        val title = titleElement.text().trim()
        val href = fixUrl(titleElement.attr("href")).replace(mainUrl, currentBaseUrl)
        val posterUrl = fixUrlNull(this.selectFirst("img.film-poster-img")?.attr("data-src"))?.replace(mainUrl, currentBaseUrl)
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
        val currentBaseUrl = getBaseUrl()
        val searchUrl = "$currentBaseUrl/search?keysearch=$query"
        val document = app.get(searchUrl).document
        return document.select("div.film_list-wrap div.flw-item").mapNotNull { it.toSearchResult(currentBaseUrl) }
    }
    
    // Tối ưu hóa: Đổi sang vòng lặp for thay vì mapNotNull để Compiler Jsoup 100% không bị miss Type
    private fun getEpisodeData(doc: Document?, type: String, currentBaseUrl: String): List<Pair<String, String>> {
        if (doc == null) return emptyList()
        val selector = if (type == "TM") "div#top-comment div.ss-list a.ssl-item" else "div#new-comment div.ss-list a.ssl-item"
        val elements = doc.select(selector) ?: return emptyList()
        
        val results = mutableListOf<Pair<String, String>>()
        for (element in elements) {
            val epUrl = element.attr("href")
            val orderText = element.selectFirst(".ssli-order")?.text()?.trim()
            if (!epUrl.isNullOrBlank() && !orderText.isNullOrBlank()) {
                results.add(Pair(orderText, fixUrl(epUrl).replace(mainUrl, currentBaseUrl)))
            }
        }
        return results
    }

    private data class MergedEpisodeInfo(var dubUrl: String? = null, var subUrl: String? = null)

    override suspend fun load(url: String): LoadResponse? {
        val currentBaseUrl = getBaseUrl()
        val document = app.get(url).document
        
        val title = document.selectFirst("h1.film-name")?.text()?.trim() 
            ?: document.selectFirst("h2.film-name")?.text()?.trim() ?: return null
            
        val poster = fixUrlNull(document.selectFirst("div.anisc-poster img")?.attr("data-src") 
            ?: document.selectFirst("div.anisc-poster img")?.attr("src") 
            ?: document.selectFirst("meta[property=og:image]")?.attr("content"))?.replace(mainUrl, currentBaseUrl)
            
        val plot = document.selectFirst("div.film-description div.text")?.text()?.trim()
        val tags = document.select("div.anisc-info a.genre").map { it.text() }
        val year = document.select("div.item-title:contains(Năm:) span.name, div.item:contains(Năm:) span.name").text().trim().toIntOrNull()

        val dubWatchUrl = fixUrlNull(document.selectFirst("a.btn-play")?.attr("href"))?.replace(mainUrl, currentBaseUrl)
        val subWatchUrl = fixUrlNull(document.selectFirst("a.custom-button-sub")?.attr("href"))?.replace(mainUrl, currentBaseUrl)
        
        val seasonLinks = document.select("div.os-list a.os-item").mapNotNull {
            fixUrlNull(it.attr("href"))?.replace(mainUrl, currentBaseUrl)
        }.filter { it != url }

        val seasons = coroutineScope {
            seasonLinks.map { seasonUrl ->
                async {
                    runCatching {
                        val seasonDoc = app.get(seasonUrl).document
                        val sTitle = seasonDoc.selectFirst("h1.film-name")?.text()?.trim() ?: seasonDoc.selectFirst("h2.film-name")?.text()?.trim() ?: return@runCatching null
                        val sPoster = fixUrlNull(seasonDoc.selectFirst("div.anisc-poster img")?.attr("data-src") ?: seasonDoc.selectFirst("meta[property=og:image]")?.attr("content"))?.replace(mainUrl, currentBaseUrl)
                        
                        newAnimeSearchResponse(sTitle, seasonUrl, TvType.Cartoon) {
                            this.posterUrl = sPoster
                        }
                    }.getOrNull()
                }
            }.awaitAll().filterNotNull()
        }

        val defaultRecommendations = document.select("section.block_area_category div.flw-item, div.film_list-wrap div.flw-item").mapNotNull { it.toSearchResult(currentBaseUrl) }
        val recommendations = seasons + defaultRecommendations
        
        val targetWatchUrl = dubWatchUrl ?: subWatchUrl ?: url

        val episodes = coroutineScope {
            val watchDoc = runCatching { app.get(targetWatchUrl).document }.getOrNull()
            
            val dubData = getEpisodeData(watchDoc, "TM", currentBaseUrl)
            val subData = getEpisodeData(watchDoc, "VS", currentBaseUrl)

            val mergedMap = mutableMapOf<String, MergedEpisodeInfo>()
            // Sửa vòng lặp gán giá trị rõ ràng, tránh component1/component2 ambiguous
            for ((name, epUrl) in dubData) {
                mergedMap.getOrPut(name) { MergedEpisodeInfo() }.dubUrl = epUrl
            }
            for ((name, epUrl) in subData) {
                mergedMap.getOrPut(name) { MergedEpisodeInfo() }.subUrl = epUrl
            }

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
            return newMovieLoadResponse(title, url, TvType.Cartoon, targetWatchUrl) {
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
        val currentBaseUrl = getBaseUrl()

        val isSubServer = data.contains("/sever2/")
        val currentBase = if (data.contains(currentBaseUrl)) currentBaseUrl else mainUrl 

        val (dubUrl, subUrl) = if (isSubServer) {
            data.replace("/sever2/", "/") to data
        } else {
            data to data.replace(currentBase, "$currentBase/sever2")
        }

        coroutineScope {
            launch { processPageLinks(dubUrl, "TM", currentBaseUrl, subtitleCallback, callback) }
            launch { processPageLinks(subUrl, "VS", currentBaseUrl, subtitleCallback, callback) }
        }
        return true
    }

    private suspend fun processPageLinks(
        pageUrl: String,
        prefix: String,
        currentBaseUrl: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        runCatching {
            val document = app.get(pageUrl).document
            
            // Đổi qua vòng for thay vì forEach để an toàn tránh rò rỉ context scope
            val elements = document.select("a.btn3dsv")
            for (element in elements) {
                val link: String = element.attr("data-src")
                if (link.isNotEmpty()) {
                    val serverDisplayName = element.text()
                    val finalName = "$prefix - $serverDisplayName"
                    routeLinkBasedOnUrl(link, finalName, currentBaseUrl, subtitleCallback, callback)
                }
            }
        }.onFailure { it.printStackTrace() }
    }

    private suspend fun routeLinkBasedOnUrl(
        url: String,
        name: String,
        currentBaseUrl: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val blockedDomains = listOf("short.icu", "streamc.xyz", "freeplayervideo.com", "abysscdn.com")
        if (blockedDomains.any { it in url }) {
            return
        }

        when {
            "play-fb-v" in url -> handleIframePlayer(url, name, currentBaseUrl, callback)
            "fbcdn.cloud/" in url -> handleFbCdnLink(url, name, currentBaseUrl, callback)
            "short-cdn.ink/video/" in url -> handleShortCdnLink(url, name, currentBaseUrl, callback)
            "cloudbeta.win/" in url -> handleCloudBetaLink(url, name, callback)
            "helvid.net/" in url -> handleHelvidLink(url, name, callback)
            "dailymotion.com/" in url -> loadExtractor(url, name, subtitleCallback, callback)
            else -> handleDirectLink(url, name, currentBaseUrl, callback)
        }
    }

    private fun getLinkType(url: String): ExtractorLinkType {
        return if (url.contains(".mp4")) ExtractorLinkType.VIDEO else ExtractorLinkType.M3U8
    }

    private suspend fun handleDirectLink(url: String, name: String, currentBaseUrl: String, callback: (ExtractorLink) -> Unit) {
        if (url.isBlank()) return
        callback(
            newExtractorLink(this.name, name, url, type = getLinkType(url)) {
                this.referer = currentBaseUrl
            }
        )
    }

    private suspend fun handleIframePlayer(url: String, name: String, currentBaseUrl: String, callback: (ExtractorLink) -> Unit) {
        runCatching {
            val playerDocument = app.get(url, referer = currentBaseUrl).document
            var videoUrl = playerDocument.selectFirst("#player")?.attr("data-stream-url")
            
            if (videoUrl.isNullOrBlank()) {
                videoUrl = Regex("""var cccc = "([^"]+)""").find(playerDocument.html())?.groupValues?.get(1)
            }

            if (!videoUrl.isNullOrBlank()) {
                callback(
                    newExtractorLink(this.name, name, videoUrl, type = getLinkType(videoUrl)) {
                        this.referer = url
                    }
                )
            }
        }.onFailure { it.printStackTrace() }
    }

    private suspend fun handleFbCdnLink(url: String, name: String, currentBaseUrl: String, callback: (ExtractorLink) -> Unit) {
        runCatching {
            val doc = app.get(url, referer = currentBaseUrl).document
            val dataObf = doc.selectFirst("#player")?.attr("data-obf")
            
            if (!dataObf.isNullOrBlank()) {
                val decodedJson = String(Base64.decode(dataObf, Base64.DEFAULT))
                val puUrl = Regex(""""pU":"([^"]+)"""").find(decodedJson)?.groupValues?.get(1)?.replace("\\/", "/")
                
                if (!puUrl.isNullOrBlank()) {
                    callback(
                        newExtractorLink(this.name, name, puUrl, type = getLinkType(puUrl)) {
                            this.referer = url 
                        }
                    )
                    return
                }
            }

            val streamUrl = when {
                url.contains("fbcdn.cloud/video/") -> "$url/master.m3u8"
                url.contains("/o1/v/t2/f2/m366/") -> url.replace("/o1/v/t2/f2/m366/", "/stream/m3u8/")
                else -> url
            }
            if (streamUrl.isNotBlank()) {
                callback(
                    newExtractorLink(this.name, name, streamUrl, type = getLinkType(streamUrl)) {
                        this.referer = currentBaseUrl
                    }
                )
            }
        }.onFailure { it.printStackTrace() }
    }

    private suspend fun handleShortCdnLink(url: String, name: String, currentBaseUrl: String, callback: (ExtractorLink) -> Unit) {
        val streamUrl = "$url/master.m3u8"
        callback(
            newExtractorLink(this.name, name, streamUrl, type = getLinkType(streamUrl)) {
                this.referer = currentBaseUrl
            }
        )
    }

    private suspend fun handleCloudBetaLink(url: String, name: String, callback: (ExtractorLink) -> Unit) {
        runCatching {
            val streamUrl = url
                .replace("player.cloudbeta.win/", "play.cloudbeta.win/file/play/")
                .plus(".m3u8")

            callback(
                newExtractorLink(this.name, name, streamUrl, type = getLinkType(streamUrl)) {
                    this.referer = url
                }
            )
        }
    }
    
    private suspend fun handleHelvidLink(url: String, name: String, callback: (ExtractorLink) -> Unit) {
        runCatching {
            val helvidDoc = app.get(url).document
            val script = helvidDoc.selectFirst("script:containsData(const sources)")?.data() ?: return@runCatching
            val obfuscatedUrl = Regex("""'file':\s*'([^']*)'""").find(script)?.groupValues?.get(1)
            if (!obfuscatedUrl.isNullOrBlank()) {
                val realUrl = obfuscatedUrl.replace("https_//", "https://")
                callback(
                    newExtractorLink(this.name, name, realUrl, type = getLinkType(realUrl)) {
                        this.referer = url
                    }
                )
            }
        }.onFailure { it.printStackTrace() }
    }

    override fun getVideoInterceptor(extractorLink: ExtractorLink): Interceptor? {
        return Interceptor { chain ->
            val request = chain.request()
            val response = chain.proceed(request)
            val url = request.url.toString()

            if (url.contains(".m3u8") || url.contains(".key") || url.contains(".mp4")) {
                return@Interceptor response
            }

            val isFakeCdn = listOf("ibyteimg", "tiktok", "ticktok", "byteimg", "fbcdn").any { url.contains(it) }

            if (isFakeCdn) {
                val body = response.body ?: return@Interceptor response
                val mediaType = "video/MP2T".toMediaTypeOrNull()

                try {
                    val source = body.source()
                    
                    source.request(1)
                    if (source.buffer.size > 0 && source.buffer[0] == 0x47.toByte()) {
                        return@Interceptor response.newBuilder()
                            .removeHeader("Content-Type")
                            .addHeader("Content-Type", "video/MP2T")
                            .body(source.asResponseBody(mediaType, body.contentLength()))
                            .build()
                    }

                    source.request(2097152)
                    val buffer = source.buffer
                    val searchLimit = minOf(buffer.size, 2097152L)
                    val syncByte = 0x47.toByte()
                    var startOffset = 0L

                    if (searchLimit > 384L) {
                        val loopLimit = (searchLimit - 384L).toInt()
                        for (i in 0 until loopLimit) {
                            val index = i.toLong()
                            if (buffer[index] == syncByte) {
                                if ((buffer[index + 188L] == syncByte && buffer[index + 376L] == syncByte) ||
                                    (buffer[index + 192L] == syncByte && buffer[index + 384L] == syncByte)) {
                                    startOffset = index
                                    break
                                }
                            }
                        }
                    }

                    if (startOffset > 0) {
                        source.skip(startOffset)
                        
                        return@Interceptor response.newBuilder()
                            .removeHeader("Content-Type")
                            .removeHeader("Content-Length")
                            .addHeader("Content-Type", "video/MP2T")
                            .body(source.asResponseBody(mediaType, -1L))
                            .build()
                    } else {
                        return@Interceptor response.newBuilder()
                            .removeHeader("Content-Type")
                            .addHeader("Content-Type", "video/MP2T")
                            .body(source.asResponseBody(mediaType, body.contentLength()))
                            .build()
                    }

                } catch (e: Exception) {
                    return@Interceptor response
                }
            }
            response
        }
    }
}
