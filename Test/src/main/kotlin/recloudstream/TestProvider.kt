package recloudstream

// Import các lớp cần thiết
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Element // Cần cho getMainPage, load, parseSearchCard
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import java.net.URLEncoder
// import com.lagradost.cloudstream3.network.CloudflareKiller

// --- Định nghĩa Data Classes cho API ---
@Serializable
data class BunnyVideo(val id: Int? = null, val attributes: BunnyVideoAttributes? = null)
// ... (Các data class Bunny... khác giữ nguyên) ...
@Serializable data class BunnyVideoAttributes(val slug: String? = null, val title: String? = null, val views: Int? = null, val thumbnail: BunnyImage? = null, val bigThumbnail: BunnyImage? = null)
@Serializable data class BunnyImage(val data: BunnyImageData? = null)
@Serializable data class BunnyImageData(val id: Int? = null, val attributes: BunnyImageAttributes? = null)
@Serializable data class BunnyImageAttributes(val name: String? = null, val url: String? = null, val formats: BunnyImageFormats? = null)
@Serializable data class BunnyImageFormats(val thumbnail: BunnyImageFormatDetail? = null)
@Serializable data class BunnyImageFormatDetail( val url: String? = null)
@Serializable data class BunnySearchResponse(val data: List<BunnyVideo>? = null, val meta: BunnyMeta? = null)
@Serializable data class BunnyMeta(val pagination: BunnyPagination? = null)
@Serializable data class BunnyPagination(val page: Int? = null, val pageSize: Int? = null, val pageCount: Int? = null, val total: Int? = null)

// Data class cho API ipa.sonar-cdn.com (*** Dựa trên mã TS, cần xác nhận cấu trúc ***)
@Serializable
data class SonarApiResponse(
    val hls: List<SonarSource>? = null,
    val mp4: List<SonarSource>? = null
)
@Serializable
data class SonarSource(
    val url: String? = null,
    val label: String? = null,
    val size: Long? = null
)

// Cấu hình Json parser
val jsonParser = Json { ignoreUnknownKeys = true }

// --- Định nghĩa lớp Provider ---

class IhentaiProvider : MainAPI() {
    // --- Thông tin cơ bản ---
    override var mainUrl = "https://ihentai.ws"
    override var name = "iHentai Test"
    override val hasMainPage = true
    override var lang = "vi"
    override val supportedTypes = setOf(TvType.NSFW)

    // API Base URL
    private val apiBaseUrl = "https://bunny-cdn.com"

    // Hàm helper để tạo URL ảnh đầy đủ
    private fun fixBunnyUrl(url: String?): String? {
        // ... (Giữ nguyên) ...
         if (url.isNullOrBlank()) return null
         if (url.startsWith("http")) return url
         return "$apiBaseUrl$url"
    }

    // --- Trang chính (Dùng parse HTML) ---
     override suspend fun getMainPage(page: Int, request : MainPageRequest): HomePageResponse {
         // ... (Giữ nguyên code getMainPage dùng HTML parse + logging) ...
         println("TestProvider: getMainPage called - Using HTML Parse method")
         val homePageList = mutableListOf<HomePageList>()
         try {
             val document = app.get(mainUrl).document
             val sections = document.select("main > div.v-container > div.tw-mb-16")
             println("TestProvider: Found ${sections.size} sections using 'main > div.v-container > div.tw-mb-16'")
             sections.forEachIndexed { index, section ->
                 try {
                     val title = section.selectFirst("h1.tw-text-3xl")?.text()?.trim() ?: "Section $index"
                     // println("TestProvider: Processing section '$title'") // Bỏ log bớt
                     val itemsElements = section.select("div.tw-grid > div.v-card")
                     // println("TestProvider: Found ${itemsElements.size} item elements in section '$title'")
                     val items = itemsElements.mapNotNull { element -> parseSearchCard(element) }
                     // println("TestProvider: Parsed ${items.size} valid items for section '$title'")
                     if (items.isNotEmpty()) { homePageList.add(HomePageList(title, items)) }
                 } catch (e: Exception) { println("TestProvider: Error processing section $index"); e.printStackTrace() }
             }
             if (homePageList.isEmpty() && sections.isEmpty()) {
                  println("TestProvider: No sections found, trying fallback selector...")
                  try {
                      val latestItemsElements = document.select("main.v-main div.v-container div.tw-grid > div.v-card")
                      // println("TestProvider: Fallback found ${latestItemsElements.size} item elements")
                      val latestItems = latestItemsElements.mapNotNull { element -> parseSearchCard(element) }
                      // println("TestProvider: Fallback parsed ${latestItems.size} valid items")
                      if (latestItems.isNotEmpty()) { homePageList.add(HomePageList("Truyện Mới (Fallback)", latestItems)) }
                  } catch(e: Exception) { println("TestProvider: Error during fallback"); e.printStackTrace() }
             }
         } catch(e: Exception) { println("TestProvider: Failed to get or parse main page HTML: ${e.message}"); e.printStackTrace() }
         println("TestProvider: getMainPage finished, returning ${homePageList.size} lists.")
         if(homePageList.isEmpty()) { println("TestProvider: WARNING - Returning empty HomePageResponse!") }
         return HomePageResponse(homePageList)
     }

      // Hàm parse HTML cũ cho trang chính (Bỏ bớt log)
      private fun parseSearchCard(element: Element): AnimeSearchResponse? {
          val linkElement = element.selectFirst("a:has(img.tw-w-full)") ?: return null
          val href = fixUrlNull(linkElement.attr("href")) ?: return null
          if (href.isBlank() || href == "/" || href.contains("/restart") || href.contains("/login") ) { return null }
          val imageElement = linkElement.selectFirst("img.tw-w-full")
          val posterUrl = fixUrlNull(imageElement?.attr("src"))
          var title = imageElement?.attr("alt")?.trim()
          if (title.isNullOrBlank()) { title = imageElement?.attr("title")?.trim() }
          if (title.isNullOrBlank()) { title = element.selectFirst("a > div.v-card-text h2.text-subtitle-1")?.text()?.trim() }
          if (title.isNullOrBlank()) { title = linkElement.attr("title").trim() }
          if (title.isNullOrBlank()) return null
          val seriesId = href.trim('/').substringBeforeLast("-", href.trim('/'))
          if (seriesId.isBlank() || seriesId.contains('/')) { return null }
          return newAnimeSearchResponse(title, href, TvType.NSFW) { // URL trỏ thẳng đến href của tập
              this.posterUrl = posterUrl
          }
      }


    // --- Tìm kiếm (Dùng parse HTML) ---
    // Giữ nguyên theo yêu cầu
    override suspend fun search(query: String): List<SearchResponse> {
        val encodedQuery = URLEncoder.encode(query, "UTF-8")
        val searchUrl = "$mainUrl/search?s=$encodedQuery"
        println("TestProvider: Searching with URL: $searchUrl")
        return try {
            val document = app.get(searchUrl).document
            val resultsGrid = document.selectFirst("main.v-main div.v-container div.tw-grid")
            resultsGrid?.select("div.v-card")?.mapNotNull { element ->
                parseSearchCard(element) // Dùng hàm parse HTML
            } ?: emptyList()
        } catch (e: Exception) {
            e.printStackTrace()
            println("Error during search HTML parse: ${e.message}")
            emptyList()
        }
    }

    // --- Tải thông tin chi tiết (Dùng parse HTML, chỉ 1 tập) ---
    // Giữ nguyên theo yêu cầu
    override suspend fun load(url: String): LoadResponse {
        println("TestProvider: load called for href: $url")
        val absoluteUrl = fixUrl(url) // URL là href từ search/mainpage
        val document = app.get(absoluteUrl).document
        val title = document.selectFirst("div.tw-mb-3 > h1.tw-text-lg")?.text()?.trim()
            ?: document.selectFirst("h1")?.text()?.trim()
            ?: throw RuntimeException("Không tìm thấy tiêu đề cho URL: $absoluteUrl")
        val posterUrl = fixUrlNull(document.selectFirst("div.tw-grid div.tw-col-span-5 img")?.attr("src"))
        val description = document.selectFirst("div.v-sheet.tw-p-5 > p.tw-text-sm")?.text()?.trim()
        val genres = document.select("div.v-sheet.tw-p-5 a.v-chip")?.mapNotNull { it.text()?.trim() }
        // data của Episode là href của trang này
        val currentEpisode = Episode(data = url, name = title) // Dùng lại url gốc (href) cho data
        val episodes = listOf(currentEpisode)
        return newAnimeLoadResponse(title, url, TvType.NSFW) { // ID vẫn là href
            this.posterUrl = posterUrl
            this.plot = description
            this.tags = genres
            addEpisodes(DubStatus.Subbed, episodes)
        }
    }


    // --- Tải Link Xem Phim/Video (*** THÊM LOGGING CHI TIẾT ***) ---
    @Suppress("DEPRECATION")
    override suspend fun loadLinks(
        data: String, // data là href của trang tập phim
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
         val episodePageUrl = fixUrl(data) // Đảm bảo URL tuyệt đối
         println("TestProvider: loadLinks started for episode page: $episodePageUrl")
         var foundLinks = false
         try {
             // 1. Tải HTML trang xem phim
             println("TestProvider: Fetching episode page HTML...")
             val document = app.get(episodePageUrl).document
             println("TestProvider: Fetched episode page HTML successfully.")

             // 2. Trích xuất iframe src
             val iframeElement = document.selectFirst("iframe.tw-w-full")
             if (iframeElement == null) {
                 println("TestProvider ERROR: iframe.tw-w-full not found on page $episodePageUrl")
                 return false
             }
             val iframeSrc = iframeElement.attr("src")
             if (iframeSrc.isNullOrBlank()) {
                  println("TestProvider ERROR: iframe found but src attribute is blank/null.")
                  return false
             }
              println("TestProvider: Found iframe source: $iframeSrc")

             // 3. Trích xuất videoId từ iframe src
              val videoId = iframeSrc.substringAfterLast("?v=", "").substringBefore("&")
              if (videoId.isBlank()) {
                   println("TestProvider ERROR: Could not extract videoId (using ?v=) from iframe src: $iframeSrc")
                   // Có thể thử thêm logic khác để lấy ID nếu cần, nhưng tạm thời báo lỗi
                   return false
              }
              println("TestProvider: Extracted videoId: $videoId")

             // 4. Tạo URL API tới ipa.sonar-cdn.com
             val sonarApiUrl = "https://ipa.sonar-cdn.com/play/$videoId"
             println("TestProvider: Calling Sonar API: $sonarApiUrl")

             // 5. Tạo headers cho API call
             val sonarApiHeaders = mapOf("Referer" to episodePageUrl)
             println("TestProvider: Sonar API Headers: $sonarApiHeaders")

             // 6. Gọi API Sonar và parse JSON
             val sonarResponseJson = app.get(sonarApiUrl, headers = sonarApiHeaders).text
             println("TestProvider: Sonar API response (first 500 chars): ${sonarResponseJson.take(500)}")

             val sonarResponse = try {
                  jsonParser.decodeFromString<SonarApiResponse>(sonarResponseJson)
             } catch (parseError: Exception) {
                  println("TestProvider ERROR: Failed to parse Sonar API JSON response.")
                  parseError.printStackTrace()
                  return false
             }
             println("TestProvider: Parsed Sonar API response successfully.")

             // 7. Trích xuất link và callback
             val allSources = (sonarResponse.hls ?: emptyList()) + (sonarResponse.mp4 ?: emptyList())
             println("TestProvider: Found ${allSources.size} sources (HLS+MP4) in API response.")

             if (allSources.isEmpty()){
                 println("TestProvider WARNING: No sources found in HLS or MP4 arrays from Sonar API.")
                 return false // Không có link nào để xử lý
             }

             val userAgent = "Mozilla/5.0 (Linux; Android 14; SM-S711B Build/UP1A.231005.007) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/135.0.7049.111 Mobile Safari/537.36"
             val linkHeaders = mapOf( "Referer" to iframeSrc, "User-Agent" to userAgent )

             allSources.forEach { source ->
                 val videoUrl = source.url
                 println("TestProvider: Processing source - Label: ${source.label}, URL: $videoUrl")
                 if (videoUrl.isNullOrBlank()) {
                     println("TestProvider WARNING: Skipping source with blank/null URL.")
                     return@forEach // Bỏ qua source này
                 }

                 val isM3u8 = videoUrl.contains(".m3u8", ignoreCase = true) || source.url?.contains("hls", ignoreCase = true) == true
                 val qualityLabel = source.label ?: (if (isM3u8) "HLS" else "MP4")

                 // Parse chất lượng thủ công
                 val quality = qualityLabel.filter { it.isDigit() }.toIntOrNull()?.let {
                     when {
                         it >= 1080 -> Qualities.P1080.value; it >= 720 -> Qualities.P720.value
                         it >= 480 -> Qualities.P480.value; it >= 360 -> Qualities.P360.value
                         else -> Qualities.Unknown.value
                     }
                 } ?: Qualities.Unknown.value

                 println("TestProvider: Calling callback with URL: $videoUrl, Quality: $qualityLabel ($quality)")
                 callback(
                     ExtractorLink(
                         source = name, name = "$name - $qualityLabel", url = videoUrl,
                         referer = iframeSrc, quality = quality,
                         type = if (isM3u8) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO,
                         headers = linkHeaders
                     )
                 )
                 foundLinks = true // Đánh dấu đã tìm thấy ít nhất 1 link
             }
             // In kết quả cuối cùng của hàm
             println("TestProvider: loadLinks finished. Found links: $foundLinks")
             return foundLinks
         } catch (e: Exception) {
             // In lỗi tổng thể của hàm loadLinks
             e.printStackTrace()
             println("TestProvider ERROR: Exception during loadLinks for $episodePageUrl: ${e.message}")
             return false
         }
    }
}
