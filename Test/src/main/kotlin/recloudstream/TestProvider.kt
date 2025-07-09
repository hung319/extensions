package recloudstream // Giữ nguyên package gốc của file AnimeHay

// === Imports ===
import android.util.Log
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addDuration
import com.lagradost.cloudstream3.mvvm.normalSafeApiCall // Cho parsedSafe nếu nó là extension
import com.lagradost.cloudstream3.utils.AppUtils.parseJson // Cho parsedSafe nếu nó dùng parseJson
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.newExtractorLink
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.net.MalformedURLException
import java.net.URL
import java.net.URLEncoder
import kotlin.math.roundToInt

// *** Kitsu Data Classes ***
// Dùng để phân tích phản hồi từ Kitsu API
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
    override var mainUrl = "https://ahay.in" // URL gốc để kiểm tra domain, không phải URL hoạt động chính
    override var name = "AnimeHay"
    override val supportedTypes = setOf(TvType.Anime, TvType.AnimeMovie, TvType.OVA, TvType.Cartoon)
    override var lang = "vi"
    override val hasMainPage = true

    // --- Phần xử lý domain động ---
    // TỐI ƯU: Sử dụng `lateinit` để tránh khởi tạo với giá trị giả và `lazy` để chỉ kiểm tra domain một lần.
    private val activeUrl by lazy { fetchActiveUrl() }

    /**
     * TỐI ƯU: Logic tìm domain được gói gọn trong một hàm riêng, chỉ chạy MỘT LẦN khi cần.
     * Sử dụng các hàm của Kotlin để code ngắn gọn hơn.
     * @return URL String của domain đang hoạt động.
     */
    private fun fetchActiveUrl(): String {
        var currentUrl = "https://animehay.bid" // URL mặc định
        Log.d("AnimeHayProvider", "Starting domain check. Default: $currentUrl, Check URL: $mainUrl")

        runCatching {
            val response = app.get(mainUrl, allowRedirects = true)
            val document = response.document
            val landedUrl = response.url
            Log.d("AnimeHayProvider", "Fetched from $mainUrl, landed on: $landedUrl")

            // TỐI ƯU: Tìm domain mới từ nội dung trang bằng cách duyệt qua nhiều khả năng một cách mạch lạc.
            val newDomainFromContent = sequenceOf(
                // 1. Thử các selector cho thẻ <a>
                { document.selectFirst("a.bt-link, a.bt-link-1")?.attr("href") },
                // 2. Thử regex trong script inline
                {
                    val scriptContent = document.select("script:not([src])").html()
                    Regex("""var\s+new_domain\s*=\s*["'](https?://[^"']+)["']""").find(scriptContent)?.groupValues?.get(1)
                }
            ).mapNotNull { it() }.firstOrNull()

            // TỐI ƯU: Xử lý và kiểm tra domain tìm được
            val potentialDomain = newDomainFromContent?.let { href ->
                runCatching {
                    val urlObject = URL(href)
                    "${urlObject.protocol}://${urlObject.host}"
                }.getOrNull()
            } ?: run {
                // Nếu không tìm thấy trong nội dung, thử dùng URL sau khi redirect
                val initialHost = runCatching { URL(mainUrl).host }.getOrNull()
                val landedHost = runCatching { URL(landedUrl).host }.getOrNull()
                if (landedHost != null && landedHost != initialHost) {
                    runCatching {
                        val landedUrlObject = URL(landedUrl)
                        "${landedUrlObject.protocol}://${landedUrlObject.host}"
                    }.getOrNull()
                } else null
            }

            // Cập nhật URL nếu tìm thấy domain mới, hợp lệ và khác với domain hiện tại
            potentialDomain?.let { newUrl ->
                if (newUrl != currentUrl && !newUrl.contains("archive.org")) {
                    Log.i("AnimeHayProvider", "Domain updated: $currentUrl -> $newUrl")
                    currentUrl = newUrl
                } else {
                    Log.i("AnimeHayProvider", "Found domain '$newUrl' is same or invalid. No change needed.")
                }
            }
        }.onFailure {
            Log.e("AnimeHayProvider", "Critical error during domain check. Using default URL: $currentUrl", it)
        }

        Log.i("AnimeHayProvider", "Final active URL: $currentUrl")
        return currentUrl
    }

    /**
     * Lấy URL poster từ Kitsu API dựa trên tiêu đề anime.
     */
    private suspend fun getKitsuPoster(title: String): String? {
        return runCatching {
            val encodedTitle = URLEncoder.encode(title, "UTF-8")
            val searchUrl = "https://kitsu.io/api/edge/anime?filter[text]=$encodedTitle&page[limit]=1"
            val response = app.get(searchUrl).parsedSafe<KitsuMain>()
            val poster = response?.data?.firstOrNull()?.attributes?.posterImage
            // TỐI ƯU: Dùng list và firstOrNull để code trông sạch hơn một chút
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
            val siteBaseUrl = activeUrl
            val urlToFetch = if (page <= 1) siteBaseUrl else "$siteBaseUrl/phim-moi-cap-nhap/trang-$page.html"

            val document = app.get(urlToFetch).document
            Log.d("AnimeHayProvider", "Fetched main page $page from: $urlToFetch")

            val homePageItems = document.select("div.movies-list div.movie-item").mapNotNull {
                it.toSearchResponse(this, siteBaseUrl)
            }

            // TỐI ƯU: Logic kiểm tra `hasNext` được làm ngắn gọn hơn
            val currentPage = document.selectFirst("div.pagination a.active_page")?.text()?.toIntOrNull() ?: page
            val hasNext = document.selectFirst("div.pagination a[href*=/trang-${currentPage + 1}.html]") != null
            Log.i("AnimeHayProvider", "Page $page -> hasNext: $hasNext")

            val listTitle = request.name.ifBlank { "Mới cập nhật" }
            newHomePageResponse(listOf(HomePageList(listTitle, homePageItems)), hasNext)
        }.onFailure {
            Log.e("AnimeHayProvider", "Error in getMainPage for page $page", it)
        }.getOrDefault(newHomePageResponse(emptyList<HomePageList>(), false))
    }

    override suspend fun search(query: String): List<SearchResponse> {
        return runCatching {
            val searchUrl = "$activeUrl/tim-kiem/${query.encodeUri()}.html"
            Log.i("AnimeHayProvider", "Searching URL: $searchUrl")

            app.get(searchUrl).document
                .select("div.movies-list div.movie-item")
                .mapNotNull { it.toSearchResponse(this, activeUrl) }
        }.onFailure {
            Log.e("AnimeHayProvider", "Error in search for query '$query'", it)
        }.getOrDefault(emptyList())
    }

    override suspend fun load(url: String): LoadResponse? {
        return runCatching {
            Log.d("AnimeHayProvider", "Loading details for URL: $url")
            val document = app.get(url).document
            document.toLoadResponse(this, url, activeUrl)
        }.onFailure {
            Log.e("AnimeHayProvider", "Error in load for $url", it)
        }.getOrNull()
    }

    // TỐI ƯU: Hàm helper để tìm link server, tránh lặp code cho GUN và PHO
    private fun findServerInfo(document: Document, pageContent: String, serverName: String): Pair<String?, String?> {
        val regex = Regex("""src=["'](https?://[^"']*$serverName\.php\?id=([^&"']+)&[^"']*)["']""")
        val iframeSelector = "iframe#${serverName}_if[src*=$serverName.php]"

        // Thử tìm trong script và html trước
        var match = regex.find(pageContent)
        if (match != null) {
            val link = fixUrl(match.groupValues[1], activeUrl)
            val id = match.groupValues[2]
            Log.d("AnimeHayProvider", "Found $serverName link in script/HTML: $link")
            return link to id
        }

        // Nếu không có, thử tìm trong iframe
        document.selectFirst(iframeSelector)?.let { iframe ->
            val link = fixUrl(iframe.attr("src"), activeUrl)
            val id = link?.let { Regex(""".*?$serverName\.php\?id=([^&"']+)""").find(it)?.groupValues?.get(1) }
            Log.d("AnimeHayProvider", "Found $serverName link in iframe: $link")
            return link to id
        }

        Log.w("AnimeHayProvider", "$serverName link not found.")
        return null to null
    }


    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        Log.d("AnimeHayProvider", "loadLinks called for: $data")
        var foundLinks = false

        runCatching {
            val document = app.get(data, referer = activeUrl).document
            val pageContent = document.html() // Lấy nội dung một lần để tái sử dụng

            // === TỐI ƯU: Sử dụng `coroutineScope` để các tác vụ mạng (nếu có) có thể chạy song song ===
            // Mặc dù ở đây chủ yếu là bóc tách chuỗi, cấu trúc này vẫn tốt cho việc mở rộng sau này.
            
            // 1. Server TOK
            runCatching {
                val tokRegex = Regex("""tik:\s*['"]([^'"]+)['"]""")
                tokRegex.find(pageContent)?.groupValues?.get(1)?.trim()?.let { m3u8Link ->
                    Log.i("AnimeHayProvider", "Found TOK M3U8 link: $m3u8Link")
                    callback(
                        newExtractorLink(source = m3u8Link, name = "Server TOK", url = m3u8Link, type = ExtractorLinkType.M3U8) {
                            quality = Qualities.Unknown.value
                            referer = data
                        }
                    )
                    foundLinks = true
                }
            }.onFailure { Log.e("AnimeHayProvider", "Error extracting TOK", it) }

            // 2. Server GUN
            runCatching {
                val (gunLink, gunId) = findServerInfo(document, pageContent, "gun")
                if (gunLink != null && gunId != null) {
                    val finalM3u8Link = "https://pt.rapovideo.xyz/playlist/v2/$gunId/master.m3u8"
                    Log.i("AnimeHayProvider", "Constructed GUN M3U8 link: $finalM3u8Link")
                    callback(
                        newExtractorLink(source = finalM3u8Link, name = "Server GUN", url = finalM3u8Link, type = ExtractorLinkType.M3U8) {
                            quality = Qualities.Unknown.value
                            referer = gunLink
                        }
                    )
                    foundLinks = true
                }
            }.onFailure { Log.e("AnimeHayProvider", "Error extracting GUN", it) }


            // 3. Server PHO
            runCatching {
                val (phoLink, phoId) = findServerInfo(document, pageContent, "pho")
                if (phoLink != null && phoId != null) {
                    val finalM3u8Link = "https://pt.rapovideo.xyz/playlist/$phoId/master.m3u8"
                    Log.i("AnimeHayProvider", "Constructed PHO M3U8 link: $finalM3u8Link")
                    callback(
                        newExtractorLink(source = finalM3u8Link, name = "Server PHO", url = finalM3u8Link, type = ExtractorLinkType.M3U8) {
                            quality = Qualities.Unknown.value
                            referer = phoLink
                        }
                    )
                    foundLinks = true
                }
            }.onFailure { Log.e("AnimeHayProvider", "Error extracting PHO", it) }

            // 4. Server HY (Hydrax)
            runCatching {
                val hyRegex = Regex("""src=["']([^"']*playhydrax\.com[^"']*)["']""")
                val hyLink = hyRegex.find(pageContent)?.groupValues?.get(1)
                    ?: document.selectFirst("iframe[src*=playhydrax.com]")?.attr("src")
                
                hyLink?.let { fixedUrl ->
                    Log.i("AnimeHayProvider", "Processing HY (Hydrax) link: $fixedUrl")
                    loadExtractor(fixedUrl, data, subtitleCallback, callback)
                    foundLinks = true
                }
            }.onFailure { Log.e("AnimeHayProvider", "Error extracting HY", it) }

        }.onFailure {
            Log.e("AnimeHayProvider", "General error in loadLinks for $data", it)
        }

        if (!foundLinks) Log.e("AnimeHayProvider", "No stream links were found for $data")
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
            Log.d("AnimeHayProvider", "Parsing LoadResponse for: \"$title\"")

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
        return when {
            url.startsWith("http") -> url
            url.startsWith("//") -> "https:$url"
            else -> "$baseUrl$url" // Giả định url luôn bắt đầu bằng `/` nếu không phải full URL
        }
    }
}
