package recloudstream

// === Imports ===
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.network.CloudflareKiller
import java.net.URLEncoder
import okhttp3.Interceptor
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import java.util.EnumSet
import java.util.Locale
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.awaitAll
import android.util.Log // Added for debug logging
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.LoadResponse.Companion.addTrailer
import com.lagradost.cloudstream3.mvvm.logError
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean
// === Imports for Toast ===
import com.lagradost.cloudstream3.CommonActivity.showToast
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import android.widget.Toast
// === FIX: Imports for newEpisode and toJson ===
import com.lagradost.cloudstream3.newEpisode
import com.lagradost.cloudstream3.utils.AppUtils.toJson
// === End FIX ===

// === Provider Class ===
class Anime47Provider : MainAPI() {
    override var mainUrl = "https://anime47.love"
    private val apiBaseUrl = "https://anime47.love/api"
    private val logTag = "Anime47Provider" // For debug logs

    override var name = "Anime47"
    override val hasMainPage = true
    override var lang = "vi"
    override val hasDownloadSupport = true
    
    override val supportedTypes = setOf(TvType.Anime)

    private val interceptor = CloudflareKiller()

    // === Data Classes for API Responses ===
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
        val image: String?,
        val type: String?,
        val episodes: String?
    )
    private data class ApiSearchResponse(
        val results: List<SearchItem>?,
        val has_more: Boolean?
    )


    // === MainPage Requests ===
    override val mainPage = mainPageOf(
        "/anime/filter?lang=vi&sort=latest" to "Anime Mới Cập Nhật",
        "/anime/filter?lang=vi&sort=rating" to "Top Đánh Giá",
        "/anime/filter?lang=vi&type=tv" to "Anime TV",
        "/anime/filter?lang=vi&type=movie" to "Anime Movie"
    )

    // === API Headers ===
    private val apiHeaders
        get() = mapOf(
            "Accept" to "application/json, text/plain, */*",
            "Origin" to mainUrl,
            "Referer" to "$mainUrl/"
        )

    // === Helper Functions ===
    private fun fixUrl(url: String?): String? {
        if (url.isNullOrBlank()) return null
        if (url.contains("via.placeholder.com", ignoreCase = true)) return null
        if (url.startsWith("http")) return url
        if (url.startsWith("//")) return "https:$url"
        val base = mainUrl
        val relativePath = if (url.startsWith("/")) url else "/$url"
        return if (base.startsWith("http")) {
            base + relativePath
        } else {
            "https:" + base + relativePath
        }
    }

    // Giữ lại hàm này để load() phân biệt Movie/TV
    private fun DetailPost.toTvType(default: TvType = TvType.Anime): TvType {
        return when {
            this.type?.equals("TV", ignoreCase = true) == true -> TvType.Anime
            this.type?.contains("movie", ignoreCase = true) == true -> TvType.AnimeMovie
            this.type?.contains("ova", ignoreCase = true) == true -> TvType.OVA
            (this.title?.contains("Hoạt Hình Trung Quốc", ignoreCase = true) == true ) -> TvType.Cartoon
            else -> default
        }
    }


    // === Core Overrides ===
    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        withContext(Dispatchers.Main) {
            try {
                CommonActivity.activity?.let {
                    showToast(it, "Provider by H4RS", Toast.LENGTH_LONG)
                }
            } catch (e: Exception) {
                logError(e)
            }
        }

        val url = "$apiBaseUrl${request.data}&page=$page"
        val res = try {
            app.get(
                url,
                headers = apiHeaders,
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

    /**
     * Hàm helper chung để tạo SearchResponse
     */
    private fun createSearchResponse(
        title: String,
        url: String, // Đã bao gồm mainUrl
        poster: String?,
        tvType: TvType, // TvType này sẽ luôn là TvType.Anime theo yêu cầu
        year: Int? = null,
        episodesStr: String? = null
    ): SearchResponse {
        val epCount = episodesStr?.filter { it.isDigit() }?.toIntOrNull()
        val episodesMap: Map<DubStatus, Int> = if (epCount != null) mapOf(DubStatus.Subbed to epCount) else emptyMap()

        return newAnimeSearchResponse(title, url, tvType) {
            this.posterUrl = fixUrl(poster)
            this.year = year
            if (episodesMap.isNotEmpty()) {
                this.dubStatus = EnumSet.of(DubStatus.Subbed)
                this.episodes = episodesMap.toMutableMap()
            }
        }
    }

    private fun Post.toSearchResult(): SearchResponse? {
        if (this.link.isBlank()) return null
        return createSearchResponse(
            title = this.title,
            url = mainUrl + this.link,
            poster = this.poster,
            tvType = TvType.Anime,
            year = this.year?.toIntOrNull(),
            episodesStr = this.episodes
        )
    }

    private fun RecommendationItem.toSearchResult(): SearchResponse? {
        if (this.link.isNullOrBlank() || this.title.isNullOrBlank()) return null
        return createSearchResponse(
            title = this.title,
            url = mainUrl + this.link,
            poster = this.poster,
            tvType = TvType.Anime,
            year = this.year?.toIntOrNull(),
            episodesStr = null
        )
    }

    private fun SearchItem.toSearchResult(): SearchResponse? {
        if (this.link.isBlank()) return null
        return createSearchResponse(
            title = this.title,
            url = mainUrl + this.link,
            poster = this.image,
            tvType = TvType.Anime,
            year = null,
            episodesStr = this.episodes
        )
    }

    override suspend fun search(query: String): List<SearchResponse> {
        if (query.isBlank()) return emptyList()

        val encodedQuery = URLEncoder.encode(query, "UTF-8")
        val url = "$apiBaseUrl/search/full/?lang=vi&keyword=$encodedQuery&page=1"

        val res = try {
            app.get(
                url,
                headers = apiHeaders,
                interceptor = interceptor,
                timeout = 10_000
            ).parsedSafe<ApiSearchResponse>()
        } catch (e: Exception) {
            logError(e)
            return emptyList()
        }
        
        return res?.results?.mapNotNull { it.toSearchResult() } ?: emptyList()
    }

    override suspend fun quickSearch(query: String): List<SearchResponse> {
        return search(query)
    }

    // === HÀM LOAD ĐÃ SỬA LỖI BIÊN DỊCH ===
    override suspend fun load(url: String): LoadResponse? {
        try {
            val animeId = url.substringAfterLast('-').trim()
            if (animeId.isBlank() || animeId.toIntOrNull() == null) {
                throw IllegalArgumentException("Invalid anime ID extracted from URL: $url")
            }

            val (infoResult, episodesResult, recommendationsResult) = coroutineScope {
                val infoTask = async {
                    val infoUrl = "$apiBaseUrl/anime/info/$animeId?lang=vi"
                    runCatching { app.get(infoUrl, headers = apiHeaders, interceptor = interceptor, timeout = 15_000).parsedSafe<ApiDetailResponse>() }
                }
                val episodesTask = async {
                    val episodesUrl = "$apiBaseUrl/anime/$animeId/episodes?lang=vi"
                    runCatching { app.get(episodesUrl, headers = apiHeaders, interceptor = interceptor, timeout = 15_000).parsedSafe<ApiEpisodeResponse>() }
                }
                val recommendationsTask = async {
                    val recUrl = "$apiBaseUrl/anime/info/$animeId/recommendations?lang=vi"
                    runCatching { app.get(recUrl, headers = apiHeaders, interceptor = interceptor, timeout = 15_000).parsedSafe<ApiRecommendationResponse>() }
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
            
            val poster = fixUrl(post.poster)
            val cover = fixUrl(post.cover) 

            val plot = post.description
            val year = post.year?.toIntOrNull()
            val tags = post.genres?.mapNotNull { it.name }?.filter { it.isNotBlank() }
            
            val tvType = post.toTvType(default = TvType.Anime)

            // === LOGIC GỘP TẬP BẮT ĐẦU ===
            val allEpisodesRaw = episodesResponse?.teams?.flatMap { team ->
                team.groups.flatMap { it.episodes }
            } ?: emptyList()

            val episodes = allEpisodesRaw
                .filter { it.number != null } // Chỉ lấy những tập có 'number'
                .groupBy { it.number!! } // Gộp nhóm theo 'number'
                .map { (epNum, epList) ->
                    
                    val epName = "Tập $epNum"
                    
                    // === FIX 1: Dùng extension function .toJson() ===
                    val idListJson = epList.map { it.id }.distinct().toJson()

                    // === FIX 2: Dùng cú pháp builder của newEpisode ===
                    newEpisode(idListJson) { // data (idListJson) được truyền vào constructor
                        name = epName      // name và episode được gán bên trong builder
                        episode = epNum 
                    }
                }
                .sortedBy { it.episode } // Sắp xếp lại theo số tập
            // === LOGIC GỘP TẬP KẾT THÚC ===

            val trailers = infoRes.data.videos?.mapNotNull { fixUrl(it.url) }
            val recommendationsList = recommendationsResult.getOrNull()?.data?.mapNotNull { it.toSearchResult() }

            return when (tvType) {
                TvType.AnimeMovie, TvType.OVA -> {
                    val movieData = episodes.firstOrNull()?.data ?: animeId
                    newMovieLoadResponse(title, url, TvType.Anime, movieData) {
                        this.posterUrl = poster 
                        this.year = year; this.plot = plot; this.tags = tags
                        addTrailer(trailers); this.recommendations = recommendationsList
                    }
                }
                else -> { 
                    newTvSeriesLoadResponse(title, url, TvType.Anime, episodes) {
                        this.posterUrl = poster 
                        this.backgroundPosterUrl = cover; this.year = year
                        this.plot = plot; this.tags = tags; addTrailer(trailers); this.recommendations = recommendationsList
                    }
                }
            }
        } catch (e: Exception) {
            logError(e)
            throw IOException("Error loading $url", e)
        }
    }

    // === HÀM LOADLINKS (Không đổi) ===
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {

        // Thử phân tích data thành danh sách ID (logic gộp tập)
        val episodeIds: List<Int> = try {
            if (data.startsWith("[")) {
                // Nếu data là "[2332, 38315]", parse nó
                AppUtils.parseJson(data)
            } else {
                // Ngược lại, nó là logic cũ (movie/ova)
                emptyList()
            }
        } catch (e: Exception) {
            logError(e)
            emptyList()
        }

        var allStreams: List<Stream>? = null

        if (episodeIds.isNotEmpty()) {
            // === LOGIC MỚI: XỬ LÝ NHIỀU ID ===
            allStreams = coroutineScope {
                episodeIds.map { id ->
                    async {
                        try {
                            val watchUrl = "$apiBaseUrl/anime/watch/episode/$id?lang=vi"
                            app.get(watchUrl, headers = apiHeaders, interceptor = interceptor, timeout = 10_000)
                                .parsedSafe<ApiWatchResponse>()?.streams
                        } catch (e: Exception) {
                            logError(IOException("Failed to get streams for episode ID: $id", e))
                            null
                        }
                    }
                }.awaitAll()
                 .filterNotNull()
                 .flatten()
            }
            // === KẾT THÚC LOGIC MỚI ===

        } else {
            // === LOGIC CŨ: XỬ LÝ 1 ID (CHO MOVIE/OVA) ===
            val id = data.substringAfterLast('/')
            if (id.isBlank()) {
                logError(IOException("loadLinks received blank non-list data: '$data'"))
                return false
            }

            try {
                val watchUrl = "$apiBaseUrl/anime/watch/episode/$id?lang=vi"
                val watchRes = app.get(watchUrl, headers = apiHeaders, interceptor = interceptor, timeout = 10_000).parsedSafe<ApiWatchResponse>()
                allStreams = watchRes?.streams
            } catch (e: Exception) {
                logError(IOException("Failed to get streams directly with ID: $id", e))
            }

            // Fallback:
            if (allStreams.isNullOrEmpty()) {
                Log.d(logTag, "No streams for ID $id. Trying fallback as anime ID.")
                try {
                    val episodesUrl = "$apiBaseUrl/anime/$id/episodes?lang=vi"
                    val episodesRes = app.get(episodesUrl, headers = apiHeaders, interceptor = interceptor, timeout = 10_000).parsedSafe<ApiEpisodeResponse>()
                    val fallbackEpisodeId = episodesRes?.teams?.firstOrNull()?.groups?.firstOrNull()?.episodes?.firstOrNull()?.id

                    if (fallbackEpisodeId != null) {
                        Log.d(logTag, "Fallback success: Found episode $fallbackEpisodeId for anime $id")
                        val fallbackWatchUrl = "$apiBaseUrl/anime/watch/episode/$fallbackEpisodeId?lang=vi"
                        allStreams = app.get(fallbackWatchUrl, headers = apiHeaders, interceptor = interceptor, timeout = 10_000).parsedSafe<ApiWatchResponse>()?.streams
                    } else {
                        logError(IOException("Fallback failed: No episodes found for animeId $id"))
                    }
                } catch (e: Exception) {
                    logError(IOException("Fallback attempt failed for animeId: $id", e))
                }
            }
            // === KẾT THÚC LOGIC CŨ ===
        }

        if (allStreams.isNullOrEmpty()) {
            logError(IOException("No streams found for data: '$data'"))
            return false
        }

        val loaded = AtomicBoolean(false)
        coroutineScope {
            allStreams.map { stream ->
                async {
                    val currentStream = stream
                    val streamUrl = currentStream.url
                    val serverName = currentStream.server_name
                    val playerType = currentStream.player_type

                    if (streamUrl.isNullOrBlank() || serverName.isNullOrBlank()) return@async
                    if (serverName.contains("HY", ignoreCase = true)) return@async

                    val ref = "$mainUrl/"

                    try {
                        if (serverName.contains("FE", ignoreCase = true) || playerType.equals("jwplayer", ignoreCase = true)) {
                            val link = newExtractorLink(
                                source = this@Anime47Provider.name,
                                name = serverName, 
                                url = streamUrl,
                                type = ExtractorLinkType.M3U8
                            ) {
                                this.referer = ref
                                this.quality = Qualities.Unknown.value
                            }
                            callback(link)
                            loaded.set(true)

                            currentStream.subtitles?.forEach { sub ->
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
                        } else {
                            logError(IOException("Unhandled stream type or server for (Data: $data): $serverName - $streamUrl (Player: $playerType)"))
                        }
                    } catch (e: Exception) {
                        logError(e)
                    }
                }
            }.awaitAll()
        }

        if (!loaded.get()) {
            logError(IOException("No extractor links were loaded for data: $data"))
        }

        return loaded.get()
    }


    // Subtitle mapping
    private val subtitleLanguageMap: Map<String, List<String>> = mapOf(
        "Vietnamese" to listOf("tiếng việt", "vietnamese", "vietsub", "viet", "vi"),
        "English" to listOf("tiếng anh", "english", "engsub", "eng", "en")
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

    // Video Interceptor
    override fun getVideoInterceptor(extractorLink: ExtractorLink): Interceptor? {
        // === FIX: Bắt link .ts chung chung, không chỉ nonprofit.asia ===
        val tsRegex = Regex(""".*\.ts($|\?.*)""") // Matches any URL ending in .ts, with or without query params

        return object : Interceptor {
            override fun intercept(chain: Interceptor.Chain): Response {
                val request = chain.request()
                // === FIX: Kiểm tra Range header để bỏ qua khi tua video ===
                val isRangeRequest = request.header("Range") != null
                
                var response: Response? = null
                try {
                    response = chain.proceed(request)
                    val url = request.url.toString()

                    // Chỉ can thiệp khi:
                    // 1. URL là file .ts
                    // 2. Request thành công (200 OK - tải toàn bộ file)
                    // 3. KHÔNG phải là Range request (không phải đang tua video / response.code 206)
                    if (tsRegex.containsMatchIn(url) && response.isSuccessful && response.code == 200 && !isRangeRequest) {
                        response.body?.let { body ->
                            try {
                                val responseBytes = body.bytes()
                                val fixedBytes = skipByteErrorRaw(responseBytes) // Chạy hàm sửa lỗi
                                val newBody = fixedBytes.toResponseBody(body.contentType())
                                response.close()
                                return response.newBuilder()
                                    .removeHeader("Content-Length")
                                    .addHeader("Content-Length", fixedBytes.size.toString())
                                    .body(newBody)
                                    .build()
                            } catch (processE: Exception) {
                                logError(processE)
                                response.close()
                                throw IOException("Failed to process video interceptor for $url", processE)
                            }
                        }
                    }
                    // Trả về response gốc nếu không phải .ts, hoặc là range request, hoặc request lỗi
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
} // End class Anime47Provider


// Helper to fix potential TS stream byte errors
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
