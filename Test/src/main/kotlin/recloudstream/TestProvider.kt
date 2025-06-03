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
// import com.lagradost.cloudstream3.plugins.CloudstreamPlugin // Bỏ comment nếu đây là plugin độc lập
// import com.lagradost.cloudstream3.plugins.Plugin // Bỏ comment nếu đây là plugin độc lập
// import android.content.Context // Có thể không cần thiết trực tiếp (đã comment trong code gốc)
// import com.lagradost.cloudstream3.mvvm.logError // Cho hàm logError nếu cần (đã comment trong code gốc)


// *** THÊM KITSU DATA CLASSES ***
// Dùng để phân tích phản hồi từ Kitsu API
data class KitsuMain(val data: List<KitsuData>?)
data class KitsuData(val attributes: KitsuAttributes?)
data class KitsuAttributes(
    val canonicalTitle: String?, // Tiêu đề chuẩn hóa trên Kitsu
    val posterImage: KitsuPoster? // Đối tượng chứa các URL poster
)
data class KitsuPoster(
    val original: String?, // URL chất lượng gốc (ưu tiên)
    val large: String?,    // URL lớn
    val medium: String?,   // URL trung bình
    val small: String?,    // URL nhỏ
    val tiny: String?      // URL siêu nhỏ
)

// === Provider Class ===
class AnimeHayProvider : MainAPI() {

    // === Thuộc tính Provider ===
    override var mainUrl = "https://ahay.in" // URL gốc để kiểm tra domain, không phải URL hoạt động chính
    override var name = "AnimeHay"
    override val supportedTypes = setOf(TvType.Anime, TvType.AnimeMovie, TvType.OVA, TvType.Cartoon) // Các loại nội dung hỗ trợ
    override var lang = "vi" // Ngôn ngữ chính của provider
    override val hasMainPage = true // Provider có trang chủ

    // --- Phần xử lý domain động ---
    private var currentActiveUrl = "https://animehay.bid" // URL mặc định hoặc đã biết là đang hoạt động
    private var domainCheckPerformed = false // Cờ đánh dấu đã kiểm tra domain hay chưa
    private val domainCheckUrl = mainUrl // URL dùng để kiểm tra domain mới (thường là domain gốc .tv)

    /**
     * Lấy URL hoạt động hiện tại của AnimeHay.
     * Sẽ kiểm tra domain mới từ `domainCheckUrl` nếu chưa kiểm tra.
     * Ưu tiên link từ nội dung trang (thẻ a, script), sau đó là URL sau khi redirect.
     * Cập nhật `currentActiveUrl` nếu tìm thấy domain mới hợp lệ.
     * @return URL String của domain đang hoạt động.
     */
    private suspend fun getBaseUrl(): String {
        if (domainCheckPerformed) {
            return currentActiveUrl
        }

        var finalNewDomain: String? = null
        Log.d("AnimeHayProvider", "Starting domain check. Current active: $currentActiveUrl, Initial check URL: $domainCheckUrl")

        try {
            // Step 1: Fetch the page from domainCheckUrl, allowing redirects to get to an announcement page or the new site.
            val response = app.get(domainCheckUrl, allowRedirects = true)
            val landedUrl = response.url // The URL we actually landed on.
            val document = response.document
            Log.d("AnimeHayProvider", "Fetched from $domainCheckUrl, landed on: $landedUrl. HTML length: ${document.html().length}")

            // Step 2: Try to parse an explicit new domain link from the content of the landed page.
            var hrefFromContent: String? = null
            // Prioritized selectors for <a> tags based on provided HTML
            val linkSelectors = listOf("a.bt-link", "a.bt-link-1")
            for (selector in linkSelectors) {
                hrefFromContent = document.selectFirst(selector)?.attr("href")
                if (!hrefFromContent.isNullOrBlank()) {
                    Log.d("AnimeHayProvider", "Found href '$hrefFromContent' using selector '$selector'")
                    break // Found a link, no need to check other <a> selectors
                }
            }

            // Try parsing from script if <a> tags failed or didn't yield a link
            if (hrefFromContent.isNullOrBlank()) {
                val scriptElements = document.select("script")
                val newDomainRegex = Regex("""var\s+new_domain\s*=\s*["'](https?://[^"']+)["']""")
                for (scriptElement in scriptElements) {
                    if (!scriptElement.hasAttr("src")) { // Only process inline scripts
                        val scriptContent = scriptElement.html()
                        val match = newDomainRegex.find(scriptContent)
                        if (match != null && match.groups.size > 1) {
                            hrefFromContent = match.groups[1]?.value
                            Log.d("AnimeHayProvider", "Found new_domain '$hrefFromContent' in script.")
                            break // Found in script, stop searching scripts
                        }
                    }
                }
            }

            if (!hrefFromContent.isNullOrBlank()) {
                try {
                    val urlObject = URL(hrefFromContent) // href should be an absolute URL like "https://animehay.tax/"
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

            // Step 3: If parsing content did not yield a new domain,
            // consider the base URL of where we landed (landedUrl),
            // IF its host is different from the original domainCheckUrl's host.
            // This covers cases where domainCheckUrl directly redirects to the new *working* site
            // which might not have the announcement structure.
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

            // Step 4: Update currentActiveUrl if a new, valid, and different domain was found.
            if (!finalNewDomain.isNullOrBlank() && finalNewDomain != currentActiveUrl) {
                // Sanity checks (e.g., against "archive.org")
                if (finalNewDomain!!.contains("archive.org", ignoreCase = true) ||
                    finalNewDomain!!.contains("web.archive.org", ignoreCase = true)) { // Check for web.archive.org specifically
                    Log.w("AnimeHayProvider", "Potential new domain '$finalNewDomain' is an archive link. Not updating.")
                } else {
                    Log.i("AnimeHayProvider", "Domain will be updated: $currentActiveUrl -> $finalNewDomain")
                    currentActiveUrl = finalNewDomain!!
                }
            } else if (finalNewDomain.isNullOrBlank()) {
                Log.w("AnimeHayProvider", "No new valid domain found by any method. Using previous active URL: $currentActiveUrl")
            } else { // finalNewDomain == currentActiveUrl
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

    /**
     * Lấy URL poster từ Kitsu API dựa trên tiêu đề anime.
     * @param title Tiêu đề anime cần tìm kiếm.
     * @return URL String của poster tìm được (ưu tiên chất lượng cao nhất) hoặc null nếu không tìm thấy/lỗi.
     */
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
        Log.d("AnimeHayProvider", "getMainPage called with page: $page, request.name: ${request.name}")

        // Theo thiết kế gốc của provider này cho danh sách chính "Mới cập nhật",
        // chỉ page = 1 mới có dữ liệu.
        if (page > 1) {
            Log.d("AnimeHayProvider", "Page > 1, returning empty list and hasNext=false by design for this main list.")
            return newHomePageResponse(emptyList(), false)
        }

        // Xử lý cho page == 1:
        try {
            val baseUrl = getBaseUrl()
            Log.d("AnimeHayProvider", "Attempting to fetch document for getMainPage (page 1) from effective baseUrl: $baseUrl")
            val document = app.get(baseUrl).document
            Log.d("AnimeHayProvider", "Document fetched successfully. Title: ${document.title()}")

            val homePageItems = document.select("div.movies-list div.movie-item")
                .mapNotNull { element ->
                    element.toSearchResponse(this, baseUrl)
                }
            Log.d("AnimeHayProvider", "Found ${homePageItems.size} items for 'Mới cập nhật'")

            // Đặt hasNext luôn là true cho page 1 theo yêu cầu.
            Log.i("AnimeHayProvider", "For page $page, hasNext is hardcoded to: true")

            val homeList = HomePageList("Mới cập nhật", homePageItems)
            // Sử dụng trực tiếp hasNext = true
            return newHomePageResponse(listOf(homeList), hasNext = true)

        } catch (e: Exception) {
            Log.e("AnimeHayProvider", "Error in getMainPage for page $page: ${e.message}", e)
            e.printStackTrace() // In chi tiết lỗi ra logcat
            return newHomePageResponse(emptyList(), false) // Trả về false nếu có lỗi
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        try {
            val baseUrl = getBaseUrl()
            val encodedQuery = query.encodeUri()
            val searchUrl = "$baseUrl/tim-kiem/$encodedQuery.html"
            Log.i("AnimeHayProvider", "Searching URL: $searchUrl")

            val document = app.get(searchUrl).document
            val moviesListContainer = document.selectFirst("div.movies-list")
            if (moviesListContainer == null) {
                Log.w("AnimeHayProvider", "Search found no 'div.movies-list' container on $searchUrl")
                return emptyList()
            }
            val movieItems = moviesListContainer.select("div.movie-item")
            Log.d("AnimeHayProvider", "Found ${movieItems.size} potential 'div.movie-item' elements.")

            val searchResults = movieItems.mapNotNull { element ->
                element.toSearchResponse(this, baseUrl)
            }

            if (searchResults.isEmpty() && movieItems.isNotEmpty()) {
                Log.w("AnimeHayProvider", "Search found ${movieItems.size} items but failed to parse any.")
            } else if (searchResults.isNotEmpty()) {
                Log.i("AnimeHayProvider", "Search successful for query '$query', parsed ${searchResults.size} items.")
            } else {
                Log.w("AnimeHayProvider", "Search found no 'div.movie-item' elements for query '$query'")
            }
            return searchResults
        } catch (e: Exception) {
            Log.e("AnimeHayProvider", "Error in search for query '$query'", e)
            return emptyList()
        }
    }

    override suspend fun load(url: String): LoadResponse? {
        try {
            Log.d("AnimeHayProvider", "Loading details for URL: $url")
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
        Log.d("AnimeHayProvider", "loadLinks called for: $data")
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
            Log.e("AnimeHayProvider", "Document is null in loadLinks for $data. Cannot proceed.")
            return false
        }

        try {
            val scriptElements = document.select("script")
            var combinedScript = ""
            scriptElements.forEach { script ->
                if (!script.hasAttr("src")) {
                    combinedScript += script.html() + "\n"
                }
            }
            val pageHtml = document.html()
            val baseUrl = getBaseUrl()

            Log.d("AnimeHayProvider", "Combined inline script length: ${combinedScript.length}")

            // 1. Server TOK
            val m3u8Regex = Regex("""tik:\s*['"]([^'"]+)['"]""")
            val m3u8Match = m3u8Regex.find(combinedScript)
            val m3u8Link = m3u8Match?.groups?.get(1)?.value?.trim()

            if (!m3u8Link.isNullOrEmpty()) {
                Log.i("AnimeHayProvider", "Found TOK M3U8 link: $m3u8Link")
                try {
                    callback(
                        newExtractorLink(source = m3u8Link, name = "Server TOK", url = m3u8Link, type = ExtractorLinkType.M3U8) {
                            this.quality = Qualities.Unknown.value
                            this.referer = data
                        }
                    )
                    foundLinks = true
                    Log.d("AnimeHayProvider", "TOK M3U8 link submitted.")
                } catch (e: Exception) { Log.e("AnimeHayProvider", "Error calling callback for TOK M3U8", e) }
            } else { Log.w("AnimeHayProvider", "TOK M3U8 link not found in script for $data") }

            // 2. Server GUN
            val gunRegexScript = Regex("""src=["'](https?://[^"']*gun\.php\?id=([^&"']+)&[^"']*)["']""")
            val gunMatchScript = gunRegexScript.find(combinedScript) ?: gunRegexScript.find(pageHtml)
            var gunLink = gunMatchScript?.groups?.get(1)?.value?.let{ fixUrl(it, baseUrl) }
            var gunId = gunMatchScript?.groups?.get(2)?.value

            if (gunLink.isNullOrEmpty() || gunId.isNullOrEmpty()) {
                Log.d("AnimeHayProvider", "GUN link not found in script/HTML, checking iframe#gun_if...")
                val gunIframe = document.selectFirst("iframe#gun_if[src*=gun.php]")
                gunLink = gunIframe?.attr("src")?.let{ fixUrl(it, baseUrl) }
                if (!gunLink.isNullOrEmpty()) {
                    val gunIdRegexIframe = Regex(""".*gun\.php\?id=([^&"']+)""")
                    gunId = gunIdRegexIframe.find(gunLink)?.groups?.get(1)?.value
                    if (gunId.isNullOrEmpty()) gunLink = null
                }
            }

            if (!gunLink.isNullOrEmpty() && !gunId.isNullOrEmpty()) {
                Log.i("AnimeHayProvider", "Processing GUN link ID: $gunId (from: $gunLink)")
                try {
                    val finalM3u8Link = "https://pt.rapovideo.xyz/playlist/v2/$gunId/master.m3u8"
                    Log.d("AnimeHayProvider", "Constructed GUN M3U8 link: $finalM3u8Link")
                    callback(
                        newExtractorLink(source = finalM3u8Link, name = "Server GUN", url = finalM3u8Link, type = ExtractorLinkType.M3U8) {
                            this.quality = Qualities.Unknown.value
                            this.referer = gunLink
                        }
                    )
                    foundLinks = true
                    Log.d("AnimeHayProvider", "GUN M3U8 link submitted.")
                } catch (e: Exception) { Log.e("AnimeHayProvider", "Error calling callback for GUN M3U8", e) }
            } else { Log.w("AnimeHayProvider", "GUN link/ID not found for $data") }

            // 3. Server PHO
            val phoRegexScript = Regex("""src=["'](https?://[^"']*pho\.php\?id=([^&"']+)&[^"']*)["']""")
            val phoMatchScript = phoRegexScript.find(combinedScript) ?: phoRegexScript.find(pageHtml)
            var phoLink = phoMatchScript?.groups?.get(1)?.value?.let{ fixUrl(it, baseUrl) }
            var phoId = phoMatchScript?.groups?.get(2)?.value

            if (phoLink.isNullOrEmpty() || phoId.isNullOrEmpty()) {
                Log.d("AnimeHayProvider", "PHO link not found in script/HTML, checking iframe#pho_if...")
                val phoIframe = document.selectFirst("iframe#pho_if[src*=pho.php]")
                phoLink = phoIframe?.attr("src")?.let{ fixUrl(it, baseUrl) }
                if (!phoLink.isNullOrEmpty()) {
                    val phoIdRegexIframe = Regex(""".*pho\.php\?id=([^&"']+)""")
                    phoId = phoIdRegexIframe.find(phoLink)?.groups?.get(1)?.value
                    if (phoId.isNullOrEmpty()) phoLink = null
                }
            }

            if (!phoLink.isNullOrEmpty() && !phoId.isNullOrEmpty()) {
                Log.i("AnimeHayProvider", "Processing PHO link ID: $phoId (from: $phoLink)")
                try {
                    val finalPhoM3u8Link = "https://pt.rapovideo.xyz/playlist/$phoId/master.m3u8"
                    Log.d("AnimeHayProvider", "Constructed PHO M3U8 link: $finalPhoM3u8Link")
                    callback(
                        newExtractorLink(source = finalPhoM3u8Link, name = "Server PHO", url = finalPhoM3u8Link, type = ExtractorLinkType.M3U8) {
                            this.quality = Qualities.Unknown.value
                            this.referer = phoLink
                        }
                    )
                    foundLinks = true
                    Log.d("AnimeHayProvider", "PHO M3U8 link submitted.")
                } catch (e: Exception) { Log.e("AnimeHayProvider", "Error calling callback for PHO M3U8", e) }
            } else { Log.w("AnimeHayProvider", "PHO link/ID not found for $data") }

            // 4. Server HY (Hydrax)
            val hyRegexScript = Regex("""src=["']([^"']*playhydrax\.com[^"']*)["']""")
            val hyMatchScript = hyRegexScript.find(combinedScript) ?: hyRegexScript.find(pageHtml)
            var hyLink = hyMatchScript?.groups?.get(1)?.value?.let{ fixUrl(it, baseUrl) }

            if (hyLink.isNullOrEmpty()) {
                Log.d("AnimeHayProvider", "HY link not found in script/HTML, checking iframe...")
                hyLink = document.selectFirst("iframe[src*=playhydrax.com]")?.attr("src")?.let{ fixUrl(it, baseUrl) }
            }

            if (!hyLink.isNullOrEmpty()) {
                Log.i("AnimeHayProvider", "Processing HY (Hydrax) link: $hyLink")
                try {
                    loadExtractor(hyLink, data, subtitleCallback, callback)
                    foundLinks = true // Assume loadExtractor attempts submission
                    Log.d("AnimeHayProvider", "Hydrax extractor called (asynchronously).")
                } catch (e: Exception) {
                    Log.e("AnimeHayProvider", "Error calling loadExtractor for HY: $hyLink", e)
                }
            } else { Log.w("AnimeHayProvider", "HY (Hydrax) link not found for $data") }

        } catch (e: Exception) {
            Log.e("AnimeHayProvider", "General error during link extraction in loadLinks for $data", e)
        }

        if (!foundLinks) {
            Log.e("AnimeHayProvider", "No stream links were successfully found or submitted for $data")
        } else {
            Log.i("AnimeHayProvider", "Finished loadLinks for $data. Link submission attempted: $foundLinks")
        }
        return foundLinks
    }


    private fun Element.toSearchResponse(provider: MainAPI, baseUrl: String): SearchResponse? {
        try {
            var linkElement = this.selectFirst("> a[href]")
            if (linkElement == null) {
                linkElement = this.selectFirst("a[href*=thong-tin-phim]")
            }

            if (linkElement == null) {
                Log.w("AnimeHayProvider", "toSearchResponse: Could not find suitable link element in item: ${this.html().take(100)}")
                return null
            }

            val hrefRaw = linkElement.attr("href")
            val href = fixUrl(hrefRaw, baseUrl)
            if (href.isNullOrBlank()) {
                Log.w("AnimeHayProvider", "toSearchResponse: Invalid or unfixable href '$hrefRaw'")
                return null
            }

            val titleFromName = this.selectFirst("div.name-movie")?.text()?.trim()
            val titleFromAttr = linkElement.attr("title")?.trim()
            val title = titleFromName?.takeIf { it.isNotBlank() } ?: titleFromAttr
            if (title.isNullOrBlank()) {
                Log.w("AnimeHayProvider", "toSearchResponse: Could not find title for href: $href")
                return null
            }

            val posterUrl = this.selectFirst("img")?.let { it.attr("src").ifBlank { it.attr("data-src") } }
            val fixedPosterUrl = fixUrl(posterUrl, baseUrl)

            val tvType = when {
                href.contains("/phim/", ignoreCase = true) -> TvType.AnimeMovie
                else -> TvType.Anime
            }

            return provider.newMovieSearchResponse(title, href, tvType) {
                this.posterUrl = fixedPosterUrl
            }
        } catch (e: Exception) {
            Log.e("AnimeHayProvider", "Error in toSearchResponse for element: ${this.html().take(100)}", e)
            return null
        }
    }

    private suspend fun Document.toLoadResponse(provider: MainAPI, url: String, baseUrl: String): LoadResponse? {
        try {
            val titleElement = this.selectFirst("h1.heading_movie")
            val title = titleElement?.text()?.trim() ?: run {
                Log.e("AnimeHayProvider", "Title (h1.heading_movie) not found for $url")
                return null
            }
            Log.d("AnimeHayProvider", "Parsing LoadResponse for: \"$title\"")

            val genres = this.select("div.list_cate a")?.mapNotNull { it.text()?.trim() } ?: emptyList()
            val isChineseAnimation = genres.any { it.contains("CN Animation", ignoreCase = true) }
            val hasEpisodes = this.selectFirst("div.list-item-episode a") != null

            val mainTvType = when {
                hasEpisodes && isChineseAnimation -> TvType.Cartoon
                hasEpisodes && !isChineseAnimation -> TvType.Anime
                !hasEpisodes && isChineseAnimation -> TvType.Cartoon // Phim lẻ TQ
                !hasEpisodes && !isChineseAnimation -> TvType.AnimeMovie
                else -> TvType.Anime
            }
            Log.d("AnimeHayProvider", "Determined mainTvType: $mainTvType (IsCN: $isChineseAnimation, HasEps: $hasEpisodes)")

            val animehayPoster = this.selectFirst("div.head div.first img")?.attr("src")
            val animehayFinalPosterUrl = fixUrl(animehayPoster, baseUrl)
            Log.d("AnimeHayProvider", "AnimeHay Poster URL (fixed): $animehayFinalPosterUrl")

            val finalPosterUrl = if (mainTvType == TvType.Anime || mainTvType == TvType.AnimeMovie || mainTvType == TvType.OVA) {
                Log.d("AnimeHayProvider", "Attempting to fetch Kitsu poster for '$title' (Type: $mainTvType)")
                val kitsuPoster = getKitsuPoster(title)
                kitsuPoster ?: animehayFinalPosterUrl
            } else {
                Log.d("AnimeHayProvider", "Skipping Kitsu search for type $mainTvType")
                animehayFinalPosterUrl
            }

            if (finalPosterUrl.isNullOrBlank()) {
                Log.e("AnimeHayProvider", "Failed to get any valid poster for $title (AnimeHay fallback: $animehayFinalPosterUrl)")
            } else if (finalPosterUrl != animehayFinalPosterUrl) {
                Log.i("AnimeHayProvider", "Using Kitsu poster for \"$title\": $finalPosterUrl")
            } else {
                Log.i("AnimeHayProvider", "Using AnimeHay poster for \"$title\": $finalPosterUrl")
            }

            val description = this.selectFirst("div.desc > div:last-child")?.text()?.trim()
            val yearText = this.selectFirst("div.update_time div:nth-child(2)")?.text()?.trim()
            val year = yearText?.filter { it.isDigit() }?.toIntOrNull()
            Log.d("AnimeHayProvider", "Parsed Year: $year (from text: '$yearText')")

            val ratingText = this.selectFirst("div.score div:nth-child(2)")?.text()?.trim()
            val rating = ratingText?.split("||")?.getOrNull(0)?.trim()?.toDoubleOrNull()?.takeIf { !it.isNaN() }?.toAnimeHayRatingInt()
            Log.d("AnimeHayProvider", "Parsed Rating (0-10000): $rating (from text: '$ratingText')")

            val statusText = this.selectFirst("div.status div:nth-child(2)")?.text()?.trim()
            val status = when {
                statusText?.contains("Hoàn thành", ignoreCase = true) == true -> ShowStatus.Completed
                statusText?.contains("Đang tiến hành", ignoreCase = true) == true -> ShowStatus.Ongoing
                statusText?.contains("Đang cập nhật", ignoreCase = true) == true -> ShowStatus.Ongoing
                else -> null
            }
            Log.d("AnimeHayProvider", "Parsed Status: $status (from text: '$statusText')")

            val episodes = this.select("div.list-item-episode a")?.mapNotNull { episodeLink ->
                val epUrl = fixUrl(episodeLink.attr("href"), baseUrl)
                val epNameSpan = episodeLink.selectFirst("span")?.text()?.trim()
                val epTitleAttr = episodeLink.attr("title")?.trim()
                val epNum = epNameSpan?.filter { it.isDigit() }?.toIntOrNull()
                val finalEpName = epTitleAttr?.takeIf { it.isNotBlank() } ?: epNameSpan?.takeIf { it.isNotBlank() } ?: epNum?.let { "Tập $it" }

                if (!finalEpName.isNullOrEmpty() && !epUrl.isNullOrEmpty()) {
                    newEpisode(data = epUrl) {
                        this.name = finalEpName
                        this.episode = null
                    }
                } else { null }
            }?.reversed() ?: emptyList()
            Log.d("AnimeHayProvider", "Parsed ${episodes.size} episodes.")

            val recommendations = this.select("div.movie-recommend div.movie-item")?.mapNotNull {
                it.toSearchResponse(provider, baseUrl)
            } ?: emptyList()
            Log.d("AnimeHayProvider", "Parsed ${recommendations.size} recommendations.")

            return if (hasEpisodes) {
                Log.i("AnimeHayProvider", "Creating TvSeriesLoadResponse for \"$title\"")
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
                Log.i("AnimeHayProvider", "Creating MovieLoadResponse for \"$title\"")
                val movieData = url
                val durationText = this.selectFirst("div.duration div:nth-child(2)")?.text()?.trim()
                val durationMinutes = durationText?.filter { it.isDigit() }?.toIntOrNull()
                Log.d("AnimeHayProvider", "Parsed Duration (minutes): $durationMinutes (from text: '$durationText')")

                provider.newMovieLoadResponse(title, url, mainTvType, movieData) {
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
            Log.e("AnimeHayProvider", "Failed to encode URI: $this", e)
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
                url.startsWith("/") -> baseUrl.trimEnd('/') + url // Ensure baseUrl doesn't end with / before appending
                else -> URL(URL(baseUrl), url).toString()
            }
        } catch (e: Exception) {
            Log.e("AnimeHayProvider", "Failed to fix URL: base='$baseUrl', url='$url'", e)
            if(url.startsWith("http")) url else null
        }
    }
} // Kết thúc class AnimeHayProvider
