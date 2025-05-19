// === File: AnimeVietsubProvider.kt ===
// Version: 2025-05-03 - Fix lỗi type inference - Bản đầy đủ

package recloudstream // Đảm bảo package name phù hợp với dự án của bạn

// === Imports ===
// import android.content.Context // Bỏ nếu không dùng Context trực tiếp
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addDuration
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.loadExtractor // Cần nếu muốn thêm fallback
import com.lagradost.cloudstream3.utils.newExtractorLink // Dù không dùng nữa nhưng có thể cần cho fallback
import android.util.Log
// import com.lagradost.cloudstream3.plugins.CloudstreamPlugin // Bỏ nếu không phải plugin context
// import com.lagradost.cloudstream3.plugins.Plugin // Bỏ nếu không phải plugin context
import com.fasterxml.jackson.annotation.JsonProperty // Cần cho data class nếu dùng Jackson thay Gson
import com.google.gson.Gson // Cần cho Gson
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
        // Chỉ kiểm tra một lần mỗi khi khởi tạo provider instance
        if (domainCheckPerformed) return currentActiveUrl
        var fetchedNewUrl: String? = null
        val urlsToCheck = listOf(currentActiveUrl) + domainCheckUrls.filter { it != currentActiveUrl }
        Log.d("AnimeVietsubProvider", "Starting domain check. URLs to check: $urlsToCheck")
        for (checkUrl in urlsToCheck) {
            try {
                Log.d("AnimeVietsubProvider", "Checking domain via $checkUrl")
                val response = app.get(checkUrl, allowRedirects = true, timeout = 10) // Thêm timeout ngắn
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
            val baseUrl = getBaseUrl() // Lấy URL đã được kiểm tra
            Log.d("AnimeVietsubProvider", "Loading main page from $baseUrl")
            val document = app.get(baseUrl).document

            // Parse các section trên trang chủ
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
                 // return newHomePageResponse(emptyList(), false) // Có thể trả về lỗi thay vì list rỗng
                 throw ErrorLoadingException("Không thể tải dữ liệu trang chủ. Selector có thể đã thay đổi.")
            }
            return newHomePageResponse(lists, hasNext = false)
        } catch (e: Exception) {
            Log.e("AnimeVietsubProvider", "Error in getMainPage", e)
            if (e is ErrorLoadingException) throw e // Ném lại lỗi đã biết
            throw RuntimeException("Lỗi không xác định khi tải trang chủ: ${e.message}") // Ném lỗi chung
        }
    }

    // === Tìm kiếm ===
    override suspend fun search(query: String): List<SearchResponse> {
        try {
            val baseUrl = getBaseUrl()
            val searchUrl = "$baseUrl/tim-kiem/${query.encodeUri()}/" // Mã hóa query
            Log.d("AnimeVietsubProvider", "Searching for '$query' using URL: $searchUrl")
            val document = app.get(searchUrl).document

            // Parse kết quả
            return document.selectFirst("ul.MovieList.Rows")?.select("li.TPostMv")
                ?.mapNotNull { it.toSearchResponse(this, baseUrl) } ?: emptyList()
        } catch (e: Exception) {
            Log.e("AnimeVietsubProvider", "Error in search for query: $query", e)
            return emptyList() // Trả về rỗng nếu có lỗi
        }
    }

    // === Lấy chi tiết ===
    override suspend fun load(url: String): LoadResponse? {
        val baseUrl = getBaseUrl()
        val infoUrl = url // URL trang thông tin chính
        val watchPageUrl = if (infoUrl.endsWith("/")) "${infoUrl}xem-phim.html" else "$infoUrl/xem-phim.html"
        Log.d("AnimeVietsubProvider", "Loading details. Info URL: $infoUrl, Watch Page URL: $watchPageUrl")

        try {
            // 1. Tải trang thông tin chính
            val infoDocument = app.get(infoUrl).document
            Log.d("AnimeVietsubProvider", "Successfully loaded info page: $infoUrl")

            // 2. Tải trang xem phim (để lấy danh sách tập theo logic cũ)
            var watchPageDocument: Document? = null
            try {
                 watchPageDocument = app.get(watchPageUrl, referer = infoUrl).document
                 Log.d("AnimeVietsubProvider", "Successfully loaded watch page: $watchPageUrl")
            } catch (e: Exception) {
                 Log.w("AnimeVietsubProvider", "Failed to load watch page ($watchPageUrl), episode list might be unavailable via this method. Error: ${e.message}")
            }

            // 3. Gọi toLoadResponse với cả hai document
            return infoDocument.toLoadResponse(this, infoUrl, baseUrl, watchPageDocument)

        } catch (e: Exception) {
            Log.e("AnimeVietsubProvider", "FATAL Error loading main info page ($infoUrl)", e)
            return null // Nếu trang info chính lỗi thì không thể tiếp tục
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
        val url: String,
        val dataId: String?,
        val duHash: String?
    )

    // === Helper parse Document thành LoadResponse ===
    private suspend fun Document.toLoadResponse(
        provider: MainAPI,
        infoUrl: String,
        baseUrl: String,
        watchPageDoc: Document? // Document trang xem phim (có thể null)
    ): LoadResponse? {
        val infoDoc = this // Document trang info
        try {
            // --- Parse thông tin phim từ infoDoc ---
            Log.d("AnimeVietsubProvider", "Parsing metadata from info page: $infoUrl")
            val title = infoDoc.selectFirst("div.TPost.Single div.Title")?.text()?.trim()
                ?: infoDoc.selectFirst("meta[property=og:title]")?.attr("content")?.substringBefore(" Tập")?.trim()
                ?: run { Log.e("AnimeVietsubProvider", "Could not find title on info page $infoUrl"); return null }
            var posterUrl = infoDoc.selectFirst("div.TPost.Single div.Image img")?.attr("src")
                ?: infoDoc.selectFirst("meta[property=og:image]")?.attr("content")
            posterUrl = fixUrl(posterUrl, baseUrl)
            val description = infoDoc.selectFirst("div.TPost.Single div.Description")?.text()?.trim()
                ?: infoDoc.selectFirst("meta[property=og:description]")?.attr("content")
            val infoSection = infoDoc.selectFirst("div.Info") ?: infoDoc
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

            // --- Parse danh sách tập phim từ watchPageDoc (nếu có) ---
            Log.d("AnimeVietsubProvider", "Parsing episodes from watch page document (if available)...")
            val episodes = if (watchPageDoc != null) {
                watchPageDoc.select("div.server ul.list-episode li a.btn-episode").mapNotNull { epLink ->
                    val epUrl = fixUrl(epLink.attr("href"), baseUrl)
                    val epName = epLink.attr("title").ifBlank { epLink.text() }.trim()
                    val dataId = epLink.attr("data-id").ifBlank { null } // Lấy data-id
                    val duHash = epLink.attr("data-hash").ifBlank { null }

                    Log.v("AnimeVietsubProvider", "[Episode Parsing - Watch Page] Processing link: name='$epName', url='$epUrl', dataId='$dataId', hash='$duHash'")
                    val episodeData = EpisodeData(url = epUrl ?: "", dataId = dataId, duHash = duHash)
                    val episodeNumber = epName.replace(Regex("""[^\d]"""), "").toIntOrNull()

                    // Cần có dataId cho loadLinks mới
                    if (!epName.isNullOrBlank() && epUrl != null && dataId != null) {
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

            // --- Xác định loại TV/Movie và trả về response ---
             val isTvSeries = episodes.size > 1 || infoSection.select("li:has(strong:containsOwn(Thể loại)) a[href*=anime-bo]").isNotEmpty()
             return if (isTvSeries) {
                 Log.d("AnimeVietsubProvider", "Creating TvSeriesLoadResponse for '$title'")
                 provider.newTvSeriesLoadResponse(title, infoUrl, TvType.TvSeries, episodes = episodes) {
                     this.posterUrl = posterUrl; this.plot = description; this.tags = genres; this.year = year; this.rating = rating; this.showStatus = status;
                 }
             } else {
                 Log.d("AnimeVietsubProvider", "Creating MovieLoadResponse for '$title'")
                 val durationText = infoSection.select("li:has(strong:containsOwn(Thời lượng))")?.firstOrNull()?.ownText()?.trim()
                 val durationMinutes = durationText?.filter { it.isDigit() }?.toIntOrNull()
                 // Movie data cần để loadLinks hoạt động, lấy từ tập duy nhất nếu có
                 val movieData = if (episodes.isNotEmpty()) episodes[0].data else gson.toJson(EpisodeData(url = infoUrl, dataId = null, duHash = null)) // Fallback data
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

    // === Lấy link xem (Sử dụng API mới, gọi Constructor ExtractorLink, fix type inference) ===
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        var foundLinks = false
        val baseUrl = getBaseUrl() // Lấy base URL đã kiểm tra
        val ajaxUrl = "$baseUrl/ajax/player?v=2019a"
        val decryptApiUrl = "https://m3u8.013666.xyz/animevietsub/decrypt"
        val textPlainMediaType = "text/plain".toMediaTypeOrNull()

        Log.d("AnimeVietsubProvider", "LoadLinks received data: $data")

        val episodeData = try {
             gson.fromJson(data, EpisodeData::class.java)
         } catch (e: Exception) {
             Log.e("AnimeVietsubProvider", "Failed to parse EpisodeData JSON in loadLinks: '$data'", e)
             return false
         }

        val episodeUrl = episodeData.url
        val episodeId = episodeData.dataId
        val episodeHash = episodeData.duHash

        if (episodeId == null || episodeUrl.isBlank()) {
             Log.e("AnimeVietsubProvider", "Missing episode ID or URL in episode data: $data. Cannot proceed.")
             return false
        }

        Log.i("AnimeVietsubProvider", "Processing episode ID: $episodeId using API method for URL: $episodeUrl")

        try {
            // --- Bước 2: Gửi POST đến ajax/player ---
             val postData = mapOf("id" to episodeId, "play" to "api", "link" to (episodeHash ?: ""), "backuplinks" to "1")
             val headers = mapOf(
                 "Content-Type" to "application/x-www-form-urlencoded; charset=UTF-8",
                 "Accept" to "*/*", "X-Requested-With" to "XMLHttpRequest",
                 "User-Agent" to USER_AGENT, "Referer" to episodeUrl
             )
             Log.d("AnimeVietsubProvider", "POSTing to AnimeVietsub AJAX API: $ajaxUrl with data: $postData")
             val ajaxResponse = app.post(ajaxUrl, data = postData, headers = headers, referer = episodeUrl)
             Log.d("AnimeVietsubProvider", "AnimeVietsub AJAX API Response Status: ${ajaxResponse.code}")
             // Log thêm body nếu cần debug kỹ hơn:
             // Log.v("AnimeVietsubProvider", "AnimeVietsub AJAX API Response Body: ${ajaxResponse.text}")


            // --- Bước 3: Parse response JSON, lấy dataEnc ---
             val playerResponse = try { gson.fromJson(ajaxResponse.text, AjaxPlayerResponse::class.java) } catch (e: Exception) { Log.e("AnimeVietsubProvider", "Failed to parse ajax/player response JSON: ${ajaxResponse.text}", e); null }
             if (playerResponse?.success != 1 || playerResponse.link.isNullOrEmpty()) { Log.e("AnimeVietsubProvider", "ajax/player request failed or response invalid: ${ajaxResponse.text}"); return false }
             val dataEnc = playerResponse.link.firstOrNull()?.file
             if (dataEnc.isNullOrBlank()) { Log.e("AnimeVietsubProvider", "Could not find 'dataenc' in ajax/player response: ${ajaxResponse.text}"); return false }
             Log.d("AnimeVietsubProvider", "Successfully got 'dataenc': ${dataEnc.take(50)}...")


            // --- Bước 4: Gọi API giải mã ---
             Log.d("AnimeVietsubProvider", "POSTing 'dataenc' to Decryption API: $decryptApiUrl")
             val requestBody = dataEnc.toByteArray().toRequestBody(textPlainMediaType)
             val decryptResponse = app.post(decryptApiUrl, headers = mapOf("User-Agent" to USER_AGENT), requestBody = requestBody)
             Log.d("AnimeVietsubProvider", "Decryption API Response Status: ${decryptResponse.code}")
             // Log thêm body nếu cần debug kỹ hơn:
             // Log.v("AnimeVietsubProvider", "Decryption API Response Body: ${decryptResponse.text}")


            // --- Bước 5: Lấy link M3U8 cuối cùng ---
            val finalM3u8Url = decryptResponse.text.trim()

            if (finalM3u8Url.startsWith("https") && finalM3u8Url.endsWith(".m3u8")) {
                Log.i("AnimeVietsubProvider", "Successfully obtained final M3U8 link: $finalM3u8Url")

                // Tạo Map chứa các header cần thiết cho trình phát
                val requiredHeaders = mapOf<String, String>( // Chỉ định rõ kiểu dữ liệu
                    "User-Agent" to USER_AGENT,
                    "Origin" to baseUrl // Origin là baseUrl đã lấy được
                )

                // Gọi Constructor ExtractorLink trực tiếp
                val extractorLinkObject = ExtractorLink(
                    source = name, // Dùng tên provider
                    name = "AnimeVietsub API", // Tên server
                    url = finalM3u8Url,
                    referer = baseUrl, // Referer là trang tập phim
                    quality = Qualities.Unknown.value, // Chưa rõ chất lượng
                    type = ExtractorLinkType.M3U8 // Thử dùng HSLS (hoặc M3U8)
                )

                // Gọi callback để trả link về cho Cloudstream
                callback(extractorLinkObject)
                foundLinks = true // Đánh dấu đã thành công

            } else {
                // Lỗi nếu API giải mã không trả về link M3U8 hợp lệ
                Log.e("AnimeVietsubProvider", "Decryption API did not return a valid M3U8 URL. Response: ${decryptResponse.text}")
                // Có thể ném lỗi ở đây để báo cho người dùng rõ hơn
                // throw ErrorLoadingException("API giải mã không trả về link hợp lệ.")
            }

        } catch (e: Exception) {
            Log.e("AnimeVietsubProvider", "Error during API link extraction process for episode ID $episodeId", e)
            // Có thể ném lỗi ở đây
            // throw e
        }

        // Fallback (có thể thêm sau nếu cần)
        // if (!foundLinks) {
        //    Log.w("AnimeVietsubProvider", "API method failed. Trying fallback extractors if any...")
        //    // Thêm code gọi loadExtractor cho các server embed khác tại đây nếu có
        // }

        if (!foundLinks) {
            Log.w("AnimeVietsubProvider", "No stream links were successfully extracted for episode ID $episodeId ($episodeUrl)")
        }
        return foundLinks // Trả về true nếu thành công, false nếu thất bại
    }


    // === Các hàm hỗ trợ ===

    // Mã hóa URL component
    private fun String?.encodeUri(): String {
        if (this == null) return ""
        return try {
            URLEncoder.encode(this, "UTF-8").replace("+", "%20") // Thay dấu + thành %20
        } catch (e: Exception) {
            Log.e("AnimeVietsubProvider", "Failed to URL encode: $this", e)
            this // Trả về chuỗi gốc nếu lỗi
        }
    }

    // Chuyển đổi điểm đánh giá sang thang 1000
    private fun Double?.toAnimeVietsubRatingInt(): Int? {
        return this?.let { (it * 100).roundToInt().coerceIn(0, 1000) }
    }

    // Sửa lỗi URL tương đối hoặc thiếu scheme
    private fun fixUrl(url: String?, baseUrl: String): String? {
        if (url.isNullOrBlank()) return null
        return try {
            when {
                url.startsWith("http") -> url
                url.startsWith("//") -> "https:$url"
                url.startsWith("/") -> URL(URL(baseUrl), url).toString()
                // Thử nối trực tiếp nếu không bắt đầu bằng các ký tự trên
                else -> URL(URL(baseUrl), "/$url".removePrefix("//")).toString()
            }
        } catch (e: Exception) {
            Log.e("AnimeVietsubProvider", "Failed to fix URL: base=$baseUrl, url=$url", e)
            null
        }
    }

} // <--- Dấu ngoặc nhọn ĐÓNG class AnimeVietsubProvider
