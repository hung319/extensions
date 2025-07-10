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
    private val domainCheckUrl = mainUrl // URL dùng để kiểm tra domain mới

    /**
     * Lấy URL hoạt động hiện tại của AnimeHay.
     * Sẽ kiểm tra domain mới nếu cần.
     */
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
            Log.d("AnimeHayProvider", "Fetched from $domainCheckUrl, landed on: $landedUrl.")

            // Method 1: Tìm link mới từ nội dung trang (thẻ a, script)
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
                val newDomainRegex = Regex("""var\s+new_domain\s*=\s*["'](https?://[^"']+)["']""")
                val scriptContent = document.select("script:not([src])").html()
                val match = newDomainRegex.find(scriptContent)
                if (match != null) {
                    hrefFromContent = match.groups[1]?.value
                    Log.d("AnimeHayProvider", "Found new_domain '$hrefFromContent' in script.")
                }
            }

            if (!hrefFromContent.isNullOrBlank()) {
                try {
                    val urlObject = URL(hrefFromContent)
                    finalNewDomain = "${urlObject.protocol}://${urlObject.host}"
                    Log.i("AnimeHayProvider", "Method 1: New domain from parsed content link: $finalNewDomain")
                } catch (e: MalformedURLException) {
                    Log.e("AnimeHayProvider", "Malformed URL from parsed content link: '$hrefFromContent'", e)
                }
            }

            // Method 2: Dùng URL sau khi redirect nếu không tìm thấy link trong nội dung
            if (finalNewDomain.isNullOrBlank()) {
                val landedUrlBase = try {
                    val landedObj = URL(landedUrl)
                    "${landedObj.protocol}://${landedObj.host}"
                } catch (e: Exception) { null }

                val initialCheckUrlHost = try { URL(domainCheckUrl).host } catch (e: Exception) { null }
                val landedUrlHost = try { URL(landedUrl).host } catch (e: Exception) { null }

                if (landedUrlBase != null && landedUrlBase.startsWith("http") && landedUrlHost != initialCheckUrlHost) {
                    finalNewDomain = landedUrlBase
                    Log.i("AnimeHayProvider", "Method 2: Using domain from redirection: $finalNewDomain")
                }
            }

            // Cập nhật URL hoạt động
            if (!finalNewDomain.isNullOrBlank() && finalNewDomain != currentActiveUrl) {
                Log.i("AnimeHayProvider", "Domain will be updated: $currentActiveUrl -> $finalNewDomain")
                currentActiveUrl = finalNewDomain
            } else {
                Log.w("AnimeHayProvider", "No new valid domain found. Using previous active URL: $currentActiveUrl")
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
     * Lấy URL poster từ Kitsu API.
     */
    private suspend fun getKitsuPoster(title: String): String? {
        Log.d("AnimeHayProvider", "Searching Kitsu for: \"$title\"")
        return try {
            val encodedTitle = URLEncoder.encode(title, "UTF-8")
            val searchUrl = "https://kitsu.io/api/edge/anime?filter[text]=$encodedTitle&page[limit]=1"
            val response = app.get(searchUrl).parsedSafe<KitsuMain>()
            val poster = response?.data?.firstOrNull()?.attributes?.posterImage
            poster?.original ?: poster?.large ?: poster?.medium ?: poster?.small ?: poster?.tiny
        } catch (e: Exception) {
            Log.e("AnimeHayProvider", "Kitsu API Error for title '$title'", e)
            null
        }
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        Log.d("AnimeHayProvider", "getMainPage called with page: $page, for list: ${request.name}")
        try {
            val siteBaseUrl = getBaseUrl()
            val urlToFetch = if (page <= 1) siteBaseUrl else "$siteBaseUrl/phim-moi-cap-nhap/trang-$page.html"
            val document = app.get(urlToFetch).document
            val homePageItems = document.select("div.movies-list div.movie-item").mapNotNull {
                it.toSearchResponse(this, siteBaseUrl)
            }

            // Logic kiểm tra trang kế tiếp
            val currentPageFromHtml = document.selectFirst("div.pagination a.active_page")?.text()?.toIntOrNull() ?: page
            val hasNext = document.selectFirst("div.pagination a[href*=/trang-${currentPageFromHtml + 1}.html]") != null

            val listTitle = request.name.ifBlank { "Mới cập nhật" }
            val homeList = HomePageList(listTitle, homePageItems)
            return newHomePageResponse(listOf(homeList), hasNext)
        } catch (e: Exception) {
            Log.e("AnimeHayProvider", "Error in getMainPage for page $page", e)
            return newHomePageResponse(emptyList(), false)
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        try {
            val baseUrl = getBaseUrl()
            val searchUrl = "$baseUrl/tim-kiem/${query.encodeUri()}.html"
            Log.i("AnimeHayProvider", "Searching URL: $searchUrl")
            val document = app.get(searchUrl).document
            return document.select("div.movies-list div.movie-item").mapNotNull {
                it.toSearchResponse(this, baseUrl)
            }
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

    /**
     * Hàm lấy link phim (phiên bản cuối cùng, đã sửa lỗi)
     */
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        var foundLinks = false
        Log.d("AnimeHayProvider", "loadLinks (Safe TOK Logic) called for: $data")

        try {
            val document = app.get(data, referer = getBaseUrl()).document

            // --- BƯỚC 1: Kiểm tra xem nút "Server TOK" có tồn tại trong danh sách server không ---
            // Đây là bước kiểm tra an toàn mấu chốt từ file Java.
            val serverListElements = document.select("#list_sv a")
            val tokServerExists = serverListElements.any { it.text().contains("TOK", ignoreCase = true) }

            // --- BƯỚC 2: Chỉ xử lý link nếu nút "Server TOK" tồn tại ---
            if (tokServerExists) {
                Log.i("AnimeHayProvider", "TOK server button found on page. Proceeding to extract link.")
                
                // Tìm thẻ script chứa thông tin video
                val scriptContent = document.selectFirst("script:containsData(function loadVideo)")?.data()

                if (!scriptContent.isNullOrBlank()) {
                    // Áp dụng Regex để tìm link M3U8 của server TOK
                    val tokRegex = Regex("""tik:\s*['"]([^'"]+)['"]""")
                    val m3u8Link = tokRegex.find(scriptContent)?.groupValues?.getOrNull(1)

                    if (!m3u8Link.isNullOrBlank()) {
                        Log.i("AnimeHayProvider", "Found valid TOK M3U8 link: $m3u8Link")
                        callback(
                            newExtractorLink(
                                source = m3u8Link,
                                name = "Server TOK",
                                url = m3u8Link,
                                type = ExtractorLinkType.M3U8
                            ) {
                                this.referer = getBaseUrl()
                                this.quality = Qualities.Unknown.value
                            }
                        )
                        foundLinks = true
                        Log.d("AnimeHayProvider", "TOK link submitted successfully.")
                    } else {
                        Log.w("AnimeHayProvider", "TOK server button exists, but M3U8 link not found in script.")
                    }
                } else {
                    Log.w("AnimeHayProvider", "TOK server button exists, but video script block not found.")
                }
            } else {
                Log.w("AnimeHayProvider", "TOK server button not found on page. Skipping link extraction.")
            }

        } catch (e: Exception) {
            Log.e("AnimeHayProvider", "Error in loadLinks (Safe TOK Logic)", e)
        }

        return foundLinks
    }

    // === Hàm phụ và Extension functions ===
    private fun Element.toSearchResponse(provider: MainAPI, baseUrl: String): SearchResponse? {
        return try {
            val linkElement = this.selectFirst("> a[href]") ?: this.selectFirst("a[href*=thong-tin-phim]") ?: return null
            val href = fixUrl(linkElement.attr("href"), baseUrl) ?: return null
            val title = this.selectFirst("div.name-movie")?.text()?.trim()?.takeIf { it.isNotBlank() } ?: linkElement.attr("title")?.trim() ?: return null
            val posterUrl = this.selectFirst("img")?.let { it.attr("src").ifBlank { it.attr("data-src") } }
            val tvType = if (href.contains("/phim/", ignoreCase = true)) TvType.AnimeMovie else TvType.Anime
            provider.newMovieSearchResponse(title, href, tvType) { this.posterUrl = fixUrl(posterUrl, baseUrl) }
        } catch (e: Exception) {
            Log.e("AnimeHayProvider", "Error in toSearchResponse for element", e)
            null
        }
    }

    private suspend fun Document.toLoadResponse(provider: MainAPI, url: String, baseUrl: String): LoadResponse? {
        try {
            val title = this.selectFirst("h1.heading_movie")?.text()?.trim() ?: return null
            val genres = this.select("div.list_cate a").mapNotNull { it.text()?.trim() }
            val isChineseAnimation = genres.any { it.contains("CN Animation", ignoreCase = true) }
            val hasEpisodes = this.selectFirst("div.list-item-episode a") != null

            val mainTvType = when {
                hasEpisodes && isChineseAnimation -> TvType.Cartoon
                hasEpisodes && !isChineseAnimation -> TvType.Anime
                !hasEpisodes && isChineseAnimation -> TvType.Cartoon
                else -> TvType.AnimeMovie
            }

            val animehayPoster = fixUrl(this.selectFirst("div.head div.first img")?.attr("src"), baseUrl)
            val finalPosterUrl = if (mainTvType == TvType.Anime || mainTvType == TvType.AnimeMovie) {
                getKitsuPoster(title) ?: animehayPoster
            } else {
                animehayPoster
            }

            val description = this.selectFirst("div.desc > div:last-child")?.text()?.trim()
            val year = this.selectFirst("div.update_time div:nth-child(2)")?.text()?.trim()?.filter { it.isDigit() }?.toIntOrNull()
            val ratingText = this.selectFirst("div.score div:nth-child(2)")?.text()?.trim()
            val rating = ratingText?.split("||")?.getOrNull(0)?.trim()?.toDoubleOrNull()?.toAnimeHayRatingInt()
            val statusText = this.selectFirst("div.status div:nth-child(2)")?.text()?.trim()
            val status = when {
                statusText?.contains("Hoàn thành", ignoreCase = true) == true -> ShowStatus.Completed
                statusText?.contains("Đang", ignoreCase = true) == true -> ShowStatus.Ongoing
                else -> null
            }

            val recommendations = this.select("div.movie-recommend div.movie-item").mapNotNull {
                it.toSearchResponse(provider, baseUrl)
            }

            return if (hasEpisodes) {
                val episodes = this.select("div.list-item-episode a").mapNotNull { epLink ->
                    val epUrl = fixUrl(epLink.attr("href"), baseUrl) ?: return@mapNotNull null
                    val finalEpName = epLink.attr("title")?.trim().takeIf { !it.isNullOrBlank() } ?: epLink.selectFirst("span")?.text()?.trim() ?: return@mapNotNull null
                    newEpisode(data = epUrl) { this.name = finalEpName }
                }.reversed()

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

    private fun String?.encodeUri(): String = URLEncoder.encode(this ?: "", "UTF-8")

    private fun Double?.toAnimeHayRatingInt(): Int? = this?.let { (it * 1000).roundToInt().coerceIn(0, 10000) }

    private fun fixUrl(url: String?, baseUrl: String): String? {
        if (url.isNullOrBlank()) return null
        return when {
            url.startsWith("http") -> url
            url.startsWith("//") -> "https:$url"
            else -> URL(URL(baseUrl), url).toString()
        }
    }
}
