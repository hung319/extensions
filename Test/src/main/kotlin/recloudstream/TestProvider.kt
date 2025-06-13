package recloudstream 

// Import các lớp cần thiết
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Element
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import java.net.URLEncoder
// import com.lagradost.cloudstream3.network.CloudflareKiller

// --- Định nghĩa Data Classes cho API (Giữ nguyên) ---
@Serializable data class BunnyVideo(val id: Int? = null, val attributes: BunnyVideoAttributes? = null)
@Serializable data class BunnyVideoAttributes(val slug: String? = null, val title: String? = null, val views: Int? = null, val thumbnail: BunnyImage? = null, val bigThumbnail: BunnyImage? = null)
@Serializable data class BunnyImage(val data: BunnyImageData? = null)
@Serializable data class BunnyImageData(val id: Int? = null, val attributes: BunnyImageAttributes? = null)
@Serializable data class BunnyImageAttributes(val name: String? = null, val url: String? = null, val formats: BunnyImageFormats? = null)
@Serializable data class BunnyImageFormats(val thumbnail: BunnyImageFormatDetail? = null)
@Serializable data class BunnyImageFormatDetail( val url: String? = null)
@Serializable data class BunnySearchResponse(val data: List<BunnyVideo>? = null, val meta: BunnyMeta? = null)
@Serializable data class BunnyMeta(val pagination: BunnyPagination? = null)
@Serializable data class BunnyPagination(val page: Int? = null, val pageSize: Int? = null, val pageCount: Int? = null, val total: Int? = null)
@Serializable data class SonarApiResponse(val hls: List<SonarSource>? = null, val mp4: List<SonarSource>? = null)
@Serializable data class SonarSource(val url: String? = null, val label: String? = null, val size: Long? = null)

// Cấu hình Json parser
val jsonParser = Json { ignoreUnknownKeys = true }

// --- Định nghĩa lớp Provider ---

class IhentaiProvider : MainAPI() {
    // --- Thông tin cơ bản ---
    override var mainUrl = "https://ihentai.ws" // *** ĐÃ CẬP NHẬT TÊN MIỀN ***
    override var name = "iHentai" // Đổi tên lại
    override val hasMainPage = true
    override var lang = "vi"
    override val supportedTypes = setOf(TvType.NSFW)

    // *** THÊM USER-AGENT CHUNG CHO TẤT CẢ REQUESTS ***
    private val userAgent = "Mozilla/5.0 (Linux; Android 14; SM-S711B Build/UP1A.231005.007) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/135.0.7049.111 Mobile Safari/537.36"
    private val headers = mapOf("User-Agent" to userAgent)

    // API Base URL
    private val apiBaseUrl = "https://bunny-cdn.com"

    // Hàm helper để tạo URL ảnh đầy đủ
    private fun fixBunnyUrl(url: String?): String? {
        if (url.isNullOrBlank()) return null
        if (url.startsWith("http")) return url
        return "$apiBaseUrl$url"
    }

    // --- Trang chính (Dùng parse HTML) ---
    override suspend fun getMainPage(page: Int, request : MainPageRequest): HomePageResponse {
        // Thêm headers vào request
        val document = app.get(mainUrl, headers = headers).document
        val homePageList = mutableListOf<HomePageList>()

        document.select("main > div.v-container > div.tw-mb-16").forEach { section ->
            try {
                val title = section.selectFirst("h1.tw-text-3xl")?.text()?.trim() ?: "Không rõ tiêu đề"
                val items = section.select("div.tw-grid > div.v-card").mapNotNull { element ->
                    parseSearchCard(element)
                }
                if (items.isNotEmpty()) {
                    homePageList.add(HomePageList(title, items))
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
        if (homePageList.isEmpty()) {
             try {
                 val latestItems = document.select("main.v-main div.v-container div.tw-grid > div.v-card").mapNotNull { element ->
                     parseSearchCard(element)
                 }
                 if (latestItems.isNotEmpty()) {
                    homePageList.add(HomePageList("Truyện Mới", latestItems))
                 }
             } catch(e: Exception) {
                  e.printStackTrace()
             }
        }
        return HomePageResponse(homePageList)
    }

    // --- Hàm Helper: Phân tích thẻ Item ---
    private fun parseSearchCard(element: Element): AnimeSearchResponse? {
        val linkElement = element.selectFirst("a:has(img.tw-w-full)") ?: return null
        val href = fixUrlNull(linkElement.attr("href")) ?: return null
        if (href.isBlank() || href == "/" || href.contains("/restart") || href.contains("/login") ) {
             return null
        }
        val imageElement = linkElement.selectFirst("img.tw-w-full")
        val posterUrl = fixUrlNull(imageElement?.attr("src"))
        var title = imageElement?.attr("alt")?.trim()
        if (title.isNullOrBlank()) { title = imageElement?.attr("title")?.trim() }
        if (title.isNullOrBlank()) { title = element.selectFirst("a > div.v-card-text h2.text-subtitle-1")?.text()?.trim() }
        if (title.isNullOrBlank()) { title = linkElement.attr("title").trim() }
        if (title.isNullOrBlank()) return null

        // Chuyển href thành URL đầy đủ cho load()
        val fullUrl = fixUrl(href)
        return newAnimeSearchResponse(title, fullUrl, TvType.NSFW) {
            this.posterUrl = posterUrl
        }
    }

    // --- Tìm kiếm (Dùng parse HTML) ---
    override suspend fun search(query: String): List<SearchResponse> {
        val encodedQuery = URLEncoder.encode(query, "UTF-8")
        val searchUrl = "$mainUrl/search?s=$encodedQuery"
        return try {
            // Thêm headers vào request
            val document = app.get(searchUrl, headers = headers).document
            val resultsGrid = document.selectFirst("main.v-main div.v-container div.tw-grid")
            resultsGrid?.select("div.v-card")?.mapNotNull { element ->
                parseSearchCard(element)
            } ?: emptyList()
        } catch (e: Exception) {
            e.printStackTrace()
            emptyList()
        }
    }

    // --- Tải thông tin chi tiết (Dùng parse HTML) ---
    override suspend fun load(url: String): LoadResponse {
        // Thêm headers vào request
        val document = app.get(url, headers = headers).document
        val title = document.selectFirst("div.tw-mb-3 > h1.tw-text-lg")?.text()?.trim()
            ?: document.selectFirst("h1")?.text()?.trim()
            ?: throw RuntimeException("Không tìm thấy tiêu đề cho URL: $url")
        val posterUrl = fixUrlNull(document.selectFirst("div.tw-grid div.tw-col-span-5 img")?.attr("src"))
        val description = document.selectFirst("div.v-sheet.tw-p-5 > p.tw-text-sm")?.text()?.trim()
        val genres = document.select("div.v-sheet.tw-p-5 a.v-chip")?.mapNotNull { it.text()?.trim() }
        val currentEpisode = Episode(data = url, name = title)
        val episodes = listOf(currentEpisode)
        return newAnimeLoadResponse(title, url, TvType.NSFW) {
            this.posterUrl = posterUrl
            this.plot = description
            this.tags = genres
            addEpisodes(DubStatus.Subbed, episodes)
        }
    }

    // --- Tải Link Xem Phim/Video (Dùng API ipa.sonar-cdn.com) ---
    @Suppress("DEPRECATION")
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        try {
            // Thêm headers vào request
            val document = app.get(data, headers = headers).document
            val iframeSrc = document.selectFirst("iframe.tw-w-full")?.attr("src")
                ?: throw RuntimeException("Không tìm thấy iframe video trên trang: $data")
             val videoId = iframeSrc.substringAfterLast("?v=", "").substringBefore("&")
             if (videoId.isBlank()) { throw RuntimeException("Không thể trích xuất videoId từ iframe src: $iframeSrc") }
            val sonarApiUrl = "https://ipa.sonar-cdn.com/play/$videoId"
            // Tạo headers cho API call, bao gồm cả User-Agent và Referer
            val sonarApiHeaders = headers + mapOf("Referer" to data)
            val sonarResponseJson = app.get(sonarApiUrl, headers = sonarApiHeaders).text
            val sonarResponse = jsonParser.decodeFromString<SonarApiResponse>(sonarResponseJson)
            var foundLinks = false
            val allSources = (sonarResponse.hls ?: emptyList()) + (sonarResponse.mp4 ?: emptyList())
            // Headers cho link video cuối cùng cũng sẽ dùng User-Agent
            val linkHeaders = headers + mapOf("Referer" to iframeSrc)

            allSources.forEach { source ->
                val videoUrl = source.url ?: return@forEach
                val isM3u8 = videoUrl.contains(".m3u8", ignoreCase = true) || source.url?.contains("hls", ignoreCase = true) == true
                val qualityLabel = source.label ?: (if (isM3u8) "HLS" else "MP4")
                val quality = qualityLabel.filter { it.isDigit() }.toIntOrNull()?.let {
                    when {
                        it >= 1080 -> Qualities.P1080.value; it >= 720 -> Qualities.P720.value
                        it >= 480 -> Qualities.P480.value; it >= 360 -> Qualities.P360.value
                        else -> Qualities.Unknown.value
                    }
                } ?: Qualities.Unknown.value
                callback(
                    ExtractorLink(
                        source = name, name = "$name - $qualityLabel", url = videoUrl,
                        referer = iframeSrc, quality = quality,
                        type = if (isM3u8) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO,
                        headers = linkHeaders
                    )
                )
                foundLinks = true
            }
            return foundLinks
        } catch (e: Exception) {
            e.printStackTrace()
            return false
        }
    }
}
