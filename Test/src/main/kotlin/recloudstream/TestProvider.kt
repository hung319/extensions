package recloudstream // Giữ nguyên package gốc

// === Imports ===
import android.util.Log
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addDuration
import com.lagradost.cloudstream3.mvvm.normalSafeApiCall
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.newExtractorLink
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.net.MalformedURLException
import java.net.URL
import java.net.URLEncoder
import kotlin.math.roundToInt

// *** KITSU DATA CLASSES ***
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

    // === Provider Attributes ===
    override var mainUrl = "https://ahay.in"
    override var name = "AnimeHay"
    override val supportedTypes = setOf(TvType.Anime, TvType.AnimeMovie, TvType.OVA, TvType.Cartoon)
    override var lang = "vi"
    override val hasMainPage = true

    // --- Dynamic Domain Handling ---
    private var currentActiveUrl = "https://animehay.bid"
    private var domainCheckPerformed = false
    private val domainCheckUrl = mainUrl

    private suspend fun getBaseUrl(): String {
        if (domainCheckPerformed) {
            return currentActiveUrl
        }
        try {
            app.get(domainCheckUrl, allowRedirects = true).let { response ->
                val landedUrl = response.url
                var newDomain = response.document.selectFirst("a.bt-link, a.bt-link-1")?.attr("href")
                
                if (newDomain.isNullOrBlank()) {
                     val scriptContent = response.document.select("script:not([src])").html()
                     val match = Regex("""var\s+new_domain\s*=\s*["'](https?://[^"']+)["']""").find(scriptContent)
                     newDomain = match?.groups?.get(1)?.value
                }
                
                val finalNewUrl = newDomain?.let {
                    try { URL(it).let { url -> "${url.protocol}://${url.host}" } } catch (e: Exception) { null }
                } ?: try { URL(landedUrl).let { url -> "${url.protocol}://${url.host}" } } catch (e: Exception) { null }

                if (finalNewUrl != null && finalNewUrl != currentActiveUrl && !finalNewUrl.contains("archive.org")) {
                     Log.i(name, "Domain updated: $currentActiveUrl -> $finalNewUrl")
                     currentActiveUrl = finalNewUrl
                }
            }
        } catch (e: Exception) {
            Log.e(name, "Domain check failed: ${e.message}", e)
        } finally {
            domainCheckPerformed = true
        }
        return currentActiveUrl
    }

    private suspend fun getKitsuPoster(title: String): String? {
        return try {
            val encodedTitle = URLEncoder.encode(title, "UTF-8")
            val searchUrl = "https://kitsu.io/api/edge/anime?filter[text]=$encodedTitle&page[limit]=1"
            val response = app.get(searchUrl).parsedSafe<KitsuMain>()
            val poster = response?.data?.firstOrNull()?.attributes?.posterImage
            poster?.original ?: poster?.large ?: poster?.medium ?: poster?.small ?: poster?.tiny
        } catch (e: Exception) {
            Log.e(name, "Kitsu API Error for title '$title': ${e.message}")
            null
        }
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val siteBaseUrl = getBaseUrl()
        val urlToFetch = if (page <= 1) siteBaseUrl else "$siteBaseUrl/phim-moi-cap-nhap/trang-$page.html"
        
        val document = app.get(urlToFetch).document
        val homePageItems = document.select("div.movies-list div.movie-item").mapNotNull {
            it.toSearchResponse(this, siteBaseUrl)
        }

        val hasNext = document.selectFirst("div.pagination a[href*=/trang-${page + 1}.html]") != null
        val listTitle = request.name.ifBlank { "Mới cập nhật" }
        
        return newHomePageResponse(listOf(HomePageList(listTitle, homePageItems)), hasNext = hasNext)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val baseUrl = getBaseUrl()
        val searchUrl = "$baseUrl/tim-kiem/${query.encodeUri()}.html"
        val document = app.get(searchUrl).document
        
        return document.select("div.movies-list div.movie-item").mapNotNull {
            it.toSearchResponse(this, baseUrl)
        }
    }

    override suspend fun load(url: String): LoadResponse? {
        val document = app.get(url).document
        return document.toLoadResponse(this, url, getBaseUrl())
    }

    // =======================================================================
    // START: HÀM LOADLINKS VIẾT LẠI HOÀN TOÀN
    // =======================================================================
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        var foundLinks = false
        val document = app.get(data, referer = getBaseUrl()).document
        val pageSource = document.html()
        val baseUrl = getBaseUrl()

        // 1. Server TOK
        Regex("""tik:\s*['"]([^'"]+)['"]""").find(pageSource)?.groupValues?.get(1)?.trim()?.let { m3u8Link ->
            Log.i(name, "Found TOK M3U8 link: $m3u8Link")
            val link = newExtractorLink(m3u8Link, "Server TOK", m3u8Link, ExtractorLinkType.M3U8) {
                this.quality = Qualities.Unknown.value
                this.referer = data
            }
            callback(link)
            foundLinks = true
        }

        // 2. Server GUN - Viết lại trực tiếp, không dùng helper
        val gunRegex = Regex("""src=["'](https?://[^"']*gun\.php\?id=([^&"']+)&[^"']*)["']""")
        gunRegex.find(pageSource)?.let { match ->
            val serverLink = match.groupValues[1].let { url -> fixUrl(url, baseUrl) }
            val serverId = match.groupValues[2]
            
            // Lồng `let` để đảm bảo an toàn tuyệt đối
            serverLink?.let { safeServerLink ->
                Log.i(name, "Processing GUN link ID: $serverId")
                val finalM3u8Link = "https://pt.rapovideo.xyz/playlist/v2/$serverId/master.m3u8"
                val link = newExtractorLink(finalM3u8Link, "Server GUN", finalM3u8Link, ExtractorLinkType.M3U8) {
                    this.quality = Qualities.Unknown.value
                    this.referer = safeServerLink
                }
                callback(link)
                foundLinks = true
            }
        }

        // 3. Server PHO - Viết lại trực tiếp, không dùng helper
        val phoRegex = Regex("""src=["'](https?://[^"']*pho\.php\?id=([^&"']+)&[^"']*)["']""")
        phoRegex.find(pageSource)?.let { match ->
            val serverLink = match.groupValues[1].let { url -> fixUrl(url, baseUrl) }
            val serverId = match.groupValues[2]

            // Lồng `let` để đảm bảo an toàn tuyệt đối
            serverLink?.let { safeServerLink ->
                Log.i(name, "Processing PHO link ID: $serverId")
                val finalM3u8Link = "https://pt.rapovideo.xyz/playlist/$serverId/master.m3u8"
                val link = newExtractorLink(finalM3u8Link, "Server PHO", finalM3u8Link, ExtractorLinkType.M3U8) {
                    this.quality = Qualities.Unknown.value
                    this.referer = safeServerLink
                }
                callback(link)
                foundLinks = true
            }
        }
        
        return foundLinks
    }
    // =======================================================================
    // END: HÀM LOADLINKS VIẾT LẠI HOÀN TOÀN
    // =================================----------------======================

    private fun Element.toSearchResponse(provider: MainAPI, baseUrl: String): SearchResponse? {
        val linkElement = this.selectFirst("> a[href]") ?: return null
        val href = fixUrl(linkElement.attr("href"), baseUrl) ?: return null
        val title = this.selectFirst("div.name-movie")?.text()?.trim()
            ?: linkElement.attr("title")?.trim() ?: return null

        val episodeInfo = this.selectFirst("div.info_right")?.text()?.trim()
        val finalDisplayName = if (!episodeInfo.isNullOrBlank()) "$title\n$episodeInfo" else title

        val posterUrl = this.selectFirst("img")?.let { it.attr("src").ifBlank { it.attr("data-src") } }
        val tvType = if (href.contains("/phim/")) TvType.AnimeMovie else TvType.Anime
        
        return provider.newMovieSearchResponse(finalDisplayName, href, tvType) {
            this.posterUrl = fixUrl(posterUrl, baseUrl)
        }
    }

    private suspend fun Document.toLoadResponse(provider: MainAPI, url: String, baseUrl: String): LoadResponse? {
        val title = this.selectFirst("h1.heading_movie")?.text()?.trim() ?: return null
        val genres = this.select("div.list_cate a").mapNotNull { it.text()?.trim() }
        val hasEpisodes = this.selectFirst("div.list-item-episode a") != null
        val isChinese = genres.any { it.contains("CN Animation", true) }
        
        val mainTvType = when {
            hasEpisodes && isChinese -> TvType.Cartoon
            hasEpisodes && !isChinese -> TvType.Anime
            !hasEpisodes && isChinese -> TvType.Cartoon
            !hasEpisodes && !isChinese -> TvType.AnimeMovie
            else -> TvType.Anime
        }

        val animehayPoster = this.selectFirst("div.head div.first img")?.attr("src")
        val finalPosterUrl = if (mainTvType == TvType.Anime || mainTvType == TvType.AnimeMovie || mainTvType == TvType.OVA) {
            getKitsuPoster(title) ?: fixUrl(animehayPoster, baseUrl)
        } else {
            fixUrl(animehayPoster, baseUrl)
        }

        val description = this.selectFirst("div.desc > div:last-child")?.text()?.trim()
        val year = this.selectFirst("div.update_time div:nth-child(2)")?.text()?.trim()?.filter { it.isDigit() }?.toIntOrNull()
        val ratingText = this.selectFirst("div.score div:nth-child(2)")?.text()?.trim()
        val rating = ratingText?.split("||")?.getOrNull(0)?.trim()?.toDoubleOrNull()?.toAnimeHayRatingInt()
        val status = when (this.selectFirst("div.status div:nth-child(2)")?.text()?.trim()) {
            "Hoàn thành" -> ShowStatus.Completed
            "Đang tiến hành", "Đang cập nhật" -> ShowStatus.Ongoing
            else -> null
        }
        val recommendations = this.select("div.movie-recommend div.movie-item").mapNotNull {
            it.toSearchResponse(provider, baseUrl)
        }

        return if (hasEpisodes) {
            val episodes = this.select("div.list-item-episode a").mapNotNull { epLink ->
                val epUrl = fixUrl(epLink.attr("href"), baseUrl) ?: return@mapNotNull null
                val epName = epLink.attr("title")?.trim().ifBlank { epLink.text() }
                newEpisode(data = epUrl) { this.name = epName }
            }.reversed()
            
            provider.newTvSeriesLoadResponse(title, url, mainTvType, episodes) {
                this.posterUrl = finalPosterUrl; this.plot = description; this.tags = genres
                this.year = year; this.rating = rating; this.showStatus = status
                this.recommendations = recommendations
            }
        } else {
            val durationMinutes = this.selectFirst("div.duration div:nth-child(2)")?.text()?.trim()?.filter { it.isDigit() }?.toIntOrNull()
            provider.newMovieLoadResponse(title, url, mainTvType, url) {
                this.posterUrl = finalPosterUrl; this.plot = description; this.tags = genres
                this.year = year; this.rating = rating
                durationMinutes?.let { addDuration(it.toString()) }
                this.recommendations = recommendations
            }
        }
    }

    private fun String?.encodeUri(): String = URLEncoder.encode(this ?: "", "UTF-8")
    private fun Double?.toAnimeHayRatingInt(): Int? = this?.let { (it * 1000).roundToInt().coerceIn(0, 10000) }
    private fun fixUrl(url: String?, baseUrl: String): String? {
        if (url.isNullOrBlank()) return null
        return when {
            url.startsWith("http") -> url
            url.startsWith("//") -> "https:$url"
            url.startsWith("/") -> baseUrl.trimEnd('/') + url
            else -> try { URL(URL(baseUrl), url).toString() } catch (e: Exception) { null }
        }
    }
}
