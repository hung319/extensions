package recloudstream

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

// --- DATA CLASSES giữ nguyên ---
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

    // --- Các hàm helper giữ nguyên ---
    private suspend fun getBaseUrl(): String {
        baseUrlMutex.withLock {
            if (baseUrl == null) {
                try {
                    val response = app.get(mainUrl, timeout = 15_000L).text
                    val doc = Jsoup.parse(response)
                    val domainText = doc.selectFirst("span.text-success.font-weight-bold")?.text()?.trim()
                    if (!domainText.isNullOrBlank()) {
                        baseUrl = "https://${domainText.lowercase().removeSuffix("/")}"
                    } else {
                        baseUrl = "https://anime10.site"
                    }
                } catch (e: Exception) {
                    baseUrl = "https://anime10.site"
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

    private fun mapElementToSearchResponse(element: Element, currentBaseUrl: String): AnimeSearchResponse? {
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

        val tvType = when {
            href.contains("/the-loai/cn-animation", ignoreCase = true) -> TvType.Cartoon
            href.contains("/the-loai/tokusatsu", ignoreCase = true) -> TvType.TvSeries
            else -> TvType.Anime
        }

        return newAnimeSearchResponse(title, href, tvType) {
            this.posterUrl = poster
            if (!poster.isNullOrBlank() && baseUrl != null && poster.startsWith(baseUrl!!)) {
                this.posterHeaders = mapOf("Referer" to currentBaseUrl)
            }
        }
    }

    // *** THAY THẾ HOÀN TOÀN HÀM getMainPage ĐỂ HỖ TRỢ PHÂN TRANG ***
    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse? {
        val currentBaseUrl = try { getBaseUrl() } catch (e: Exception) { return null }

        // Định nghĩa cấu trúc trang chủ
        // data = đường dẫn duy nhất cho mỗi danh sách, dùng để phân trang
        val homePageLists = listOf(
            HomePageList("Phim Mới Cập Nhật", emptyList(), data = "/danh-sach/phim-moi-cap-nhat/"),
            HomePageList("CN Animation", emptyList(), data = "/the-loai/cn-animation/"),
            HomePageList("Tokusatsu", emptyList(), data = "/danh-sach/phim-sieu-nhan/")
        )

        // `request.data` sẽ chứa đường dẫn của danh sách cần tải
        // Nếu `request.data` không nằm trong danh sách của chúng ta, đó là lần tải đầu tiên
        val isInitialLoad = homePageLists.none { it.data == request.data }

        if (isInitialLoad) {
            // TẢI LẦN ĐẦU: Tải trang 1 của tất cả danh sách để hiển thị preview
            val allLists = coroutineScope {
                homePageLists.map { list ->
                    async {
                        try {
                            // URL trang đầu tiên
                            val url = "$currentBaseUrl${list.data}trang-1.html"
                            val document = app.get(url, referer = currentBaseUrl).document
                            val items = document.select("ul.MovieList li.TPostMv")
                                .mapNotNull { mapElementToSearchResponse(it, currentBaseUrl) }
                            // Trả về danh sách với nội dung đã tải
                            HomePageList(list.name, items, data = list.data)
                        } catch (e: Exception) {
                            Log.e(name, "Failed to load initial list '${list.name}'", e)
                            null
                        }
                    }
                }
            }.mapNotNull { it.await() }
            return newHomePageResponse(allLists)
        } else {
            // TẢI TRANG TIẾP THEO (page >= 2)
            // `request.data` lúc này là đường dẫn của danh sách cụ thể, ví dụ: "/danh-sach/phim-moi-cap-nhat/"
            val path = request.data
            val url = "$currentBaseUrl${path}trang-$page.html"
            Log.d(name, "Paginating list '${request.name}' page $page with URL: $url")

            try {
                val document = app.get(url, referer = currentBaseUrl).document
                val items = document.select("ul.MovieList li.TPostMv")
                    .mapNotNull { mapElementToSearchResponse(it, currentBaseUrl) }

                // Chỉ cần trả về danh sách các item mới, ứng dụng sẽ tự nối vào danh sách cũ
                // Nếu `items` rỗng, ứng dụng sẽ hiểu là đã hết trang
                return newHomePageResponse(HomePageList(request.name, items, data = request.data))
            } catch (e: Exception) {
                Log.e(name, "Pagination failed for $url", e)
                return null
            }
        }
    }


    // --- Các hàm còn lại (search, load, ...) giữ nguyên ---
    override suspend fun search(query: String): List<SearchResponse>? {
       val currentBaseUrl = try { getBaseUrl() } catch (e: Exception) { return null }
       val encodedQuery = URLEncoder.encode(query, "UTF-8")
       val searchUrl = "$currentBaseUrl/tim-kiem/$encodedQuery.html"
       return try {
            val document = app.get(searchUrl, referer = currentBaseUrl).document
            val results = document.select("ul.MovieList li.TPostMv")
            results.mapNotNull { mapElementToSearchResponse(it, currentBaseUrl) }
           } catch (e: Exception) {
             null
           }
    }

    override suspend fun load(url: String): LoadResponse? {
        // ... (Toàn bộ nội dung hàm load của bạn ở đây) ...
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        // ... (Toàn bộ nội dung hàm loadLinks của bạn ở đây) ...
    }
}
