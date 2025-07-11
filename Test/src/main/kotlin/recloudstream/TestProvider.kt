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
import okhttp3.Interceptor
import okhttp3.ResponseBody.Companion.toResponseBody
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.net.MalformedURLException
import java.net.URL
import java.net.URLEncoder
import kotlin.math.roundToInt

// Data classes giữ nguyên
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

class AnimeHayProvider : MainAPI() {

    override var mainUrl = "https://ahay.in"
    override var name = "AnimeHay"
    override val supportedTypes = setOf(TvType.Anime, TvType.AnimeMovie, TvType.OVA, TvType.Cartoon)
    override var lang = "vi"
    override val hasMainPage = true
    private var currentActiveUrl = "https://animehay.bid"
    private var domainCheckPerformed = false
    private val domainCheckUrl = mainUrl

    // =================================================================================================
    // *** PHẦN KIỂM TRA DEBUG ***
    // =================================================================================================
    override fun getVideoInterceptor(extractorLink: ExtractorLink): Interceptor? {
        return Interceptor { chain ->
            val request = chain.request()
            val url = request.url.toString()

            val isIbyte = url.contains("ibyteimg.com", ignoreCase = true)
            val isTiktok = url.contains(".tiktokcdn.", ignoreCase = true)
            val isSegment = url.contains("segment.cloudbeta.win/file/segment/", ignoreCase = true)
            val isHtmlToken = url.contains(".html?token=", ignoreCase = true)
            
            val needsFix = isIbyte || isTiktok || (isSegment && !isHtmlToken)

            if (needsFix) {
                // === MÃ KIỂM TRA: CỐ TÌNH GÂY LỖI ĐỂ XEM INTERCEPTOR CÓ CHẠY KHÔNG ===
                throw Exception("ANIMEHAY_DEBUG_INTERCEPTOR_IS_WORKING")
            }
            
            chain.proceed(request)
        }
    }
    // =================================================================================================
    // *** KẾT THÚC PHẦN KIỂM TRA DEBUG ***
    // =================================================================================================

    private suspend fun getBaseUrl(): String {
        if (domainCheckPerformed) {
            return currentActiveUrl
        }
        var finalNewDomain: String? = null
        try {
            val response = app.get(domainCheckUrl, allowRedirects = true)
            val landedUrl = response.url
            val document = response.document
            var hrefFromContent: String? = null
            val linkSelectors = listOf("a.bt-link", "a.bt-link-1")
            for (selector in linkSelectors) {
                hrefFromContent = document.selectFirst(selector)?.attr("href")
                if (!hrefFromContent.isNullOrBlank()) break
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
                            break
                        }
                    }
                }
            }
            if (!hrefFromContent.isNullOrBlank()) {
                try {
                    val urlObject = URL(hrefFromContent)
                    val extractedBase = "${urlObject.protocol}://${urlObject.host}"
                    if (extractedBase.startsWith("http")) finalNewDomain = extractedBase
                } catch (_: MalformedURLException) {}
            }
            if (finalNewDomain.isNullOrBlank()) {
                val landedUrlBase = try {
                    val landedObj = URL(landedUrl)
                    "${landedObj.protocol}://${landedObj.host}"
                } catch (_: Exception) { null }
                val initialCheckUrlHost = try { URL(domainCheckUrl).host } catch (_: Exception) { null }
                val landedUrlHost = try { URL(landedUrl).host } catch (_: Exception) { null }
                if (landedUrlBase != null && landedUrlBase.startsWith("http") && landedUrlHost != initialCheckUrlHost) {
                    finalNewDomain = landedUrlBase
                }
            }
            if (!finalNewDomain.isNullOrBlank() && finalNewDomain != currentActiveUrl) {
                if (!finalNewDomain!!.contains("archive.org", ignoreCase = true)) {
                    currentActiveUrl = finalNewDomain!!
                }
            }
        } catch (_: Exception) {} 
        finally { domainCheckPerformed = true }
        return currentActiveUrl
    }
    
    private suspend fun getKitsuPoster(title: String): String? {
        return try {
            val encodedTitle = URLEncoder.encode(title, "UTF-8")
            val searchUrl = "https://kitsu.io/api/edge/anime?filter[text]=$encodedTitle&page[limit]=1"
            val response = app.get(searchUrl).parsedSafe<KitsuMain>()
            val poster = response?.data?.firstOrNull()?.attributes?.posterImage
            poster?.original ?: poster?.large ?: poster?.medium ?: poster?.small ?: poster?.tiny
        } catch (_: Exception) {
            null
        }
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        try {
            val siteBaseUrl = getBaseUrl() 
            val urlToFetch = if (page <= 1) siteBaseUrl else "$siteBaseUrl/phim-moi-cap-nhap/trang-$page.html"
            val document = app.get(urlToFetch).document
            val homePageItems = document.select("div.movies-list div.movie-item").mapNotNull { it.toSearchResponse(this, siteBaseUrl) }
            if (page > 1 && homePageItems.isEmpty()) return newHomePageResponse(emptyList(), false)
            val calculatedHasNext = document.selectFirst("a[href*=/trang-${page + 1}.html]") != null
            val listTitle = request.name.ifBlank { "Mới cập nhật" } 
            return newHomePageResponse(HomePageList(listTitle, homePageItems), hasNext = calculatedHasNext)
        } catch (e: Exception) {
            return newHomePageResponse(emptyList(), false)
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        try {
            val baseUrl = getBaseUrl()
            val encodedQuery = query.encodeUri()
            val searchUrl = "$baseUrl/tim-kiem/$encodedQuery.html"
            val document = app.get(searchUrl).document
            return document.select("div.movies-list div.movie-item").mapNotNull { it.toSearchResponse(this, baseUrl) }
        } catch (e: Exception) {
            return emptyList()
        }
    }

    override suspend fun load(url: String): LoadResponse? {
        try {
            val document = app.get(url).document
            return document.toLoadResponse(this, url, getBaseUrl())
        } catch (e: Exception) {
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
            app.get(data, headers = mapOf("Referer" to getBaseUrl())).document
        } catch (_: Exception) { null }

        if (document == null) return false

        try {
            val combinedScript = document.select("script:not([src])").joinToString("\n") { it.html() }
            val pageHtml = document.html()
            val baseUrl = getBaseUrl()

            // 1. Server TOK
            val m3u8Link = Regex("""tik:\s*['"]([^'"]+)['"]""").find(combinedScript)?.groupValues?.get(1)?.trim()
            if (!m3u8Link.isNullOrEmpty()) {
                callback(
                    newExtractorLink(source = m3u8Link, name = "Server TOK", url = m3u8Link, type = ExtractorLinkType.M3U8) {
                        this.quality = Qualities.Unknown.value
                        this.referer = baseUrl 
                    }
                )
                foundLinks = true
            }

            // Các server khác...
            listOf("gun", "pho").forEach { server ->
                val idRegex = Regex("""src=["'](https?://[^"']*$server\.php\?id=([^&"']+)&[^"']*)["']""")
                var match = idRegex.find(combinedScript) ?: idRegex.find(pageHtml)
                var link = match?.groupValues?.get(1)?.let{ fixUrl(it, baseUrl) }
                var id = match?.groupValues?.get(2)

                if (link.isNullOrEmpty() || id.isNullOrEmpty()) {
                    val iframe = document.selectFirst("iframe#${server}_if[src*=$server.php]")
                    link = iframe?.attr("src")?.let{ fixUrl(it, baseUrl) }
                    if (!link.isNullOrEmpty()) {
                        id = Regex(""".*$server\.php\?id=([^&"']+)""").find(link)?.groupValues?.get(1)
                        if (id.isNullOrEmpty()) link = null
                    }
                }
                if (!link.isNullOrEmpty() && !id.isNullOrEmpty()) {
                    val playlistVersion = if (server == "gun") "v2/" else ""
                    val finalM3u8Link = "https://pt.rapovideo.xyz/playlist/$playlistVersion$id/master.m3u8"
                    callback(
                        newExtractorLink(source = finalM3u8Link, name = "Server ${server.uppercase()}", url = finalM3u8Link, type = ExtractorLinkType.M3U8) {
                            this.quality = Qualities.Unknown.value
                            this.referer = link
                        }
                    )
                    foundLinks = true
                }
            }

            val hydraxLink = (Regex("""src=["']([^"']*playhydrax\.com[^"']*)["']""").find(combinedScript)?.groupValues?.get(1)
                ?: document.selectFirst("iframe[src*=playhydrax.com]")?.attr("src"))?.let { fixUrl(it, baseUrl) }
            
            if (!hydraxLink.isNullOrEmpty()) {
                loadExtractor(hydraxLink, data, subtitleCallback, callback)
                foundLinks = true
            }

        } catch (e: Exception) {
            Log.e("AnimeHayProvider", "loadLinks error", e)
        }
        return foundLinks
    }

    private fun Element.toSearchResponse(provider: MainAPI, baseUrl: String): SearchResponse? {
        return try {
            val linkElement = this.selectFirst("> a[href], a[href*=thong-tin-phim]") ?: return null
            val href = fixUrl(linkElement.attr("href"), baseUrl) ?: return null
            val title = (this.selectFirst("div.name-movie")?.text()?.trim()?.takeIf { it.isNotBlank() }
                ?: linkElement.attr("title")?.trim()) ?: return null
            val posterUrl = this.selectFirst("img")?.let { it.attr("src").ifBlank { it.attr("data-src") } }?.let { fixUrl(it, baseUrl) }
            val tvType = if (href.contains("/phim/", ignoreCase = true)) TvType.AnimeMovie else TvType.Anime
            provider.newMovieSearchResponse(title, href, tvType) { this.posterUrl = posterUrl }
        } catch (_: Exception) { null }
    }

    private suspend fun Document.toLoadResponse(provider: MainAPI, url: String, baseUrl: String): LoadResponse? {
        try {
            val title = this.selectFirst("h1.heading_movie")?.text()?.trim() ?: return null
            val genres = this.select("div.list_cate a").mapNotNull { it.text()?.trim() }
            val isChineseAnimation = genres.any { it.equals("CN Animation", ignoreCase = true) }
            val hasEpisodes = this.selectFirst("div.list-item-episode a") != null
            val mainTvType = when {
                hasEpisodes && isChineseAnimation -> TvType.Cartoon
                hasEpisodes && !isChineseAnimation -> TvType.Anime
                !hasEpisodes && isChineseAnimation -> TvType.Cartoon
                !hasEpisodes && !isChineseAnimation -> TvType.AnimeMovie
                else -> TvType.Anime
            }
            val animehayPoster = this.selectFirst("div.head div.first img")?.attr("src")?.let { fixUrl(it, baseUrl) }
            val finalPosterUrl = if (mainTvType == TvType.Anime || mainTvType == TvType.AnimeMovie || mainTvType == TvType.OVA) {
                getKitsuPoster(title) ?: animehayPoster
            } else { animehayPoster }
            val description = this.selectFirst("div.desc > div:last-child")?.text()?.trim()
            val year = this.selectFirst("div.update_time div:nth-child(2)")?.text()?.trim()?.filter { it.isDigit() }?.toIntOrNull()
            val rating = this.selectFirst("div.score div:nth-child(2)")?.text()?.trim()?.split("||")?.getOrNull(0)?.trim()?.toDoubleOrNull()?.toAnimeHayRatingInt()
            val status = when (this.selectFirst("div.status div:nth-child(2)")?.text()?.trim()) {
                "Hoàn thành" -> ShowStatus.Completed
                "Đang tiến hành", "Đang cập nhật" -> ShowStatus.Ongoing
                else -> null
            }
            val recommendations = this.select("div.movie-recommend div.movie-item").mapNotNull { it.toSearchResponse(provider, baseUrl) }
            return if (hasEpisodes) {
                val episodes = this.select("div.list-item-episode a").mapNotNull {
                    val epUrl = fixUrl(it.attr("href"), baseUrl) ?: return@mapNotNull null
                    val epName = it.attr("title").trim().ifBlank { it.selectFirst("span")?.text()?.trim() }
                    newEpisode(data = epUrl) { this.name = epName }
                }.reversed()
                provider.newTvSeriesLoadResponse(title, url, mainTvType, episodes) {
                    this.posterUrl = finalPosterUrl; this.plot = description; this.tags = genres
                    this.year = year; this.rating = rating; this.showStatus = status; this.recommendations = recommendations
                }
            } else {
                val durationMinutes = this.selectFirst("div.duration div:nth-child(2)")?.text()?.trim()?.filter { it.isDigit() }?.toIntOrNull()
                provider.newMovieLoadResponse(title, url, mainTvType, url) {
                    this.posterUrl = finalPosterUrl; this.plot = description; this.tags = genres
                    this.year = year; this.rating = rating; this.recommendations = recommendations
                    durationMinutes?.let { addDuration(it.toString()) }
                }
            }
        } catch (e: Exception) {
            return null
        }
    }

    private fun String?.encodeUri(): String = URLEncoder.encode(this ?: "", "UTF-8")
    private fun Double?.toAnimeHayRatingInt(): Int? = this?.let { (it * 1000).roundToInt().coerceIn(0, 10000) }
    private fun fixUrl(url: String?, baseUrl: String): String? {
        if (url.isNullOrBlank()) return null
        return try {
            when {
                url.startsWith("http") -> url
                url.startsWith("//") -> "https:$url"
                else -> URL(URL(baseUrl), url).toString()
            }
        } catch (_: Exception) { null }
    }
}
