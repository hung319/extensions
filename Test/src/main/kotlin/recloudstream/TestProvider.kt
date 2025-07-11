package recloudstream

// === Imports ===
import android.util.Log
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addDuration
import com.lagradost.cloudstream3.mvvm.normalSafeApiCall
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.newExtractorLink
import okhttp3.Interceptor
import okhttp3.Response
import okhttp3.ResponseBody
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.net.URL
import java.net.URLEncoder
import kotlin.math.roundToInt

// *** DATA CLASSES CHO KITSU API ***
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

// === LỚP PROVIDER CHÍNH ===
class AnimeHayProvider : MainAPI() {

    override var mainUrl = "https://ahay.in"
    override var name = "AnimeHay"
    override val supportedTypes = setOf(TvType.Anime, TvType.AnimeMovie, TvType.OVA, TvType.Cartoon)
    override var lang = "vi"
    override val hasMainPage = true

    private var currentActiveUrl = "https://animehay.bid"
    private var domainCheckPerformed = false

    private suspend fun getBaseUrl(): String {
        if (domainCheckPerformed) return currentActiveUrl
        
        try {
            app.get(mainUrl, allowRedirects = true).document.let { document ->
                var newDomain = document.selectFirst("a.bt-link, a.bt-link-1")?.attr("href")
                if (newDomain.isNullOrBlank()) {
                    val scriptContent = document.select("script:not([src])").html()
                    newDomain = Regex("""var\s+new_domain\s*=\s*["'](https?://[^"']+)["']""").find(scriptContent)?.groupValues?.get(1)
                }
                if (!newDomain.isNullOrBlank()) {
                    val host = URL(newDomain).host
                    if (host.isNotBlank() && !host.contains("archive.org")) {
                        currentActiveUrl = "https://$host"
                    }
                }
            }
        } catch (e: Exception) {
            Log.e("AnimeHayProvider", "Domain check failed: ${e.message}")
        } finally {
            domainCheckPerformed = true
        }
        return currentActiveUrl
    }
    
    private suspend fun getKitsuPoster(title: String): String? {
        return try {
            val searchUrl = "https://kitsu.io/api/edge/anime?filter[text]=${URLEncoder.encode(title, "UTF-8")}&page[limit]=1"
            val response = app.get(searchUrl).parsedSafe<KitsuMain>()
            response?.data?.firstOrNull()?.attributes?.posterImage?.let {
                it.original ?: it.large ?: it.medium ?: it.small ?: it.tiny
            }
        } catch (e: Exception) {
            null
        }
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val baseUrl = getBaseUrl()
        val url = if (page <= 1) baseUrl else "$baseUrl/phim-moi-cap-nhap/trang-$page.html"
        val document = app.get(url).document
        val items = document.select("div.movies-list div.movie-item").mapNotNull { it.toSearchResponse(this, baseUrl) }
        val hasNext = document.selectFirst("div.pagination a.active_page + a") != null
        return newHomePageResponse(HomePageList(request.name, items), hasNext)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val baseUrl = getBaseUrl()
        val url = "$baseUrl/tim-kiem/${URLEncoder.encode(query, "UTF-8")}.html"
        return app.get(url).document.select("div.movies-list div.movie-item").mapNotNull {
            it.toSearchResponse(this, baseUrl)
        }
    }

    override suspend fun load(url: String): LoadResponse? {
        val document = app.get(url).document
        return document.toLoadResponse(this, url)
    }

    override suspend fun loadLinks(data: String, isCasting: Boolean, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit): Boolean {
        val baseUrl = getBaseUrl()
        val responseText = app.get(data, referer = baseUrl).text
        
        val sources = buildMap {
            responseText.substringAfter("tik: '", "").substringBefore("',", "").takeIf { it.isNotBlank() }?.let { put("Server TOK", it) }
            responseText.substringAfter("gun.php?id=", "").substringBefore("&", "").takeIf { it.isNotBlank() }?.let { put("Server GUN", "https://pt.rapovideo.xyz/playlist/v2/$it/master.m3u8") }
            responseText.substringAfter("pho.php?id=", "").substringBefore("&", "").takeIf { it.isNotBlank() }?.let { put("Server PHO", "https://pt.rapovideo.xyz/playlist/$it/master.m3u8") }
        }

        sources.forEach { (serverName, url) ->
            callback(newExtractorLink(name, serverName, url, type = ExtractorLinkType.M3U8) {
                this.referer = "$baseUrl/"
            })
        }
        return sources.isNotEmpty()
    }

    override fun getVideoInterceptor(extractorLink: ExtractorLink): Interceptor? {
        return object : Interceptor {
            override fun intercept(chain: Interceptor.Chain): Response {
                val request = chain.request()
                val response = chain.proceed(request)
                val url = request.url.toString()
                if (url.contains("ibyteimg.com") ||
                    url.contains(".tiktokcdn.") ||
                    (url.contains("segment.cloudbeta.win/file/segment/") && url.contains(".html?token="))
                ) {
                    val body = response.body
                    if (body != null) {
                        try {
                            val fixedBytes = skipByteError(body) 
                            val newBody = ResponseBody.create(body.contentType(), fixedBytes)
                            return response.newBuilder().body(newBody).build()
                        } catch (e: Exception) {
                            // Ignored
                        }
                    }
                }
                return response
            }
        }
    }
}

// ========================================================================================
// HÀM HELPER (BÊN NGOÀI LỚP PROVIDER)
// ========================================================================================

private fun Element.toSearchResponse(provider: MainAPI, baseUrl: String): SearchResponse? {
    val linkElement = this.selectFirst("a") ?: return null
    val href = fixUrlSafe(linkElement.attr("href"), baseUrl) ?: return null
    val title = this.selectFirst(".name-movie")?.text() ?: linkElement.attr("title") ?: return null
    val posterUrl = fixUrlSafe(this.selectFirst("img")?.attr("src"), baseUrl)
    val episodeText = this.selectFirst(".episode-latest span")?.text()?.trim()

    return provider.newAnimeSearchResponse(title, href, TvType.Anime) {
        this.posterUrl = posterUrl
        episodeText?.let { text ->
            Regex("""\d+""").findAll(text).lastOrNull()?.value?.toIntOrNull()?.let {
                this.episodes[DubStatus.Subbed] = it
            }
        }
    }
}

private suspend fun Document.toLoadResponse(provider: MainAPI, url: String): LoadResponse? {
    val baseUrl = provider.mainUrl
    val title = this.selectFirst("h1.movie-title")?.text() ?: return null
    val posterUrl = fixUrlSafe(this.selectFirst(".movie-poster img")?.attr("src"), baseUrl)
    val plot = this.selectFirst(".film-description .content")?.text()

    var year: Int? = null
    var status: ShowStatus? = null
    val tags = mutableListOf<String>()

    this.select(".meta-item").forEach { element ->
        val header = element.selectFirst("span.meta-label")?.text()?.trim() ?: ""
        val body = element.selectFirst("span.meta-value")?.text()?.trim() ?: element.select("a").joinToString(", ") { it.text() }

        when {
            header.contains("Năm", true) -> year = body.toIntOrNull()
            header.contains("Trạng thái", true) -> {
                status = when {
                    body.contains("Hoàn thành", true) -> ShowStatus.Completed
                    body.contains("Đang", true) -> ShowStatus.Ongoing
                    else -> null
                }
            }
            header.contains("Thể loại", true) -> tags.addAll(element.select("a").map { it.text() })
        }
    }

    val episodes = this.select(".list-episode a").mapNotNull {
        val epUrl = fixUrlSafe(it.attr("href"), baseUrl) ?: return@mapNotNull null
        newEpisode(epUrl) { name = it.attr("title") }
    }.reversed()
    
    return if (episodes.isNotEmpty()) {
        provider.newTvSeriesLoadResponse(title, url, TvType.Anime, episodes) {
            this.posterUrl = posterUrl
            this.plot = plot
            this.year = year
            this.showStatus = status
            this.tags = tags
        }
    } else {
        provider.newMovieLoadResponse(title, url, TvType.AnimeMovie, url) {
            this.posterUrl = posterUrl
            this.plot = plot
            this.year = year
            this.tags = tags
        }
    }
}

private fun fixUrlSafe(url: String?, baseUrl: String): String? {
    if (url.isNullOrBlank()) return null
    return try {
        URL(URL(baseUrl), url).toString()
    } catch (e: Exception) {
        url
    }
}

private fun skipByteError(responseBody: ResponseBody): ByteArray {
    val source = responseBody.source()
    source.request(Long.MAX_VALUE)
    val buffer = source.buffer.clone()
    source.close()
    val byteArray = buffer.readByteArray()
    val length = byteArray.size - 188
    var start = 0
    for (i in 0 until length) {
        val nextIndex = i + 188
        if (nextIndex < byteArray.size && byteArray[i].toInt() == 71 && byteArray[nextIndex].toInt() == 71) {
            start = i
            break
        }
    }
    return if (start > 0) byteArray.copyOfRange(start, byteArray.size) else byteArray
}
