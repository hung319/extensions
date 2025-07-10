package recloudstream

// === Imports ===
import android.util.Log
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import okhttp3.Interceptor
import okhttp3.ResponseBody.Companion.toResponseBody
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.net.URL
import java.net.URLEncoder

// === Provider Class ===
class AnimeHayProvider : MainAPI() {

    // === Thuộc tính Provider ===
    override var mainUrl = "https://animehay.bid"
    override var name = "AnimeHay"
    override val supportedTypes = setOf(TvType.Anime, TvType.AnimeMovie)
    override var lang = "vi"
    override val hasMainPage = true

    // === Hàm chính ===
    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        try {
            val url = if (page <= 1) mainUrl else "$mainUrl/phim-moi-cap-nhap/trang-$page.html"
            val document = app.get(url).document
            
            val homePageItems = document.select("div.movies-list div.movie-item").mapNotNull {
                it.toSearchResponse()
            }
            
            val hasNext = document.selectFirst("div.pagination a.active_page + a") != null
            return newHomePageResponse(HomePageList("Phim Mới Cập Nhật", homePageItems), hasNext)

        } catch (e: Exception) {
            Log.e("AnimeHayProvider", "Error in getMainPage", e)
            throw e
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val searchUrl = "$mainUrl/tim-kiem/${query.encodeUri()}.html"
        val document = app.get(searchUrl).document
        return document.select("div.movies-list div.movie-item").mapNotNull {
            it.toSearchResponse()
        }
    }

    override suspend fun load(url: String): LoadResponse {
        val document = app.get(url).document
        val title = document.selectFirst("h1.heading_movie")?.text()?.trim() ?: "Không rõ"
        val plot = document.selectFirst("div.desc > div:last-child")?.text()?.trim()
        val posterUrl = fixUrl(document.selectFirst("div.head div.first img")?.attr("src"))
        
        val episodes = document.select("div.list-item-episode a").mapNotNull { epLink ->
            val epUrl = fixUrl(epLink.attr("href")) ?: return@mapNotNull null
            val epName = epLink.attr("title")?.trim()?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            newEpisode(epUrl) { this.name = epName }
        }.reversed()

        return newTvSeriesLoadResponse(title, url, TvType.Anime, episodes) {
            this.plot = plot
            this.posterUrl = posterUrl
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        var foundLinks = false
        Log.d("AnimeHayProvider", "loadLinks called for: $data")
        try {
            val document = app.get(data).document
            val tokServerExists = document.select("#list_sv a").any { it.text().contains("TOK", ignoreCase = true) }

            if (tokServerExists) {
                val scriptContent = document.selectFirst("script:containsData(function loadVideo)")?.data()
                if (!scriptContent.isNullOrBlank()) {
                    val tokRegex = Regex("""tik:\s*['"]([^'"]+)['"]""")
                    val m3u8Link = tokRegex.find(scriptContent)?.groupValues?.getOrNull(1)
                    if (!m3u8Link.isNullOrBlank()) {
                        callback(
                            // CẬP NHẬT: Sử dụng cấu trúc ExtractorLink mới
                            ExtractorLink(
                                source = m3u8Link,
                                name = "Server TOK",
                                url = m3u8Link,
                                referer = "", // referer là String, không phải String?
                                quality = Qualities.Unknown.value,
                                type = ExtractorLinkType.M3U8
                            )
                        )
                        foundLinks = true
                    }
                }
            }
        } catch (e: Exception) {
            Log.e("AnimeHayProvider", "Error in loadLinks", e)
        }
        return foundLinks
    }
    
    override fun getVideoInterceptor(extractorLink: ExtractorLink): Interceptor? {
        return Interceptor { chain ->
            val request = chain.request()
            val response = chain.proceed(request)
            val url = request.url.toString()
            val needsFixing = ".tiktokcdn." in url || "ibyteimg.com" in url || "segment.cloudbeta.win" in url

            if (needsFixing && response.isSuccessful) {
                val body = response.body
                if (body != null) {
                    try {
                        val fixedBytes = body.bytes()
                        val fixedBody = fixedBytes.toResponseBody(body.contentType())
                        return@Interceptor response.newBuilder().body(fixedBody).build()
                    } catch (e: Exception) {
                         Log.e("AnimeHayProvider", "Failed to fix bytes for video segment", e)
                    }
                }
            }
            response
        }
    }

    // === Hàm phụ ===
    private fun Element.toSearchResponse(): SearchResponse? {
        val linkElement = this.selectFirst("a") ?: return null
        val href = fixUrl(linkElement.attr("href")) ?: return null
        val title = this.selectFirst(".name-movie")?.text() ?: return null
        val posterUrl = fixUrl(this.selectFirst("img")?.attr("src"))
        return newMovieSearchResponse(title, href, TvType.Anime) {
            this.posterUrl = posterUrl
        }
    }

    private fun String.encodeUri(): String = URLEncoder.encode(this, "UTF-8")

    private fun fixUrl(url: String?): String? {
        if (url.isNullOrBlank()) return null
        return when {
            url.startsWith("http") -> url
            url.startsWith("//") -> "https:$url"
            else -> URL(URL(mainUrl), url).toString()
        }
    }
}
