package recloudstream // Hoặc package bạn muốn dùng

// Import các lớp cần thiết
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Element
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app // Import app instance
import kotlin.text.Regex // Import Regex
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.AppUtils // Import AppUtils
import org.jsoup.Jsoup
import java.net.URLEncoder
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
// fixUrl là extension function, thường được bao bởi utils.*
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.async
import android.util.Log

// --- Data class để parse JSON từ API mới của bạn ---
data class M3u8ApiResponse(
    val m3u8: String? = null
)

// === DATA CLASSES CHO KITSU API ===
// ... (giữ nguyên)
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

// --- Định nghĩa lớp Provider ---
class AnimetProvider : MainAPI() {
    // ... (Thông tin cơ bản giữ nguyên) ...
    override var mainUrl = "https://animet.org"
    override var name = "Animet"
    override val hasMainPage = true
    override var lang = "vi"
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(
        TvType.Anime,
        TvType.AnimeMovie,
        TvType.OVA,
        TvType.Cartoon,
        TvType.TvSeries
    )

    private var baseUrl: String? = null
    private val baseUrlMutex = Mutex()

    // --- Các hàm pomocniczy (Helper Functions) ---
    // ... (getBaseUrl, fixUrlBased, getOriginalImageUrl, getKitsuPoster giữ nguyên) ...
     private suspend fun getBaseUrl(): String {
        baseUrlMutex.withLock {
            if (baseUrl == null) {
                Log.w(name, "BaseUrl is null, fetching from $mainUrl")
                try {
                    val response = app.get(mainUrl, timeout = 15_000L).text
                    val doc = Jsoup.parse(response)
                    val domainText = doc.selectFirst("span.text-success.font-weight-bold")?.text()?.trim()
                    if (!domainText.isNullOrBlank()) {
                        baseUrl = "https://${domainText.lowercase().removeSuffix("/")}"
                        Log.w(name, "Fetched BaseUrl: $baseUrl")
                    } else {
                        Log.e(name, "Could not fetch baseUrl from $mainUrl. Falling back to default.")
                        baseUrl = "https://anime6.site"
                    }
                } catch (e: Exception) {
                    Log.e(name, "Failed to fetch or parse baseUrl from $mainUrl: ${e.message}")
                    baseUrl = "https://anime6.site"
                }
            }
        }
        return baseUrl ?: throw ErrorLoadingException("Could not determine base URL")
    }

     private fun fixUrlBased(url: String?): String? {
         if (url.isNullOrBlank()) return null
         val currentBaseUrl = baseUrl ?: return fixUrl(url)
         return when {
             url.startsWith("//") -> fixUrl("https:$url")
             url.startsWith("/") -> fixUrl("$currentBaseUrl$url")
             else -> fixUrl(url)
         }
     }

     private fun getOriginalImageUrl(thumbnailUrl: String?): String? {
        if (thumbnailUrl.isNullOrBlank()) return null
        val imageSizeRegex = Regex("""(-\d+x\d+)(\.\w+)$""")
        return imageSizeRegex.replace(thumbnailUrl, "$2")
     }

     private suspend fun getKitsuPoster(title: String): String? {
         Log.d(name, "Searching Kitsu for: $title")
         return try {
             val encodedTitle = URLEncoder.encode(title, "UTF-8")
             val searchUrl = "https://kitsu.io/api/edge/anime?filter[text]=$encodedTitle&page[limit]=1"
             Log.d(name, "Kitsu Search URL: $searchUrl")
             val response = app.get(searchUrl, timeout = 10_000L).parsedSafe<KitsuMain>()
             val poster = response?.data?.firstOrNull()?.attributes?.posterImage
             val kitsuPosterUrl = poster?.original ?: poster?.large ?: poster?.medium ?: poster?.small ?: poster?.tiny
             if (!kitsuPosterUrl.isNullOrBlank()) {
                 Log.d(name, "Found Kitsu poster: $kitsuPosterUrl")
             } else {
                 Log.w(name, "Kitsu poster not found for title '$title'")
             }
             kitsuPosterUrl
         } catch (e: Exception) {
             Log.e(name, "Kitsu API Error for title '$title'. Error: ${e.message}", e)
             null
         }
     }

    // ... (mapElementToSearchResponse, mapTopAnimeElement, getMainPage, search giữ nguyên) ...
    private fun mapElementToSearchResponse(element: Element, currentBaseUrl: String, forceTvType: TvType? = null): AnimeSearchResponse? {
        val linkTag = element.selectFirst("article a") ?: element.selectFirst("a") ?: return null
        val href = fixUrlBased(linkTag.attr("href")) ?: return null
        if (href.isBlank()) return null
        val title = linkTag.selectFirst("h2.Title")?.text()?.trim()
            ?: linkTag.attr("title")?.trim()
            ?: linkTag.text()?.trim()
            ?: return null
        if (title.isBlank()) return null

        val poster = linkTag.selectFirst("div.Image figure img")?.let { img ->
            img.attr("data-src").ifBlank { img.attr("src") }
        }?.let { fixUrlBased(it) }

        val latestEpText = linkTag.selectFirst("span.mli-eps, span.mli-quality")?.text()?.trim()

        val tvType = forceTvType ?: when {
            href.contains("/the-loai/cn-animation", ignoreCase = true) -> TvType.Cartoon
            href.contains("/the-loai/tokusatsu", ignoreCase = true) -> TvType.TvSeries
            href.contains("phim-le", ignoreCase = true) -> TvType.AnimeMovie
            title.contains("Donghua", ignoreCase = true) || title.contains("(CN)", ignoreCase = true) -> TvType.Cartoon
            latestEpText?.contains("Movie", ignoreCase = true) == true -> TvType.AnimeMovie
            latestEpText?.contains("OVA", ignoreCase = true) == true -> TvType.OVA
            title.contains("Movie", ignoreCase = true) -> TvType.AnimeMovie
            title.contains("OVA", ignoreCase = true) -> TvType.OVA
            title.contains("Phần", ignoreCase = true) -> TvType.Anime
            else -> TvType.Anime
        }

        return newAnimeSearchResponse(title, href, tvType) {
            this.posterUrl = poster
            if (!poster.isNullOrBlank() && baseUrl != null && poster.startsWith(baseUrl!!)) {
                 this.posterHeaders = mapOf("Referer" to currentBaseUrl)
            }
        }
    }

     private fun mapTopAnimeElement(element: Element, currentBaseUrl: String): AnimeSearchResponse? {
        val linkTag = element.selectFirst("a") ?: return null
        val href = fixUrlBased(linkTag.attr("href")) ?: return null
        if (href.isBlank()) return null
        val title = linkTag.selectFirst("div.Title")?.text()?.trim() ?: return null
        val poster = linkTag.selectFirst("div.Image figure img")?.let { img ->
            img.attr("data-src").ifBlank { img.attr("src") }
        }?.let { fixUrlBased(it) }
        val tvType = if (title.contains("Donghua", ignoreCase = true) || title.contains("(CN)", ignoreCase = true)) TvType.Cartoon else TvType.Anime

        return newAnimeSearchResponse(title, href, tvType) {
             this.posterUrl = poster
             if (!poster.isNullOrBlank() && baseUrl != null && poster.startsWith(baseUrl!!)) {
                this.posterHeaders = mapOf("Referer" to currentBaseUrl)
             }
         }
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse? {
        if (page > 1) return null
        val currentBaseUrl = try { getBaseUrl() } catch (e: Exception) {
            Log.e(name, "getMainPage failed to get BaseUrl: ${e.message}"); return null
        }
        Log.d(name, "getMainPage using baseUrl: $currentBaseUrl")
        val document = try { app.get(currentBaseUrl).document } catch (e: Exception) {
            Log.e(name,"getMainPage failed to load content from $currentBaseUrl: ${e.message}"); return null
        }
        val allLists = mutableListOf<HomePageList>()

        fun parseSection(sectionSelector: String, listTitle: String, forceTvType: TvType? = null) {
             try {
                val section = document.select(sectionSelector)
                if (section.isNotEmpty()) {
                    val items = section.mapNotNull { mapElementToSearchResponse(it, currentBaseUrl, forceTvType) }
                    if (items.isNotEmpty()) {
                        allLists.add(HomePageList(listTitle, items))
                        Log.d(name, "getMainPage: Added '$listTitle' with ${items.size} items.")
                    } else {
                         Log.w(name, "getMainPage: No items found for '$listTitle' using selector '$sectionSelector'")
                    }
                } else {
                     Log.w(name, "getMainPage: Section not found for '$listTitle' using selector '$sectionSelector'")
                }
            } catch (e: Exception) {
                 Log.e(name,"getMainPage error processing '$listTitle': ${e.message}", e)
            }
        }

        // Bỏ qua phần Phim Đề Cử/Carousel

        parseSection("section#tv-home ul.MovieList li.TPostMv", "Anime Mới")
        parseSection("section#cn-home ul.MovieList li.TPostMv", "CN Animation Mới", forceTvType = TvType.Cartoon)
        parseSection("section#sn-home ul.MovieList li.TPostMv", "Tokusatsu Mới", forceTvType = TvType.TvSeries)

        try {
            val topAnimeSection = document.select("section#showTopPhim ul.MovieList li div.TPost.A")
             if (topAnimeSection.isNotEmpty()) {
                 val topAnimeList = topAnimeSection.mapNotNull { mapTopAnimeElement(it, currentBaseUrl) }
                 if (topAnimeList.isNotEmpty()) {
                     allLists.add(HomePageList("TOP ANIME NGÀY", topAnimeList))
                     Log.d(name, "getMainPage: Added 'TOP ANIME NGÀY' with ${topAnimeList.size} items.")
                 } else {
                      Log.w(name, "getMainPage: No items found for 'TOP ANIME NGÀY'")
                 }
             } else {
                  Log.w(name, "getMainPage: Section not found for 'TOP ANIME NGÀY'")
             }
        } catch (e: Exception) {
            Log.e(name,"getMainPage error processing TOP ANIME NGÀY: ${e.message}", e)
        }

        if (allLists.isEmpty()) {
            Log.w(name, "getMainPage: No lists were successfully populated. Returning empty response.")
            return newHomePageResponse(emptyList())
        }
        Log.d(name, "getMainPage successful. Returning ${allLists.size} lists.")
        return newHomePageResponse(allLists)

    }

    override suspend fun search(query: String): List<SearchResponse>? {
        val currentBaseUrl = try { getBaseUrl() } catch (e: Exception) {
            Log.e(name, "search failed to get BaseUrl: ${e.message}"); return null
        }
        val encodedQuery = URLEncoder.encode(query, "UTF-8")
        val searchUrl = "$currentBaseUrl/tim-kiem/$encodedQuery.html"
        Log.d(name, "Searching URL: $searchUrl")
        return try {
             val document = app.get(searchUrl, referer = currentBaseUrl).document
             val results = document.select("ul.MovieList li.TPostMv")
             Log.d(name, "Found ${results.size} potential search results for '$query'")
             results.mapNotNull { mapElementToSearchResponse(it, currentBaseUrl) }
         } catch (e: Exception) {
             Log.e(name,"Search failed for query '$query' at url $searchUrl: ${e.message}", e); null
         }
    }

    override suspend fun load(url: String): LoadResponse? {
         Log.d(name, "Loading URL: $url")
         try {
             val currentBaseUrl = try { getBaseUrl() } catch (e: Exception) {
                 Log.e(name, "load failed to get BaseUrl: ${e.message}"); return null
             }
             val mainDocument = app.get(url, referer = currentBaseUrl).document

             val mainTitle = mainDocument.selectFirst("h1.Title")?.text()?.trim()
             val subTitle = mainDocument.selectFirst("h2.SubTitle")?.text()?.trim()

             val displayTitle = mainTitle?.takeIf { it.isNotBlank() }
                 ?: subTitle?.takeIf { it.isNotBlank() }
                 ?: throw ErrorLoadingException("Không tìm thấy tiêu đề (h1.Title hoặc h2.SubTitle)")
             Log.d(name, "Display Title: $displayTitle")

             val titleForKitsu = mainTitle?.takeIf { it.isNotBlank() } ?: subTitle?.takeIf { it.isNotBlank() }
             Log.d(name, "Title for Kitsu Search: $titleForKitsu")

             val animetPosterRaw = mainDocument.selectFirst("article.TPost header div.Image figure.Objf img")?.let { img ->
                 img.attr("data-src").ifBlank { img.attr("src") }
             }
             val animetPoster = animetPosterRaw?.let { fixUrlBased(it) }
             Log.d(name, "Animet Poster Raw: $animetPosterRaw, Fixed: $animetPoster")

             // ... (Lấy description, year, rating, status, genres như cũ) ...
              val description = mainDocument.selectFirst("article.TPost header div.Description")?.text()?.trim()
             val year = mainDocument.selectFirst("p.Info span.Date a")?.text()?.toIntOrNull()
             val ratingText = mainDocument.selectFirst("div#star")?.attr("data-score")
             val rating = ratingText?.toFloatOrNull()?.times(1000)?.toInt()
             Log.d(name, "Rating text: $ratingText, Parsed Rating: $rating")
             val statusElement = mainDocument.selectFirst("div#MvTb-Info div.mvici-left ul.InfoList li:contains(Trạng thái)")
             val statusText = statusElement?.selectFirst("span.Info")?.text()?.trim() ?: statusElement?.ownText()?.trim()
             val parsedStatus = when {
                 statusText?.contains("Đang tiến hành", ignoreCase = true) == true -> ShowStatus.Ongoing
                 statusText?.contains("Hoàn thành", ignoreCase = true) == true -> ShowStatus.Completed
                 statusText?.matches(Regex("""Tập\s+\d+\s*/\s*\d+""", RegexOption.IGNORE_CASE)) == true -> {
                     val parts = statusText.split("/").mapNotNull { it.filter { char -> char.isDigit() }.toIntOrNull() }
                     if (parts.size == 2 && parts[0] >= parts[1]) ShowStatus.Completed else ShowStatus.Ongoing
                 }
                 statusText?.matches(Regex("""Tập\s+\d+""", RegexOption.IGNORE_CASE)) == true -> ShowStatus.Completed
                 else -> null
             }
             val genres = mainDocument.select("div#MvTb-Info div.mvici-left ul.InfoList li:contains(Thể loại) a")
                              .mapNotNull { it.text()?.trim()?.takeIf { tag -> tag.isNotBlank() } }
             Log.d(name, "Genres: $genres")

             // Xác định TvType đã cải thiện
             val isDonghuaGenre = genres.any { it.equals("CN Animation", ignoreCase = true) || it.equals("Hoạt hình Trung Quốc", ignoreCase = true) || it.equals("Donghua", ignoreCase = true) }
             val isDonghuaTitle = displayTitle.contains("Donghua", ignoreCase = true) || displayTitle.contains("(CN)", ignoreCase = true)
             val isDonghuaUrl = url.contains("/cn-animation/", ignoreCase = true)
             val isTokusatsuGenre = genres.any { it.equals("Tokusatsu", ignoreCase = true) }
             val isTokusatsuUrl = url.contains("/tokusatsu/", ignoreCase = true)
             val isMovieGenre = genres.any { it.equals("Movie & OVA", ignoreCase = true) }
             val isOVAGenre = genres.any { it.equals("OVA", ignoreCase = true) }
             val isMovieUrl = url.contains("/movie-ova/", ignoreCase = true)
             val tvType = when {
                 isDonghuaGenre || isDonghuaTitle || isDonghuaUrl -> TvType.Cartoon
                 isTokusatsuGenre || isTokusatsuUrl -> TvType.TvSeries
                 isMovieGenre || isMovieUrl -> TvType.AnimeMovie
                 isOVAGenre -> TvType.OVA
                 else -> TvType.Anime
             }
             Log.d(name, "Determined TvType: $tvType (isDonghuaGenre=$isDonghuaGenre, isDonghuaTitle=$isDonghuaTitle, isDonghuaUrl=$isDonghuaUrl)")

             // Logic lấy poster (chỉ lấy Kitsu cho Anime)
             var finalPosterUrl = animetPoster
             val animeTypesForKitsu = setOf(TvType.Anime, TvType.AnimeMovie, TvType.OVA)
             if (tvType in animeTypesForKitsu && !titleForKitsu.isNullOrBlank()) {
                 Log.d(name, "Type is Anime, attempting Kitsu search for '$titleForKitsu'")
                 val kitsuPoster = getKitsuPoster(titleForKitsu)
                 if (!kitsuPoster.isNullOrBlank()) {
                     finalPosterUrl = kitsuPoster
                     Log.d(name, "Using Kitsu poster: $finalPosterUrl")
                 } else {
                     Log.w(name, "Kitsu poster not found for '$titleForKitsu', using Animet poster: $animetPoster")
                 }
             } else {
                 Log.d(name, "Skipping Kitsu search (Type: $tvType). Using Animet poster: $animetPoster")
             }

             // Logic lấy tập phim (đã sửa selector)
             val watchPageUrl = mainDocument.selectFirst("a.watch_button_more")?.attr("href")?.let { fixUrlBased(it) }
             Log.d(name, "Watch Page URL: $watchPageUrl")
             var episodes = emptyList<Episode>()
             if (!watchPageUrl.isNullOrBlank()) {
                 // ... (try-catch lấy episodes với selector đã sửa giữ nguyên) ...
                 try {
                      Log.d(name, "Fetching episode list from: $watchPageUrl")
                      val episodeListPageDocument = app.get(watchPageUrl, referer = url).document
                      episodes = episodeListPageDocument.select("ul.list-episode li.episode a.episode-link") // Selector đã sửa
                          .mapNotNull episodelist@{ epElement ->
                           val epHref = epElement.attr("href")?.let { fixUrlBased(it) }
                           if (epHref.isNullOrBlank()) {
                               Log.w(name, "Skipping episode due to missing href. Element: ${epElement.outerHtml()}")
                               return@episodelist null
                           }
                           val epIdentifier = epElement.attr("data-epname").trim().ifBlank { epElement.text().trim() }
                           if (epIdentifier.isBlank()) {
                                Log.w(name, "Skipping episode due to missing identifier (data-epname and text). Element: ${epElement.outerHtml()}")
                               return@episodelist null
                           }
                           val epName = if (epIdentifier.equals("Full", ignoreCase = true) || epIdentifier.equals("Movie", ignoreCase = true)) {
                               epIdentifier
                           } else {
                               "Tập $epIdentifier"
                           }
                           val epNum = epIdentifier.takeWhile { it.isDigit() }.toIntOrNull()
                           Log.v(name, "Found episode: Name='$epName', Num=$epNum, URL='$epHref'")
                           newEpisode(epHref) {
                               this.name = epName
                               this.episode = null
                           }
                      }
                      Log.d(name, "Successfully fetched ${episodes.size} episodes.")
                      if (episodes.isEmpty()) {
                          val listHtml = episodeListPageDocument.select("ul.list-episode")?.html()?.take(500) ?: "ul.list-episode not found"
                          Log.w(name, "Episode list is empty after parsing. Selector 'ul.list-episode li.episode a.episode-link' might need further adjustment or page structure changed. List HTML snippet: $listHtml")
                      }
                  } catch (e: Exception) {
                       Log.e(name,"load: Error fetching/parsing episode page $watchPageUrl: ${e.message}", e)
                  }
             } else {
                 Log.w(name, "load: Could not find 'Watch button' (a.watch_button_more) on page $url")
             }
             if (episodes.isEmpty() && tvType != TvType.AnimeMovie && tvType != TvType.OVA) {
                Log.w(name, "load: Episode list is empty for $url (TvType: $tvType).")
             }


             // === BẮT ĐẦU LẤY PHẦN PHIM LIÊN QUAN (SEASONS) ===
             val seasonRecommendations = coroutineScope {
                 Log.d(name, "Starting to fetch related seasons...")
                 // Chọn các thẻ <a> trong div.season_item, loại bỏ thẻ có class 'active'
                 mainDocument.select("div.season_item a:not(.active)").map { seasonElement ->
                     async { // Chạy song song cho mỗi phần
                         val seasonHref = seasonElement.attr("href")?.let { fixUrlBased(it) }
                         val seasonTitle = seasonElement.text()?.trim()
                         var seasonPoster: String? = null

                         if (!seasonHref.isNullOrBlank() && !seasonTitle.isNullOrBlank()) {
                             Log.d(name, "Processing season: '$seasonTitle' - $seasonHref")
                             try {
                                 // Tải trang của phần phim liên quan để lấy poster
                                 Log.d(name, "Fetching season page: $seasonHref")
                                 val seasonDoc = app.get(seasonHref, referer = url).document // Thêm referer
                                 // Lấy poster từ img.wp-post-image trên trang đó
                                 seasonPoster = seasonDoc.selectFirst("img.wp-post-image")?.attr("src")?.let { fixUrlBased(it) }
                                 // Có thể thêm logic xóa param resize nếu cần: getOriginalImageUrl(fixUrlBased(it))
                                 Log.d(name, "Found season poster: $seasonPoster for '$seasonTitle'")

                             } catch (e: Exception) {
                                 // Nếu lỗi khi lấy poster thì bỏ qua, dùng poster null
                                 Log.e(name, "Failed to fetch season page or poster for '$seasonTitle' ($seasonHref): ${e.message}")
                             }
                             // Tạo AnimeSearchResponse, sử dụng tvType của phim chính
                             newAnimeSearchResponse(seasonTitle, seasonHref, tvType) {
                                 this.posterUrl = seasonPoster // Dùng poster lấy được (hoặc null)
                                 if (!seasonPoster.isNullOrBlank() && baseUrl != null && seasonPoster.startsWith(baseUrl!!)) {
                                    this.posterHeaders = mapOf("Referer" to currentBaseUrl)
                                 }
                             }
                         } else {
                             Log.w(name, "Skipping season item due to missing href or title. Element: ${seasonElement.outerHtml()}")
                             null // Bỏ qua nếu không có href hoặc title
                         }
                     } // Kết thúc async
                 }.mapNotNull { it.await() } // Chờ tất cả hoàn thành và lọc bỏ null
             }
             Log.d(name, "Finished fetching ${seasonRecommendations.size} related seasons.")
             // === KẾT THÚC LẤY PHẦN PHIM LIÊN QUAN ===


             // === LẤY RECOMMENDATIONS KHÁC (TỪ MovieListRelated) ===
             val relatedRecommendations = coroutineScope {
                 // ... (code lấy recommendations từ div.MovieListRelated giữ nguyên) ...
                 Log.d(name, "Starting to fetch other recommendations...")
                 val recommendationSelector = "div.MovieListRelated ul.MovieList li.TPostMv"
                 mainDocument.select(recommendationSelector).map { recElement ->
                     async {
                         val recHref = recElement.selectFirst("a")?.attr("href")?.let { fixUrlBased(it) }
                         val recTitle = recElement.selectFirst("a h2.Title")?.text()?.trim()
                             ?: recElement.selectFirst("a")?.attr("title")?.trim()
                         var recPoster: String? = null
                         var recType: TvType = TvType.Anime
                         if (!recHref.isNullOrBlank() && !recTitle.isNullOrBlank()) {
                              Log.v(name, "Processing related recommendation: '$recTitle' - $recHref")
                             try {
                                 recPoster = recElement.selectFirst("div.Image figure img")?.let { img -> img.attr("data-src").ifBlank { img.attr("src") } }?.let { fixUrlBased(it) }
                                 Log.v(name, "Related recommendation poster (direct): $recPoster")
                                 recType = when {
                                      recHref.contains("/cn-animation/", ignoreCase = true) -> TvType.Cartoon
                                      recHref.contains("/tokusatsu/", ignoreCase = true) -> TvType.TvSeries
                                      recHref.contains("/movie-ova/", ignoreCase = true) -> TvType.AnimeMovie
                                      recTitle.contains("Donghua", ignoreCase = true) || recTitle.contains("(CN)", ignoreCase = true) -> TvType.Cartoon
                                      recTitle.contains("Movie", ignoreCase = true) -> TvType.AnimeMovie
                                      recTitle.contains("OVA", ignoreCase = true) -> TvType.OVA
                                      else -> TvType.Anime
                                 }
                                 Log.v(name, "Related recommendation type: $recType")

                             } catch (e: Exception) {
                                 Log.w(name,"Failed to process related recommendation '$recTitle': ${e.message}")
                             }
                             newAnimeSearchResponse(recTitle, recHref, recType) {
                                 this.posterUrl = recPoster
                                 if (!recPoster.isNullOrBlank() && baseUrl != null && recPoster.startsWith(baseUrl!!)) {
                                      this.posterHeaders = mapOf("Referer" to currentBaseUrl)
                                 }
                             }
                         } else {
                              Log.w(name, "Skipping related recommendation due to missing href or title.")
                              null
                         }
                     }
                 }.mapNotNull { it.await() }
             }
             Log.d(name, "Finished fetching ${relatedRecommendations.size} other recommendations.")
             // === KẾT THÚC LẤY RECOMMENDATIONS KHÁC ===


             // *** KẾT HỢP CÁC LOẠI RECOMMENDATIONS ***
             val allRecommendations = seasonRecommendations + relatedRecommendations
             Log.d(name, "Total recommendations: ${allRecommendations.size}")


             // Trả về LoadResponse phù hợp
             // *** SỬA: Dùng allRecommendations ***
             if (tvType == TvType.AnimeMovie || tvType == TvType.OVA || (tvType != TvType.TvSeries && tvType != TvType.Cartoon && tvType != TvType.Anime && episodes.isEmpty()) ) {
                 Log.d(name, "Returning newMovieLoadResponse for '$displayTitle' (Type: $tvType, Eps: ${episodes.size})")
                 return newMovieLoadResponse(displayTitle, url, tvType, url) {
                     this.apiName = this@AnimetProvider.name
                     this.posterUrl = finalPosterUrl
                     this.year = year
                     this.plot = description
                     this.rating = rating
                     this.tags = genres
                     this.recommendations = allRecommendations // *** Sử dụng list đã kết hợp ***
                     if (!finalPosterUrl.isNullOrBlank() && baseUrl != null && finalPosterUrl.startsWith(baseUrl!!)) {
                        this.posterHeaders = mapOf("Referer" to currentBaseUrl)
                     }
                 }
             } else {
                 Log.d(name, "Returning newTvSeriesLoadResponse for '$displayTitle' (Type: $tvType, Eps: ${episodes.size})")
                 return newTvSeriesLoadResponse(displayTitle, url, tvType, episodes) {
                     this.apiName = this@AnimetProvider.name
                     this.posterUrl = finalPosterUrl
                     this.year = year
                     this.plot = description
                     this.rating = rating
                     this.tags = genres
                     this.showStatus = parsedStatus
                     this.recommendations = allRecommendations // *** Sử dụng list đã kết hợp ***
                      if (!finalPosterUrl.isNullOrBlank() && baseUrl != null && finalPosterUrl.startsWith(baseUrl!!)) {
                         this.posterHeaders = mapOf("Referer" to currentBaseUrl)
                      }
                 }
             }
         } catch (e: Exception) {
             Log.e(name,"Load failed for URL $url: ${e.message}", e)
             return null
         }
    }

    // ... (loadLinks giữ nguyên) ...
     override suspend fun loadLinks(
        data: String, // episodeUrl
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        // ... (code loadLinks giữ nguyên) ...
        val episodeUrl = data
        if (!episodeUrl.startsWith("http")) {
             Log.e(name, "loadLinks: Invalid episodeUrl received: '$episodeUrl'")
             return false
        }
        Log.d(name, "loadLinks triggered for episodeUrl: $episodeUrl")

        val encodedUrl = try {
            URLEncoder.encode(episodeUrl, "UTF-8")
        } catch (e: Exception) {
            Log.e(name, "loadLinks: Failed to URL encode episodeUrl: $episodeUrl - ${e.message}")
            return false
        }

        val apiUrl = "https://m3u8.013666.xyz/animet/get-m3u8?url=$encodedUrl"
        Log.d(name, "loadLinks: Calling new JSON API: $apiUrl")

        try {
            val response = app.get(apiUrl, timeout = 20_000L)

            if (!response.isSuccessful) {
                Log.e(name, "loadLinks: API request failed! Code: ${response.code}, URL: $apiUrl, Response: ${response.text.take(500)}")
                return false
            }

            val responseText = response.text
            Log.d(name, "loadLinks: API Response Text: $responseText")

            val apiResponse = try {
                 AppUtils.parseJson<M3u8ApiResponse>(responseText)
            } catch (e: Exception) {
                 Log.e(name, "loadLinks: Failed to parse JSON response. Error: ${e.message}", e)
                 Log.e(name, "loadLinks: Response text was: $responseText")
                 return false
            }

            val m3u8Link = apiResponse.m3u8?.trim()

            if (m3u8Link.isNullOrBlank()) {
                 Log.e(name, "loadLinks: Missing 'm3u8' field in JSON response.")
                 return false
            }

            if (!m3u8Link.startsWith("http") || !m3u8Link.contains(".m3u8", ignoreCase = true)) {
                Log.w(name, "loadLinks: URL from API doesn't seem like M3U8: '$m3u8Link'")
                 if (m3u8Link.startsWith("http") && m3u8Link.contains(".mp4", ignoreCase = true)) {
                     Log.i(name, "loadLinks: Found MP4 link instead. Submitting as MP4.")
                      callback.invoke(
                          newExtractorLink(
                              source = this.name,
                              name = "Animet (MP4)",
                              url = m3u8Link,
                              type = ExtractorLinkType.VIDEO
                          ) {
                              this.quality = Qualities.Unknown.value
                          }
                      )
                     return true
                 }
                 Log.e(name, "loadLinks: URL is not a valid M3U8 or MP4 link.")
                return false
            }

            Log.d(name, "loadLinks: Extracted M3U8 URL: $m3u8Link")

            callback.invoke(
                 newExtractorLink(
                     source = this.name,
                     name = "Animet",
                     url = m3u8Link,
                     type = ExtractorLinkType.M3U8
                 ) {
                      this.quality = Qualities.Unknown.value
                 }
             )
            Log.d(name, "loadLinks: M3U8 link submitted successfully.")

            Log.d(name, "loadLinks: Subtitle extraction skipped.")

            return true

        } catch (e: Exception) {
            when (e) {
                is java.net.SocketTimeoutException, is java.io.InterruptedIOException -> {
                    Log.e(name, "loadLinks: Timeout waiting for API response from $apiUrl")
                }
                else -> {
                    Log.e(name, "loadLinks: Exception during API call or JSON parsing! ${e.message}", e)
                }
            }
            return false
        }
    }


} // <-- Dấu ngoặc đóng class AnimetProvider
