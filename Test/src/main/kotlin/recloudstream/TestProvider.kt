package recloudstream

// === Imports ===
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.network.CloudflareKiller
import java.net.URLEncoder
import okhttp3.Interceptor
import okhttp3.Response
import okhttp3.ResponseBody
import okhttp3.ResponseBody.Companion.toResponseBody
import java.util.EnumSet
import java.util.Locale
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import com.lagradost.cloudstream3.CommonActivity.showToast
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import android.widget.Toast
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.LoadResponse.Companion.addTrailer
import com.lagradost.cloudstream3.mvvm.logError
import java.io.IOException

// === Provider Class ===
class Anime47Provider : MainAPI() {
    override var mainUrl = "https://anime47.love"
    private val apiBaseUrl = "https://anime47.love/api"

    override var name = "Anime47"
    override val hasMainPage = true
    override var lang = "vi"
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(TvType.Anime, TvType.AnimeMovie, TvType.OVA, TvType.Cartoon)

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
        val id: Int?, // Thêm ID stream để phân biệt nếu cần
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
    private val apiHeaders = mapOf(
        "Accept" to "application/json, text/plain, */*",
        "Origin" to mainUrl,
        "Referer" to "$mainUrl/"
    )

    // === Helper Functions ===
    private fun fixUrl(url: String?): String? {
        if (url.isNullOrBlank()) return null
        // Skip placeholders that don't resolve
        if (url.contains("via.placeholder.com", ignoreCase = true)) return null
        if (url.startsWith("http")) return url
        if (url.startsWith("//")) return "https:$url"
        val base = mainUrl // Use mainUrl which is set correctly
        // Ensure relative paths start with '/'
        val relativePath = if (url.startsWith("/")) url else "/$url"
        // Combine base and relative path
        return base + relativePath // Simplified combination
    }


    private fun String?.toTvType(): TvType {
        return when {
            this?.contains("movie", ignoreCase = true) == true -> TvType.AnimeMovie
            this?.contains("ova", ignoreCase = true) == true -> TvType.OVA
            this?.contains("special", ignoreCase = true) == true -> TvType.OVA
            else -> TvType.Anime // Default to Anime
        }
    }

    // === Core Overrides ===
    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        withContext(Dispatchers.Main) {
            try {
                CommonActivity.activity?.let {
                    showToast(it, "Provider by H4RS", Toast.LENGTH_LONG)
                }
            } catch (e: Exception) { /* Ignore UI errors */ }
        }

        val url = "$apiBaseUrl${request.data}&page=$page"
        val res = try {
            app.get(url, headers = apiHeaders, interceptor = interceptor, timeout = 15_000).parsed<ApiFilterResponse>()
        } catch (e: Exception) {
            logError(e) // Log the actual exception
            throw ErrorLoadingException("Không thể tải trang chủ. Lỗi Cloudflare hoặc API.")
        }

        val home = res.data.posts.mapNotNull { post ->
            val epCount = post.episodes?.filter { it.isDigit() }?.toIntOrNull()
            toSearchResponseInternal(
                title = post.title,
                url = post.link, // Pass relative URL
                posterUrl = post.poster,
                tvType = post.type.toTvType(),
                year = post.year?.toIntOrNull(),
                epCount = epCount
            )
        }
        val hasNext = home.size >= 24
        return newHomePageResponse(request.name, home, hasNext = hasNext)
    }

    // Consolidated helper for creating SearchResponse
    private fun toSearchResponseInternal(
        title: String,
        url: String, // Relative URL from API (e.g., /thong-tin/...)
        posterUrl: String?,
        tvType: TvType,
        year: Int?,
        epCount: Int?
    ): SearchResponse? {
        val fullUrl = mainUrl + url // Prepend mainUrl here
        if (fullUrl.isBlank()) return null
        val poster = fixUrl(posterUrl) ?: return null // Filter placeholders

        return newAnimeSearchResponse(title, fullUrl, tvType) {
            this.posterUrl = poster
            this.year = year
            this.dubStatus = EnumSet.of(DubStatus.Subbed) // Assume Subbed
            if (epCount != null) {
                this.episodes = mutableMapOf(DubStatus.Subbed to epCount)
            }
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        if (query.isBlank()) return emptyList()

        val encodedQuery = URLEncoder.encode(query, "UTF-8")
        val url = "$apiBaseUrl/search/full/?lang=vi&keyword=$encodedQuery&page=1"

        val res = try {
            app.get(url, headers = apiHeaders, interceptor = interceptor, timeout = 10_000).parsedSafe<ApiSearchResponse>()
        } catch (e: Exception) {
            logError(e) // Log the actual exception
            return emptyList()
        }

         return res?.results?.mapNotNull { item ->
             val epCount = item.episodes?.filter { it.isDigit() }?.toIntOrNull()
             toSearchResponseInternal(
                 title = item.title,
                 url = item.link, // Pass relative URL
                 posterUrl = item.image,
                 tvType = item.type.toTvType(),
                 year = null, // No year in search results
                 epCount = epCount
             )
         } ?: emptyList()
    }

    // quickSearch calls search directly
    override suspend fun quickSearch(query: String): List<SearchResponse> = search(query)

    override suspend fun load(url: String): LoadResponse? {
        try {
            val animeId = url.substringAfterLast('-').trim()
            if (animeId.isBlank() || animeId.toIntOrNull() == null) {
                throw IllegalArgumentException("Invalid anime ID extracted from URL: $url")
            }

            // Fetch info, episodes, recommendations concurrently
            val (infoResult, episodesResult, recommendationsResult) = coroutineScope {
                val infoTask = async {
                    runCatching { app.get("$apiBaseUrl/anime/info/$animeId?lang=vi", headers = apiHeaders, interceptor = interceptor, timeout = 15_000).parsedSafe<ApiDetailResponse>() }
                }
                val episodesTask = async {
                     runCatching { app.get("$apiBaseUrl/anime/$animeId/episodes?lang=vi", headers = apiHeaders, interceptor = interceptor, timeout = 15_000).parsedSafe<ApiEpisodeResponse>() }
                }
                val recommendationsTask = async {
                    runCatching { app.get("$apiBaseUrl/anime/info/$animeId/recommendations?lang=vi", headers = apiHeaders, interceptor = interceptor, timeout = 15_000).parsedSafe<ApiRecommendationResponse>() }
                }
                Triple(infoTask.await(), episodesTask.await(), recommendationsTask.await())
            }

            val infoRes = infoResult.getOrElse { throw IOException("Failed to fetch anime info", it) }
                ?: throw IOException("Parsed anime info is null")

            val episodesResponse = episodesResult.getOrElse {
                logError(IOException("Failed to fetch episodes", it))
                null // Allow loading details even if episodes fail
            }

            recommendationsResult.exceptionOrNull()?.let { logError(it) }

            val post = infoRes.data
            val title = post.title ?: "Unknown Title $animeId"
            val poster = fixUrl(post.poster)
            val cover = fixUrl(post.cover)
            val plot = post.description
            val year = post.year?.toIntOrNull()
            val tags = post.genres?.mapNotNull { it.name }?.filter { it.isNotBlank() }
            val tvType = post.type.toTvType()

            // === GỘP TẬP THEO SỐ ===
            val episodes = episodesResponse?.teams?.flatMap { it.groups }?.flatMap { it.episodes }
                ?.filter { it.number != null } // Chỉ lấy tập có số
                ?.groupBy { it.number!! } // Nhóm theo số tập (Map<Int, List<EpisodeListItem>>)
                ?.mapNotNull { (epNum, epList) ->
                    // Chọn tên: Ưu tiên có [BD], nếu không thì lấy title của item đầu tiên
                    val bestTitleItem = epList.firstOrNull { it.title?.contains("[BD]", ignoreCase = true) == true } ?: epList.firstOrNull()
                    val rawTitle = bestTitleItem?.title ?: epNum.toString() // Title gốc từ API (01[BD], 01, 12_END)

                    // Tạo tên hiển thị (Tập 1 [BD], Tập 12 END)
                    val baseName = "Tập $epNum"
                    val numRegex = """^\d+""".toRegex()
                    val match = numRegex.find(rawTitle)
                    val suffix = if (match != null) rawTitle.substring(match.value.length) else rawTitle
                    val cleanedSuffix = suffix.replace("_", " ").trim()
                    val displayName = if (cleanedSuffix.isEmpty() || cleanedSuffix.equals(epNum.toString(), ignoreCase = true)) baseName else "$baseName $cleanedSuffix"

                    // Tạo data là danh sách ID nối chuỗi
                    val episodeIds = epList.map { it.id.toString() }.joinToString(",") // "2332,38315"

                    newEpisode(episodeIds) { // Data chứa nhiều ID
                        name = displayName
                        episode = epNum // Giữ số tập để sort
                    }
                }
                ?.sortedBy { it.episode } // Sắp xếp theo số tập
                ?: emptyList()
            // === KẾT THÚC GỘP TẬP ===

            val trailers = post.videos?.mapNotNull { fixUrl(it.url) }
            val recommendationsList = recommendationsResult.getOrNull()?.data?.mapNotNull { item ->
                 // Check nulls before calling helper
                if (item.title == null || item.link == null) null else
                toSearchResponseInternal(
                    title = item.title,
                    url = item.link, // Pass relative URL
                    posterUrl = item.poster,
                    tvType = item.type.toTvType(),
                    year = item.year?.toIntOrNull(),
                    epCount = null
                )
            }

            return when (tvType) {
                TvType.AnimeMovie, TvType.OVA -> {
                    // Phim giờ chỉ có 1 tập (đã gộp), lấy data của tập đó
                    val movieData = episodes.firstOrNull()?.data
                    if (movieData == null) {
                        logError(IOException("No valid episodes found for Movie/OVA after grouping: $title"))
                        return null
                    }
                    newMovieLoadResponse(title, url, tvType, movieData) { // movieData là chuỗi IDs
                        this.posterUrl = poster
                        this.year = year; this.plot = plot; this.tags = tags
                        addTrailer(trailers); this.recommendations = recommendationsList
                    }
                }
                else -> { // Anime, Cartoon
                    newTvSeriesLoadResponse(title, url, tvType, episodes) { // episodes là list đã gộp
                        this.posterUrl = poster
                        this.backgroundPosterUrl = cover; this.year = year
                        this.plot = plot; this.tags = tags; addTrailer(trailers); this.recommendations = recommendationsList
                    }
                }
            }
        } catch (e: Exception) {
            logError(e) // Log the specific error
            throw IOException("Error loading $url", e) // Re-throw generic error for UI
        }
    }

    // === LoadLinks đã sửa để xử lý nhiều ID ===
    override suspend fun loadLinks(
        data: String, // Data is now a comma-separated string of IDs "id1,id2,..."
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {

        val episodeIds = data.split(',').filter { it.isNotBlank() }
        if (episodeIds.isEmpty()) {
            //logError(IOException("loadLinks received empty episode IDs: '$data'"))
            return false
        }

        var loaded = false

        // Fetch streams for all IDs in parallel
        val allStreams = coroutineScope {
            episodeIds.map { id ->
                async {
                    try {
                        val watchUrl = "$apiBaseUrl/anime/watch/episode/$id?lang=vi"
                        app.get(watchUrl, headers = apiHeaders, interceptor = interceptor, timeout = 10_000).parsedSafe<ApiWatchResponse>()?.streams
                    } catch (e: Exception) {
                        logError(IOException("Failed to get streams for ID: $id", e))
                        null // Return null on error for this specific ID
                    }
                }
            }.flatMap { it.await() ?: emptyList() } // Wait for all, flatten results, ignore nulls/empty lists
        }

        if (allStreams.isEmpty()){
             logError(IOException("No streams found for any episode IDs: $episodeIds"))
             return false
        }

        // Process the combined list of streams
        // Dùng Set để tránh trùng lặp link hoàn toàn (cùng server, cùng url)
        val processedUrls = mutableSetOf<String>()

        allStreams.forEach { stream ->
            val streamUrl = stream.url
            val serverName = stream.server_name
            val playerType = stream.player_type

            // Check required fields and filter unwanted servers
            if (streamUrl.isNullOrBlank() || serverName.isNullOrBlank()) return@forEach
            if (serverName.contains("HY", ignoreCase = true)) return@forEach
            // Skip if this exact URL from this server was already added
            if (!processedUrls.add("$serverName|$streamUrl")) return@forEach


            val ref = "$mainUrl/"

            try {
                if (serverName.contains("FE", ignoreCase = true) || playerType.equals("jwplayer", ignoreCase = true)) {
                    val link = newExtractorLink(
                        source = this@Anime47Provider.name,
                        name = serverName, // Use server name directly as API provides it
                        url = streamUrl,
                        type = ExtractorLinkType.M3U8
                    ) {
                        this.referer = ref
                        this.quality = Qualities.Unknown.value // M3U8 quality is usually determined by player
                    }
                    callback(link)
                    loaded = true // Mark as loaded if at least one link is added

                    // Add subtitles
                    stream.subtitles?.forEach { sub ->
                        if (!sub.file.isNullOrBlank() && !sub.label.isNullOrBlank()) {
                            try {
                                subtitleCallback(
                                    newSubtitleFile( // Use suspend function
                                        lang = mapSubtitleLabel(sub.label),
                                        url = sub.file
                                    )
                                )
                            } catch (e: Exception) {
                                logError(e) // Log subtitle specific errors
                            }
                        }
                    }
                }
                // No need to log unhandled server types, reduces noise
            } catch (e: Exception) {
                logError(e) // Log errors during link processing
            }
        } // End of forEach stream

        // No need for a final log if !loaded, handled by return value

        return loaded
    }


    // Subtitle mapping
    private val subtitleLanguageMap: Map<String, List<String>> = mapOf(
        "Vietnamese" to listOf("tiếng việt", "vietnamese", "vietsub", "viet", "vi"),
        "English" to listOf("tiếng anh", "english", "engsub", "eng", "en"),
        "Spanish" to listOf("español", "spanish", "es"),
        "Portuguese" to listOf("português", "portuguese", "pt", "br"),
        "French" to listOf("français", "french", "fr"),
        "German" to listOf("deutsch", "german", "de"),
        "Italian" to listOf("italiano", "italian", "it"),
        "Russian" to listOf("русский", "russian", "ru"),
        "Japanese" to listOf("日本語", "japanese", "ja", "raw"),
        "Korean" to listOf("한국어", "korean", "ko"),
        "Chinese" to listOf("中文", "chinese", "zh", "cn"),
        "Thai" to listOf("ไทย", "thai", "th"),
        "Indonesian" to listOf("indonesia", "indonesian", "id"),
        "Malay" to listOf("malay", "ms", "malaysia"),
        "Arabic" to listOf("العربية", "arabic", "ar"),
    )

    private fun mapSubtitleLabel(label: String): String {
        val lowerLabel = label.trim().lowercase(Locale.ROOT)
        if (lowerLabel.isBlank()) return "Subtitle" // Default label
        for ((language, keywords) in subtitleLanguageMap) {
            if (keywords.any { keyword -> lowerLabel.contains(keyword) }) {
                return language
            }
        }
        // Return original label capitalized if no match
        return label.trim().replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale.ROOT) else it.toString() }
    }

    // Video Interceptor
    override fun getVideoInterceptor(extractorLink: ExtractorLink): Interceptor? {
        val nonprofitAsiaTsRegex = Regex("""https://cdn\d*\.nonprofit\.asia/.*""")

        return object : Interceptor {
            override fun intercept(chain: Interceptor.Chain): Response {
                val request = chain.request()
                var response: Response? = null
                try {
                    response = chain.proceed(request)
                    val url = request.url.toString()

                    if (nonprofitAsiaTsRegex.containsMatchIn(url) && response.isSuccessful) {
                        response.body?.let { body ->
                            try {
                                val responseBytes = body.bytes()
                                val fixedBytes = skipByteErrorRaw(responseBytes)
                                val newBody = fixedBytes.toResponseBody(body.contentType())
                                response.close() // Close the original response body
                                return response.newBuilder()
                                    .removeHeader("Content-Length") // Remove old header
                                    .addHeader("Content-Length", fixedBytes.size.toString()) // Add corrected length
                                    .body(newBody)
                                    .build()
                            } catch (processE: Exception) {
                                logError(processE)
                                response.close() // Ensure original response is closed on error
                                throw IOException("Failed to process video interceptor for $url", processE)
                            }
                        } ?: run {
                           throw IOException("Successful response but null body for $url")
                        }
                    }
                    return response

                } catch (networkE: IOException) {
                    response?.close()
                    logError(networkE)
                    throw networkE // Re-throw network errors
                } catch (e: Exception) {
                    response?.close()
                    logError(e)
                    throw IOException("Unexpected error during video interception", e) // Wrap in IOException
                }
            }
        }
    }
} // End class Anime47Provider


// Helper to fix potential TS stream byte errors
private fun skipByteErrorRaw(byteArray: ByteArray): ByteArray {
    return try {
        if (byteArray.isEmpty()) return byteArray
        val tsPacketSize = 188
        val syncByte = 0x47.toByte() // 'G' in ASCII (71 decimal)

        var firstSyncByte = -1
        for (i in byteArray.indices) {
            // Check if the byte is the sync byte AND it's at the start of a packet position
            if (byteArray[i] == syncByte && (i % tsPacketSize == 0)) {
                firstSyncByte = i
                break
            }
        }

        if (firstSyncByte == -1) {
            //logError(IOException("TS Sync Byte (0x47) not found at expected packet start.")) // Reduce log noise
            return byteArray
        }

        if (firstSyncByte > 0) {
            //logError(IOException("Trimming ${firstSyncByte} bytes from TS stream start.")) // Reduce log noise
            return byteArray.copyOfRange(firstSyncByte, byteArray.size)
        }

        byteArray // Already starts with sync byte
    } catch (e: Exception) {
        logError(e) // Log actual error if processing fails
        byteArray // Return original array on error
    }
}
