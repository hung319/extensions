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
    // Tối ưu an toàn: Thêm helper để lấy URL ảnh tốt nhất
    val bestUrl: String?
        get() = original ?: large ?: medium ?: small ?: tiny
}

// Tối ưu an toàn: Data class cho phản hồi Ajax trong loadLinks
data class AjaxPlayerResponse(val file: String?)


// --- Định nghĩa lớp Provider ---
class AnimetProvider : MainAPI() {
    override var mainUrl = "https://animet.org"
    override var name = "Animet"
    override val hasMainPage = true
    override var lang = "vi"
    override val hasDownloadSupport = true
    override val supportedTypes = setOf( TvType.Anime, TvType.AnimeMovie, TvType.OVA, TvType.Cartoon, TvType.TvSeries )

    @Volatile
    private var baseUrl: String? = null
    private val baseUrlMutex = Mutex()
    private val defaultBaseUrl = "https://anime6.site"

    // === HELPER FUNCTIONS ===

    private suspend fun getBaseUrl(): String {
        baseUrl?.let { return it }
        return baseUrlMutex.withLock {
            baseUrl?.let { return it }
            val newUrl = try {
                val response = app.get(mainUrl, timeout = 15_000L).text
                val domainText = Jsoup.parse(response).selectFirst("span.text-success.font-weight-bold")?.text()?.trim()
                if (!domainText.isNullOrBlank()) "https://${domainText.lowercase().removeSuffix("/")}" else defaultBaseUrl
            } catch (e: Exception) {
                Log.e(name, "Failed to fetch or parse baseUrl from $mainUrl: ${e.message}")
                defaultBaseUrl
            }
            baseUrl = newUrl
            newUrl
        }
    }
    
    // Giữ nguyên hàm fixUrl của code gốc vì nó hoạt động đúng
    private suspend fun fixUrlBased(url: String?): String? {
        if (url.isNullOrBlank()) return null
        val currentBaseUrl = getBaseUrl()
        return when {
            url.startsWith("http") -> url
            url.startsWith("//") -> "https:$url"
            url.startsWith("/") -> "$currentBaseUrl$url"
            else -> url
        }
    }
    
    private fun getOriginalImageUrl(thumbnailUrl: String?): String? {
        if (thumbnailUrl.isNullOrBlank()) return null
        return thumbnailUrl.replace(Regex("(-\\d+x\\d+)(?=\\.\\w+$)"), "")
    }

    private suspend fun getKitsuPoster(title: String): String? {
        return try {
            val encodedTitle = URLEncoder.encode(title, "UTF-8")
            val searchUrl = "https://kitsu.io/api/edge/anime?filter[text]=$encodedTitle&page[limit]=1"
            app.get(searchUrl, timeout = 10_000L).parsedSafe<KitsuMain>()?.data?.firstOrNull()?.attributes?.posterImage?.bestUrl
        } catch (e: Exception) {
            Log.e(name, "Kitsu API Error for title '$title'", e)
            null
        }
    }

    // Tối ưu an toàn: Hợp nhất 2 hàm map element
    private suspend fun mapElementToSearchResponse(element: Element, forceTvType: TvType? = null): AnimeSearchResponse? {
        val linkTag = element.selectFirst("article a, a") ?: return null
        val href = fixUrlBased(linkTag.attr("href")) ?: return null
        
        val title = linkTag.selectFirst("h2.Title")?.text()?.trim()
            ?: linkTag.attr("title")?.trim()
            ?: linkTag.text()?.trim()
        if (title.isNullOrBlank()) return null

        val posterUrl = linkTag.selectFirst("div.Image figure img")?.let { img ->
            fixUrlBased(img.attr("data-src").ifBlank { img.attr("src") })
        }

        val tvType = forceTvType ?: run {
            val latestEpText = linkTag.selectFirst("span.mli-eps, span.mli-quality")?.text()?.trim()
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
        return newAnimeSearchResponse(title, href, tvType) { this.posterUrl = posterUrl }
    }

    // === API OVERRIDES ===

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse? {
        if (page > 1) return null
        val document = app.get(getBaseUrl()).document
        // Tối ưu an toàn: Chạy song song các tác vụ trên trang chủ
        return coroutineScope {
            val lists = listOf(
                async {
                    val items = document.select("section#tv-home ul.MovieList li.TPostMv").mapNotNull { mapElementToSearchResponse(it) }
                    if (items.isNotEmpty()) HomePageList("Anime Mới", items) else null
                },
                async {
                    val items = document.select("section#cn-home ul.MovieList li.TPostMv").mapNotNull { mapElementToSearchResponse(it, TvType.Cartoon) }
                    if (items.isNotEmpty()) HomePageList("CN Animation Mới", items) else null
                },
                async {
                    val items = document.select("section#sn-home ul.MovieList li.TPostMv").mapNotNull { mapElementToSearchResponse(it, TvType.TvSeries) }
                    if (items.isNotEmpty()) HomePageList("Tokusatsu Mới", items) else null
                },
                async {
                    val items = document.select("section#showTopPhim ul.MovieList li div.TPost.A").mapNotNull { mapElementToSearchResponse(it) }
                    if (items.isNotEmpty()) HomePageList("TOP ANIME NGÀY", items) else null
                }
            ).awaitAll().filterNotNull()
            newHomePageResponse(lists)
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        return try {
            val searchUrl = "${getBaseUrl()}/tim-kiem/${URLEncoder.encode(query, "UTF-8")}.html"
            app.get(searchUrl).document
                .select("ul.MovieList li.TPostMv")
                .mapNotNull { mapElementToSearchResponse(it) }
        } catch (e: Exception) {
            Log.e(name, "Search failed", e)
            emptyList()
        }
    }

    override suspend fun load(url: String): LoadResponse? {
        try {
            val mainDocument = app.get(url).document
            val displayTitle = mainDocument.selectFirst("h1.Title, h2.SubTitle")?.text() ?: return null
            val animetPoster = mainDocument.selectFirst("article.TPost img.wp-post-image")?.attr("src")
            val finalPoster = getKitsuPoster(displayTitle) ?: fixUrlBased(animetPoster)

            val plot = mainDocument.selectFirst("article.TPost header div.Description")?.text()?.trim()
            val year = mainDocument.selectFirst("p.Info span.Date a")?.text()?.toIntOrNull()
            val rating = mainDocument.selectFirst("div#star")?.attr("data-score")?.toFloatOrNull()?.times(1000)?.toInt()
            val tags = mainDocument.select("div#MvTb-Info div.mvici-left ul.InfoList li:contains(Thể loại) a").mapNotNull { it.text() }
            val statusText = mainDocument.selectFirst("div#MvTb-Info div.mvici-left ul.InfoList li:contains(Trạng thái) span.Info")?.text()
            val showStatus = if (statusText?.contains("Đang tiến hành", true) == true) ShowStatus.Ongoing else ShowStatus.Completed
            val tvType = if (url.contains("phim-le")) TvType.AnimeMovie else TvType.Anime

            // =========================================================================
            // GIỮ NGUYÊN LOGIC GỐC: TẢI TRANG XEM PHIM ĐỂ LẤY DANH SÁCH TẬP
            // =========================================================================
            val watchPageUrl = mainDocument.selectFirst("a.watch_button_more")?.attr("href")?.let { fixUrlBased(it) }
            val episodes = if (watchPageUrl != null) {
                try {
                    val episodeListPageDocument = app.get(watchPageUrl, referer = url).document
                    episodeListPageDocument.select("ul.list-episode li.episode a.episode-link").mapNotNull {
                        val epHref = fixUrlBased(it.attr("href")) ?: return@mapNotNull null
                        val epName = it.attr("data-epname").trim().ifBlank { it.text().trim() }
                        newEpisode(epHref) { this.name = if (epName.contains("Full") || epName.contains("Movie")) epName else "Tập $epName" }
                    }
                } catch (e: Exception) {
                    Log.e(name, "Failed to get episodes from watch page", e)
                    emptyList()
                }
            } else {
                emptyList()
            }
            // =========================================================================

            val recommendations = mainDocument.select("div.MovieListRelated ul.MovieList li.TPostMv")
                .mapNotNull { mapElementToSearchResponse(it) }

            return if (tvType == TvType.AnimeMovie || episodes.isEmpty()) {
                newMovieLoadResponse(displayTitle, url, tvType, url) {
                    this.posterUrl = finalPoster; this.year = year; this.plot = plot; this.rating = rating; this.tags = tags; this.recommendations = recommendations
                }
            } else {
                newTvSeriesLoadResponse(displayTitle, url, tvType, episodes) {
                    this.posterUrl = finalPoster; this.year = year; this.plot = plot; this.rating = rating; this.tags = tags; this.showStatus = showStatus; this.recommendations = recommendations
                }
            }
        } catch (e: Exception) {
            Log.e(name, "Load failed", e)
            return null
        }
    }

    override suspend fun loadLinks(data: String, isCasting: Boolean, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit): Boolean {
        val (filmId, epId) = Regex("-(\\d+)\\.(\\d+)\\.html$").find(data)?.destructured ?: return false
        return try {
            val currentBaseUrl = getBaseUrl()
            val ajaxUrl = "$currentBaseUrl/ajax/player"
            val postData = mapOf("id" to filmId, "ep" to epId, "sv" to "0")
            val headers = mapOf("Referer" to currentBaseUrl, "Origin" to currentBaseUrl)

            // Tối ưu an toàn: Dùng parsedSafe thay vì Regex
            val playerResponse = app.post(ajaxUrl, data = postData, headers = headers).parsedSafe<AjaxPlayerResponse>()
            val m3u8Link = playerResponse?.file?.replace("\\/", "/") ?: return false
            
            callback(newExtractorLink(source = name, name = name, url = m3u8Link, type = ExtractorLinkType.M3U8) {
                this.referer = currentBaseUrl
                this.quality = Qualities.Unknown.value
            })
            true
        } catch (e: Exception) {
            Log.e(name, "loadLinks failed", e)
            false
        }
    }
}
