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
// import android.util.Base64 // Không cần
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
// === FIX: Thêm lại Imports Toast ===
import com.lagradost.cloudstream3.CommonActivity.showToast
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import android.widget.Toast
// === Imports mới cho API ===
// import com.lagradost.cloudstream3.LoadResponse.Companion.addTrailer // Đã xóa
import com.lagradost.cloudstream3.mvvm.logError
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import java.io.IOException
import com.lagradost.cloudstream3.newEpisode
import java.net.URL // Cần cho loadLinks headers

// === Provider Class ===
class Anime47Provider : MainAPI() {
    // === FIX: Đặt mainUrl, xóa baseUrl ===
    override var mainUrl = "https://anime47.best"
    // private var baseUrl = "https://anime47.live" // Đã xóa
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
    
    // === FIX: Xóa getBaseUrl ===
    // private var domainResolutionAttempted = false
    // private suspend fun getBaseUrl(): String { ... }
    // === Kết thúc FIX ===

    // === Data Classes cho API (Giữ nguyên) ===
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
        val videos: List<VideoItem>? // Dùng cho trailer
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
        val image: String?,
        val type: String?,
        val episodes: String?
    )
    private data class ApiSearchResponse(
        val results: List<SearchItem>?,
        val has_more: Boolean?
    )
    // === Kết thúc Data Classes ===

    // === FIX: fixUrl dùng mainUrl ===
    private fun fixUrl(url: String?): String? {
        if (url.isNullOrBlank()) return null
        if (url.contains("via.placeholder.com", ignoreCase = true)) return null
        if (url.startsWith("http")) return url
        if (url.startsWith("//")) return "https:$url"
        val relativePath = if (url.startsWith("/")) url else "/$url"
        return if (mainUrl.startsWith("http")) { // Dùng mainUrl
            mainUrl + relativePath
        } else {
            "https:" + mainUrl + relativePath // Dùng mainUrl
        }
    }

    // === FIX: getMainPage (API) + Thêm Toast ===
    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        // === FIX: Thêm lại Toast ===
        withContext(Dispatchers.Main) {
            try {
                CommonActivity.activity?.let {
                    showToast(it, "Free Repo From H4RS", Toast.LENGTH_LONG)
                }
            } catch (e: Exception) {
                logError(e)
            }
        }
        // === Kết thúc FIX ===
        
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

    // === FIX: search (API) ===
    override suspend fun search(query: String): List<SearchResponse> {
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

    // === FIX: load (API) + Gộp link + TvType.Anime + Bỏ Trailer ===
    override suspend fun load(url: String): LoadResponse? {
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
            
            val poster = fixUrl(post.poster) // Dùng fixUrl (với mainUrl)
            val plot = post.description
            val tags = post.genres?.mapNotNull { it.name }?.filter { it.isNotBlank() }
            val year = post.year?.toIntOrNull()
            
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

            // val trailers = infoRes.data.videos?.mapNotNull { fixUrl(it.url) } // Đã xóa
            val recommendationsList = recommendationsResult.getOrNull()?.data?.mapNotNull { it.toSearchResult() }
            
            if (episodes.isEmpty() && (internalTvType == TvType.AnimeMovie || internalTvType == TvType.OVA)) {
                 return newMovieLoadResponse(title, url, TvType.Anime, animeId) { // FIX: Trả về TvType.Anime
                    this.posterUrl = poster; this.plot = plot; this.tags = tags; this.year = year
                    // addTrailer(trailers) // Đã xóa
                    this.recommendations = recommendationsList
                }
            }
            
            return newTvSeriesLoadResponse(title, url, TvType.Anime, episodes) { // FIX: Trả về TvType.Anime
                this.posterUrl = poster
                this.plot = plot
                this.tags = tags
                this.year = year
                // addTrailer(trailers) // Đã xóa
                this.recommendations = recommendationsList
            }
        } catch (e: Exception) {
            logError(e)
            throw IOException("Error loading $url", e)
        }
    }
    
    // === Xóa Crypto ===
    
    // === FIX: Tối ưu Subtitle Map (Chỉ Tiếng Việt) ===
    private val subtitleLanguageMap: Map<String, List<String>> = mapOf(
        "Vietnamese" to listOf("tiếng việt", "vietnamese", "vietsub", "viet", "vi")
    )
    
    private fun mapSubtitleLabel(label: String): String {
        val lowerLabel = label.trim().lowercase(Locale.ROOT)
        if (lowerLabel.isBlank()) return "Subtitle" 

        for ((language, keywords) in subtitleLanguageMap) {
            if (keywords.any { keyword -> lowerLabel.contains(keyword) }) {
                return language
            }
        }
        // Fallback: Nếu không phải Tiếng Việt, trả về label gốc
        return label.trim().replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale.ROOT) else it.toString() }
    }
    
    // === FIX: loadLinks (API) + Gộp link + Headers + Dùng mainUrl ===
    override suspend fun loadLinks(
        data: String, isCasting: Boolean, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit
    ): Boolean {
        
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
        val ref = "$mainUrl/" // Referer dùng mainUrl (hardcoded)

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
                                
                                val headers = mutableMapOf(
                                    "Referer" to ref, // Dùng ref từ mainUrl
                                    "User-Agent" to "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/137.0.0.0 Mobile Safari/537.36",
                                    "sec-ch-ua" to "\"Chromium\";v=\"137\", \"Not/A)Brand\";v=\"24\"",
                                    "sec-ch-ua-mobile" to "?1",
                                    "sec-ch-ua-platform" to "\"Android\""
                                )

                                if (streamUrl.contains("vlogphim.net")) {
                                    headers["Origin"] = ref // Dùng ref từ mainUrl
                                    try {
                                        headers["authority"] = URL(streamUrl).host
                                    } catch (_: Exception) {
                                        headers["authority"] = "pl.vlogphim.net"
                                    }
                                }

                                val link = newExtractorLink(
                                    source = this@Anime47Provider.name,
                                    name = serverName,
                                    url = streamUrl,
                                    type = ExtractorLinkType.M3U8
                                ) {
                                    this.referer = ref 
                                    this.quality = Qualities.Unknown.value
                                    this.headers = headers
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

    // === FIX: Giữ nguyên Interceptor (phiên bản sửa lỗi tua) ===
    override fun getVideoInterceptor(extractorLink: ExtractorLink): Interceptor? {
        val nonprofitRegex = Regex(""".*nonprofit.*""")
        
        val referer = "$mainUrl/" // Dùng mainUrl hardcoded
        val userAgent = "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/137.0.0.0 Mobile Safari/537.36"

        return object : Interceptor {
            override fun intercept(chain: Interceptor.Chain): Response {
                val request = chain.request()
                val isRangeRequest = request.header("Range") != null
                
                // 1. Sửa Request: Thêm headers
                val newRequest = request.newBuilder()
                    .header("Referer", referer)
                    .header("User-Agent", userAgent)
                    .build()

                var response: Response? = null
                try {
                    response = chain.proceed(newRequest)
                    val url = request.url.toString()

                    // 2. Sửa Response: Chỉ sửa lỗi byte nếu là file .ts VÀ không phải đang tua
                    if (nonprofitRegex.containsMatchIn(url) && response.isSuccessful && response.code == 200 && !isRangeRequest) {
                        response.body?.let { body ->
                            try {
                                val responseBytes = body.bytes() 
                                val fixedBytes = skipByteErrorRaw(responseBytes) // Dùng helper mới
                                val newBody = fixedBytes.toResponseBody(body.contentType())
                                
                                response.close() // Đóng response cũ
                                return response.newBuilder()
                                    .removeHeader("Content-Length")
                                    .addHeader("Content-Length", fixedBytes.size.toString())
                                    .body(newBody)
                                    .build()
                            } catch (e: Exception) {
                                logError(e)
                                response.close() // Đóng nếu lỗi
                                throw IOException("Failed to process interceptor for $url", e)
                            }
                        }
                    }
                    
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

// === FIX: Giữ nguyên helper (phiên bản sửa lỗi tua) ===
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
