package recloudstream // Hoặc package bạn muốn dùng

// Import các lớp cần thiết
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Element
import com.lagradost.cloudstream3.app
import kotlin.text.Regex
import org.jsoup.Jsoup
import java.net.URLEncoder
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.*
import android.util.Log

// === DATA CLASSES ===
// Dùng cho Kitsu API
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
) {
    // Tối ưu: Thêm một helper property để lấy URL tốt nhất hiện có
    val bestUrl: String?
        get() = original ?: large ?: medium ?: small ?: tiny
}

// Dùng cho Ajax request trong loadLinks
data class AjaxPlayerResponse(val file: String?)


// --- Định nghĩa lớp Provider ---
class AnimetProvider : MainAPI() {
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

    // Cache cho baseUrl để giảm thiểu request không cần thiết
    private var baseUrl: String? = null
    private val baseUrlMutex = Mutex()
    private val defaultBaseUrl = "https://anime6.site"

    // === HELPER FUNCTIONS ===

    /**
     * Lấy và cache baseUrl.
     * Tối ưu: Hợp nhất logic fallback và ghi log rõ ràng hơn.
     */
    private suspend fun getBaseUrl(): String {
        baseUrl?.let { return it } // Trả về ngay nếu đã có trong cache
        return baseUrlMutex.withLock {
            // Kiểm tra lại sau khi vào lock để tránh race condition
            baseUrl?.let { return it }

            Log.d(name, "BaseUrl is null, fetching from $mainUrl")
            val newUrl = try {
                val response = app.get(mainUrl, timeout = 15_000L).text
                val domainText = Jsoup.parse(response).selectFirst("span.text-success.font-weight-bold")?.text()?.trim()
                if (!domainText.isNullOrBlank()) {
                    "https://${domainText.lowercase().removeSuffix("/")}".also {
                        Log.d(name, "Fetched BaseUrl successfully: $it")
                    }
                } else {
                    Log.w(name, "Could not extract domain from $mainUrl. Falling back to default.")
                    defaultBaseUrl
                }
            } catch (e: Exception) {
                Log.e(name, "Failed to fetch or parse baseUrl from $mainUrl: ${e.message}. Falling back to default.")
                defaultBaseUrl
            }
            baseUrl = newUrl
            newUrl
        }
    }

    /**
     * Tối ưu: Đơn giản hoá Regex và logic.
     */
    private fun getOriginalImageUrl(thumbnailUrl: String?): String? {
        if (thumbnailUrl.isNullOrBlank()) return null
        return thumbnailUrl.replace(Regex("(-\\d+x\\d+)(?=\\.\\w+$)"), "")
    }

    /**
     * Tìm kiếm poster trên Kitsu API.
     * Tối ưu: Sử dụng helper property 'bestUrl' từ KitsuPoster.
     */
    private suspend fun getKitsuPoster(title: String): String? {
        Log.d(name, "Searching Kitsu for: $title")
        return try {
            val encodedTitle = URLEncoder.encode(title, "UTF-8")
            val searchUrl = "https://kitsu.io/api/edge/anime?filter[text]=$encodedTitle&page[limit]=1"
            val response = app.get(searchUrl, timeout = 10_000L).parsedSafe<KitsuMain>()
            response?.data?.firstOrNull()?.attributes?.posterImage?.bestUrl?.also {
                Log.d(name, "Found Kitsu poster: $it")
            } ?: run {
                Log.w(name, "Kitsu poster not found for title '$title'")
                null
            }
        } catch (e: Exception) {
            Log.e(name, "Kitsu API Error for title '$title'. Error: ${e.message}", e)
            null
        }
    }
    
    /**
     * Tối ưu: Hợp nhất mapElementToSearchResponse và mapTopAnimeElement thành một hàm duy nhất
     * để giảm lặp code.
     */
    private fun mapElementToSearchResponse(element: Element, currentBaseUrl: String, forceTvType: TvType? = null): AnimeSearchResponse? {
        val linkTag = element.selectFirst("a") ?: return null
        val href = fixUrl(linkTag.attr("href"), currentBaseUrl)
        if (href.isBlank()) return null

        // Lấy title từ nhiều vị trí có thể
        val title = element.selectFirst("h2.Title, div.Title")?.text()?.trim()
            ?: linkTag.attr("title")?.trim()
            ?: linkTag.text()?.trim()
        if (title.isNullOrBlank()) return null

        val posterTag = element.selectFirst("div.Image figure img")
        val posterUrl = posterTag?.let { img ->
            img.attr("data-src").ifBlank { img.attr("src") }
        }?.let { fixUrl(it, currentBaseUrl) }

        // Logic xác định TvType được giữ nguyên và cải tiến
        val tvType = forceTvType ?: run {
            val latestEpText = element.selectFirst("span.mli-eps, span.mli-quality")?.text()?.trim()
            when {
                href.contains("/the-loai/cn-animation", true) -> TvType.Cartoon
                href.contains("/the-loai/tokusatsu", true) -> TvType.TvSeries
                href.contains("phim-le", true) -> TvType.AnimeMovie
                title.contains("Donghua", true) || title.contains("(CN)", true) -> TvType.Cartoon
                latestEpText?.contains("Movie", true) == true -> TvType.AnimeMovie
                latestEpText?.contains("OVA", true) == true -> TvType.OVA
                title.contains("Movie", true) -> TvType.AnimeMovie
                title.contains("OVA", true) -> TvType.OVA
                else -> TvType.Anime
            }
        }

        return newAnimeSearchResponse(title, href, tvType) {
            this.posterUrl = posterUrl
            // Thêm posterHeaders nếu poster host trên cùng domain
            if (!posterUrl.isNullOrBlank() && posterUrl.startsWith(currentBaseUrl)) {
                this.posterHeaders = mapOf("Referer" to currentBaseUrl)
            }
        }
    }
    
    // === API OVERRIDES ===

    /**
     * Tối ưu: Tạo một hàm helper `getMainPageList` để loại bỏ hoàn toàn việc lặp code
     * khi xử lý các danh sách trên trang chủ.
     */
    private suspend fun getMainPageList(
        document: org.jsoup.nodes.Document,
        currentBaseUrl: String,
        listName: String,
        selector: String,
        forceTvType: TvType? = null
    ): HomePageList? {
        return try {
            val items = document.select(selector)
                .mapNotNull { mapElementToSearchResponse(it, currentBaseUrl, forceTvType) }
            if (items.isNotEmpty()) HomePageList(listName, items) else null
        } catch (e: Exception) {
            Log.e(name, "getMainPage error processing '$listName': ${e.message}", e)
            null
        }
    }
    
    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse? {
        if (page > 1) return null
        val currentBaseUrl = getBaseUrl()
        Log.d(name, "getMainPage using baseUrl: $currentBaseUrl")

        val document = try {
            app.get(currentBaseUrl).document
        } catch (e: Exception) {
            Log.e(name, "getMainPage failed to load content from $currentBaseUrl: ${e.message}")
            return null
        }

        // Sử dụng coroutineScope và hàm helper mới để fetch song song và code gọn gàng
        val lists = coroutineScope {
            listOf(
                async { getMainPageList(document, currentBaseUrl, "Anime Mới", "section#tv-home ul.MovieList li.TPostMv") },
                async { getMainPageList(document, currentBaseUrl, "CN Animation Mới", "section#cn-home ul.MovieList li.TPostMv", TvType.Cartoon) },
                async { getMainPageList(document, currentBaseUrl, "Tokusatsu Mới", "section#sn-home ul.MovieList li.TPostMv", TvType.TvSeries) },
                async { getMainPageList(document, currentBaseUrl, "TOP ANIME NGÀY", "section#showTopPhim ul.MovieList li div.TPost.A") }
            ).awaitAll().filterNotNull()
        }

        if (lists.isEmpty()) {
            Log.w(name, "getMainPage: No lists were successfully populated.")
            return newHomePageResponse(emptyList())
        }

        Log.d(name, "getMainPage successful. Returning ${lists.size} lists.")
        return newHomePageResponse(lists)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val currentBaseUrl = getBaseUrl()
        val encodedQuery = URLEncoder.encode(query, "UTF-8")
        val searchUrl = "$currentBaseUrl/tim-kiem/$encodedQuery.html"
        Log.d(name, "Searching URL: $searchUrl")

        return try {
            val document = app.get(searchUrl, referer = currentBaseUrl).document
            document.select("ul.MovieList li.TPostMv")
                .mapNotNull { mapElementToSearchResponse(it, currentBaseUrl) }
        } catch (e: Exception) {
            Log.e(name, "Search failed for query '$query' at url $searchUrl: ${e.message}", e)
            emptyList()
        }
    }

    override suspend fun load(url: String): LoadResponse? {
        Log.d(name, "Loading URL: $url")
        try {
            val currentBaseUrl = getBaseUrl()
            val mainDocument = app.get(url, referer = currentBaseUrl).document

            val mainTitle = mainDocument.selectFirst("h1.Title")?.text()?.trim()
            val subTitle = mainDocument.selectFirst("h2.SubTitle")?.text()?.trim()
            val displayTitle = mainTitle.takeIf { !it.isNullOrBlank() }
                ?: subTitle.takeIf { !it.isNullOrBlank() }
                ?: throw ErrorLoadingException("Không tìm thấy tiêu đề")

            val description = mainDocument.selectFirst("article.TPost header div.Description")?.text()?.trim()
            val year = mainDocument.selectFirst("p.Info span.Date a")?.text()?.toIntOrNull()
            val rating = mainDocument.selectFirst("div#star")?.attr("data-score")?.toFloatOrNull()?.times(1000)?.toInt()
            val genres = mainDocument.select("div#MvTb-Info div.mvici-left ul.InfoList li:contains(Thể loại) a").mapNotNull { it.text() }

            val statusText = mainDocument.selectFirst("div#MvTb-Info div.mvici-left ul.InfoList li:contains(Trạng thái) span.Info")?.text()?.trim()
            val parsedStatus = when {
                statusText?.contains("Đang tiến hành", true) == true -> ShowStatus.Ongoing
                statusText?.contains("Hoàn thành", true) == true -> ShowStatus.Completed
                else -> null
            }

            // Xác định TvType chính xác hơn
            val tvType = when {
                genres.any { it.contains("CN Animation", true) || it.contains("Donghua", true) } ||
                displayTitle.contains("Donghua", true) || displayTitle.contains("(CN)", true) ||
                url.contains("/cn-animation/", true) -> TvType.Cartoon
                genres.any { it.contains("Tokusatsu", true) } || url.contains("/tokusatsu/", true) -> TvType.TvSeries
                genres.any { it.contains("Movie", true) } || url.contains("/movie-ova/", true) -> TvType.AnimeMovie
                genres.any { it.contains("OVA", true) } -> TvType.OVA
                else -> TvType.Anime
            }

            val animetPoster = mainDocument.selectFirst("article.TPost img")?.let { getOriginalImageUrl(it.attr("src")) }?.let { fixUrl(it, currentBaseUrl) }

            // Lấy poster từ Kitsu nếu là Anime
            val finalPosterUrl = if (tvType in setOf(TvType.Anime, TvType.AnimeMovie, TvType.OVA)) {
                getKitsuPoster(mainTitle ?: subTitle ?: "") ?: animetPoster
            } else {
                animetPoster
            }

            // Lấy danh sách tập phim
            val episodes = mainDocument.select("ul.list-episode li.episode a.episode-link").mapNotNull { epElement ->
                val epHref = fixUrl(epElement.attr("href"), currentBaseUrl)
                val epIdentifier = epElement.attr("data-epname").trim().ifBlank { epElement.text().trim() }
                if (epHref.isBlank() || epIdentifier.isBlank()) return@mapNotNull null

                val epName = if (epIdentifier.equals("Full", true) || epIdentifier.equals("Movie", true)) epIdentifier else "Tập $epIdentifier"
                newEpisode(epHref) { this.name = epName }
            }

            // Lấy phim đề xuất (bao gồm cả các phần và phim liên quan khác)
            val recommendations = coroutineScope {
                mainDocument.select("div.season_item a:not(.active), div.MovieListRelated ul.MovieList li.TPostMv").map { element ->
                    async { mapElementToSearchResponse(element, currentBaseUrl) }
                }.awaitAll().filterNotNull()
            }

            // Dùng isMovie to load movie or series
            val isMovie = tvType == TvType.AnimeMovie || tvType == TvType.OVA || episodes.isEmpty()
            return if (isMovie) {
                newMovieLoadResponse(displayTitle, url, tvType, url) {
                    this.posterUrl = finalPosterUrl
                    this.year = year
                    this.plot = description
                    this.rating = rating
                    this.tags = genres
                    this.recommendations = recommendations
                }
            } else {
                newTvSeriesLoadResponse(displayTitle, url, tvType, episodes) {
                    this.posterUrl = finalPosterUrl
                    this.year = year
                    this.plot = description
                    this.rating = rating
                    this.tags = genres
                    this.showStatus = parsedStatus
                    this.recommendations = recommendations
                }
            }
        } catch (e: Exception) {
            Log.e(name, "Load failed for URL $url: ${e.message}", e)
            return null
        }
    }

    /**
     * Tối ưu: Sử dụng data class và `parsedSafe` để parse JSON một cách an toàn và ổn định,
     * thay vì dùng Regex.
     */
    override suspend fun loadLinks(
        data: String, // episodeUrl
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val episodeUrl = data
        Log.d(name, "loadLinks triggered for episodeUrl: $episodeUrl")

        val idRegex = Regex("-(\\d+)\\.(\\d+)\\.html$")
        val (filmId, epId) = idRegex.find(episodeUrl)?.destructured ?: run {
            Log.e(name, "Could not extract filmId/epId from URL: $episodeUrl")
            return false
        }
        Log.d(name, "Extracted -> FilmID: $filmId, EpID: $epId")

        return try {
            val currentBaseUrl = getBaseUrl()
            val ajaxUrl = "$currentBaseUrl/ajax/player"

            val postData = mapOf("id" to filmId, "ep" to epId, "sv" to "0")
            val headers = mapOf("Referer" to currentBaseUrl, "Origin" to currentBaseUrl)

            // Tối ưu: Dùng parsedSafe để parse JSON
            val playerResponse = app.post(ajaxUrl, data = postData, headers = headers).parsedSafe<AjaxPlayerResponse>()
            val m3u8Link = playerResponse?.file?.replace("\\/", "/")

            if (m3u8Link.isNullOrBlank()) {
                Log.e(name, "Could not extract m3u8 link from AJAX response.")
                return false
            }

            Log.d(name, "Extracted M3U8 URL: $m3u8Link")
            callback.invoke(
                newExtractorLink(
                    source = this.name,
                    name = this.name,
                    url = m3u8Link,
                    referer = currentBaseUrl, // Referer rất quan trọng cho HLS
                    quality = Qualities.Unknown.value,
                    type = ExtractorLinkType.M3U8,
                )
            )
            true
        } catch (e: Exception) {
            Log.e(name, "loadLinks failed: ${e.message}", e)
            false
        }
    }
}
