package recloudstream

import android.util.Log
import android.widget.Toast
import android.net.Uri
import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.utils.AppUtils.toJson
import java.net.URLEncoder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import com.lagradost.cloudstream3.CommonActivity.showToast
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import okhttp3.Interceptor
import okhttp3.ResponseBody
import okhttp3.ResponseBody.Companion.toResponseBody

data class EpisodeSource(
    @JsonProperty("server") val server: String,
    @JsonProperty("url") val url: String
)

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

    override val mainPage = mainPageOf(
        "/danh-sach/phim-moi-cap-nhat/" to "Phim Mới Cập Nhật",
        "/the-loai/cn-animation/" to "CN Animation",
        "/danh-sach/phim-sieu-nhan/" to "Tokusatsu",
        "/bang-xep-hang/day.html" to "Xem Nhiều Trong Ngày"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse? {
        // Chỉ hiện Toast ở trang 1
        if (page == 1) {
            withContext(Dispatchers.Main) {
                CommonActivity.activity?.let { activity ->
                    showToast(activity, "Free Repo From H4RS", Toast.LENGTH_LONG)
                }
            }
        }
        
        val currentBaseUrl = try { getBaseUrl() } catch (e: Exception) { return null }

        val path = request.data
        val url = if (page > 1 && !path.contains("bang-xep-hang")) {
            val slug = if (path.endsWith('/')) path else "$path/"
            "$currentBaseUrl${slug}trang-$page.html"
        } else {
            "$currentBaseUrl$path"
        }

        return try {
            val document = app.get(url, referer = currentBaseUrl).document

            val home = when {
                path.contains("bang-xep-hang") -> {
                    document.select("ul.bxh-movie-phimletv li.group").mapNotNull { element ->
                        val titleElement = element.selectFirst("h3.title-item a") ?: return@mapNotNull null
                        val href = fixUrlBased(titleElement.attr("href")) ?: return@mapNotNull null
                        val title = titleElement.text()
                        val posterUrl = fixUrlBased(element.selectFirst("a.thumb img")?.attr("src"))

                        newAnimeSearchResponse(title, href, TvType.Anime) {
                            this.posterUrl = posterUrl
                        }
                    }
                }
                else -> {
                    document.select("ul.MovieList li.TPostMv").mapNotNull {
                        mapElementToSearchResponse(it, currentBaseUrl)
                    }
                }
            }

            val hasNextPage = if (path.contains("bang-xep-hang")) {
                false
            } else {
                document.selectFirst("div.wp-pagenavi > a.next, div.wp-pagenavi > span.current + a") != null
            }

            newHomePageResponse(
                list = HomePageList(
                    name = request.name,
                    list = home
                ),
                hasNext = hasNextPage
            )
        } catch (e: Exception) {
            Log.e(name, "getMainPage failed for url '$url'", e)
            null
        }
    }

    private suspend fun getBaseUrl(): String {
        baseUrlMutex.withLock {
            if (baseUrl == null) {
                try {
                    val response = app.get(mainUrl, timeout = 15_000L).text
                    val scriptMainUrl = Regex("var MAIN_URL\\s*=\\s*['\"]([^'\"]+)['\"]").find(response)?.groupValues?.get(1)
                    
                    if (!scriptMainUrl.isNullOrBlank()) {
                        baseUrl = scriptMainUrl.removeSuffix("/")
                    }
                } catch (e: Exception) {
                    Log.e(name, "Lỗi khi lấy MAIN_URL từ script: ${e.message}")
                }

                if (baseUrl == null) {
                    try {
                        val redirectResponse = app.get("https://anime12.site")
                        baseUrl = redirectResponse.url.removeSuffix("/")
                        Log.d(name, "Đã lấy baseUrl từ redirect anime12.site: $baseUrl")
                    } catch (e: Exception) {
                        Log.e(name, "Lỗi khi check redirect từ anime12.site: ${e.message}")
                    }
                }

                if (baseUrl == null) {
                    baseUrl = "https://anime13.site"
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

    private fun mapElementToSearchResponse(
        element: Element,
        currentBaseUrl: String
    ): AnimeSearchResponse? {
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

    override suspend fun search(query: String): List<SearchResponse>? {
        val currentBaseUrl = try { getBaseUrl() } catch (e: Exception) { return null }
        val encodedQuery = URLEncoder.encode(query, "UTF-8")
        val searchUrl = "$currentBaseUrl/tim-kiem/$encodedQuery.html"
        return try {
            val document = app.get(searchUrl, referer = currentBaseUrl).document
            val results = document.select("ul.MovieList li.TPostMv")
            results.mapNotNull { mapElementToSearchResponse(it, currentBaseUrl) }
        } catch (e: Exception) {
            Log.e(name, "Search failed for query '$query' at url $searchUrl", e)
            null
        }
    }

    override suspend fun load(url: String): LoadResponse? {
        try {
            val currentBaseUrl = try { getBaseUrl() } catch (e: Exception) { return null }
            val mainDocument = app.get(url, referer = currentBaseUrl).document

            val mainTitle = mainDocument.selectFirst("h1.Title")?.text()?.trim()
            val subTitle = mainDocument.selectFirst("h2.SubTitle")?.text()?.trim()

            val displayTitle = mainTitle?.takeIf { it.isNotBlank() }
                ?: subTitle?.takeIf { it.isNotBlank() }
                ?: throw ErrorLoadingException("Không tìm thấy tiêu đề")

            val finalPosterUrl =
                mainDocument.selectFirst("article.TPost header div.Image figure.Objf img")?.let {
                    it.attr("data-src").ifBlank { it.attr("src") }
                }?.let { fixUrlBased(it) }
            
            val description =
                mainDocument.selectFirst("article.TPost header div.Description")?.text()?.trim()
            val year = mainDocument.selectFirst("p.Info span.Date a")?.text()?.toIntOrNull()
            val ratingText = mainDocument.selectFirst("div#star")?.attr("data-score")
            val rating = ratingText?.toFloatOrNull()
            val statusElement =
                mainDocument.selectFirst("div#MvTb-Info div.mvici-left ul.InfoList li:contains(Trạng thái)")
            val statusText =
                statusElement?.selectFirst("span.Info")?.text()?.trim() ?: statusElement?.ownText()
                    ?.trim()
            val parsedStatus = when {
                statusText?.contains("Đang tiến hành", ignoreCase = true) == true -> ShowStatus.Ongoing
                statusText?.contains("Hoàn thành", ignoreCase = true) == true -> ShowStatus.Completed
                else -> null
            }
            
            val genreElements = mainDocument.select("div#MvTb-Info div.mvici-left ul.InfoList li:contains(Thể loại) a")
            val genres = genreElements.mapNotNull { it.text()?.trim()?.takeIf { tag -> tag.isNotBlank() } }

            val isDonghuaGenre = genres.any {
                it.equals("CN Animation", ignoreCase = true) || it.equals("Hoạt hình Trung Quốc", ignoreCase = true) || it.equals("Donghua", ignoreCase = true)
            }
            val isDonghuaTitle = displayTitle.contains("Donghua", ignoreCase = true) || displayTitle.contains("(CN)", ignoreCase = true)
            val isDonghuaUrl = url.contains("/cn-animation/", ignoreCase = true)
            val isTokusatsuUrl = url.contains("/tokusatsu/", ignoreCase = true)
            val isMovieUrl = url.contains("/movie-ova/", ignoreCase = true)

            val tvType = when {
                isDonghuaGenre || isDonghuaTitle || isDonghuaUrl -> TvType.Cartoon
                isTokusatsuUrl || genres.any { it.equals("Tokusatsu", ignoreCase = true) } -> TvType.TvSeries
                isMovieUrl || genres.any { it.equals("Movie & OVA", ignoreCase = true) } -> TvType.AnimeMovie
                genres.any { it.equals("OVA", ignoreCase = true) } -> TvType.OVA
                else -> TvType.Anime
            }

            var recommendations: List<SearchResponse>? = null
            
            val firstGenreLink = genreElements.firstOrNull()?.attr("href")?.let { fixUrlBased(it) }

            if (!firstGenreLink.isNullOrBlank()) {
                try {
                    val genreDoc = app.get(firstGenreLink, referer = url).document
                    val genreMovies = genreDoc.select("ul.MovieList li.TPostMv").mapNotNull { 
                        mapElementToSearchResponse(it, currentBaseUrl) 
                    }
                    if (genreMovies.isNotEmpty()) {
                        recommendations = genreMovies.shuffled().take(12)
                    }
                } catch (e: Exception) {
                    Log.e(name, "Lỗi khi lấy recommendation từ genre: $firstGenreLink", e)
                }
            }

            if (recommendations.isNullOrEmpty()) {
                recommendations = mainDocument.select("div.MovieListRelated ul.MovieList li.TPostMv").mapNotNull {
                    mapElementToSearchResponse(it, currentBaseUrl)
                }
            }

            val watchPageUrl =
                mainDocument.selectFirst("a.watch_button_more")?.attr("href")?.let { fixUrlBased(it) }
            var episodes = emptyList<Episode>()
            if (!watchPageUrl.isNullOrBlank()) {
                try {
                    val episodeListPageDocument = app.get(watchPageUrl, referer = url).document
                    
                    val episodeMap = mutableMapOf<String, MutableList<EpisodeSource>>()

                    episodeListPageDocument.select("div.server.clearfix.server-group").forEach { serverBlock ->
                        val serverName = serverBlock.selectFirst("h3.server-name")?.text()?.replace(":", "")?.trim() ?: "Server"
                        serverBlock.select("ul.list-episode li.episode a.episode-link").forEach { epElement ->
                            val epHref = epElement.attr("href")?.let { fixUrlBased(it) }
                            if (!epHref.isNullOrBlank()) {
                                val epIdentifier = epElement.attr("data-epname").trim().ifBlank { epElement.text().trim() }
                                if (epIdentifier.isNotBlank()) {
                                    val source = EpisodeSource(server = serverName, url = epHref)
                                    episodeMap.getOrPut(epIdentifier) { mutableListOf() }.add(source)
                                 }
                            }
                        }
                    }

                    episodes = episodeMap.map { (epIdentifier, sources) ->
                        val epName = if (epIdentifier.equals("Full", ignoreCase = true) || epIdentifier.equals("Movie", ignoreCase = true)) {
                            epIdentifier
                        } else {
                            "Tập $epIdentifier"
                        }
                        
                        val data = sources.toJson()
                        
                        newEpisode(data) {
                            this.name = epName
                        }
                    }.sortedBy { ep ->
                        ep.name?.replace(Regex("[^0-9]"), "")?.toIntOrNull() ?: Int.MAX_VALUE
                    }
                    
                } catch (e: Exception) {
                    Log.e(name, "load: Error fetching/parsing episode page $watchPageUrl", e)
                }
            }
            
            return newTvSeriesLoadResponse(displayTitle, url, tvType, episodes) {
                this.apiName = this@AnimetProvider.name
                this.posterUrl = finalPosterUrl
                this.year = year
                this.plot = description
                this.score = rating?.let { Score.from10(it) }
                this.tags = genres
                this.showStatus = parsedStatus
                this.recommendations = recommendations
                if (!finalPosterUrl.isNullOrBlank() && baseUrl != null && finalPosterUrl.startsWith(baseUrl!!)) {
                    this.posterHeaders = mapOf("Referer" to currentBaseUrl)
                }
            }
        } catch (e: Exception) {
            Log.e(name, "Load failed for URL $url", e)
            return null
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        try {
            val sources = AppUtils.parseJson<List<EpisodeSource>>(data)
            val uniqueSources = sources.distinctBy { it.url }

            coroutineScope {
                uniqueSources.map { source ->
                    async {
                        try {
                            val currentBaseUrl = getBaseUrl() 
                            val responseText = app.get(source.url).text
                            val doc = Jsoup.parse(responseText)

                            val movieId = Regex("MovieId\\s*=\\s*['\"](\\d+)['\"]").find(responseText)?.groupValues?.get(1)
                            val episodeId = Regex("EpisodeId\\s*=\\s*['\"](\\d+)['\"]").find(responseText)?.groupValues?.get(1)

                            if (movieId != null && episodeId != null) {
                                val serverElements = doc.select(".list-server1 .server-item span[data-index]")

                                serverElements.map { element ->
                                    async {
                                        val svId = element.attr("data-index") 
                                        val serverName = element.text().trim() 

                                        val ajaxUrl = "$currentBaseUrl/ajax/LoadPlayer_photov2"
                                        val postData = mapOf(
                                            "id" to movieId,
                                            "ep" to episodeId,
                                            "sv" to svId
                                        )
                                        
                                        val headers = mapOf(
                                            "X-Requested-With" to "XMLHttpRequest",
                                            "Referer" to source.url,
                                            "Origin" to currentBaseUrl
                                        )

                                        try {
                                            val ajaxResponse = app.post(ajaxUrl, data = postData, headers = headers).text
                                            val iframeSrc = Jsoup.parse(ajaxResponse).select("iframe").attr("src")

                                            if (iframeSrc.isNotEmpty()) {
                                                val m3u8Url = Uri.parse(iframeSrc).getQueryParameter("v")
                                                
                                                if (!m3u8Url.isNullOrBlank()) {
                                                    callback.invoke(
                                                        newExtractorLink(
                                                            source = this@AnimetProvider.name,
                                                            name = serverName,
                                                            url = m3u8Url,
                                                            type = ExtractorLinkType.M3U8
                                                        ) {
                                                            this.referer = currentBaseUrl 
                                                        }
                                                    )
                                                }
                                            }
                                        } catch (e: Exception) {
                                            Log.e(name, "Lỗi khi gọi API LoadPlayer cho server $serverName: ${e.message}")
                                        }
                                    }
                                }.awaitAll()
                            } else {
                                Log.e(name, "Không tìm thấy MovieId hoặc EpisodeId trong trang: ${source.url}")
                            }
                        } catch (e: Exception) {
                            Log.e(name, "Lỗi khi xử lý source: ${source.url}", e)
                        }
                    }
                }.awaitAll()
            }
            return true
        } catch (e: Exception) {
            Log.e(name, "loadLinks thất bại hoàn toàn", e)
            return false
        }
    }

    override fun getVideoInterceptor(extractorLink: ExtractorLink): Interceptor? {
        return object : Interceptor {
            override fun intercept(chain: Interceptor.Chain): okhttp3.Response {
                val request = chain.request()
                val response = chain.proceed(request)
                val url = request.url.toString()
                
                if (url.contains(".tiktokcdn.") || url.contains("ibyteimg.com")) {
                    response.body?.let { body ->
                        try {
                            val fixedBytes = skipByteError(body)
                            val newBody = fixedBytes.toResponseBody(body.contentType())
                            return response.newBuilder().body(newBody).build()
                        } catch (e: Exception) {
                            Log.e(name, "skipByteError failed for URL: $url", e)
                        }
                    }
                }
                return response
            }
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
