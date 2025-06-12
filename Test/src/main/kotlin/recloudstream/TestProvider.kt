package recloudstream // Hoặc package của bạn

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
    // Helper property để lấy URL ảnh tốt nhất
    val bestUrl: String?
        get() = original ?: large ?: medium ?: small ?: tiny
}

// Data class cho phản hồi Ajax trong loadLinks
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

    @Volatile
    private var baseUrl: String? = null
    private val baseUrlMutex = Mutex()
    private val defaultBaseUrl = "https://anime6.site"

    // === HELPER FUNCTIONS ===

    private suspend fun getBaseUrl(): String {
        baseUrl?.let { return it }
        return baseUrlMutex.withLock {
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

    private fun fixUrlBased(url: String?, currentBaseUrl: String): String? {
        if (url.isNullOrBlank()) return null
        return when {
            url.startsWith("http") -> url
            url.startsWith("//") -> "https:$url"
            url.startsWith("/") -> "$currentBaseUrl$url"
            else -> url
        }
    }
    
    /**
     * SỬA LỖI: Tách câu lệnh if để trình biên dịch có thể "smart cast"
     * thumbnailUrl thành kiểu String không-null.
     */
    private fun getOriginalImageUrl(thumbnailUrl: String?): String? {
        if (thumbnailUrl.isNullOrBlank()) {
            return null
        }
        // Sau khi kiểm tra isNullOrBlank, ở đây thumbnailUrl được đảm bảo là non-null.
        return thumbnailUrl.replace(Regex("(-\\d+x\\d+)(?=\\.\\w+$)"), "")
    }

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
    
    private fun mapElementToSearchResponse(element: Element, currentBaseUrl: String, forceTvType: TvType? = null): AnimeSearchResponse? {
        val linkTag = element.selectFirst("a") ?: return null
        val href = fixUrlBased(linkTag.attr("href"), currentBaseUrl) ?: return null

        val title = element.selectFirst("h2.Title, div.Title")?.text()?.trim()
            ?: linkTag.attr("title")?.trim()
            ?: linkTag.text()?.trim()
        if (title.isNullOrBlank()) return null

        val posterTag = element.selectFirst("div.Image figure img")
        val posterUrl = posterTag?.let { img ->
            img.attr("data-src").ifBlank { img.attr("src") }
        }?.let { fixUrlBased(it, currentBaseUrl) }

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
            if (!posterUrl.isNullOrBlank() && posterUrl.startsWith(currentBaseUrl)) {
                this.posterHeaders = mapOf("Referer" to currentBaseUrl)
            }
        }
    }

    // === API OVERRIDES ===

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
        val document = app.get(currentBaseUrl).document

        val lists = coroutineScope {
            listOf(
                async { getMainPageList(document, currentBaseUrl, "Anime Mới", "section#tv-home ul.MovieList li.TPostMv") },
                async { getMainPageList(document, currentBaseUrl, "CN Animation Mới", "section#cn-home ul.MovieList li.TPostMv", TvType.Cartoon) },
                async { getMainPageList(document, currentBaseUrl, "Tokusatsu Mới", "section#sn-home ul.MovieList li.TPostMv", TvType.TvSeries) },
                async { getMainPageList(document, currentBaseUrl, "TOP ANIME NGÀY", "section#showTopPhim ul.MovieList li div.TPost.A") }
            ).awaitAll().filterNotNull()
        }

        return newHomePageResponse(lists.ifEmpty { emptyList() })
    }

    override suspend fun search(query: String): List<SearchResponse> {
        return try {
            val currentBaseUrl = getBaseUrl()
            val encodedQuery = URLEncoder.encode(query, "UTF-8")
            val searchUrl = "$currentBaseUrl/tim-kiem/$encodedQuery.html"
            app.get(searchUrl, referer = currentBaseUrl).document
                .select("ul.MovieList li.TPostMv")
                .mapNotNull { mapElementToSearchResponse(it, currentBaseUrl) }
        } catch (e: Exception) {
            Log.e(name, "Search failed for query '$query': ${e.message}", e)
            emptyList()
        }
    }

    override suspend fun load(url: String): LoadResponse? {
        try {
            val currentBaseUrl = getBaseUrl()
            val mainDocument = app.get(url, referer = currentBaseUrl).document
            
            val displayTitle = mainDocument.selectFirst("h1.Title, h2.SubTitle")?.text()
                ?: throw ErrorLoadingException("Không tìm thấy tiêu đề")

            // Lấy thông tin phim
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
            val tvType = when {
                genres.any { it.contains("CN Animation", true) || it.contains("Donghua", true) } ||
                displayTitle.contains("Donghua", true) || displayTitle.contains("(CN)", true) ||
                url.contains("/cn-animation/", true) -> TvType.Cartoon
                genres.any { it.contains("Tokusatsu", true) } || url.contains("/tokusatsu/", true) -> TvType.TvSeries
                genres.any { it.contains("Movie", true) } || url.contains("/movie-ova/", true) -> TvType.AnimeMovie
                genres.any { it.contains("OVA", true) } -> TvType.OVA
                else -> TvType.Anime
            }
            val animetPoster = mainDocument.selectFirst("article.TPost img")?.attr("src")?.let {
                fixUrlBased(getOriginalImageUrl(it), currentBaseUrl)
            }
            val finalPosterUrl = if (tvType in setOf(TvType.Anime, TvType.AnimeMovie, TvType.OVA)) {
                getKitsuPoster(displayTitle) ?: animetPoster
            } else {
                animetPoster
            }

            // =================================================================
            // NƠI CẬP NHẬT SELECTOR KHI WEBSITE THAY ĐỔI
            // =================================================================
            val episodeSelector = "div.episode_list ul.list-episode li a" 
            Log.d(name, "Finding episodes with selector: '$episodeSelector'")
            val episodeElements = mainDocument.select(episodeSelector)
            if (episodeElements.isEmpty()) {
                Log.w(name, "Episode list is EMPTY. Selector '$episodeSelector' is likely outdated. Please inspect the website's HTML.")
            } else {
                Log.d(name, "Found ${episodeElements.size} episode elements.")
            }
            
            val episodes = episodeElements.mapNotNull { epElement ->
                val epHref = fixUrlBased(epElement.attr("href"), currentBaseUrl) ?: return@mapNotNull null
                val epIdentifier = epElement.attr("data-epname").trim().ifBlank {
                    epElement.selectFirst("span.ep_name")?.text()?.trim() ?: epElement.text().trim()
                }
                if (epIdentifier.isBlank()) return@mapNotNull null
                val epName = if (epIdentifier.equals("Full", true) || epIdentifier.equals("Movie", true)) epIdentifier else "Tập $epIdentifier"
                newEpisode(epHref) { this.name = epName }
            }

            val recommendationSelector = "div.season_item a:not(.active), div.MovieListRelated ul.MovieList li"
            Log.d(name, "Finding recommendations with selector: '$recommendationSelector'")
            val recommendationElements = mainDocument.select(recommendationSelector)
            if (recommendationElements.isEmpty()){
                Log.w(name, "Recommendation list is EMPTY. Selector '$recommendationSelector' is likely outdated.")
            } else {
                Log.d(name, "Found ${recommendationElements.size} recommendation elements.")
            }

            val recommendations = coroutineScope {
                recommendationElements.map { element ->
                    async { mapElementToSearchResponse(element, currentBaseUrl) }
                }.awaitAll().filterNotNull()
            }
            // =================================================================

            val isMovie = tvType == TvType.AnimeMovie || tvType == TvType.OVA || (tvType == TvType.Anime && episodes.isEmpty())
            return if (isMovie) {
                newMovieLoadResponse(displayTitle, url, tvType, url) {
                    this.posterUrl = finalPosterUrl; this.year = year; this.plot = description
                    this.rating = rating; this.tags = genres; this.recommendations = recommendations
                }
            } else {
                newTvSeriesLoadResponse(displayTitle, url, tvType, episodes) {
                    this.posterUrl = finalPosterUrl; this.year = year; this.plot = description
                    this.rating = rating; this.tags = genres; this.showStatus = parsedStatus
                    this.recommendations = recommendations
                }
            }
        } catch (e: Exception) {
            Log.e(name, "Load failed for URL $url: ${e.message}", e)
            return null
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val idRegex = Regex("-(\\d+)\\.(\\d+)\\.html$")
        val (filmId, epId) = idRegex.find(data)?.destructured ?: return false

        return try {
            val currentBaseUrl = getBaseUrl()
            val ajaxUrl = "$currentBaseUrl/ajax/player"
            val postData = mapOf("id" to filmId, "ep" to epId, "sv" to "0")
            val headers = mapOf("Referer" to currentBaseUrl, "Origin" to currentBaseUrl)

            val playerResponse = app.post(ajaxUrl, data = postData, headers = headers).parsedSafe<AjaxPlayerResponse>()
            val m3u8Link = playerResponse?.file?.replace("\\/", "/") ?: return false

            callback.invoke(
                newExtractorLink(
                    source = this.name,
                    name = this.name,
                    url = m3u8Link,
                    type = ExtractorLinkType.M3U8
                ) {
                    this.referer = currentBaseUrl
                    this.quality = Qualities.Unknown.value
                }
            )
            true
        } catch (e: Exception) {
            Log.e(name, "loadLinks failed: ${e.message}", e)
            false
        }
    }
}
