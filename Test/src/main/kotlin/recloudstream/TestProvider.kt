package recloudstream

import android.util.Log
import android.widget.Toast
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.CommonActivity.showToast
import com.lagradost.cloudstream3.LoadResponse.Companion.addDuration
import com.lagradost.cloudstream3.utils.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Interceptor
import okhttp3.Response
import okhttp3.ResponseBody
import okhttp3.ResponseBody.Companion.toResponseBody
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.net.MalformedURLException
import java.net.URL
import java.net.URLEncoder
import java.util.*

class AnimeHayProvider : MainAPI() {

    // === Cấu hình Provider ===
    override var mainUrl = "https://ahay.in"
    override var name = "AnimeHay"
    override val supportedTypes = setOf(TvType.Anime, TvType.AnimeMovie, TvType.OVA, TvType.Cartoon)
    override var lang = "vi"
    override val hasMainPage = true

    // === Xử lý Domain động ===
    private var currentActiveUrl = "https://animehay.bid"
    private var domainCheckPerformed = false
    private val domainCheckUrl = mainUrl

    private suspend fun getBaseUrl(): String {
        if (domainCheckPerformed) return currentActiveUrl

        var finalNewDomain: String? = null
        try {
            val response = app.get(domainCheckUrl, allowRedirects = true)
            val landedUrl = response.url
            val document = response.document

            // 1. Tìm link trong thẻ a
            val linkSelectors = listOf("a.bt-link", "a.bt-link-1")
            var hrefFromContent: String? = null
            for (selector in linkSelectors) {
                hrefFromContent = document.selectFirst(selector)?.attr("href")
                if (!hrefFromContent.isNullOrBlank()) break
            }

            // 2. Tìm link trong script
            if (hrefFromContent.isNullOrBlank()) {
                val scriptElements = document.select("script")
                val newDomainRegex = Regex("""var\s+new_domain\s*=\s*["'](https?://[^"']+)["']""")
                for (scriptElement in scriptElements) {
                    if (!scriptElement.hasAttr("src")) {
                        val match = newDomainRegex.find(scriptElement.html())
                        if (match != null && match.groups.size > 1) {
                            hrefFromContent = match.groups[1]?.value
                            break
                        }
                    }
                }
            }

            // Xử lý URL tìm được
            if (!hrefFromContent.isNullOrBlank()) {
                try {
                    val urlObject = URL(hrefFromContent)
                    finalNewDomain = "${urlObject.protocol}://${urlObject.host}"
                } catch (e: MalformedURLException) {
                    Log.e("AnimeHayProvider", "Malformed URL: $hrefFromContent")
                }
            }

            // 3. Kiểm tra redirect
            if (finalNewDomain.isNullOrBlank()) {
                val landedUrlBase = try {
                    val landedObj = URL(landedUrl)
                    "${landedObj.protocol}://${landedObj.host}"
                } catch (e: Exception) { null }

                val initialCheckUrlHost = try { URL(domainCheckUrl).host } catch (e: Exception) { null }

                if (landedUrlBase != null && landedUrlBase.startsWith("http") && landedUrlBase != initialCheckUrlHost) {
                    finalNewDomain = landedUrlBase
                }
            }

            // Cập nhật URL
            if (!finalNewDomain.isNullOrBlank() && finalNewDomain != currentActiveUrl) {
                if (!finalNewDomain.contains("archive.org", ignoreCase = true)) {
                    currentActiveUrl = finalNewDomain
                }
            }
        } catch (e: Exception) {
            Log.e("AnimeHayProvider", "Domain check failed", e)
        } finally {
            domainCheckPerformed = true
        }
        return currentActiveUrl
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        withContext(Dispatchers.Main) {
            CommonActivity.activity?.let { activity ->
                showToast(activity, "Free Repo From H4RS", Toast.LENGTH_LONG)
            }
        }

        try {
            val siteBaseUrl = getBaseUrl()
            val urlToFetch = if (page <= 1) siteBaseUrl else "$siteBaseUrl/phim-moi-cap-nhap/trang-$page.html"
            
            val document = app.get(urlToFetch).document
            val homePageItems = document.select("div.movies-list div.movie-item")
                .mapNotNull { element -> element.toSearchResponse(this, siteBaseUrl) }

            if (page > 1 && homePageItems.isEmpty()) return newHomePageResponse(emptyList(), false)

            var calculatedHasNext = false
            val pagination = document.selectFirst("div.pagination")
            if (pagination != null) {
                val activePageElement = pagination.selectFirst("a.active_page")
                val currentPageFromHtml = activePageElement?.text()?.toIntOrNull() ?: page
                val nextPageSelector = "a[href*=/trang-${currentPageFromHtml + 1}.html]"
                if (pagination.selectFirst(nextPageSelector) != null) {
                    calculatedHasNext = true
                }
            }

            val listTitle = request.name.ifBlank { "Mới cập nhật" }
            val homeList = HomePageList(listTitle, homePageItems)
            return newHomePageResponse(listOf(homeList), hasNext = calculatedHasNext)

        } catch (e: Exception) {
            Log.e("AnimeHayProvider", "Error getMainPage", e)
            return newHomePageResponse(emptyList(), false)
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        try {
            val baseUrl = getBaseUrl()
            val encodedQuery = query.encodeUri()
            val searchUrl = "$baseUrl/tim-kiem/$encodedQuery.html"
            val document = app.get(searchUrl).document

            val moviesListContainer = document.selectFirst("div.movies-list") ?: return emptyList()
            return moviesListContainer.select("div.movie-item").mapNotNull { 
                it.toSearchResponse(this, baseUrl) 
            }
        } catch (e: Exception) {
            Log.e("AnimeHayProvider", "Error search", e)
            return emptyList()
        }
    }

    override suspend fun load(url: String): LoadResponse? {
        try {
            val document = app.get(url).document
            // Truyền thêm hàm load ở đây để fetch nội dung bên trong
            return document.toLoadResponse(this, url, getBaseUrl())
        } catch (e: Exception) {
            Log.e("AnimeHayProvider", "Error load", e)
            return null
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val directUrl = URL(data).let { "${it.protocol}://${it.host}" }
        val response = app.get(data, referer = directUrl).text
        val sources = mutableMapOf<String, String>()

        // 1. Server TOK
        val tokLink = response.substringAfter("tik: '", "").substringBefore("',", "")
        if (tokLink.isNotBlank()) sources["Server TOK"] = tokLink

        // 2. Server GUN
        val gunId = response.substringAfter("$directUrl/gun.php?id=", "").substringBefore("&ep_id=", "")
        if (gunId.isNotBlank()) sources["Server GUN"] = "https://pt.rapovideo.xyz/playlist/v2/$gunId/master.m3u8"

        // 3. Server PHO
        val phoId = response.substringAfter("$directUrl/pho.php?id=", "").substringBefore("&ep_id=", "")
        if (phoId.isNotBlank()) sources["Server PHO"] = "https://pt.rapovideo.xyz/playlist/$phoId/master.m3u8"

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
                    response.body?.let { body ->
                        try {
                            val fixedBytes = skipByteError(body)
                            val newBody = fixedBytes.toResponseBody(body.contentType())
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

    // === Helpers ===

    private fun Element.toSearchResponse(provider: MainAPI, baseUrl: String): AnimeSearchResponse? {
        return runCatching {
            val linkElement = this.selectFirst("> a[href], a[href*=thong-tin-phim]") ?: return null
            val href = fixUrl(linkElement.attr("href"), baseUrl) ?: return null
            val title = this.selectFirst("div.name-movie")?.text()?.takeIf { it.isNotBlank() }
                ?: linkElement.attr("title").takeIf { it.isNotBlank() } ?: return null
            val posterUrl = fixUrl(this.selectFirst("img")?.let { it.attr("src").ifBlank { it.attr("data-src") } }, baseUrl)
            val tvType = if (href.contains("/phim/", true)) TvType.AnimeMovie else TvType.Anime

            provider.newAnimeSearchResponse(name = title, url = href, type = tvType) {
                this.posterUrl = posterUrl
                this.dubStatus = EnumSet.of(DubStatus.Subbed)
                val episodeText = this@toSearchResponse.selectFirst("div.episode-latest span")?.text()?.trim()
                if (episodeText != null && !episodeText.contains("phút", ignoreCase = true)) {
                    val epCount = episodeText.substringBefore("/").substringBefore("-").filter { it.isDigit() }.toIntOrNull()
                    if (epCount != null) {
                        this.episodes[DubStatus.Subbed] = epCount
                    }
                }
            }
        }.getOrNull()
    }

    // === START EDIT: Cập nhật hàm toLoadResponse để fetch Genre ===
    private suspend fun Document.toLoadResponse(provider: MainAPI, url: String, baseUrl: String): LoadResponse? {
        val title = this.selectFirst("h1.heading_movie")?.text()?.trim() ?: return null
        
        // Lấy danh sách thể loại và Link thể loại đầu tiên
        val genreElements = this.select("div.list_cate div:nth-child(2) a")
        val genres = genreElements.mapNotNull { it.text()?.trim() }
        val genreUrl = genreElements.firstOrNull()?.attr("href")?.let { fixUrl(it, baseUrl) } // Lấy URL thể loại
        
        val isChineseAnimation = genres.any { it.contains("CN Animation", ignoreCase = true) }
        
        val posterUrl = fixUrl(this.selectFirst("div.head div.first img")?.attr("src"), baseUrl)
        val description = this.selectFirst("div.desc > div:last-child")?.text()?.trim()
        val year = this.selectFirst("div.update_time div:nth-child(2)")?.text()?.filter { it.isDigit() }?.toIntOrNull()
        
        val statusText = this.selectFirst("div.status div:nth-child(2)")?.text()?.trim()
        val status = when {
            statusText?.contains("Hoàn thành", ignoreCase = true) == true -> ShowStatus.Completed
            statusText?.contains("Đang tiến hành", ignoreCase = true) == true -> ShowStatus.Ongoing
            else -> ShowStatus.Ongoing
        }

        val ratingText = this.selectFirst("div.score div:nth-child(2)")?.text()?.trim()
        val rating = ratingText?.split("||")?.firstOrNull()?.trim()?.toFloatOrNull()?.takeIf { !it.isNaN() }

        val episodeElements = this.select("div.list-item-episode a")
        val hasEpisodes = episodeElements.isNotEmpty()

        val mainTvType = if (isChineseAnimation) TvType.Cartoon else if (hasEpisodes) TvType.Anime else TvType.AnimeMovie

        val episodes = episodeElements.mapNotNull { episodeLink ->
            val epUrl = fixUrl(episodeLink.attr("href"), baseUrl)
            val epNameSpan = episodeLink.selectFirst("span")?.text()?.trim()
            val epNum = epNameSpan?.filter { it.isDigit() }?.toIntOrNull()
            
            val finalEpName = when {
                epNameSpan.isNullOrBlank() -> "Tập $epNum"
                epNameSpan.all { it.isDigit() } -> "Tập $epNameSpan"
                else -> epNameSpan 
            }

            if (epUrl != null) {
                newEpisode(data = epUrl) {
                    this.name = finalEpName
                    this.episode = epNum
                }
            } else null
        }.reversed()

        // === LOGIC RECOMMENDATION MỚI ===
        // 1. Thử lấy recommendation có sẵn từ web
        var recommendations = this.select("div.movie-recommend div.movie-item").mapNotNull {
            it.toSearchResponse(provider, baseUrl)
        }

        // 2. Nếu web không có, tự fetch từ trang Genre (random)
        if (recommendations.isEmpty() && !genreUrl.isNullOrBlank()) {
            try {
                // Fetch trang thể loại
                val genreDoc = app.get(genreUrl).document
                // Parse danh sách phim từ trang thể loại
                val genreItems = genreDoc.select("div.movies-list div.movie-item")
                
                recommendations = genreItems.mapNotNull { 
                    it.toSearchResponse(provider, baseUrl) 
                }
                .filter { it.url != url } // Loại bỏ phim hiện tại
                .shuffled() // Xáo trộn ngẫu nhiên
                .take(12) // Lấy 12 phim
                
            } catch (e: Exception) {
                Log.e("AnimeHayProvider", "Failed to fetch random recommendations from genre", e)
            }
        }
        // ==============================

        return provider.newAnimeLoadResponse(title, url, mainTvType) {
            this.posterUrl = posterUrl
            this.plot = description
            this.tags = genres
            this.year = year
            this.score = rating?.let { Score.from10(it) }
            this.showStatus = status
            this.recommendations = recommendations

            if (hasEpisodes) {
                addEpisodes(DubStatus.Subbed, episodes)
            } else {
                val durationText = this@toLoadResponse.selectFirst("div.duration div:nth-child(2)")?.text()?.trim()
                val durationMinutes = durationText?.filter { it.isDigit() }?.toIntOrNull()
                if (durationMinutes != null) {
                   addDuration(durationMinutes.toString())
                }
            }
        }
    }
    // === END EDIT ===

    private fun String?.encodeUri(): String {
        return try {
            URLEncoder.encode(this ?: "", "UTF-8")
        } catch (e: Exception) {
            this ?: ""
        }
    }

    private fun fixUrl(url: String?, baseUrl: String): String? {
        if (url.isNullOrBlank()) return null
        return try {
            when {
                url.startsWith("http") -> url
                url.startsWith("//") -> "https:$url"
                url.startsWith("/") -> baseUrl.trimEnd('/') + url
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
        if (byteArray[i].toInt() == 71 && byteArray[i + 188].toInt() == 71) {
            start = i
            break
        }
    }
    return if (start > 0) byteArray.copyOfRange(start, byteArray.size) else byteArray
}
