package recloudstream // Giữ nguyên package gốc của file AnimeHay

// === Imports ===
import android.util.Log
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addDuration
import com.lagradost.cloudstream3.mvvm.normalSafeApiCall
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.newExtractorLink
import okhttp3.Interceptor // Import cần thiết
import okhttp3.ResponseBody.Companion.toResponseBody // Import cần thiết
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.net.MalformedURLException
import java.net.URL
import java.net.URLEncoder
import kotlin.math.roundToInt

// *** THÊM KITSU DATA CLASSES ***
data class KitsuMain(val data: List<KitsuData>?)
data class KitsuData(val attributes: KitsuAttributes?)
data class KitsuAttributes(
    val canonicalTitle: String?,
    val posterImage: KitsuPoster?
)
data class KitsuPoster(
    val original: String?,
    val large: String?,
    val medium: String?,
    val small: String?,
    val tiny: String?
)

// === Provider Class ===
class AnimeHayProvider : MainAPI() {

    // === Thuộc tính Provider ===
    override var mainUrl = "https://ahay.in"
    override var name = "AnimeHay"
    override val supportedTypes = setOf(TvType.Anime, TvType.AnimeMovie, TvType.OVA, TvType.Cartoon)
    override var lang = "vi"
    override val hasMainPage = true

    // --- Phần xử lý domain động ---
    private var currentActiveUrl = "https://animehay.bid"
    private var domainCheckPerformed = false
    private val domainCheckUrl = mainUrl

    private suspend fun getBaseUrl(): String {
        if (domainCheckPerformed) {
            return currentActiveUrl
        }

        var finalNewDomain: String? = null
        Log.d("AnimeHayProvider", "Starting domain check. Current active: $currentActiveUrl, Initial check URL: $domainCheckUrl")

        try {
            val response = app.get(domainCheckUrl, allowRedirects = true)
            val landedUrl = response.url
            val document = response.document
            Log.d("AnimeHayProvider", "Fetched from $domainCheckUrl, landed on: $landedUrl. HTML length: ${document.html().length}")

            var hrefFromContent: String? = null
            val linkSelectors = listOf("a.bt-link", "a.bt-link-1")
            for (selector in linkSelectors) {
                hrefFromContent = document.selectFirst(selector)?.attr("href")
                if (!hrefFromContent.isNullOrBlank()) {
                    Log.d("AnimeHayProvider", "Found href '$hrefFromContent' using selector '$selector'")
                    break
                }
            }

            if (hrefFromContent.isNullOrBlank()) {
                val scriptElements = document.select("script")
                val newDomainRegex = Regex("""var\s+new_domain\s*=\s*["'](https?://[^"']+)["']""")
                for (scriptElement in scriptElements) {
                    if (!scriptElement.hasAttr("src")) {
                        val scriptContent = scriptElement.html()
                        val match = newDomainRegex.find(scriptContent)
                        if (match != null && match.groups.size > 1) {
                            hrefFromContent = match.groups[1]?.value
                            Log.d("AnimeHayProvider", "Found new_domain '$hrefFromContent' in script.")
                            break
                        }
                    }
                }
            }

            if (!hrefFromContent.isNullOrBlank()) {
                try {
                    val urlObject = URL(hrefFromContent)
                    val extractedBase = "${urlObject.protocol}://${urlObject.host}"
                    if (extractedBase.startsWith("http")) {
                        finalNewDomain = extractedBase
                        Log.i("AnimeHayProvider", "Method 1: New domain from parsed content link: $finalNewDomain")
                    } else {
                        Log.w("AnimeHayProvider", "Parsed href '$hrefFromContent' did not result in a valid http base URL.")
                    }
                } catch (e: MalformedURLException) {
                    Log.e("AnimeHayProvider", "Malformed URL from parsed content link: '$hrefFromContent'", e)
                }
            } else {
                Log.d("AnimeHayProvider", "No explicit link found in <a> tags or script variable.")
            }

            if (finalNewDomain.isNullOrBlank()) {
                val landedUrlBase = try {
                    val landedObj = URL(landedUrl)
                    "${landedObj.protocol}://${landedObj.host}"
                } catch (e: Exception) { null }

                val initialCheckUrlHost = try { URL(domainCheckUrl).host } catch (e: Exception) { null }
                val landedUrlHost = try { URL(landedUrl).host } catch (e: Exception) { null }


                if (landedUrlBase != null && landedUrlBase.startsWith("http") && landedUrlHost != initialCheckUrlHost) {
                    finalNewDomain = landedUrlBase
                    Log.i("AnimeHayProvider", "Method 2: Using domain from redirection: $finalNewDomain (landed on $landedUrl from $domainCheckUrl)")
                } else {
                    Log.d("AnimeHayProvider", "Method 2: Redirected URL's base ($landedUrlBase) not suitable or same host as initial check URL ($initialCheckUrlHost).")
                }
            }

            if (!finalNewDomain.isNullOrBlank() && finalNewDomain != currentActiveUrl) {
                if (finalNewDomain!!.contains("archive.org", ignoreCase = true) ||
                    finalNewDomain!!.contains("web.archive.org", ignoreCase = true)) {
                    Log.w("AnimeHayProvider", "Potential new domain '$finalNewDomain' is an archive link. Not updating.")
                } else {
                    Log.i("AnimeHayProvider", "Domain will be updated: $currentActiveUrl -> $finalNewDomain")
                    currentActiveUrl = finalNewDomain!!
                }
            } else if (finalNewDomain.isNullOrBlank()) {
                Log.w("AnimeHayProvider", "No new valid domain found by any method. Using previous active URL: $currentActiveUrl")
            } else {
                Log.i("AnimeHayProvider", "Found domain '$finalNewDomain' is same as current active URL. No change needed.")
            }

        } catch (e: Exception) {
            Log.e("AnimeHayProvider", "Critical error during domain check. Using previous active URL: $currentActiveUrl", e)
        } finally {
            domainCheckPerformed = true
        }
        Log.i("AnimeHayProvider", "getBaseUrl ultimately returning: $currentActiveUrl")
        return currentActiveUrl
    }
    
    // =================================================================================================
    // *** BẮT ĐẦU PHẦN SỬA LỖI QUAN TRỌNG: INTERCEPTOR VỚI LOGIC CHÍNH XÁC ***
    // =================================================================================================
    override fun getVideoInterceptor(extractorLink: ExtractorLink): Interceptor? {
        return Interceptor { chain ->
            val request = chain.request()
            val response = chain.proceed(request)
            val url = request.url.toString()

            // Phân tích logic từ file java, điều kiện để sửa byte video là:
            // (URL chứa "ibyteimg.com") HOẶC
            // (URL chứa ".tiktokcdn.") HOẶC
            // (URL chứa "segment.cloudbeta.win/file/segment/" VÀ KHÔNG chứa ".html?token=")
            val isIbyte = url.contains("ibyteimg.com", ignoreCase = true)
            val isTiktok = url.contains(".tiktokcdn.", ignoreCase = true)
            val isSegment = url.contains("segment.cloudbeta.win/file/segment/", ignoreCase = true)
            val isHtmlToken = url.contains(".html?token=", ignoreCase = true)

            // Áp dụng logic đã phân tích chính xác
            val needsFix = isIbyte || isTiktok || (isSegment && !isHtmlToken)

            if (needsFix) {
                Log.d("AnimeHayProvider", "Interceptor chính xác đang xử lý URL: $url")
                val originalBody = response.body
                if (originalBody != null) {
                    try {
                        // Các CDN này thêm 2 byte rác vào đầu mỗi segment video.
                        // Ta cần đọc toàn bộ bytes và bỏ đi 2 byte đầu tiên.
                        val bytes = originalBody.bytes()
                        if (bytes.size > 2) {
                            val fixedBytes = bytes.drop(2).toByteArray()
                            val newBody = fixedBytes.toResponseBody(originalBody.contentType())
                            // Trả về response mới với nội dung đã được sửa
                            return@Interceptor response.newBuilder().body(newBody).build()
                        }
                    } catch (e: Exception) {
                        Log.e("AnimeHayProvider", "Interceptor thất bại khi sửa bytes", e)
                    }
                }
            }
            // Trả về response gốc nếu không cần sửa
            response
        }
    }
    // =================================================================================================
    // *** KẾT THÚC PHẦN SỬA LỖI ***
    // =================================================================================================

    private suspend fun getKitsuPoster(title: String): String? {
        Log.d("AnimeHayProvider", "Searching Kitsu for: \"$title\"")
        return try {
            val encodedTitle = URLEncoder.encode(title, "UTF-8")
            val searchUrl = "https://kitsu.io/api/edge/anime?filter[text]=$encodedTitle&page[limit]=1"
            Log.d("AnimeHayProvider", "Kitsu search URL: $searchUrl")

            val response = app.get(searchUrl).parsedSafe<KitsuMain>()
            val poster = response?.data?.firstOrNull()?.attributes?.posterImage
            val kitsuPosterUrl = poster?.original ?: poster?.large ?: poster?.medium ?: poster?.small ?: poster?.tiny

            if (!kitsuPosterUrl.isNullOrBlank()) {
                Log.d("AnimeHayProvider", "Found Kitsu poster: $kitsuPosterUrl")
            } else {
                Log.w("AnimeHayProvider", "Kitsu poster not found for title '$title'")
            }
            kitsuPosterUrl
        } catch (e: Exception) {
            Log.e("AnimeHayProvider", "Kitsu API Error for title '$title': ${e.message}", e)
            null
        }
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        try {
            val siteBaseUrl = getBaseUrl() 
            val urlToFetch = if (page <= 1) {
                siteBaseUrl 
            } else {
                "$siteBaseUrl/phim-moi-cap-nhap/trang-$page.html"
            }

            val document = app.get(urlToFetch).document
            val homePageItems = document.select("div.movies-list div.movie-item")
                .mapNotNull { element ->
                    element.toSearchResponse(this, siteBaseUrl) 
                }

            if (page > 1 && homePageItems.isEmpty()) {
                return newHomePageResponse(emptyList(), false)
            }

            var calculatedHasNext = false
            val pagination = document.selectFirst("div.pagination") 

            if (pagination != null) {
                val activePageElement = pagination.selectFirst("a.active_page")
                val currentPageFromHtml = activePageElement?.text()?.toIntOrNull() ?: page
                val nextPageSelector = "a[href*=/trang-${currentPageFromHtml + 1}.html]"
                val nextPageLink = pagination.selectFirst(nextPageSelector)
                if (nextPageLink != null) {
                    calculatedHasNext = true
                }
            }
            
            val listTitle = request.name.ifBlank { "Mới cập nhật" } 
            val homeList = HomePageList(listTitle, homePageItems)
            return newHomePageResponse(listOf(homeList), hasNext = calculatedHasNext)

        } catch (e: Exception) {
            Log.e("AnimeHayProvider", "Error in getMainPage for page $page: ${e.message}", e)
            return newHomePageResponse(emptyList(), false)
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        try {
            val baseUrl = getBaseUrl()
            val encodedQuery = query.encodeUri()
            val searchUrl = "$baseUrl/tim-kiem/$encodedQuery.html"
            val document = app.get(searchUrl).document
            val moviesListContainer = document.selectFirst("div.movies-list")
            if (moviesListContainer == null) {
                return emptyList()
            }
            val movieItems = moviesListContainer.select("div.movie-item")
            return movieItems.mapNotNull { element ->
                element.toSearchResponse(this, baseUrl)
            }
        } catch (e: Exception) {
            Log.e("AnimeHayProvider", "Error in search for query '$query'", e)
            return emptyList()
        }
    }

    override suspend fun load(url: String): LoadResponse? {
        try {
            val document = app.get(url).document
            return document.toLoadResponse(this, url, getBaseUrl())
        } catch (e: Exception) {
            Log.e("AnimeHayProvider", "Error in load for $url", e)
            return null
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        var foundLinks = false
        val document: Document? = try {
            app.get(data, headers = mapOf(
                "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,image/apng,*/*;q=0.8,application/signed-exchange;v=b3;q=0.7",
                "Referer" to getBaseUrl()
            )).document
        } catch (e: Exception) {
            Log.e("AnimeHayProvider", "Failed to GET document in loadLinks for: $data", e)
            null
        }

        if (document == null) {
            return false
        }

        try {
            var combinedScript = ""
            document.select("script").forEach { script ->
                if (!script.hasAttr("src")) {
                    combinedScript += script.html() + "\n"
                }
            }
            val pageHtml = document.html()
            val baseUrl = getBaseUrl()

            // 1. Server TOK
            val m3u8Regex = Regex("""tik:\s*['"]([^'"]+)['"]""")
            val m3u8Match = m3u8Regex.find(combinedScript)
            val m3u8Link = m3u8Match?.groups?.get(1)?.value?.trim()

            if (!m3u8Link.isNullOrEmpty()) {
                Log.i("AnimeHayProvider", "Found TOK M3U8 link: $m3u8Link")
                callback(
                    newExtractorLink(source = m3u8Link, name = "Server TOK", url = m3u8Link, type = ExtractorLinkType.M3U8) {
                        this.quality = Qualities.Unknown.value
                        // Sửa referer để giống phiên bản hoạt động
                        this.referer = baseUrl 
                    }
                )
                foundLinks = true
            }

            // 2. Server GUN
            val gunRegexScript = Regex("""src=["'](https?://[^"']*gun\.php\?id=([^&"']+)&[^"']*)["']""")
            val gunMatchScript = gunRegexScript.find(combinedScript) ?: gunRegexScript.find(pageHtml)
            var gunLink = gunMatchScript?.groups?.get(1)?.value?.let{ fixUrl(it, baseUrl) }
            var gunId = gunMatchScript?.groups?.get(2)?.value

            if (gunLink.isNullOrEmpty() || gunId.isNullOrEmpty()) {
                val gunIframe = document.selectFirst("iframe#gun_if[src*=gun.php]")
                gunLink = gunIframe?.attr("src")?.let{ fixUrl(it, baseUrl) }
                if (!gunLink.isNullOrEmpty()) {
                    val gunIdRegexIframe = Regex(""".*gun\.php\?id=([^&"']+)""")
                    gunId = gunIdRegexIframe.find(gunLink)?.groups?.get(1)?.value
                    if (gunId.isNullOrEmpty()) gunLink = null
                }
            }

            if (!gunLink.isNullOrEmpty() && !gunId.isNullOrEmpty()) {
                val finalM3u8Link = "https://pt.rapovideo.xyz/playlist/v2/$gunId/master.m3u8"
                callback(
                    newExtractorLink(source = finalM3u8Link, name = "Server GUN", url = finalM3u8Link, type = ExtractorLinkType.M3U8) {
                        this.quality = Qualities.Unknown.value
                        this.referer = gunLink
                    }
                )
                foundLinks = true
            }

            // 3. Server PHO
            val phoRegexScript = Regex("""src=["'](https?://[^"']*pho\.php\?id=([^&"']+)&[^"']*)["']""")
            val phoMatchScript = phoRegexScript.find(combinedScript) ?: phoRegexScript.find(pageHtml)
            var phoLink = phoMatchScript?.groups?.get(1)?.value?.let{ fixUrl(it, baseUrl) }
            var phoId = phoMatchScript?.groups?.get(2)?.value

            if (phoLink.isNullOrEmpty() || phoId.isNullOrEmpty()) {
                val phoIframe = document.selectFirst("iframe#pho_if[src*=pho.php]")
                phoLink = phoIframe?.attr("src")?.let{ fixUrl(it, baseUrl) }
                if (!phoLink.isNullOrEmpty()) {
                    val phoIdRegexIframe = Regex(""".*pho\.php\?id=([^&"']+)""")
                    phoId = phoIdRegexIframe.find(phoLink)?.groups?.get(1)?.value
                    if (phoId.isNullOrEmpty()) phoLink = null
                }
            }

            if (!phoLink.isNullOrEmpty() && !phoId.isNullOrEmpty()) {
                val finalPhoM3u8Link = "https://pt.rapovideo.xyz/playlist/$phoId/master.m3u8"
                callback(
                    newExtractorLink(source = finalPhoM3u8Link, name = "Server PHO", url = finalPhoM3u8Link, type = ExtractorLinkType.M3U8) {
                        this.quality = Qualities.Unknown.value
                        this.referer = phoLink
                    }
                )
                foundLinks = true
            }

            // 4. Server HY (Hydrax)
            val hyRegexScript = Regex("""src=["']([^"']*playhydrax\.com[^"']*)["']""")
            val hyMatchScript = hyRegexScript.find(combinedScript) ?: hyRegexScript.find(pageHtml)
            var hyLink = hyMatchScript?.groups?.get(1)?.value?.let{ fixUrl(it, baseUrl) }

            if (hyLink.isNullOrEmpty()) {
                hyLink = document.selectFirst("iframe[src*=playhydrax.com]")?.attr("src")?.let{ fixUrl(it, baseUrl) }
            }

            if (!hyLink.isNullOrEmpty()) {
                loadExtractor(hyLink, data, subtitleCallback, callback)
                foundLinks = true
            }

        } catch (e: Exception) {
            Log.e("AnimeHayProvider", "General error during link extraction in loadLinks for $data", e)
        }

        return foundLinks
    }

    private fun Element.toSearchResponse(provider: MainAPI, baseUrl: String): SearchResponse? {
        try {
            var linkElement = this.selectFirst("> a[href]")
            if (linkElement == null) {
                linkElement = this.selectFirst("a[href*=thong-tin-phim]")
            }
            if (linkElement == null) return null

            val hrefRaw = linkElement.attr("href")
            val href = fixUrl(hrefRaw, baseUrl) ?: return null

            val titleFromName = this.selectFirst("div.name-movie")?.text()?.trim()
            val titleFromAttr = linkElement.attr("title")?.trim()
            val title = titleFromName?.takeIf { it.isNotBlank() } ?: titleFromAttr
            if (title.isNullOrBlank()) return null

            val posterUrl = this.selectFirst("img")?.let { it.attr("src").ifBlank { it.attr("data-src") } }
            val fixedPosterUrl = fixUrl(posterUrl, baseUrl)

            val tvType = if (href.contains("/phim/", ignoreCase = true)) TvType.AnimeMovie else TvType.Anime

            return provider.newMovieSearchResponse(title, href, tvType) {
                this.posterUrl = fixedPosterUrl
            }
        } catch (e: Exception) {
            return null
        }
    }

    private suspend fun Document.toLoadResponse(provider: MainAPI, url: String, baseUrl: String): LoadResponse? {
        try {
            val title = this.selectFirst("h1.heading_movie")?.text()?.trim() ?: return null

            val genres = this.select("div.list_cate a")?.mapNotNull { it.text()?.trim() } ?: emptyList()
            val isChineseAnimation = genres.any { it.contains("CN Animation", ignoreCase = true) }
            val hasEpisodes = this.selectFirst("div.list-item-episode a") != null

            val mainTvType = when {
                hasEpisodes && isChineseAnimation -> TvType.Cartoon
                hasEpisodes && !isChineseAnimation -> TvType.Anime
                !hasEpisodes && isChineseAnimation -> TvType.Cartoon
                !hasEpisodes && !isChineseAnimation -> TvType.AnimeMovie
                else -> TvType.Anime
            }

            val animehayPoster = this.selectFirst("div.head div.first img")?.attr("src")
            val animehayFinalPosterUrl = fixUrl(animehayPoster, baseUrl)

            val finalPosterUrl = if (mainTvType == TvType.Anime || mainTvType == TvType.AnimeMovie || mainTvType == TvType.OVA) {
                getKitsuPoster(title) ?: animehayFinalPosterUrl
            } else {
                animehayFinalPosterUrl
            }

            val description = this.selectFirst("div.desc > div:last-child")?.text()?.trim()
            val year = this.selectFirst("div.update_time div:nth-child(2)")?.text()?.trim()?.filter { it.isDigit() }?.toIntOrNull()
            val rating = this.selectFirst("div.score div:nth-child(2)")?.text()?.trim()?.split("||")?.getOrNull(0)?.trim()?.toDoubleOrNull()?.takeIf { !it.isNaN() }?.toAnimeHayRatingInt()

            val statusText = this.selectFirst("div.status div:nth-child(2)")?.text()?.trim()
            val status = when {
                statusText?.contains("Hoàn thành", ignoreCase = true) == true -> ShowStatus.Completed
                statusText?.contains("Đang tiến hành", ignoreCase = true) == true -> ShowStatus.Ongoing
                statusText?.contains("Đang cập nhật", ignoreCase = true) == true -> ShowStatus.Ongoing
                else -> null
            }

            val episodes = this.select("div.list-item-episode a")?.mapNotNull { episodeLink ->
                val epUrl = fixUrl(episodeLink.attr("href"), baseUrl)
                val epNameSpan = episodeLink.selectFirst("span")?.text()?.trim()
                val epTitleAttr = episodeLink.attr("title")?.trim()
                val finalEpName = epTitleAttr?.takeIf { it.isNotBlank() } ?: epNameSpan

                if (!finalEpName.isNullOrEmpty() && !epUrl.isNullOrEmpty()) {
                    newEpisode(data = epUrl) {
                        this.name = finalEpName
                    }
                } else { null }
            }?.reversed() ?: emptyList()

            val recommendations = this.select("div.movie-recommend div.movie-item")?.mapNotNull {
                it.toSearchResponse(provider, baseUrl)
            } ?: emptyList()

            return if (hasEpisodes) {
                provider.newTvSeriesLoadResponse(title, url, mainTvType, episodes = episodes) {
                    this.posterUrl = finalPosterUrl
                    this.plot = description
                    this.tags = genres
                    this.year = year
                    this.rating = rating
                    this.showStatus = status
                    this.recommendations = recommendations
                }
            } else {
                val durationMinutes = this.selectFirst("div.duration div:nth-child(2)")?.text()?.trim()?.filter { it.isDigit() }?.toIntOrNull()
                provider.newMovieLoadResponse(title, url, mainTvType, url) {
                    this.posterUrl = finalPosterUrl
                    this.plot = description
                    this.tags = genres
                    this.year = year
                    this.rating = rating
                    durationMinutes?.let { addDuration(it.toString()) }
                    this.recommendations = recommendations
                }
            }
        } catch (e: Exception) {
            Log.e("AnimeHayProvider", "Error in toLoadResponse for url: $url", e)
            return null
        }
    }

    private fun String?.encodeUri(): String {
        return try {
            URLEncoder.encode(this ?: "", "UTF-8")
        } catch (e: Exception) {
            this ?: ""
        }
    }

    private fun Double?.toAnimeHayRatingInt(): Int? {
        return this?.takeIf { !it.isNaN() }?.let { (it * 1000).roundToInt().coerceIn(0, 10000) }
    }

    private fun fixUrl(url: String?, baseUrl: String): String? {
        if (url.isNullOrBlank()) return null
        return try {
            when {
                url.startsWith("http") -> url
                url.startsWith("//") -> "https:$url"
                url.startsWith("/") -> baseUrl.trimEnd('/') + url
                else -> URL(URL(baseUrl), url).toString()
            }
        } catch (e: Exception) {
            if(url.startsWith("http")) url else null
        }
    }
}
