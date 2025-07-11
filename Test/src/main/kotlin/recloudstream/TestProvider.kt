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
import com.lagradost.cloudstream3.utils.newExtractorLink
import okhttp3.Interceptor
import okhttp3.Response
import okhttp3.ResponseBody
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.net.MalformedURLException
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

    // === Thuộc tính Provider ===
    override var mainUrl = "https://ahay.in"
    override var name = "AnimeHay"
    override val supportedTypes = setOf(TvType.Anime, TvType.AnimeMovie, TvType.OVA, TvType.Cartoon)
    override var lang = "vi"
    override val hasMainPage = true

    // --- Phần xử lý domain động ---
    private var currentActiveUrl = "https://animehay.bid"
    private var domainCheckPerformed = false
    private val domainCheckUrl = mainUrl
    private var directUrl = currentActiveUrl

    private suspend fun getBaseUrl(): String {
        if (domainCheckPerformed) {
            return currentActiveUrl
        }
        var finalNewDomain: String? = null
        try {
            val response = app.get(domainCheckUrl, allowRedirects = true)
            val landedUrl = response.url
            val document = response.document
            var hrefFromContent: String? = null
            val linkSelectors = listOf("a.bt-link", "a.bt-link-1")
            for (selector in linkSelectors) {
                hrefFromContent = document.selectFirst(selector)?.attr("href")
                if (!hrefFromContent.isNullOrBlank()) break
            }
            if (hrefFromContent.isNullOrBlank()) {
                val scriptElements = document.select("script")
                val newDomainRegex = Regex("""var\s+new_domain\s*=\s*["'](https?://[^"']+)["']""")
                for (scriptElement in scriptElements) {
                    if (!scriptElement.hasAttr("src")) {
                        val scriptContent = scriptElement.html()
                        val match = newDomainRegex.find(scriptContent)
                        if (match != null && match.groups.size > 1) {
                            hrefFromContent = match.groups[1]?.value
                            break
                        }
                    }
                }
            }
            if (!hrefFromContent.isNullOrBlank()) {
                try {
                    val urlObject = URL(hrefFromContent)
                    finalNewDomain = "${urlObject.protocol}://${urlObject.host}"
                } catch (e: MalformedURLException) {
                    Log.e("AnimeHayProvider", "Malformed URL from parsed content link: '$hrefFromContent'", e)
                }
            }
            if (finalNewDomain.isNullOrBlank()) {
                val landedUrlBase = try {
                    val landedObj = URL(landedUrl)
                    "${landedObj.protocol}://${landedObj.host}"
                } catch (e: Exception) { null }
                val initialCheckUrlHost = try { URL(domainCheckUrl).host } catch (e: Exception) { null }
                val landedUrlHost = try { URL(landedUrl).host } catch (e: Exception) { null }
                if (landedUrlBase != null && landedUrlBase.startsWith("http") && landedUrlHost != initialCheckUrlHost) {
                    finalNewDomain = landedUrlBase
                }
            }
            if (!finalNewDomain.isNullOrBlank() && finalNewDomain != currentActiveUrl) {
                if (!finalNewDomain.contains("archive.org", ignoreCase = true)) {
                    currentActiveUrl = finalNewDomain
                }
            }
        } catch (e: Exception) {
            Log.e("AnimeHayProvider", "Critical error during domain check. Using previous active URL: $currentActiveUrl", e)
        } finally {
            domainCheckPerformed = true
            directUrl = currentActiveUrl
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
            Log.e("AnimeHayProvider", "Kitsu API Error for title '$title'", e)
            null
        }
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val siteBaseUrl = getBaseUrl()
        val urlToFetch = if (page <= 1) siteBaseUrl else "$siteBaseUrl/phim-moi-cap-nhap/trang-$page.html"
        return try {
            val document = app.get(urlToFetch).document
            val homePageItems = document.select("div.movies-list div.movie-item")
                .mapNotNull { it.toSearchResponse(this, siteBaseUrl) }
            if (page > 1 && homePageItems.isEmpty()) {
                return newHomePageResponse(emptyList(), false)
            }
            val pagination = document.selectFirst("div.pagination")
            val currentPageFromHtml = pagination?.selectFirst("a.active_page")?.text()?.toIntOrNull() ?: page
            val hasNext = pagination?.selectFirst("a[href*=/trang-${currentPageFromHtml + 1}.html]") != null
            val listTitle = request.name.ifBlank { "Mới cập nhật" }
            val homeList = HomePageList(listTitle, homePageItems)
            newHomePageResponse(listOf(homeList), hasNext = hasNext)
        } catch (e: Exception) {
            Log.e("AnimeHayProvider", "Error in getMainPage for page $page", e)
            newHomePageResponse(emptyList(), false)
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        return try {
            val baseUrl = getBaseUrl()
            val searchUrl = "$baseUrl/tim-kiem/${query.encodeUri()}.html"
            val document = app.get(searchUrl).document
            document.select("div.movies-list div.movie-item").mapNotNull {
                it.toSearchResponse(this, baseUrl)
            }
        } catch (e: Exception) {
            Log.e("AnimeHayProvider", "Error in search for query '$query'", e)
            emptyList()
        }
    }

    override suspend fun load(url: String): LoadResponse? {
        return try {
            val document = app.get(url).document
            document.toLoadResponse(this, url, getBaseUrl())
        } catch (e: Exception) {
            Log.e("AnimeHayProvider", "Error in load for $url", e)
            null
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val response = app.get(data, referer = directUrl).text
        val sources = mutableMapOf<String, String>()
        val tokLink = response.substringAfter("tik: '", "").substringBefore("',", "")
        if (tokLink.isNotBlank()) {
            sources["Server TOK"] = tokLink
        }
        val gunId = response.substringAfter("$directUrl/gun.php?id=", "").substringBefore("&ep_id=", "")
        if (gunId.isNotBlank()) {
            sources["Server GUN"] = "https://pt.rapovideo.xyz/playlist/v2/$gunId/master.m3u8"
        }
        val phoId = response.substringAfter("$directUrl/pho.php?id=", "").substringBefore("&ep_id=", "")
        if (phoId.isNotBlank()) {
            sources["Server PHO"] = "https://pt.rapovideo.xyz/playlist/$phoId/master.m3u8"
        }
        sources.forEach { (serverName, url) ->
            callback(
                newExtractorLink(
                    source = name,
                    name = serverName,
                    url = url,
                    type = ExtractorLinkType.M3U8
                ) {
                    this.referer = "$directUrl/"
                    this.quality = Qualities.Unknown.value
                }
            )
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
    
    /**
    * HÀM ĐƯỢC CẬP NHẬT ĐỂ HIỂN THỊ TẬP MỚI
    */
    private fun Element.toSearchResponse(provider: MainAPI, baseUrl: String): SearchResponse? {
        try {
            val linkElement = this.selectFirst("> a[href]") ?: this.selectFirst("a[href*=thong-tin-phim]") ?: return null
            val href = fixUrl(linkElement.attr("href"), baseUrl) ?: return null
            val title = this.selectFirst("div.name-movie")?.text()?.trim()
                ?: linkElement.attr("title")?.trim()
                ?: return null
            val posterUrl = this.selectFirst("img")?.let { it.attr("src").ifBlank { it.attr("data-src") } }
            
            val tvType = when {
                href.contains("/phim/", ignoreCase = true) -> TvType.AnimeMovie
                else -> TvType.Anime
            }

            // TÌM VÀ THÊM THÔNG TIN TẬP
            val episodeStatusText = this.selectFirst("span.ribbon-text")?.text()?.trim()

            // Sử dụng newAnimeSearchResponse
            return provider.newAnimeSearchResponse(title, href, tvType) {
                this.posterUrl = fixUrl(posterUrl, baseUrl)
                
                if (!episodeStatusText.isNullOrBlank()) {
                    // SỬA LỖI: Bỏ dòng `this.quality` vì `SearchQuality.Custom` không tồn tại.
                    // Giao diện sẽ tự hiển thị số tập từ map `episodes`.

                    // Cải thiện logic để lấy số tập cuối cùng trong chuỗi
                    // (xử lý "Tập 24" -> 24, "Full 12/12" -> 12)
                    val epRegex = Regex("""(\d+)""")
                    val episodeNumber = epRegex.findAll(episodeStatusText).lastOrNull()?.value?.toIntOrNull()

                    if (episodeNumber != null) {
                        this.episodes[DubStatus.Subbed] = episodeNumber
                    }
                }
            }
        } catch (e: Exception) {
            Log.e("AnimeHayProvider", "Error in toSearchResponse for element: ${this.html().take(100)}", e)
            return null
        }
    }

    private suspend fun Document.toLoadResponse(provider: MainAPI, url: String, baseUrl: String): LoadResponse? {
        try {
            val title = this.selectFirst("h1.heading_movie")?.text()?.trim() ?: return null
            val genres = this.select("div.list_cate a").mapNotNull { it.text()?.trim() }
            val isChineseAnimation = genres.any { it.contains("CN Animation", ignoreCase = true) }
            val hasEpisodes = this.selectFirst("div.list-item-episode a") != null
            val mainTvType = when {
                hasEpisodes && isChineseAnimation -> TvType.Cartoon
                hasEpisodes -> TvType.Anime
                !hasEpisodes && isChineseAnimation -> TvType.Cartoon
                else -> TvType.AnimeMovie
            }
            val animehayPoster = this.selectFirst("div.head div.first img")?.attr("src")
            val animehayFinalPosterUrl = fixUrl(animehayPoster, baseUrl)
            val finalPosterUrl = if (mainTvType == TvType.Anime || mainTvType == TvType.AnimeMovie || mainTvType == TvType.OVA) {
                getKitsuPoster(title) ?: animehayFinalPosterUrl
            } else {
                animehayFinalPosterUrl
            }
            val description = this.selectFirst("div.desc > div:last-child")?.text()?.trim()
            val year = this.selectFirst("div.update_time div:nth-child(2)")?.text()?.trim()?.filter { it.isDigit() }?.toIntOrNull()
            val ratingText = this.selectFirst("div.score div:nth-child(2)")?.text()?.trim()
            val rating = ratingText?.split("||")?.getOrNull(0)?.trim()?.toDoubleOrNull()?.toAnimeHayRatingInt()
            val statusText = this.selectFirst("div.status div:nth-child(2)")?.text()?.trim()
            val status = when {
                statusText?.contains("Hoàn thành", ignoreCase = true) == true -> ShowStatus.Completed
                statusText?.contains("Đang", ignoreCase = true) == true -> ShowStatus.Ongoing
                else -> null
            }
            val episodes = this.select("div.list-item-episode a").mapNotNull { epLink ->
                val epUrl = fixUrl(epLink.attr("href"), baseUrl)
                val epName = epLink.attr("title")?.trim().takeIf { !it.isNullOrBlank() } ?: epLink.text()
                if (epUrl != null && epName.isNotBlank()) {
                    newEpisode(data = epUrl) { this.name = epName }
                } else null
            }.reversed()
            val recommendations = this.select("div.movie-recommend div.movie-item").mapNotNull {
                it.toSearchResponse(provider, baseUrl)
            }
            return if (hasEpisodes) {
                provider.newTvSeriesLoadResponse(title, url, mainTvType, episodes) {
                    this.posterUrl = finalPosterUrl
                    this.plot = description
                    this.tags = genres
                    this.year = year
                    this.rating = rating
                    this.showStatus = status
                    this.recommendations = recommendations
                }
            } else {
                val durationMinutes = this.selectFirst("div.duration div:nth-child(2)")?.text()?.filter { it.isDigit() }?.toIntOrNull()
                provider.newMovieLoadResponse(title, url, mainTvType, url) {
                    this.posterUrl = finalPosterUrl
                    this.plot = description
                    this.tags = genres
                    this.year = year
                    this.rating = rating
                    durationMinutes?.let { addDuration(it.toString()) }
                    this.recommendations = recommendations
                }
            }
        } catch (e: Exception) {
            Log.e("AnimeHayProvider", "Error in toLoadResponse for url: $url", e)
            return null
        }
    }

    private fun String?.encodeUri(): String = URLEncoder.encode(this ?: "", "UTF-8")
    private fun Double?.toAnimeHayRatingInt(): Int? = this?.let { (it * 1000).roundToInt().coerceIn(0, 10000) }
    private fun fixUrl(url: String?, baseUrl: String): String? {
        if (url.isNullOrBlank()) return null
        return try {
            when {
                url.startsWith("http") -> url
                url.startsWith("//") -> "https:$url"
                else -> URL(URL(baseUrl), url).toString()
            }
        } catch (e: Exception) {
            if (url.startsWith("http")) url else null
        }
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
