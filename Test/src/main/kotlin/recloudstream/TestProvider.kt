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
import okhttp3.Interceptor
import okhttp3.Response
import okhttp3.ResponseBody
import java.util.EnumSet


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

                // Xác định trang hiện tại từ HTML (nếu có class 'active_page'), nếu không thì dùng 'page' từ input
                val activePageElement = pagination.selectFirst("a.active_page")
                val currentPageFromHtml = activePageElement?.text()?.toIntOrNull() ?: page
                Log.d("AnimeHayProvider", "HASNEXT_DEBUG (Page $page): Current page interpreted as: $currentPageFromHtml.")

                // Kiểm tra duy nhất: Tìm link đến trang kế tiếp (currentPageFromHtml + 1)
                // Ví dụ: nếu đang ở trang 1 (currentPageFromHtml = 1), tìm link có href chứa "/trang-2.html"
                val nextPageSelector = "a[href*=/trang-${currentPageFromHtml + 1}.html]"
                val nextPageLink = pagination.selectFirst(nextPageSelector)

                if (nextPageLink != null) {
                    calculatedHasNext = true
                    Log.d("AnimeHayProvider", "HASNEXT_DEBUG (Page $page): Next page link found (for page ${currentPageFromHtml + 1}). Selector: '$nextPageSelector'. Setting hasNext = true.")
                } else {
                    // calculatedHasNext vẫn là false
                    Log.d("AnimeHayProvider", "HASNEXT_DEBUG (Page $page): No next page link found (for page ${currentPageFromHtml + 1}). Selector: '$nextPageSelector'. Setting hasNext = false.")
                }
            } else {
                Log.w("AnimeHayProvider", "No div.pagination found on page $page ($urlToFetch). Setting hasNext = false.")
                // calculatedHasNext vẫn là false
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

    val directUrl = URL(data).let { "${it.protocol}://${it.host}" }
    // Lấy nội dung trang xem phim
    val response = app.get(data, referer = directUrl).text
    val sources = mutableMapOf<String, String>()

    // 1. Server TOK
    // Tìm link M3U8 từ biến 'tik' trong script
    val tokLink = response.substringAfter("tik: '", "").substringBefore("',", "")
    if (tokLink.isNotBlank()) {
        sources["Server TOK"] = tokLink
    }

    // 2. Server GUN
    // Tìm ID từ link 'gun.php' và xây dựng lại URL playlist
    val gunId = response.substringAfter("$directUrl/gun.php?id=", "").substringBefore("&ep_id=", "")
    if (gunId.isNotBlank()) {
        sources["Server GUN"] = "https://pt.rapovideo.xyz/playlist/v2/$gunId/master.m3u8"
    }

    // 3. Server PHO
    // Tìm ID từ link 'pho.php' và xây dựng lại URL playlist
    val phoId = response.substringAfter("$directUrl/pho.php?id=", "").substringBefore("&ep_id=", "")
    if (phoId.isNotBlank()) {
        sources["Server PHO"] = "https://pt.rapovideo.xyz/playlist/$phoId/master.m3u8"
    }

    // Gửi tất cả các link đã tìm thấy về cho Cloudstream
    sources.forEach { (serverName, url) ->
        callback(
            // SỬA LỖI: Đặt referer và quality vào trong khối lambda
            newExtractorLink(
                source = name, // Tên của provider
                name = serverName,
                url = url,
                type = ExtractorLinkType.M3U8
            ) {
                // 'this' ở đây tham chiếu đến đối tượng ExtractorLink đang được tạo
                this.referer = "$directUrl/"
                this.quality = Qualities.Unknown.value
            }
        )
    }
    
    // Trả về true nếu tìm thấy ít nhất một link
    return sources.isNotEmpty()
}

/**
     * Interceptor để sửa lỗi phát video từ một số server. Rất quan trọng!
     */
    override fun getVideoInterceptor(extractorLink: ExtractorLink): Interceptor? {
        return object : Interceptor {
            override fun intercept(chain: Interceptor.Chain): Response {
                val request = chain.request()
                val response = chain.proceed(request)
                val url = request.url.toString()

                // Chỉ áp dụng cho các link video cần sửa lỗi
                if (url.contains("ibyteimg.com") ||
                    url.contains(".tiktokcdn.") ||
                    (url.contains("segment.cloudbeta.win/file/segment/") && url.contains(".html?token="))
                ) {
                    val body = response.body
                    if (body != null) {
                        try {
                            // Bỏ qua các byte lỗi ở đầu file video
                            val fixedBytes = skipByteError(body) 
                            val newBody = ResponseBody.create(body.contentType(), fixedBytes)
                            return response.newBuilder().body(newBody).build()
                        } catch (e: Exception) {
                            // Bỏ qua và trả về response gốc nếu có lỗi
                        }
                    }
                }
                return response
            }
        }
    }

    private fun Element.toSearchResponse(provider: MainAPI, baseUrl: String): AnimeSearchResponse? {
    return runCatching {
        val element = this

        val linkElement = element.selectFirst("> a[href], a[href*=thong-tin-phim]") ?: return null
        
        val href = fixUrl(linkElement.attr("href"), baseUrl) ?: return null

        val title = element.selectFirst("div.name-movie")?.text()?.takeIf { it.isNotBlank() }
            ?: linkElement.attr("title").takeIf { it.isNotBlank() } ?: return null

        val posterUrl = fixUrl(element.selectFirst("img")?.let { it.attr("src").ifBlank { it.attr("data-src") } }, baseUrl)

        val tvType = if (href.contains("/phim/", true)) TvType.AnimeMovie else TvType.Anime

        provider.newAnimeSearchResponse(name = title, url = href, type = tvType) {
            this.posterUrl = posterUrl
            
            // 1. Hiển thị tag "Phụ đề"
            this.dubStatus = EnumSet.of(DubStatus.Subbed)

            val episodeText = element.selectFirst("div.episode-latest span")?.text()?.trim()
            if (episodeText != null) {
                // 2. Hiển thị tag số tập
                val episodeCount = episodeText.substringBefore("/")
                                              .substringBefore("-")
                                              .filter { it.isDigit() }
                                              .toIntOrNull()
                if (episodeCount != null) {
                    this.episodes[DubStatus.Subbed] = episodeCount
                }

                // ==================== PHẦN THÊM MỚI ====================
                // 3. Hiển thị trạng thái qua 'otherName'
                var statusTag: String? = null
                val cleanText = episodeText.replace(" ", "")
                when {
                    cleanText.contains("SP/", ignoreCase = true) || cleanText.contains("OVA/", ignoreCase = true) -> {
                        statusTag = "Hoàn thành"
                    }
                    cleanText.contains('/') -> {
                        val parts = cleanText.split('/')
                        if (parts.size == 2) {
                            val currentEp = parts[0].toFloatOrNull()
                            val totalEp = parts[1].filter { it.isDigit() || it == '.' }.toFloatOrNull()
                            if (currentEp != null && totalEp != null && currentEp >= totalEp) {
                                statusTag = "Hoàn thành"
                            }
                        }
                    }
                    cleanText.contains("phút", ignoreCase = true) -> {
                         statusTag = "Hoàn thành"
                    }
                    cleanText.contains("drop", ignoreCase = true) -> {
                        statusTag = "Bị Drop"
                    }
                }
                this.otherName = statusTag
                // ==========================================================
            }
        }
    }.getOrNull()
}
    
    // ==================== THAY ĐỔI LỚN TẠI HÀM NÀY ====================
    private suspend fun Document.toLoadResponse(provider: MainAPI, url: String, baseUrl: String): LoadResponse? {
        try {
            val titleElement = this.selectFirst("h1.heading_movie")
            val title = titleElement?.text()?.trim() ?: run {
                Log.e("AnimeHayProvider", "Title (h1.heading_movie) not found for $url")
                return null
            }
            Log.d("AnimeHayProvider", "Parsing LoadResponse for: \"$title\"")

            // --- Lấy tất cả thông tin cần thiết trước ---
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
            val finalPosterUrl = if (mainTvType == TvType.Anime || mainTvType == TvType.AnimeMovie || mainTvType == TvType.OVA) {
                getKitsuPoster(title) ?: animehayFinalPosterUrl
            } else {
                animehayFinalPosterUrl
            }

            val description = this.selectFirst("div.desc > div:last-child")?.text()?.trim()
            val yearText = this.selectFirst("div.update_time div:nth-child(2)")?.text()?.trim()
            val year = yearText?.filter { it.isDigit() }?.toIntOrNull()

            val ratingText = this.selectFirst("div.score div:nth-child(2)")?.text()?.trim()
            val rating = ratingText?.split("||")?.getOrNull(0)?.trim()?.toDoubleOrNull()?.takeIf { !it.isNaN() }?.toAnimeHayRatingInt()

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
                val epNum = epNameSpan?.filter { it.isDigit() }?.toIntOrNull()
                val finalEpName = epTitleAttr?.takeIf { it.isNotBlank() } ?: epNameSpan?.takeIf { it.isNotBlank() } ?: epNum?.let { "Tập $it" }

                if (!finalEpName.isNullOrEmpty() && !epUrl.isNullOrEmpty()) {
                    newEpisode(data = epUrl) {
                        this.name = finalEpName
                        this.episode = null
                    }
                } else { null }
            }?.reversed() ?: emptyList()

            val recommendations = this.select("div.movie-recommend div.movie-item")?.mapNotNull {
                it.toSearchResponse(provider, baseUrl)
            } ?: emptyList()

            // --- Sử dụng newAnimeLoadResponse cho cả phim bộ và phim lẻ ---
            Log.i("AnimeHayProvider", "Creating AnimeLoadResponse for \"$title\"")
            return provider.newAnimeLoadResponse(title, url, mainTvType) {
                this.posterUrl = finalPosterUrl
                this.plot = description
                this.tags = genres
                this.year = year
                this.rating = rating
                this.showStatus = status
                this.recommendations = recommendations

                if (hasEpisodes) {
                    // Nếu là phim bộ, thêm danh sách tập phim
                    addEpisodes(DubStatus.Subbed, episodes)
                } else {
                    // Nếu là phim lẻ, thêm thời lượng
                    val durationText = this@toLoadResponse.selectFirst("div.duration div:nth-child(2)")?.text()?.trim()
                    val durationMinutes = durationText?.filter { it.isDigit() }?.toIntOrNull()
                    durationMinutes?.let { addDuration(it.toString()) }
                }
            }
        } catch (e: Exception) {
            Log.e("AnimeHayProvider", "Error in toLoadResponse for url: $url", e)
            return null
        }
    }
    // =================================================================

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

/**
 * Hàm phụ trợ cho interceptor, đặt ở ngoài class.
 * Dùng để sửa lỗi video bằng cách bỏ qua các byte lỗi ở đầu.
 */
private fun skipByteError(responseBody: ResponseBody): ByteArray {
    val source = responseBody.source()
    source.request(Long.MAX_VALUE)
    val buffer = source.buffer.clone()
    source.close()

    val byteArray = buffer.readByteArray()
    val length = byteArray.size - 188
    var start = 0
    for (i in 0 until length) {
        val nextIndex = i + 188
        if (nextIndex < byteArray.size && byteArray[i].toInt() == 71 && byteArray[nextIndex].toInt() == 71) {
            start = i
            break
        }
    }
    return if (start > 0) byteArray.copyOfRange(start, byteArray.size) else byteArray
}
