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
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.net.URL
import java.net.URLEncoder
import kotlin.math.roundToInt

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

    // --- Phần xử lý domain động ---
    // SỬA LỖI: Quay lại dùng biến trạng thái để lưu URL và cờ kiểm tra.
    private var activeUrl = "https://animehay.bid"
    private var isDomainCheckComplete = false

    /**
     * SỬA LỖI: Logic tìm domain được đặt trong một hàm `suspend` riêng.
     * Hàm này sẽ chỉ thực hiện kiểm tra mạng ở lần gọi đầu tiên.
     */
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
        }.onSuccess {
            if (it != null) Log.d("AnimeHayProvider", "Found Kitsu poster for '$title': $it")
            else Log.w("AnimeHayProvider", "Kitsu poster not found for '$title'")
        }.onFailure {
            Log.e("AnimeHayProvider", "Kitsu API Error for title '$title'", it)
        }.getOrNull()
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        return runCatching {
            val siteBaseUrl = getActiveUrl() // SỬA LỖI: Gọi hàm suspend
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
            val siteBaseUrl = getActiveUrl() // SỬA LỖI: Gọi hàm suspend
            val searchUrl = "$siteBaseUrl/tim-kiem/${query.encodeUri()}.html"
            Log.i("AnimeHayProvider", "Searching URL: $searchUrl")

            app.get(searchUrl).document
                .select("div.movies-list div.movie-item")
                .mapNotNull { it.toSearchResponse(this, siteBaseUrl) }
        }.onFailure {
            Log.e("AnimeHayProvider", "Error in search for query '$query'", it)
        }.getOrDefault(emptyList())
    }

    override suspend fun load(url: String): LoadResponse? {
        return runCatching {
            val siteBaseUrl = getActiveUrl() // SỬA LỖI: Gọi hàm suspend
            Log.d("AnimeHayProvider", "Loading details for URL: $url")
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
            val siteBaseUrl = getActiveUrl() // SỬA LỖI: Gọi hàm suspend
            val document = app.get(data, referer = siteBaseUrl).document
            val pageContent = document.html()

            runCatching {
                val tokRegex = Regex("""tik:\s*['"]([^'"]+)['"]""")
                tokRegex.find(pageContent)?.groupValues?.get(1)?.trim()?.let { m3u8Link ->
                    callback(
                        newExtractorLink(source = m3u8Link, name = "Server TOK", url = m3u8Link, type = ExtractorLinkType.M3U8) {
                            quality = Qualities.Unknown.value
                            referer = data
                            this.headers = mapOf("Origin" to siteBaseUrl)
                        }
                    )
                    foundLinks = true
                }
            }.onFailure { Log.e("AnimeHayProvider", "Error extracting TOK", it) }
            
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

            runCatching {
                val hyRegex = Regex("""src=["']([^"']*playhydrax\.com[^"']*)["']""")
                val hyLink = hyRegex.find(pageContent)?.groupValues?.get(1)
                    ?: document.selectFirst("iframe[src*=playhydrax.com]")?.attr("src")

                hyLink?.let { fixedUrl ->
                    loadExtractor(fixedUrl, data, subtitleCallback, callback)
                    foundLinks = true
                }
            }.onFailure { Log.e("AnimeHayProvider", "Error extracting HY", it) }

        }.onFailure {
            Log.e("AnimeHayProvider", "General error in loadLinks for $data", it)
        }

        return foundLinks
    }

    private fun Element.toSearchResponse(provider: MainAPI, baseUrl: String): SearchResponse? {
        return runCatching {
            val linkElement = this.selectFirst("> a[href], a[href*=thong-tin-phim]") ?: return null
            val href = fixUrl(linkElement.attr("href"), baseUrl) ?: return null
            val title = this.selectFirst("div.name-movie")?.text()?.takeIf { it.isNotBlank() }
                ?: linkElement.attr("title").takeIf { it.isNotBlank() } ?: return null

            val posterUrl = this.selectFirst("img")?.let { it.attr("src").ifBlank { it.attr("data-src") } }
            val fixedPosterUrl = fixUrl(posterUrl, baseUrl)
            val tvType = if (href.contains("/phim/", true)) TvType.AnimeMovie else TvType.Anime

            provider.newMovieSearchResponse(title, href, tvType) {
                this.posterUrl = fixedPosterUrl
            }
        }.getOrNull()
    }

    private suspend fun Document.toLoadResponse(provider: MainAPI, url: String, baseUrl: String): LoadResponse? {
        return runCatching {
            val title = this.selectFirst("h1.heading_movie")?.text()?.trim() ?: return null
            val genres = this.select("div.list_cate a").mapNotNull { it.text()?.trim() }
            val isChineseAnimation = genres.any { it.equals("CN Animation", ignoreCase = true) }
            val hasEpisodes = this.selectFirst("div.list-item-episode a") != null

            val mainTvType = when {
                hasEpisodes && isChineseAnimation -> TvType.Cartoon
                hasEpisodes -> TvType.Anime
                !hasEpisodes && isChineseAnimation -> TvType.Cartoon
                else -> TvType.AnimeMovie
            }

            val animehayPosterUrl = fixUrl(this.selectFirst("div.head div.first img")?.attr("src"), baseUrl)
            val finalPosterUrl = if (mainTvType in setOf(TvType.Anime, TvType.AnimeMovie, TvType.OVA)) {
                getKitsuPoster(title) ?: animehayPosterUrl
            } else {
                animehayPosterUrl
            }

            val description = this.selectFirst("div.desc > div:last-child")?.text()?.trim()
            val year = this.selectFirst("div.update_time div:nth-child(2)")?.text()?.filter { it.isDigit() }?.toIntOrNull()
            val rating = this.selectFirst("div.score div:nth-child(2)")?.text()
                ?.substringBefore("||")?.trim()?.toDoubleOrNull()?.toAnimeHayRatingInt()

            val status = when (this.selectFirst("div.status div:nth-child(2)")?.text()?.trim()) {
                "Hoàn thành" -> ShowStatus.Completed
                "Đang tiến hành", "Đang cập nhật" -> ShowStatus.Ongoing
                else -> null
            }

            val episodes = this.select("div.list-item-episode a").mapNotNull { epLink ->
                val epUrl = fixUrl(epLink.attr("href"), baseUrl) ?: return@mapNotNull null
                val epName = epLink.attr("title").ifBlank { epLink.selectFirst("span")?.text() }?.trim() ?: return@mapNotNull null
                newEpisode(data = epUrl) { this.name = epName }
            }.reversed()

            val recommendations = this.select("div.movie-recommend div.movie-item").mapNotNull {
                it.toSearchResponse(provider, baseUrl)
            }

            if (hasEpisodes) {
                provider.newTvSeriesLoadResponse(title, url, mainTvType, episodes) {
                    this.posterUrl = finalPosterUrl
                    this.plot = description
                    this.tags = genres
                    this.year = year
                    this.rating = rating
                    this.showStatus = status
                    this.recommendations = recommendations
                }
            } else {
                val durationMinutes = this.selectFirst("div.duration div:nth-child(2)")?.text()?.filter { it.isDigit() }?.toIntOrNull()
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
            "https:$url"
        } else {
            // Đảm bảo baseUrl không có dấu / ở cuối và url có dấu / ở đầu
            baseUrl.removeSuffix("/") + "/" + url.removePrefix("/")
        }
    }
}
