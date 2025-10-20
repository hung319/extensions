package recloudstream

// === Imports ===
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.network.CloudflareKiller
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import com.lagradost.cloudstream3.utils.AppUtils.toJson
// import org.jsoup.nodes.Element // Đã xóa
import java.net.URLEncoder
// --- Xóa Imports Crypto ---
import android.util.Base64
import okhttp3.Interceptor
import okhttp3.Response
import okhttp3.ResponseBody
import okhttp3.ResponseBody.Companion.toResponseBody
import java.util.EnumSet
import java.util.concurrent.atomic.AtomicBoolean
import java.util.Locale
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.awaitAll
import com.lagradost.cloudstream3.CommonActivity.showToast
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import android.widget.Toast
// === Imports mới cho API ===
import com.lagradost.cloudstream3.LoadResponse.Companion.addTrailer
import com.lagradost.cloudstream3.mvvm.logError
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import java.io.IOException
import com.lagradost.cloudstream3.newEpisode
import java.net.URL // Thêm import cho getBaseUrl và headers

// === Provider Class ===
class Anime47Provider : MainAPI() {
    // === FIX: Giữ lại getBaseUrl cho web ===
    override var mainUrl = "https://bit.ly/anime47moi"
    private val secondaryRedirectUrl = "https://hoangsabelongtovn.site/"
    private var baseUrl = "https://anime47.live" // Domain cho web (Referer, fixUrl)
    
    // === FIX: Thêm biến API_URL riêng ===
    private val apiBaseUrl = "https://anime47.love/api" // Domain cho API
    // === Kết thúc FIX ===

    override var name = "Anime47"
    override val hasMainPage = true
    override var lang = "vi"
    override val hasDownloadSupport = true
    
    // === FIX: Chỉ giữ lại Anime ===
    override val supportedTypes = setOf(TvType.Anime)

    private val interceptor = CloudflareKiller()

    // === FIX: mainPage (API) ===
    override val mainPage = mainPageOf(
        "/anime/filter?lang=vi&sort=latest" to "Anime Mới Cập Nhật",
        "/anime/filter?lang=vi&sort=rating" to "Top Đánh Giá",
        "/anime/filter?lang=vi&type=tv" to "Anime TV",
        "/anime/filter?lang=vi&type=movie" to "Anime Movie"
    )
    
    // === FIX: Giữ lại getBaseUrl ===
    private var domainResolutionAttempted = false

    private suspend fun getBaseUrl(): String {
        if (domainResolutionAttempted) {
            return baseUrl
        }

        var newBaseUrl: String? = null

        // Attempt 1: HEAD request
        try {
            val response = app.head(secondaryRedirectUrl, allowRedirects = false)
            val location = response.headers["Location"]
            if (!location.isNullOrBlank()) {
                val urlObject = java.net.URL(location)
                newBaseUrl = "${urlObject.protocol}://${urlObject.host}"
            }
        } catch (_: Exception) {
            // Proceed to next
        }

        // Attempt 2: Full GET redirect
        if (newBaseUrl == null) {
            try {
                val response = app.get(mainUrl, allowRedirects = true, timeout = 10_000)
                val finalUrl = java.net.URL(response.url)
                newBaseUrl = "${finalUrl.protocol}://${finalUrl.host}"
            } catch (_: Exception) {
                // Fallback
            }
        }
        
        domainResolutionAttempted = true

        if (newBaseUrl != null) {
            baseUrl = newBaseUrl
        }
        
        return baseUrl
    } 
    // === Kết thúc FIX ===

    // === Thêm Data Classes cho API ===
    private data class GenreInfo(val name: String?)
    private data class Post(
        val id: Int,
        val title: String,
        val slug: String,
        val link: String,
        val poster: String?,
        val episodes: String?,
        val type: String?,
        val year: String?
    )
    private data class ApiFilterData(val posts: List<Post>)
    private data class ApiFilterResponse(val data: ApiFilterData)

    private data class VideoItem(val url: String?)

    private data class DetailPost(
        val id: Int,
        val title: String?,
        val description: String?,
        val poster: String?,
        val cover: String?,
        val type: String?,
        val year: String?,
        val genres: List<GenreInfo>?,
        val videos: List<VideoItem>?
    )
    private data class ApiDetailResponse(val data: DetailPost)

    private data class EpisodeListItem(
        val id: Int,
        val number: Int?,
        val title: String?
    )
    private data class EpisodeGroup(
        val name: String?,
        val episodes: List<EpisodeListItem>
    )
    private data class EpisodeTeam(
        val team_name: String?,
        val groups: List<EpisodeGroup>
    )
    private data class ApiEpisodeResponse(val teams: List<EpisodeTeam>)

    private data class SubtitleItem(
        val file: String?,
        val label: String?
    )

    private data class Stream(
        val url: String?,
        val server_name: String?,
        val player_type: String?,
        val subtitles: List<SubtitleItem>?
    )
    private data class WatchAnimeInfo(
        val id: Int,
        val title: String?,
        val slug: String?,
        val thumbnail: String?
    )
    private data class ApiWatchResponse(
        val id: Int?,
        val streams: List<Stream>?,
        val anime: WatchAnimeInfo?
    )

    private data class RecommendationItem(
        val id: Int,
        val title: String?,
        val link: String?,
        val poster: String?,
        val type: String?,
        val year: String?
    )
    private data class ApiRecommendationResponse(val data: List<RecommendationItem>?)

    private data class SearchItem(
        val id: Int,
        val title: String,
        val link: String,
        val image: String?, // API dùng 'image'
        val type: String?,
        val episodes: String?
    )
    private data class ApiSearchResponse(
        val results: List<SearchItem>?,
        val has_more: Boolean?
    )
    // === Kết thúc Data Classes ===

    // Hàm fixUrl (cho API)
    private fun fixUrl(url: String?): String? {
        if (url.isNullOrBlank()) return null
        if (url.contains("via.placeholder.com", ignoreCase = true)) return null
        if (url.startsWith("http")) return url
        if (url.startsWith("//")) return "https:$url"
        val base = baseUrl // Dùng biến baseUrl (được cập nhật bởi getBaseUrl)
        val relativePath = if (url.startsWith("/")) url else "/$url"
        return if (base.startsWith("http")) {
            base + relativePath
        } else {
            "https:" + base + relativePath
        }
    }

    // === FIX: getMainPage (API) + getBaseUrl ===
    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        withContext(Dispatchers.Main) {
            CommonActivity.activity?.let { activity ->
                showToast(activity, "Free Repo From H4RS", Toast.LENGTH_LONG)
            }
        }
        
        getBaseUrl() // Gọi getBaseUrl để cập nhật baseUrl (cho fixUrl)
        val url = "$apiBaseUrl${request.data}&page=$page" // Dùng apiBaseUrl cố định
        
        val res = try {
            app.get(
                url,
                interceptor = interceptor,
                timeout = 15_000
            ).parsed<ApiFilterResponse>()
        } catch (e: Exception) {
            logError(e) 
            throw ErrorLoadingException("Không thể tải trang chủ. Lỗi Cloudflare hoặc API. Chi tiết: ${e.message}")
        }

        val home = res.data.posts.mapNotNull { it.toSearchResult() }
        val hasNext = home.size >= 24
        return newHomePageResponse(request.name, home, hasNext = hasNext)
    }

    // === Thêm các hàm helper cho API ===

    // Giữ lại để phân biệt Movie/Series trong load()
    private fun DetailPost.toTvType(default: TvType = TvType.Anime): TvType {
        return when {
            this.type?.equals("TV", ignoreCase = true) == true -> TvType.Anime
            this.type?.contains("movie", ignoreCase = true) == true -> TvType.AnimeMovie
            this.type?.contains("ova", ignoreCase = true) == true -> TvType.OVA
            (this.title?.contains("Hoạt Hình Trung Quốc", ignoreCase = true) == true ) -> TvType.Cartoon
            else -> default
        }
    }

    private fun createSearchResponse(
        title: String,
        url: String, // Phải là link tuyệt đối
        poster: String?,
        tvType: TvType, // Sẽ luôn là TvType.Anime
        year: Int? = null,
        episodesStr: String? = null,
        otherName: String? = null
    ): SearchResponse {
        val epCount = episodesStr?.filter { it.isDigit() }?.toIntOrNull()
        val episodesMap: Map<DubStatus, Int> = if (epCount != null) mapOf(DubStatus.Subbed to epCount) else emptyMap()

        return newAnimeSearchResponse(title, url, tvType) {
            this.posterUrl = fixUrl(poster)
            this.year = year
            this.otherName = otherName
            if (episodesMap.isNotEmpty()) {
                this.dubStatus = EnumSet.of(DubStatus.Subbed)
                this.episodes = episodesMap.toMutableMap()
            }
        }
    }

    private fun Post.toSearchResult(): SearchResponse? {
        if (this.link.isBlank()) return null
        val fullUrl = fixUrl(this.link) ?: return null
        return createSearchResponse(
            title = this.title,
            url = fullUrl,
            poster = this.poster,
            tvType = TvType.Anime, // FIX: Chỉ dùng Anime
            year = this.year?.toIntOrNull(),
            episodesStr = this.episodes,
            otherName = this.episodes
        )
    }

    // Thêm lại helper này cho load()
    private fun RecommendationItem.toSearchResult(): SearchResponse? {
        if (this.link.isNullOrBlank() || this.title.isNullOrBlank()) return null
        val fullUrl = fixUrl(this.link) ?: return null
        return createSearchResponse(
            title = this.title,
            url = fullUrl,
            poster = this.poster,
            tvType = TvType.Anime, // FIX: Chỉ dùng Anime
            year = this.year?.toIntOrNull(),
            episodesStr = null,
            otherName = null
        )
    }

    private fun SearchItem.toSearchResult(): SearchResponse? {
        if (this.link.isBlank()) return null
        val fullUrl = fixUrl(this.link) ?: return null
        return createSearchResponse(
            title = this.title,
            url = fullUrl,
            poster = this.image,
            tvType = TvType.Anime, // FIX: Chỉ dùng Anime
            year = null,
            episodesStr = this.episodes,
            otherName = this.episodes
        )
    }
    // === Kết thúc các hàm helper ===

    // === FIX: search (API) + getBaseUrl ===
    override suspend fun search(query: String): List<SearchResponse> {
        getBaseUrl() // Gọi getBaseUrl để cập nhật baseUrl (cho fixUrl)
        val encodedQuery = URLEncoder.encode(query, "UTF-8")
        val url = "$apiBaseUrl/search/full/?lang=vi&keyword=$encodedQuery&page=1" // Dùng apiBaseUrl cố định

        val res = try {
            app.get(
                url,
                interceptor = interceptor,
                timeout = 10_000
            ).parsedSafe<ApiSearchResponse>()
        } catch (e: Exception) {
            logError(e)
            return emptyList()
        }
        
        return res?.results?.mapNotNull { it.toSearchResult() } ?: emptyList()
    }

    // === FIX: load (API) + Gộp link + getBaseUrl + TvType.Anime ===
    override suspend fun load(url: String): LoadResponse? {
        getBaseUrl() // Gọi getBaseUrl để cập nhật baseUrl (cho fixUrl)
        val animeId = url.substringAfterLast('-').trim()
        if (animeId.isBlank() || animeId.toIntOrNull() == null) {
            throw IllegalArgumentException("Invalid anime ID extracted from URL: $url")
        }

        try {
            val (infoResult, episodesResult, recommendationsResult) = coroutineScope {
                val infoTask = async {
                    val infoUrl = "$apiBaseUrl/anime/info/$animeId?lang=vi" // Dùng apiBaseUrl cố định
                    runCatching { app.get(infoUrl, interceptor = interceptor, timeout = 15_000).parsedSafe<ApiDetailResponse>() }
                }
                val episodesTask = async {
                    val episodesUrl = "$apiBaseUrl/anime/$animeId/episodes?lang=vi" // Dùng apiBaseUrl cố định
                    runCatching { app.get(episodesUrl, interceptor = interceptor, timeout = 15_000).parsedSafe<ApiEpisodeResponse>() }
                }
                val recommendationsTask = async {
                    val recUrl = "$apiBaseUrl/anime/info/$animeId/recommendations?lang=vi" // Dùng apiBaseUrl cố định
                    runCatching { app.get(recUrl, interceptor = interceptor, timeout = 15_000).parsedSafe<ApiRecommendationResponse>() }
                }
                Triple(infoTask.await(), episodesTask.await(), recommendationsTask.await())
            }

            val infoRes = infoResult.getOrThrow()
            if (infoRes == null) {
                throw IOException("Failed to parse anime info (null response) for ID: $animeId")
            }

            val episodesResponse = episodesResult.getOrThrow() 
            recommendationsResult.exceptionOrNull()?.let { logError(it) }

            val post = infoRes.data
            val title = post.title ?: "Unknown Title $animeId"
            
            val poster = fixUrl(post.poster) // Dùng fixUrl (với baseUrl)
            val plot = post.description
            val tags = post.genres?.mapNotNull { it.name }?.filter { it.isNotBlank() }
            val year = post.year?.toIntOrNull()
            
            // Giữ logic phân biệt Movie/Series
            val internalTvType = post.toTvType(default = TvType.Anime)

            // === LOGIC GỘP TẬP (API) ===
            val allEpisodesRaw = episodesResponse?.teams?.flatMap { team ->
                team.groups.flatMap { it.episodes }
            } ?: emptyList()

            val episodes = allEpisodesRaw
                .filter { it.number != null }
                .groupBy { it.number!! }
                .map { (epNum, epList) ->
                    val epName = "Tập $epNum"
                    val idListJson = epList.map { it.id }.distinct().toJson()
                    newEpisode(idListJson) {
                        this.name = epName
                        this.episode = epNum
                    }
                }
                .sortedBy { it.episode }
            // === KẾT THÚC LOGIC GỘP TẬP ===

            val recommendationsList = recommendationsResult.getOrNull()?.data?.mapNotNull { it.toSearchResult() }
            
            // Dùng internalTvType để quyết định logic, nhưng trả về TvType.Anime
            if (episodes.isEmpty() && (internalTvType == TvType.AnimeMovie || internalTvType == TvType.OVA)) {
                 return newMovieLoadResponse(title, url, TvType.Anime, animeId) { // FIX: Trả về TvType.Anime
                    this.posterUrl = poster; this.plot = plot; this.tags = tags; this.year = year
                    this.recommendations = recommendationsList
                }
            }
            
            return newTvSeriesLoadResponse(title, url, TvType.Anime, episodes) { // FIX: Trả về TvType.Anime
                this.posterUrl = poster
                this.plot = plot
                this.tags = tags
                this.year = year
                this.recommendations = recommendationsList
            }
        } catch (e: Exception) {
            logError(e)
            throw IOException("Error loading $url", e)
        }
    }
    
    // === Xóa Crypto ===
    
    // === Giữ nguyên Subtitle Map và Helper ===
    private val subtitleLanguageMap: Map<String, List<String>> = mapOf(
        "Vietnamese" to listOf("tiếng việt", "vietnamese", "vietsub", "viet", "vi"),
        "English" to listOf("tiếng anh", "english", "engsub", "eng", "en"),
        "Chinese" to listOf("tiếng trung", "chinese", "mandarin", "cn", "zh"),
        "Japanese" to listOf("tiếng nhật", "japanese", "jpn", "ja"),
        "Korean" to listOf("tiếng hàn", "korean", "kor", "ko"),
        "French" to listOf("tiếng pháp", "français", "french", "fra", "fr"),
        "German" to listOf("tiếng đức", "deutsch", "german", "deu", "de"),
        "Spanish" to listOf("español", "spanish", "spa", "es"),
        "Russian" to listOf("русский", "russian", "rus", "ru"),
        "Portuguese" to listOf("português", "portuguese", "por", "pt"),
        "Italian" to listOf("italiano", "italian", "ita", "it"),
        "Arabic" to listOf("العربية", "arabic", "ara", "ar"),
        "Thai" to listOf("ไทย", "thai", "tha", "th"),
        "Indonesian" to listOf("bahasa indonesia", "indonesian", "ind", "id"),
        "Malay" to listOf("bahasa melayu", "malay", "may", "ms"),
        "Esperanto" to listOf("esperanto", "epo", "eo"),
        "Estonian" to listOf("eesti", "estonian", "est", "et")
    )
    private fun mapSubtitleLabel(label: String): String {
        val lowerLabel = label.trim().lowercase(Locale.ROOT)
        if (lowerLabel.isBlank()) return "Subtitle" 

        for ((language, keywords) in subtitleLanguageMap) {
            if (keywords.any { keyword -> lowerLabel.contains(keyword) }) {
                return language
            }
        }
        return label.trim().replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale.ROOT) else it.toString() }
    }
    
    // === FIX: loadLinks (API) + Gộp link + getBaseUrl + Thêm Headers ===
    override suspend fun loadLinks(
        data: String, isCasting: Boolean, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit
    ): Boolean {
        
        val currentBaseUrl = getBaseUrl() // Gọi getBaseUrl để lấy referer/origin

        val episodeIds: List<Int> = try {
            if (data.startsWith("[")) {
                parseJson(data)
            } else {
                listOf(data.toInt())
            }
        } catch (e: Exception) {
            logError(e)
            return false
        }

        if (episodeIds.isEmpty()) return false

        val loaded = AtomicBoolean(false)
        val ref = "$currentBaseUrl/" // Referer dùng base URL

        coroutineScope {
            episodeIds.map { id ->
                async {
                    try {
                        val watchUrl = "$apiBaseUrl/anime/watch/episode/$id?lang=vi" // Dùng apiBaseUrl cố định
                        val watchRes = app.get(watchUrl, interceptor = interceptor, timeout = 10_000).parsedSafe<ApiWatchResponse>()
                        
                        watchRes?.streams?.forEach { stream ->
                            val streamUrl = stream.url
                            val serverName = stream.server_name
                            if (streamUrl.isNullOrBlank() || serverName.isNullOrBlank()) return@forEach
                            if (serverName.contains("HY", ignoreCase = true)) return@forEach

                            if (serverName.contains("FE", ignoreCase = true) || stream.player_type.equals("jwplayer", ignoreCase = true)) {
                                
                                // === FIX: Thêm Headers ===
                                val headers = mutableMapOf(
                                    "Referer" to ref, // Dùng ref từ getBaseUrl
                                    "User-Agent" to "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/137.0.0.0 Mobile Safari/537.36",
                                    "sec-ch-ua" to "\"Chromium\";v=\"137\", \"Not/A)Brand\";v=\"24\"",
                                    "sec-ch-ua-mobile" to "?1",
                                    "sec-ch-ua-platform" to "\"Android\""
                                )

                                if (streamUrl.contains("vlogphim.net")) {
                                    headers["Origin"] = ref // Dùng ref từ getBaseUrl
                                    try {
                                        headers["authority"] = URL(streamUrl).host
                                    } catch (_: Exception) {
                                        headers["authority"] = "pl.vlogphim.net"
                                    }
                                }
                                // === Kết thúc FIX Headers ===

                                val link = newExtractorLink(
                                    source = this@Anime47Provider.name,
                                    name = serverName,
                                    url = streamUrl,
                                    type = ExtractorLinkType.M3U8
                                ) {
                                    this.referer = ref // Giữ lại referer riêng
                                    this.quality = Qualities.Unknown.value
                                    this.headers = headers // Thêm map headers
                                }
                                callback(link)
                                loaded.set(true)

                                // Xử lý Subtitles
                                stream.subtitles?.forEach { sub ->
                                    if (!sub.file.isNullOrBlank() && !sub.label.isNullOrBlank()) {
                                        try {
                                            subtitleCallback(
                                                SubtitleFile(
                                                    mapSubtitleLabel(sub.label),
                                                    sub.file
                                                )
                                            )
                                        } catch (e: Exception) {
                                            logError(e) 
                                        }
                                    }
                                }
                            }
                        }
                    } catch (e: Exception) {
                        logError(IOException("Failed to get streams for episode ID: $id", e))
                    }
                }
            }.awaitAll()
        }

        return loaded.get()
    }

    // === FIX: Thêm Headers vào Request và Xử lý Tua video (Range Request) ===
    override fun getVideoInterceptor(extractorLink: ExtractorLink): Interceptor? {
        val nonprofitRegex = Regex(""".*nonprofit.*""")
        
        // Nắm bắt baseUrl và User-Agent tại thời điểm tạo interceptor
        // (baseUrl đã được cập nhật bởi getBaseUrl() trong loadLinks)
        val referer = "$baseUrl/"
        val userAgent = "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/137.0.0.0 Mobile Safari/537.36"

        return object : Interceptor {
            override fun intercept(chain: Interceptor.Chain): Response {
                val request = chain.request()
                // Kiểm tra xem đây có phải là request khi tua (Range) không
                val isRangeRequest = request.header("Range") != null
                
                // 1. Sửa Request: Thêm headers cần thiết
                val newRequest = request.newBuilder()
                    .header("Referer", referer)
                    .header("User-Agent", userAgent)
                    .build()

                var response: Response? = null
                try {
                    // Tiến hành request với header đã sửa
                    response = chain.proceed(newRequest)
                    val url = request.url.toString()

                    // 2. Sửa Response: Chỉ sửa lỗi byte nếu là file .ts VÀ không phải đang tua
                    if (nonprofitRegex.containsMatchIn(url) && response.isSuccessful && response.code == 200 && !isRangeRequest) {
                        response.body?.let { body ->
                            try {
                                // Sử dụng hàm skipByteErrorRaw mới (an toàn hơn)
                                val responseBytes = body.bytes() 
                                val fixedBytes = skipByteErrorRaw(responseBytes)
                                val newBody = fixedBytes.toResponseBody(body.contentType())
                                
                                response.close() // Đóng response cũ
                                return response.newBuilder()
                                    .removeHeader("Content-Length") // Xóa header cũ
                                    .addHeader("Content-Length", fixedBytes.size.toString()) // Thêm header mới
                                    .body(newBody)
                                    .build()
                            } catch (e: Exception) {
                                logError(e)
                                response.close() // Đóng nếu lỗi
                                throw IOException("Failed to process interceptor for $url", e)
                            }
                        }
                    }
                    
                    // Trả về response gốc (cho file M3U8, file .ts khi tua, hoặc file lỗi)
                    return response ?: throw IOException("Proceed returned null response for $url")

                } catch (networkE: Exception) {
                    response?.close()
                    logError(networkE)
                    throw networkE
                } catch (e: Exception) {
                    response?.close()
                    logError(e)
                    throw e
                }
            }
        }
    }
} // Kết thúc class Anime47Provider

// === FIX: Helper mới, an toàn hơn cho Interceptor, thay thế skipByteError cũ ===
private fun skipByteErrorRaw(byteArray: ByteArray): ByteArray {
    return try {
        if (byteArray.isEmpty()) return byteArray
        val length = byteArray.size - 188
        var start = 0
        if (length > 0) {
            for (i in 0 until length) {
                val nextIndex = i + 188
                if (nextIndex < byteArray.size && byteArray[i].toInt() == 71 && byteArray[nextIndex].toInt() == 71) { // 'G'
                    start = i; break
                }
            }
        }
        if (start > 0) byteArray.copyOfRange(start, byteArray.size) else byteArray
    } catch (e: Exception) {
        logError(e)
        byteArray
    }
}
