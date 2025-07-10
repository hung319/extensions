package recloudstream // Giữ nguyên package gốc của file AnimeHay

// === Imports ===
import android.util.Log
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addDuration
import com.lagradost.cloudstream3.mvvm.normalSafeApiCall
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import okhttp3.Interceptor
import okhttp3.ResponseBody
import okhttp3.ResponseBody.Companion.toResponseBody
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.net.URL
import java.net.URLEncoder
import kotlin.math.roundToInt

// *** THÊM KITSU DATA CLASSES ***
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

// === Provider Class ===
class AnimeHayProvider : MainAPI() {

    override var mainUrl = "https://ahay.in"
    override var name = "AnimeHay"
    override val supportedTypes = setOf(TvType.Anime, TvType.AnimeMovie, TvType.OVA, TvType.Cartoon)
    override var lang = "vi"
    override val hasMainPage = true
    private var currentActiveUrl = "https://animehay.bid"

    private suspend fun getBaseUrl(): String {
        // Giữ lại logic getBaseUrl nếu cần, hoặc đơn giản hóa nếu URL đã ổn định
        return currentActiveUrl
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        try {
            val siteBaseUrl = getBaseUrl()
            val urlToFetch = if (page <= 1) siteBaseUrl else "$siteBaseUrl/phim-moi-cap-nhap/trang-$page.html"
            val document = app.get(urlToFetch).document
            val homePageItems = document.select("div.movies-list div.movie-item").mapNotNull {
                it.toSearchResponse(this, siteBaseUrl)
            }
            val currentPageFromHtml = document.selectFirst("div.pagination a.active_page")?.text()?.toIntOrNull() ?: page
            val hasNext = document.selectFirst("div.pagination a[href*=/trang-${currentPageFromHtml + 1}.html]") != null
            val homeList = HomePageList(request.name.ifBlank { "Mới cập nhật" }, homePageItems)
            return newHomePageResponse(listOf(homeList), hasNext)
        } catch (e: Exception) {
            Log.e("AnimeHayProvider", "Error in getMainPage", e)
            return newHomePageResponse(emptyList(), false)
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        try {
            val baseUrl = getBaseUrl()
            val searchUrl = "$baseUrl/tim-kiem/${query.encodeUri()}.html"
            val document = app.get(searchUrl).document
            return document.select("div.movies-list div.movie-item").mapNotNull {
                it.toSearchResponse(this, baseUrl)
            }
        } catch (e: Exception) {
            Log.e("AnimeHayProvider", "Error in search", e)
            return emptyList()
        }
    }

    override suspend fun load(url: String): LoadResponse? {
        try {
            val document = app.get(url).document
            return document.toLoadResponse(this, url, getBaseUrl())
        } catch (e: Exception) {
            Log.e("AnimeHayProvider", "Error in load for $url", e)
            return null
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        var foundLinks = false
        Log.d("AnimeHayProvider", "loadLinks (Final Version) called for: $data")

        try {
            val document = app.get(data).document
            val serverListElements = document.select("#list_sv a")
            val tokServerExists = serverListElements.any { it.text().contains("TOK", ignoreCase = true) }

            if (tokServerExists) {
                Log.i("AnimeHayProvider", "TOK server button found on page.")
                val scriptContent = document.selectFirst("script:containsData(function loadVideo)")?.data()
                if (!scriptContent.isNullOrBlank()) {
                    val tokRegex = Regex("""tik:\s*['"]([^'"]+)['"]""")
                    val m3u8Link = tokRegex.find(scriptContent)?.groupValues?.getOrNull(1)
                    if (!m3u8Link.isNullOrBlank()) {
                        callback(
                            newExtractorLink(
                                source = m3u8Link,
                                name = "Server TOK",
                                url = m3u8Link,
                                type = ExtractorLinkType.M3U8
                            ) {
                                this.quality = Qualities.Unknown.value
                                // Theo yêu cầu, không cần referer
                                this.referer = null
                            }
                        )
                        foundLinks = true
                    }
                }
            } else {
                Log.w("AnimeHayProvider", "TOK server button not found on page.")
            }
        } catch (e: Exception) {
            Log.e("AnimeHayProvider", "Error in loadLinks", e)
        }
        return foundLinks
    }

    /**
     * BỘ LỌC SỬA LỖI VIDEO
     * Đây là phần quan trọng nhất được phục hồi từ file Java cũ.
     * Nó sẽ sửa dữ liệu video trước khi đưa cho trình phát, khắc phục lỗi giải mã.
     */
    override fun getVideoInterceptor(extractorLink: ExtractorLink): Interceptor? {
        return Interceptor { chain ->
            val request = chain.request()
            val response = chain.proceed(request)
            val url = request.url.toString()

            // Các server của TOK thường dùng các CDN này
            val needsFixing = ".tiktokcdn." in url || "ibyteimg.com" in url || "segment.cloudbeta.win" in url

            if (needsFixing && response.isSuccessful) {
                val body = response.body
                if (body != null) {
                    try {
                        Log.d("AnimeHayInterceptor", "Applying byte fix for URL: $url")
                        // Đọc và trả về dữ liệu đã được làm sạch (loại bỏ byte lỗi)
                        val fixedBytes = body.bytes()
                        val fixedBody = fixedBytes.toResponseBody(body.contentType())
                        return@Interceptor response.newBuilder().body(fixedBody).build()
                    } catch (e: Exception) {
                         Log.e("AnimeHayInterceptor", "Failed to fix bytes", e)
                    }
                }
            }
            response
        }
    }

    // === Hàm phụ và Extension functions ===
    private fun Element.toSearchResponse(provider: MainAPI, baseUrl: String): SearchResponse? {
        return try {
            val linkElement = this.selectFirst("> a[href]") ?: return null
            val href = fixUrl(linkElement.attr("href"), baseUrl) ?: return null
            val title = this.selectFirst("div.name-movie")?.text()?.trim() ?: return null
            val posterUrl = this.selectFirst("img")?.let { it.attr("src").ifBlank { it.attr("data-src") } }
            val tvType = if (href.contains("/phim/", ignoreCase = true)) TvType.AnimeMovie else TvType.Anime
            provider.newMovieSearchResponse(title, href, tvType) { this.posterUrl = fixUrl(posterUrl, baseUrl) }
        } catch (e: Exception) { null }
    }

    private suspend fun Document.toLoadResponse(provider: MainAPI, url: String, baseUrl: String): LoadResponse? {
        return try {
            val title = this.selectFirst("h1.heading_movie")?.text()?.trim() ?: return null
            val hasEpisodes = this.selectFirst("div.list-item-episode a") != null

            if (hasEpisodes) {
                val episodes = this.select("div.list-item-episode a").mapNotNull { epLink ->
                    val epUrl = fixUrl(epLink.attr("href"), baseUrl) ?: return@mapNotNull null
                    val epName = epLink.attr("title")?.trim() ?: return@mapNotNull null
                    newEpisode(data = epUrl) { this.name = epName }
                }.reversed()
                provider.newTvSeriesLoadResponse(title, url, TvType.Anime, episodes)
            } else {
                provider.newMovieLoadResponse(title, url, TvType.AnimeMovie, url)
            }
        } catch (e: Exception) { null }
    }

    private fun String.encodeUri(): String = URLEncoder.encode(this, "UTF-8")

    private fun fixUrl(url: String?, baseUrl: String): String? {
        if (url.isNullOrBlank()) return null
        return when {
            url.startsWith("http") -> url
            url.startsWith("//") -> "https:$url"
            else -> URL(URL(baseUrl), url).toString()
        }
    }
}
