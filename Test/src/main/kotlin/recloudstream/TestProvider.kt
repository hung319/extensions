package recloudstream // Thay thế bằng package của bạn

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
import java.net.URL
import java.net.URLEncoder
import kotlin.math.roundToInt
import com.lagradost.cloudstream3.DubStatus
import java.util.EnumSet

// *** Kitsu Data Classes ***
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

    override var mainUrl = "https://ahay.in"
    override var name = "AnimeHay"
    override val supportedTypes = setOf(TvType.Anime, TvType.AnimeMovie, TvType.OVA, TvType.Cartoon)
    override var lang = "vi"
    override val hasMainPage = true

    private var activeUrl = "https://animehay.bid"
    private var isDomainCheckComplete = false

    private suspend fun getActiveUrl(): String {
        if (isDomainCheckComplete) {
            return activeUrl
        }

        Log.d("AnimeHayProvider", "Starting domain check. Default: $activeUrl, Check URL: $mainUrl")
        runCatching {
            val response = app.get(mainUrl, allowRedirects = true)
            val document = response.document
            val landedUrl = response.url
            Log.d("AnimeHayProvider", "Fetched from $mainUrl, landed on: $landedUrl")

            val newDomainFromContent = sequenceOf(
                { document.selectFirst("a.bt-link, a.bt-link-1")?.attr("href") },
                {
                    val scriptContent = document.select("script:not([src])").html()
                    Regex("""var\s+new_domain\s*=\s*["'](https?://[^"']+)["']""").find(scriptContent)?.groupValues?.get(1)
                }
            ).mapNotNull { it() }.firstOrNull()

            val potentialDomain = newDomainFromContent?.let { href ->
                runCatching {
                    val urlObject = URL(href)
                    "${urlObject.protocol}://${urlObject.host}"
                }.getOrNull()
            } ?: run {
                val initialHost = runCatching { URL(mainUrl).host }.getOrNull()
                val landedHost = runCatching { URL(landedUrl).host }.getOrNull()
                if (landedHost != null && landedHost != initialHost) {
                    runCatching {
                        val landedUrlObject = URL(landedUrl)
                        "${landedUrlObject.protocol}://${landedUrlObject.host}"
                    }.getOrNull()
                } else null
            }

            potentialDomain?.let { newUrl ->
                if (newUrl != activeUrl && !newUrl.contains("archive.org")) {
                    Log.i("AnimeHayProvider", "Domain updated: $activeUrl -> $newUrl")
                    activeUrl = newUrl
                }
            }
        }.onFailure {
            Log.e("AnimeHayProvider", "Critical error during domain check. Using current URL: $activeUrl", it)
        }

        isDomainCheckComplete = true
        Log.i("AnimeHayProvider", "Final active URL: $activeUrl")
        return activeUrl
    }

    private suspend fun getKitsuPoster(title: String): String? {
        return runCatching {
            val encodedTitle = URLEncoder.encode(title, "UTF-8")
            val searchUrl = "https://kitsu.io/api/edge/anime?filter[text]=$encodedTitle&page[limit]=1"
            val response = app.get(searchUrl).parsedSafe<KitsuMain>()
            val poster = response?.data?.firstOrNull()?.attributes?.posterImage
            listOfNotNull(poster?.original, poster?.large, poster?.medium, poster?.small, poster?.tiny).firstOrNull()
        }.getOrNull()
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        return runCatching {
            val siteBaseUrl = getActiveUrl()
            val urlToFetch = if (page <= 1) siteBaseUrl else "$siteBaseUrl/phim-moi-cap-nhap/trang-$page.html"

            val document = app.get(urlToFetch).document
            val homePageItems = document.select("div.movies-list div.movie-item").mapNotNull {
                it.toSearchResponse(this, siteBaseUrl)
            }

            val currentPage = document.selectFirst("div.pagination a.active_page")?.text()?.toIntOrNull() ?: page
            val hasNext = document.selectFirst("div.pagination a[href*=/trang-${currentPage + 1}.html]") != null

            val listTitle = request.name.ifBlank { "Mới cập nhật" }
            newHomePageResponse(listOf(HomePageList(listTitle, homePageItems)), hasNext)
        }.onFailure {
            Log.e("AnimeHayProvider", "Error in getMainPage for page $page", it)
        }.getOrDefault(newHomePageResponse(emptyList<HomePageList>(), false))
    }

    override suspend fun search(query: String): List<SearchResponse> {
        return runCatching {
            val siteBaseUrl = getActiveUrl()
            val searchUrl = "$siteBaseUrl/tim-kiem/${query.encodeUri()}.html"
            
            val document = app.get(searchUrl).document
            document.select("div.movies-list div.movie-item").mapNotNull { 
                it.toSearchResponse(this, siteBaseUrl) 
            }
        }.onFailure {
            Log.e("AnimeHayProvider", "Error in search for query '$query'", it)
        }.getOrDefault(emptyList())
    }

    override suspend fun load(url: String): LoadResponse? {
        return runCatching {
            val siteBaseUrl = getActiveUrl()
            val document = app.get(url).document
            document.toLoadResponse(this, url, siteBaseUrl)
        }.onFailure {
            Log.e("AnimeHayProvider", "Error in load for $url", it)
        }.getOrNull()
    }

    private fun findServerInfo(document: Document, pageContent: String, serverName: String, baseUrl: String): Pair<String?, String?> {
        val regex = Regex("""src=["'](https?://[^"']*$serverName\.php\?id=([^&"']+)&[^"']*)["']""")
        val iframeSelector = "iframe#${serverName}_if[src*=$serverName.php]"

        var match = regex.find(pageContent)
        if (match != null) {
            val link = fixUrl(match.groupValues[1], baseUrl)
            val id = match.groupValues[2]
            return link to id
        }

        document.selectFirst(iframeSelector)?.let { iframe ->
            val link = fixUrl(iframe.attr("src"), baseUrl)
            val id = link?.let { Regex(""".*?$serverName\.php\?id=([^&"']+)""").find(it)?.groupValues?.get(1) }
            return link to id
        }
        
        return null to null
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        var foundLinks = false
        runCatching {
            val siteBaseUrl = getActiveUrl()
            val document = app.get(data, referer = siteBaseUrl).document
            val pageContent = document.html()

            // Server TOK
            runCatching {
                val tokRegex = Regex("""tik:\s*['"]([^'"]+)['"]""")
                tokRegex.find(pageContent)?.groupValues?.get(1)?.trim()?.let { m3u8Link ->
                    callback(
                        ExtractorLink(
                            source = this.name,
                            name = "Server TOK",
                            url = m3u8Link,
                            referer = siteBaseUrl,
                            quality = Qualities.Unknown.value,
                            type = ExtractorLinkType.M3U8, // Đã dùng đúng type
                            headers = mapOf(
                                "Origin" to siteBaseUrl,
                                "Referer" to siteBaseUrl
                            )
                        )
                    )
                    foundLinks = true
                }
            }.onFailure { Log.e("AnimeHayProvider", "Error extracting TOK", it) }
            
            // Server GUN
            runCatching {
                val (gunLink, gunId) = findServerInfo(document, pageContent, "gun", siteBaseUrl)
                if (gunLink != null && gunId != null) {
                    val finalM3u8Link = "https://pt.rapovideo.xyz/playlist/v2/$gunId/master.m3u8"
                    callback(
                        newExtractorLink(source = finalM3u8Link, name = "Server GUN", url = finalM3u8Link, type = ExtractorLinkType.M3U8) {
                            quality = Qualities.Unknown.value
                            this.referer = gunLink
                        }
                    )
                    foundLinks = true
                }
            }.onFailure { Log.e("AnimeHayProvider", "Error extracting GUN", it) }

            // Server PHO
            runCatching {
                val (phoLink, phoId) = findServerInfo(document, pageContent, "pho", siteBaseUrl)
                if (phoLink != null && phoId != null) {
                    val finalM3u8Link = "https://pt.rapovideo.xyz/playlist/$phoId/master.m3u8"
                    callback(
                        newExtractorLink(source = finalM3u8Link, name = "Server PHO", url = finalM3u8Link, type = ExtractorLinkType.M3U8) {
                            quality = Qualities.Unknown.value
                            this.referer = phoLink
                        }
                    )
                    foundLinks = true
                }
            }.onFailure { Log.e("AnimeHayProvider", "Error extracting PHO", it) }

        }.onFailure {
            Log.e("AnimeHayProvider", "General error in loadLinks for $data", it)
        }

        return foundLinks
    }

    override fun getVideoInterceptor(extractorLink: ExtractorLink): Interceptor? {
        return Interceptor { chain ->
            val request = chain.request()
            val response = chain.proceed(request)
            val url = request.url.toString()

            if (url.contains("tiktokcdn.com") || url.contains("ibyteimg.com") || url.contains("segment.cloudbeta.win")) {
                val body = response.body
                if (body != null) {
                    try {
                        val originalBytes = body.bytes()
                        // THAY ĐỔI CUỐI CÙNG: Thử nghiệm cắt 9 byte thay vì 10
                        val bytesToSkip = 7
                        if (originalBytes.size > bytesToSkip) {
                            val fixedBytes = originalBytes.copyOfRange(bytesToSkip, originalBytes.size)
                            val newBody = fixedBytes.toResponseBody(body.contentType())
                            return@Interceptor response.newBuilder().body(newBody).build()
                        }
                    } catch (e: Exception) {
                        return@Interceptor response
                    }
                }
            }
            return@Interceptor response
        }
    }

    private fun Element.toSearchResponse(provider: MainAPI, baseUrl: String): AnimeSearchResponse? {
        val element = this
        return runCatching {
            val linkElement = element.selectFirst("> a[href], a[href*=thong-tin-phim]") ?: return null
            val href = fixUrl(linkElement.attr("href"), baseUrl) ?: return null
            val title = element.selectFirst("div.name-movie")?.text()?.takeIf { it.isNotBlank() }
                ?: linkElement.attr("title").takeIf { it.isNotBlank() } ?: return null

            val posterUrl = element.selectFirst("img")?.let { it.attr("src").ifBlank { it.attr("data-src") } }
            val fixedPosterUrl = fixUrl(posterUrl, baseUrl)
            val tvType = if (href.contains("/phim/", true)) TvType.AnimeMovie else TvType.Anime

            provider.newAnimeSearchResponse(name = title, url = href, type = tvType) {
                this.posterUrl = fixedPosterUrl
                this.dubStatus = EnumSet.of(DubStatus.Subbed)
                
                val episodeText = element.selectFirst("div.episode-latest span")?.text()?.trim()
                if (episodeText != null) {
                    val episodeCount = episodeText.substringBefore("/").substringBefore("-").filter { it.isDigit() }.toIntOrNull()
                    if (episodeCount != null) {
                        this.episodes[DubStatus.Subbed] = episodeCount
                    }
                }
            }
        }.getOrNull()
    }

    private suspend fun Document.toLoadResponse(provider: MainAPI, url: String, baseUrl: String): AnimeLoadResponse? {
        val document = this
        return runCatching {
            val title = document.selectFirst("h1.heading_movie")?.text()?.trim() ?: return null
            val genres = document.select("div.list_cate a").mapNotNull { it.text()?.trim() }
            val isChineseAnimation = genres.any { it.equals("CN Animation", ignoreCase = true) }
            val hasEpisodes = document.selectFirst("div.list-item-episode a") != null

            val mainTvType = when {
                hasEpisodes && isChineseAnimation -> TvType.Cartoon
                hasEpisodes -> TvType.Anime
                !hasEpisodes && isChineseAnimation -> TvType.Cartoon
                else -> TvType.AnimeMovie
            }

            val animehayPosterUrl = fixUrl(document.selectFirst("div.head div.first img")?.attr("src"), baseUrl)
            
            val finalPosterUrl = if (mainTvType in setOf(TvType.Anime, TvType.AnimeMovie, TvType.OVA)) {
                 getKitsuPoster(title) ?: animehayPosterUrl
            } else {
                animehayPosterUrl
            }

            provider.newAnimeLoadResponse(name = title, url = url, type = mainTvType) {
                this.posterUrl = finalPosterUrl
                this.plot = document.selectFirst("div.desc > div:last-child")?.text()?.trim()
                this.year = document.selectFirst("div.update_time div:nth-child(2)")?.text()?.filter { it.isDigit() }?.toIntOrNull()
                this.rating = document.selectFirst("div.score div:nth-child(2)")?.text()
                    ?.substringBefore("||")?.trim()?.toDoubleOrNull()?.toAnimeHayRatingInt()
                this.tags = genres
                this.showStatus = when (document.selectFirst("div.status div:nth-child(2)")?.text()?.trim()) {
                    "Hoàn thành" -> ShowStatus.Completed
                    "Đang tiến hành", "Đang cập nhật" -> ShowStatus.Ongoing
                    else -> null
                }
                this.recommendations = document.select("div.movie-recommend div.movie-item").mapNotNull {
                    it.toSearchResponse(provider, baseUrl)
                }

                if(hasEpisodes) {
                    val episodeList = document.select("div.list-item-episode a").mapNotNull { epLink ->
                        val epUrl = fixUrl(epLink.attr("href"), baseUrl) ?: return@mapNotNull null
                        val epName = epLink.attr("title").ifBlank { epLink.selectFirst("span")?.text() }?.trim() ?: return@mapNotNull null
                        Episode(data = epUrl, name = epName)
                    }.reversed()
                    this.episodes[DubStatus.Subbed] = episodeList
                } else {
                    this.duration = document.selectFirst("div.duration div:nth-child(2)")?.text()?.filter { it.isDigit() }?.toIntOrNull()
                }
            }
        }.onFailure {
            Log.e("AnimeHayProvider", "Error in toLoadResponse for url: $url", it)
        }.getOrNull()
    }

    private fun String?.encodeUri(): String = this?.let { URLEncoder.encode(it, "UTF-8") } ?: ""
    private fun Double?.toAnimeHayRatingInt(): Int? = this?.let { (it * 1000).roundToInt().coerceIn(0, 10000) }

    private fun fixUrl(url: String?, baseUrl: String): String? {
        if (url.isNullOrBlank()) return null
        return if (url.startsWith("http")) {
            url
        } else if (url.startsWith("//")) {
            "https:" + url
        } else {
            baseUrl.removeSuffix("/") + "/" + url.removePrefix("/")
        }
    }
}
