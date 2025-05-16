// === File: AnimeVietsubProvider.kt ===
// Version: 2025-05-16 - Cập nhật proxy và hoàn thiện code

package recloudstream // Đảm bảo package name phù hợp với dự án của bạn

// === Imports ===
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addDuration
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import android.util.Log
import com.fasterxml.jackson.annotation.JsonProperty
import com.google.gson.Gson
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.net.URLEncoder
import java.net.URL
import kotlin.math.roundToInt
import kotlin.text.Regex

// Imports cho RequestBody và MediaType (Cần cho loadLinks)
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.MediaType.Companion.toMediaTypeOrNull

// === Provider Class ===
class AnimeVietsubProvider : MainAPI() {

    // Tạo một instance Gson để tái sử dụng
    private val gson = Gson()

    // USER_AGENT (quan trọng cho nhiều request, bao gồm cả proxy headers)
    private val USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/100.0.0.0 Safari/537.36"

    // Thông tin cơ bản của Provider
    override var mainUrl = "https://animevietsub.lol" // URL mặc định, sẽ được cập nhật bởi getBaseUrl
    override var name = "AnimeVietsub"
    override val supportedTypes = setOf(TvType.Anime)
    override var lang = "vi"
    override val hasMainPage = true

    // --- Phần xử lý domain động ---
    private var currentActiveUrl = mainUrl // Khởi tạo bằng mainUrl
    private var domainCheckPerformed = false
    private val domainCheckUrls = listOf("https://animevietsub.lol") // Có thể thêm mirror khác vào đây

    private suspend fun getBaseUrl(): String {
        if (domainCheckPerformed) return currentActiveUrl
        var fetchedNewUrl: String? = null
        val urlsToCheck = listOf(currentActiveUrl) + domainCheckUrls.filter { it != currentActiveUrl }
        Log.d("AnimeVietsubProvider", "Starting domain check. URLs to check: $urlsToCheck")
        for (checkUrl in urlsToCheck) {
            try {
                Log.d("AnimeVietsubProvider", "Checking domain via $checkUrl")
                val response = app.get(checkUrl, allowRedirects = true, timeout = 10)
                val finalUrlString = response.url
                val urlObject = URL(finalUrlString)
                val extractedBaseUrl = "${urlObject.protocol}://${urlObject.host}"
                if (extractedBaseUrl.startsWith("http")) {
                    fetchedNewUrl = extractedBaseUrl
                    Log.d("AnimeVietsubProvider", "Successfully resolved $checkUrl to $fetchedNewUrl")
                    break
                } else {
                    Log.w("AnimeVietsubProvider", "Invalid URL scheme obtained from $checkUrl -> $finalUrlString")
                }
            } catch (e: Exception) {
                Log.e("AnimeVietsubProvider", "Failed to check domain from $checkUrl. Error: ${e.message}")
            }
        }
        if (fetchedNewUrl != null && fetchedNewUrl != currentActiveUrl) {
            Log.i("AnimeVietsubProvider", "Domain updated: $currentActiveUrl -> $fetchedNewUrl")
            currentActiveUrl = fetchedNewUrl
            mainUrl = currentActiveUrl // Cập nhật cả mainUrl của provider
        } else if (fetchedNewUrl == null) {
            Log.w("AnimeVietsubProvider", "All domain check URLs failed. Using last known URL: $currentActiveUrl")
        } else {
            Log.d("AnimeVietsubProvider", "Domain check complete. Current active URL remains: $currentActiveUrl")
        }
        domainCheckPerformed = true
        return currentActiveUrl
    }
    // --- Kết thúc phần xử lý domain động ---

    // === Trang chủ ===
    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        if (page > 1) return newHomePageResponse(emptyList(), false) // Trang chủ không phân trang

        val lists = mutableListOf<HomePageList>()
        try {
            val baseUrl = getBaseUrl()
            Log.d("AnimeVietsubProvider", "Loading main page from $baseUrl")
            val document = app.get(baseUrl).document

            document.select("section#single-home ul.MovieList.Rows li.TPostMv")
                .mapNotNull { it.toSearchResponse(this, baseUrl) }
                .takeIf { it.isNotEmpty() }?.let { lists.add(HomePageList("Mới cập nhật", it)) }

            document.select("section#new-home ul.MovieList.Rows li.TPostMv")
                .mapNotNull { it.toSearchResponse(this, baseUrl) }
                .takeIf { it.isNotEmpty() }?.let { lists.add(HomePageList("Sắp chiếu", it)) }

            document.select("section#hot-home ul.MovieList.Rows li.TPostMv")
                .mapNotNull { it.toSearchResponse(this, baseUrl) }
                .takeIf { it.isNotEmpty() }?.let {
                    val hotListName = document.selectFirst("section#hot-home div.Top a.STPb.Current")?.text() ?: "Đề cử"
                    lists.add(HomePageList(hotListName, it))
                }

            if (lists.isEmpty()) {
                Log.w("AnimeVietsubProvider", "No lists found on homepage, check selectors or website structure.")
                throw ErrorLoadingException("Không thể tải dữ liệu trang chủ. Selector có thể đã thay đổi.")
            }
            return newHomePageResponse(lists, hasNext = false)
        } catch (e: Exception) {
            Log.e("AnimeVietsubProvider", "Error in getMainPage", e)
            if (e is ErrorLoadingException) throw e
            throw RuntimeException("Lỗi không xác định khi tải trang chủ: ${e.message}")
        }
    }

    // === Tìm kiếm ===
    override suspend fun search(query: String): List<SearchResponse> {
        try {
            val baseUrl = getBaseUrl()
            val searchUrl = "$baseUrl/tim-kiem/${query.encodeUri()}/"
            Log.d("AnimeVietsubProvider", "Searching for '$query' using URL: $searchUrl")
            val document = app.get(searchUrl).document
            return document.selectFirst("ul.MovieList.Rows")?.select("li.TPostMv")
                ?.mapNotNull { it.toSearchResponse(this, baseUrl) } ?: emptyList()
        } catch (e: Exception) {
            Log.e("AnimeVietsubProvider", "Error in search for query: $query", e)
            return emptyList()
        }
    }

    // === Lấy chi tiết ===
    override suspend fun load(url: String): LoadResponse? {
        val baseUrl = getBaseUrl()
        val infoUrl = url
        val watchPageUrl = if (infoUrl.endsWith("/")) "${infoUrl}xem-phim.html" else "$infoUrl/xem-phim.html"
        Log.d("AnimeVietsubProvider", "Loading details. Info URL: $infoUrl, Watch Page URL: $watchPageUrl")

        try {
            val infoDocument = app.get(infoUrl).document
            Log.d("AnimeVietsubProvider", "Successfully loaded info page: $infoUrl")
            var watchPageDocument: Document? = null
            try {
                watchPageDocument = app.get(watchPageUrl, referer = infoUrl).document
                Log.d("AnimeVietsubProvider", "Successfully loaded watch page: $watchPageUrl")
            } catch (e: Exception) {
                Log.w("AnimeVietsubProvider", "Failed to load watch page ($watchPageUrl), episode list might be unavailable via this method. Error: ${e.message}")
            }
            return infoDocument.toLoadResponse(this, infoUrl, baseUrl, watchPageDocument)
        } catch (e: Exception) {
            Log.e("AnimeVietsubProvider", "FATAL Error loading main info page ($infoUrl)", e)
            return null
        }
    }

    // === Helper parse Item thành SearchResponse ===
    private fun Element.toSearchResponse(provider: MainAPI, baseUrl: String): SearchResponse? {
        return try {
            val linkElement = this.selectFirst("article.TPost > a") ?: return null
            val relativeHref = linkElement.attr("href")
            val href = fixUrl(relativeHref, baseUrl) ?: return null
            val title = linkElement.selectFirst("h2.Title")?.text()?.trim()?.takeIf { it.isNotBlank() } ?: return null
            val posterUrl = fixUrl(linkElement.selectFirst("div.Image img")?.attr("src"), baseUrl)
            // Xác định TvType dựa trên sự hiện diện của thông tin tập hoặc chất lượng
            val isTvSeries = this.selectFirst("span.mli-eps") != null || this.selectFirst("span.mli-quality") == null
            val tvType = if (isTvSeries) TvType.TvSeries else TvType.Movie

            provider.newMovieSearchResponse(title, href, tvType) {
                this.posterUrl = posterUrl
            }
        } catch (e: Exception) {
            Log.e("AnimeVietsubProvider", "Error parsing search result item: ${this.html()}", e)
            null
        }
    }

    // Data class để lưu thông tin episode
    data class EpisodeData(
        val url: String, // URL đầy đủ của trang tập phim
        val dataId: String?,
        val duHash: String?
    )

    // === Helper parse Document thành LoadResponse ===
    private suspend fun Document.toLoadResponse(
        provider: MainAPI,
        infoUrl: String, // URL của trang thông tin phim (đã được fixUrl nếu cần từ search)
        baseUrl: String, // Base URL của trang web (ví dụ: https://animevietsub.lol)
        watchPageDoc: Document? // Document của trang xem phim (có thể null)
    ): LoadResponse? {
        val infoDoc = this // Document của trang thông tin phim
        try {
            Log.d("AnimeVietsubProvider", "Parsing metadata from info page: $infoUrl")
            val title = infoDoc.selectFirst("div.TPost.Single div.Title")?.text()?.trim()
                ?: infoDoc.selectFirst("meta[property=og:title]")?.attr("content")?.substringBefore(" Tập")?.trim()
                ?: run { Log.e("AnimeVietsubProvider", "Could not find title on info page $infoUrl"); return null }
            var posterUrl = infoDoc.selectFirst("div.TPost.Single div.Image img")?.attr("src")
                ?: infoDoc.selectFirst("meta[property=og:image]")?.attr("content")
            posterUrl = fixUrl(posterUrl, baseUrl) // Đảm bảo posterUrl là URL đầy đủ
            val description = infoDoc.selectFirst("div.TPost.Single div.Description")?.text()?.trim()
                ?: infoDoc.selectFirst("meta[property=og:description]")?.attr("content")
            val infoSection = infoDoc.selectFirst("div.Info") ?: infoDoc // Fallback về infoDoc nếu .Info không có
            val genres = infoSection.select("li:has(strong:containsOwn(Thể loại)) a[href*=the-loai]").mapNotNull { it.text()?.trim() }
            val yearText = infoSection.select("li:has(strong:containsOwn(Năm))")?.firstOrNull()?.ownText()?.trim()
            val year = yearText?.filter { it.isDigit() }?.toIntOrNull()
            val ratingText = infoSection.select("li:has(strong:containsOwn(Điểm))")?.firstOrNull()?.ownText()?.trim()?.substringBefore("/")
            val rating = ratingText?.toDoubleOrNull()?.toAnimeVietsubRatingInt()
            val statusText = infoSection.select("li:has(strong:containsOwn(Trạng thái))")?.firstOrNull()?.ownText()?.trim()
            val status = when {
                statusText?.contains("Đang tiến hành", ignoreCase = true) == true -> ShowStatus.Ongoing
                statusText?.contains("Hoàn thành", ignoreCase = true) == true -> ShowStatus.Completed
                else -> null
            }

            Log.d("AnimeVietsubProvider", "Parsing episodes from watch page document (if available)...")
            val episodes = if (watchPageDoc != null) {
                watchPageDoc.select("div.server ul.list-episode li a.btn-episode").mapNotNull { epLink ->
                    // epUrl phải là URL đầy đủ đến trang xem tập phim, dùng cho EpisodeData.url -> referer
                    val epUrl = fixUrl(epLink.attr("href"), baseUrl) ?: return@mapNotNull null
                    val epName = epLink.attr("title").ifBlank { epLink.text() }.trim()
                    val dataId = epLink.attr("data-id").ifBlank { null }
                    val duHash = epLink.attr("data-hash").ifBlank { null }

                    Log.v("AnimeVietsubProvider", "[Episode Parsing - Watch Page] Processing link: name='$epName', url='$epUrl', dataId='$dataId', hash='$duHash'")
                    // EpisodeData.url phải là URL đầy đủ của trang tập phim đó
                    val episodeData = EpisodeData(url = epUrl, dataId = dataId, duHash = duHash)
                    val episodeNumber = epName.replace(Regex("""[^\d]"""), "").toIntOrNull()

                    if (epName.isNotBlank() && dataId != null) { // dataId là bắt buộc cho API mới
                        newEpisode(data = gson.toJson(episodeData)) {
                            this.name = if (epName.contains("tập", ignoreCase = true) || epName.matches(Regex("^\\d+$"))) {
                                "Tập ${epName.replace("tập", "", ignoreCase = true).trim()}"
                            } else { epName }
                            this.episode = episodeNumber
                        }
                    } else {
                        Log.w("AnimeVietsubProvider", "[Episode Parsing - Watch Page] Skipping episode '$epName': Missing required attribute (URL, Name, or **data-id**). Element: ${epLink.outerHtml()}")
                        null
                    }
                }.sortedBy { it.episode ?: Int.MAX_VALUE }
            } else {
                Log.w("AnimeVietsubProvider", "[Episode Parsing - Watch Page] Watch page document was null. Cannot parse episodes using old method.")
                emptyList<Episode>()
            }
            Log.i("AnimeVietsubProvider", "[Episode Parsing - Watch Page] Finished parsing. Found ${episodes.size} valid episodes.")

            // Xác định loại TV/Movie
            val isTvSeries = episodes.size > 1 || infoSection.select("li:has(strong:containsOwn(Thể loại)) a[href*=anime-bo]").isNotEmpty()

            return if (isTvSeries) {
                Log.d("AnimeVietsubProvider", "Creating TvSeriesLoadResponse for '$title'")
                provider.newTvSeriesLoadResponse(title, infoUrl, TvType.TvSeries, episodes = episodes) {
                    this.posterUrl = posterUrl; this.plot = description; this.tags = genres; this.year = year; this.rating = rating; this.showStatus = status;
                }
            } else { // Movie hoặc OVA/Special (ít tập)
                Log.d("AnimeVietsubProvider", "Creating MovieLoadResponse for '$title'")
                val durationText = infoSection.select("li:has(strong:containsOwn(Thời lượng))")?.firstOrNull()?.ownText()?.trim()
                val durationMinutes = durationText?.filter { it.isDigit() }?.toIntOrNull()

                // Đối với phim lẻ, data cho loadLinks có thể lấy từ tập duy nhất nếu có,
                // hoặc fallback về infoUrl nếu không có tập nào được parse (ví dụ trang xem phim bị lỗi)
                // EpisodeData.url cho phim lẻ nên là infoUrl nếu không có epUrl cụ thể.
                val movieData = episodes.firstOrNull()?.data
                    ?: gson.toJson(EpisodeData(url = infoUrl, dataId = null, duHash = null)) // Fallback data quan trọng

                provider.newMovieLoadResponse(title, infoUrl, TvType.Movie, movieData) {
                    this.posterUrl = posterUrl; this.plot = description; this.tags = genres; this.year = year; this.rating = rating; durationMinutes?.let { addDuration(it.toString()) }
                }
            }
        } catch (e: Exception) {
            Log.e("AnimeVietsubProvider", "Error in toLoadResponse processing for url: $infoUrl", e); return null
        }
    }


    // Data class để parse response từ ajax/player (cho loadLinks)
    private data class AjaxPlayerResponse(
        @JsonProperty("success") val success: Int? = null,
        @JsonProperty("link") val link: List<LinkSource>? = null
    )

    private data class LinkSource(
        @JsonProperty("file") val file: String? = null // Chứa "dataenc"
    )

    // === Lấy link xem (Sử dụng API mới và proxy) ===
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        var foundLinks = false
        val currentSiteBaseUrl = getBaseUrl() // Ví dụ: https://animevietsub.lol
        val ajaxUrl = "$currentSiteBaseUrl/ajax/player?v=2019a"
        val decryptApiUrl = "https://m3u8.013666.xyz/animevietsub/decrypt"
        val textPlainMediaType = "text/plain".toMediaTypeOrNull()

        // --- Cấu hình Proxy ---
        val proxyServiceUrl = "https://m3u8-proxy-chi.vercel.app/m3u8-proxy"
        val useProxy = true // Đặt thành true để sử dụng proxy, false để dùng link trực tiếp
        // --------------------

        Log.d("AnimeVietsubProvider", "LoadLinks received data: $data")

        val episodeData = try {
            gson.fromJson(data, EpisodeData::class.java)
        } catch (e: Exception) {
            Log.e("AnimeVietsubProvider", "Failed to parse EpisodeData JSON in loadLinks: '$data'", e)
            return false
        }

        // episodeFullUrl là URL đầy đủ của trang tập phim, ví dụ: https://animevietsub.lol/phim/ten-phim/tap-1.html
        // Nó rất quan trọng để làm Referer.
        val episodeFullUrl = episodeData.url
        val episodeId = episodeData.dataId
        val episodeHash = episodeData.duHash

        if (episodeId == null || episodeFullUrl.isBlank()) {
            Log.e("AnimeVietsubProvider", "Missing episode ID or full episode URL in episode data: $data. Cannot proceed.")
            return false
        }
        // Kiểm tra sơ bộ episodeFullUrl có phải là URL hợp lệ không
        try {
            URL(episodeFullUrl)
        } catch (e: MalformedURLException) {
            Log.e("AnimeVietsubProvider", "Invalid episodeFullUrl in episode data: '$episodeFullUrl'", e)
            return false
        }


        Log.i("AnimeVietsubProvider", "Processing episode ID: $episodeId for episode page: $episodeFullUrl")

        try {
            // --- Bước 2: Gửi POST đến ajax/player ---
            val postData = mapOf("id" to episodeId, "play" to "api", "link" to (episodeHash ?: ""), "backuplinks" to "1")
            val headersForAjax = mapOf(
                "Content-Type" to "application/x-www-form-urlencoded; charset=UTF-8",
                "Accept" to "*/*", "X-Requested-With" to "XMLHttpRequest",
                "User-Agent" to USER_AGENT,
                "Referer" to episodeFullUrl // Referer là trang tập phim hiện tại
            )
            Log.d("AnimeVietsubProvider", "POSTing to AnimeVietsub AJAX API: $ajaxUrl with data: $postData")
            val ajaxResponse = app.post(ajaxUrl, data = postData, headers = headersForAjax, referer = episodeFullUrl)
            Log.d("AnimeVietsubProvider", "AnimeVietsub AJAX API Response Status: ${ajaxResponse.code}")

            // --- Bước 3: Parse response JSON, lấy dataEnc ---
            val playerResponse = try { gson.fromJson(ajaxResponse.text, AjaxPlayerResponse::class.java) } catch (e: Exception) { Log.e("AnimeVietsubProvider", "Failed to parse ajax/player response JSON: ${ajaxResponse.text}", e); null }
            if (playerResponse?.success != 1 || playerResponse.link.isNullOrEmpty()) { Log.e("AnimeVietsubProvider", "ajax/player request failed or response invalid: ${ajaxResponse.text}"); return false }
            val dataEnc = playerResponse.link.firstOrNull()?.file
            if (dataEnc.isNullOrBlank()) { Log.e("AnimeVietsubProvider", "Could not find 'dataenc' in ajax/player response: ${ajaxResponse.text}"); return false }
            Log.d("AnimeVietsubProvider", "Successfully got 'dataenc': ${dataEnc.take(50)}...")

            // --- Bước 4: Gọi API giải mã ---
            Log.d("AnimeVietsubProvider", "POSTing 'dataenc' to Decryption API: $decryptApiUrl")
            val requestBody = dataEnc.toByteArray().toRequestBody(textPlainMediaType)
            val decryptHeaders = mapOf("User-Agent" to USER_AGENT, "Referer" to ajaxUrl) // Thêm Referer cho decrypt API
            val decryptResponse = app.post(decryptApiUrl, headers = decryptHeaders, requestBody = requestBody)
            Log.d("AnimeVietsubProvider", "Decryption API Response Status: ${decryptResponse.code}")

            // --- Bước 5: Lấy link M3U8 cuối cùng ---
            val finalM3u8Url = decryptResponse.text.trim()

            // API luôn trả về M3U8 theo xác nhận
            if (finalM3u8Url.startsWith("https://") && finalM3u8Url.contains(".m3u8")) {
                Log.i("AnimeVietsubProvider", "Successfully obtained final M3U8 link: $finalM3u8Url")

                val m3u8UrlToLoadInPlayer: String
                val headersForExtractorLink: Map<String, String>

                if (useProxy) {
                    // Headers mà proxy sẽ sử dụng để fetch M3U8 gốc.
                    // Key "referer" (viết thường) theo ví dụ proxy mới.
                    // User-Agent và Origin vẫn được thêm vào, giả sử proxy sẽ chuyển tiếp chúng.
                    // Nếu proxy này chỉ xử lý "referer" hoặc gặp lỗi, có thể bỏ User-Agent/Origin.
                    val headersForProxyToUse = mapOf(
                        "referer" to episodeFullUrl,
                        "User-Agent" to USER_AGENT,
                        "Origin" to currentSiteBaseUrl
                    )
                    
                    val encodedFinalM3u8Url = finalM3u8Url.encodeUri()
                    val encodedHeadersJson = gson.toJson(headersForProxyToUse).encodeUri()

                    m3u8UrlToLoadInPlayer = "$proxyServiceUrl?url=$encodedFinalM3u8Url&headers=$encodedHeadersJson"
                    
                    Log.d("AnimeVietsubProvider", "Using new proxied M3U8 URL: $m3u8UrlToLoadInPlayer")
                    Log.d("AnimeVietsubProvider", "Original M3U8 for proxy: $finalM3u8Url")
                    Log.d("AnimeVietsubProvider", "Headers for proxy to use (JSON source for encoding): ${gson.toJson(headersForProxyToUse)}")

                    headersForExtractorLink = mapOf(
                        "User-Agent" to USER_AGENT
                        // Referer đến proxy sẽ được ExtractorLink tự động thêm từ trường 'referer'.
                    )
                } else {
                    // Sử dụng link M3U8 trực tiếp
                    m3u8UrlToLoadInPlayer = finalM3u8Url
                    Log.d("AnimeVietsubProvider", "Using direct M3U8 URL: $m3u8UrlToLoadInPlayer")
                    headersForExtractorLink = mapOf(
                        "User-Agent" to USER_AGENT,
                        "Origin" to currentSiteBaseUrl // Origin cho server M3U8 trực tiếp
                        // Referer đến M3U8 server sẽ được ExtractorLink tự động thêm.
                    )
                }

                val extractorLinkObject = ExtractorLink(
                    source = name, // Tên provider
                    name = if (useProxy) "$name (Proxy)" else "$name API", // Tên server
                    url = m3u8UrlToLoadInPlayer, // URL sẽ được load bởi player
                    referer = episodeFullUrl,    // Referer cho request đến 'url'
                    quality = Qualities.Unknown.value,
                    type = ExtractorLinkType.M3U8, // Luôn là M3U8
                    headers = headersForExtractorLink // Headers bổ sung cho request đến 'url'
                )
                callback(extractorLinkObject)
                foundLinks = true

            } else {
                Log.e("AnimeVietsubProvider", "Decryption API did not return a valid M3U8 URL. Response was: ${decryptResponse.text}")
            }

        } catch (e: Exception) {
            Log.e("AnimeVietsubProvider", "Error during link extraction process for episode ID $episodeId", e)
        }

        if (!foundLinks) {
            Log.w("AnimeVietsubProvider", "No stream links were successfully extracted for episode ID $episodeId ($episodeFullUrl)")
        }
        return foundLinks
    }


    // === Các hàm hỗ trợ ===

    // Mã hóa URL component (tương đương encodeURIComponent của JavaScript)
    private fun String?.encodeUri(): String {
        if (this == null) return ""
        return try {
            URLEncoder.encode(this, "UTF-8").replace("+", "%20")
        } catch (e: Exception) {
            Log.e("AnimeVietsubProvider", "Failed to URL encode: $this", e)
            this // Trả về chuỗi gốc nếu lỗi
        }
    }

    // Chuyển đổi điểm đánh giá sang thang 1000
    private fun Double?.toAnimeVietsubRatingInt(): Int? {
        return this?.let { (it * 100).roundToInt().coerceIn(0, 1000) }
    }

    // Sửa lỗi URL tương đối hoặc thiếu scheme, đảm bảo trả về URL đầy đủ
    private fun fixUrl(url: String?, baseUrl: String): String? {
        if (url.isNullOrBlank()) return null
        return try {
            when {
                url.startsWith("http") -> url
                url.startsWith("//") -> "https:$url"
                url.startsWith("/") -> {
                    val base = URL(baseUrl) // Đảm bảo baseUrl là một URL hợp lệ
                    URL(base, url).toString()
                }
                // Xử lý path tương đối không bắt đầu bằng '/'
                else -> {
                     val base = URL(baseUrl)
                     // Lấy path của baseUrl, nếu không có thì dùng "/"
                     // Ví dụ: baseUrl = "https://example.com/folder1/page.html", url = "image.jpg" -> "https://example.com/folder1/image.jpg"
                     // Ví dụ: baseUrl = "https://example.com/folder1/", url = "image.jpg" -> "https://example.com/folder1/image.jpg"
                     // Ví dụ: baseUrl = "https://example.com", url = "image.jpg" -> "https://example.com/image.jpg"
                     val basePath = if (base.path.isNullOrEmpty() || !base.path.contains('/') || base.path.endsWith("/")) {
                         base.path ?: "/" // Nếu path rỗng, hoặc không có / (chỉ domain), hoặc đã kết thúc bằng /
                     } else {
                         base.path.substringBeforeLast('/') + "/"
                     }
                     // Đảm bảo basePath kết thúc bằng / nếu nó không rỗng và chưa có
                     val properlyEndedBasePath = if (basePath.isNotEmpty() && !basePath.endsWith("/")) "$basePath/" else basePath

                     URL(base, (properlyEndedBasePath + url).replace("//", "/")).toString() // Nối và chuẩn hóa //
                }
            }
        } catch (e: java.net.MalformedURLException) {
            Log.e("AnimeVietsubProvider", "MalformedURLException in fixUrl: base=$baseUrl, url=$url", e)
            // Thử nối trực tiếp nếu các cách trên lỗi và url không chứa scheme (cách cũ hơn)
            if (!url.contains("://")) {
                return try {
                    URL(URL(baseUrl), "/$url".removePrefix("//")).toString()
                } catch (e2: Exception) {
                    Log.e("AnimeVietsubProvider", "Retry fixUrl (old method) also failed: base=$baseUrl, url=$url", e2)
                    null
                }
            }
            null
        } catch (e: Exception) {
            Log.e("AnimeVietsubProvider", "Failed to fix URL: base=$baseUrl, url=$url", e)
            null
        }
    }

} // <--- Dấu ngoặc nhọn ĐÓNG class AnimeVietsubProvider
