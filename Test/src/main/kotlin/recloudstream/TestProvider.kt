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
import okhttp3.Interceptor // *** THÊM IMPORT NÀY ***
import okhttp3.ResponseBody.Companion.toResponseBody // *** THÊM IMPORT NÀY ***
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.net.MalformedURLException
import java.net.URL
import java.net.URLEncoder
import kotlin.math.roundToInt

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
    
    // =================================================================================================
    // *** BẮT ĐẦU PHẦN SỬA LỖI: THÊM INTERCEPTOR ĐỂ SỬA VIDEO TỪ SERVER TOK ***
    // =================================================================================================
    override fun getVideoInterceptor(extractorLink: ExtractorLink): Interceptor? {
        return Interceptor { chain ->
            val request = chain.request()
            val response = chain.proceed(request)
            val url = request.url.toString()

            // Các CDN của server TOK thường thêm 2 bytes rác vào đầu mỗi segment video
            val needsFix = listOf(
                "ibyteimg.com",
                ".tiktokcdn.",
                "segment.cloudbeta.win/file/segment/"
            ).any { url.contains(it, ignoreCase = true) }

            // Tránh xử lý các trang HTML trả về lỗi (có token)
            val isHtmlErrorPage = url.contains(".html?token=", ignoreCase = true)

            if (needsFix && !isHtmlErrorPage) {
                Log.d("AnimeHayProvider", "Interceptor đang xử lý URL: $url")
                val originalBody = response.body
                if (originalBody != null) {
                    try {
                        // Đọc toàn bộ bytes của response và bỏ đi 2 bytes đầu tiên
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
        Log.d("AnimeHayProvider", "getMainPage called with page: $page, for list: ${request.name}")

        try {
            val siteBaseUrl = getBaseUrl() 
            val urlToFetch = if (page <= 1) {
                siteBaseUrl 
            } else {
                "$siteBaseUrl/phim-moi-cap-nhap/trang-$page.html"
            }

            Log.d("AnimeHayProvider", "Attempting to fetch document for page $page from URL: $urlToFetch")
            val document = app.get(urlToFetch).document
            Log.d("AnimeHayProvider", "Document for page $page fetched successfully. Title: ${document.title()}")

            val homePageItems = document.select("div.movies-list div.movie-item")
                .mapNotNull { element ->
                    element.toSearchResponse(this, siteBaseUrl) 
                }
            Log.d("AnimeHayProvider", "Found ${homePageItems.size} items for page $page")

            if (page > 1 && homePageItems.isEmpty()) {
                Log.w("AnimeHayProvider", "No items found on page $page ($urlToFetch). Assuming no more pages.")
                return newHomePageResponse(emptyList(), false)
            }

            var calculatedHasNext = false
            val pagination = document.selectFirst("div.pagination") 

            if (pagination != null) {
                Log.d("AnimeHayProvider", "Pagination div found for page $page. HTML snapshot: ${pagination.html().take(150)}...")
                val activePageElement = pagination.selectFirst("a.active_page")
                val currentPageFromHtml = activePageElement?.text()?.toIntOrNull() ?: page
                Log.d("AnimeHayProvider", "HASNEXT_DEBUG (Page $page): Current page interpreted as: $currentPageFromHtml.")
                val nextPageSelector = "a[href*=/trang-${currentPageFromHtml + 1}.html]"
                val nextPageLink = pagination.selectFirst(nextPageSelector)

                if (nextPageLink != null) {
                    calculatedHasNext = true
                    Log.d("AnimeHayProvider", "HASNEXT_DEBUG (Page $page): Next page link found (for page ${currentPageFromHtml + 1}). Selector: '$nextPageSelector'. Setting hasNext = true.")
                } else {
                    Log.d("AnimeHayProvider", "HASNEXT_DEBUG (Page $page): No next page link found (for page ${currentPageFromHtml + 1}). Selector: '$nextPageSelector'. Setting hasNext = false.")
                }
            } else {
                Log.w("AnimeHayProvider", "No div.pagination found on page $page ($urlToFetch). Setting hasNext = false.")
            }

            Log.i("AnimeHayProvider", "Final calculatedHasNext for page $page: $calculatedHasNext")
            
            val listTitle = request.name.ifBlank { "Mới cập nhật" } 
            val homeList = HomePageList(listTitle, homePageItems)
            return newHomePageResponse(listOf(homeList), hasNext = calculatedHasNext)

        } catch (e: Exception) {
            Log.e("AnimeHayProvider", "Error in getMainPage for page $page: ${e.message}", e)
            e.printStackTrace()
            return newHomePageResponse(emptyList(), false)
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
                            // *** SỬA LỖI: THAY ĐỔI REFERER ĐỂ GIỐNG PHIÊN BẢN HOẠT ĐỘNG ***
                            this.referer = baseUrl 
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
