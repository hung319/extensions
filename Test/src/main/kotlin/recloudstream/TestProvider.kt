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

    private fun String?.toTvType(): TvType {
        return when {
            this?.contains("movie", ignoreCase = true) == true -> TvType.AnimeMovie
            this?.contains("ova", ignoreCase = true) == true -> TvType.OVA
            this?.contains("special", ignoreCase = true) == true -> TvType.OVA
            else -> TvType.Anime
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
                // Ignore UI errors
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
            logError("Get MainPage failed: ${e.message}") // Log only critical failure
            throw ErrorLoadingException("Không thể tải trang chủ. Lỗi Cloudflare hoặc API.") // Simplified message
        }

        val home = res.data.posts.mapNotNull { post ->
            val epCount = post.episodes?.filter { it.isDigit() }?.toIntOrNull()
            toSearchResponseInternal(
                title = post.title,
                url = post.link,
                posterUrl = post.poster,
                tvType = post.type.toTvType(),
                year = post.year?.toIntOrNull(),
                epCount = epCount
            )
        }
        val hasNext = home.size >= 24
        return newHomePageResponse(request.name, home, hasNext = hasNext)
    }

    private fun toSearchResponseInternal(
        title: String,
        url: String, // Relative URL
        posterUrl: String?,
        tvType: TvType,
        year: Int?,
        epCount: Int?
    ): SearchResponse? {
        val fullUrl = mainUrl + url
        if (fullUrl.isBlank()) return null
        val poster = fixUrl(posterUrl) ?: return null

        return newAnimeSearchResponse(title, fullUrl, tvType) {
            this.posterUrl = poster
            this.year = year
            this.dubStatus = EnumSet.of(DubStatus.Subbed)
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
            app.get(
                url,
                headers = apiHeaders,
                interceptor = interceptor,
                timeout = 10_000
            ).parsedSafe<ApiSearchResponse>()
        } catch (e: Exception) {
            logError("Search failed: ${e.message}")
            return emptyList()
        }

         return res?.results?.mapNotNull { item ->
             val epCount = item.episodes?.filter { it.isDigit() }?.toIntOrNull()
             toSearchResponseInternal(
                 title = item.title,
                 url = item.link,
                 posterUrl = item.image,
                 tvType = item.type.toTvType(),
                 year = null,
                 epCount = epCount
             )
         } ?: emptyList()
    }

    override suspend fun quickSearch(query: String): List<SearchResponse> {
        return search(query)
    }

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

            val infoRes = infoResult.getOrElse {
                throw IOException("Failed to fetch anime info for ID $animeId", it)
            } ?: throw IOException("Parsed anime info is null for ID $animeId")

            val episodesResponse = episodesResult.getOrElse {
                logError("Failed to fetch episodes for ID $animeId: $it") // Log episode fetch errors but don't crash
                null // Continue loading even if episodes fail
            }

            recommendationsResult.exceptionOrNull()?.let { logError("Failed to fetch recommendations: $it") }


            val post = infoRes.data
            val title = post.title ?: "Unknown Title $animeId"

            val poster = fixUrl(post.poster)
            val cover = fixUrl(post.cover)
            val plot = post.description
            val year = post.year?.toIntOrNull()
            val tags = post.genres?.mapNotNull { it.name }?.filter { it.isNotBlank() }
            val tvType = post.type.toTvType()

            // === SỬA TÊN TẬP VÀ episode = null ===
            val episodes = episodesResponse?.teams?.flatMap { team ->
                team.groups.flatMap { group ->
                    group.episodes.mapNotNull { ep ->
                        // Lấy tên gốc từ API, fallback về ID nếu null
                        // Thêm prefix "Tập "
                        val epName = ep.title?.let { "Tập $it" } ?: "Tập ${ep.id}"

                        newEpisode(ep.id.toString()) {
                            name = epName
                            episode = null // Đặt episode = null
                        }
                    }
                }
            }?.distinctBy { it.data } ?: emptyList()
            // === KẾT THÚC SỬA TÊN TẬP ===

            val trailers = infoRes.data.videos?.mapNotNull { fixUrl(it.url) }

            val recommendationsList = recommendationsResult.getOrNull()?.data?.mapNotNull { item ->
                if (item.title == null || item.link == null) {
                    null
                } else {
                    toSearchResponseInternal(
                        title = item.title,
                        url = item.link,
                        posterUrl = item.poster,
                        tvType = item.type.toTvType(),
                        year = item.year?.toIntOrNull(),
                        epCount = null
                    )
                }
            }

            return when (tvType) {
                TvType.AnimeMovie, TvType.OVA -> {
                    val movieData = episodes.firstOrNull()?.data
                    if (movieData == null) {
                        logError("No valid episode found for Movie/OVA: $title (ID: $animeId)")
                        return null
                    }
                    newMovieLoadResponse(title, url, tvType, movieData) {
                        this.posterUrl = poster
                        this.year = year; this.plot = plot; this.tags = tags
                        addTrailer(trailers); this.recommendations = recommendationsList
                    }
                }
                else -> { // Anime, Cartoon
                    newTvSeriesLoadResponse(title, url, tvType, episodes) {
                        this.posterUrl = poster
                        this.backgroundPosterUrl = cover; this.year = year
                        this.plot = plot; this.tags = tags; addTrailer(trailers); this.recommendations = recommendationsList
                    }
                }
            }
        } catch (e: Exception) {
            logError("Error loading $url: $e") // Log the main load error
            throw IOException("Error loading $url", e) // Re-throw for app UI
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {

        val id = data.substringAfterLast('/')
        if (id.isBlank()) {
            // logError("loadLinks received blank data: '$data'") // Removed redundant log
            return false
        }

        var streams: List<Stream>? = null

        try {
            val watchUrl = "$apiBaseUrl/anime/watch/episode/$id?lang=vi"
            val watchRes = app.get(watchUrl, headers = apiHeaders, interceptor = interceptor, timeout = 10_000).parsedSafe<ApiWatchResponse>()
            streams = watchRes?.streams
        } catch (e: Exception) {
            logError("Failed to get streams directly with ID: $id - ${e.message}") // Log only critical failure
        }

        if (streams.isNullOrEmpty()) {
            // logError("No streams found for episode ID: '$id'") // Removed redundant log
            return false
        }

        var loaded = false
        streams.forEach { stream ->
            val streamUrl = stream.url
            val serverName = stream.server_name
            val playerType = stream.player_type

            if (streamUrl.isNullOrBlank() || serverName.isNullOrBlank()) return@forEach
            if (serverName.contains("HY", ignoreCase = true)) return@forEach

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
                    loaded = true

                    // === SỬ DỤNG newSubtitleFile ===
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
                                logError("Failed processing subtitle ${sub.file}: $e")
                            }
                        }
                    }
                    // === KẾT THÚC SỬ DỤNG newSubtitleFile ===
                }
                // Removed redundant log for unhandled server type
            } catch (e: Exception) {
                logError("Error processing stream $streamUrl: $e")
            }
        }

        // Removed redundant log for no links loaded
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
        // Regex needs to be defined within the function or globally, ensure it's correct
        val nonprofitAsiaTsRegex = Regex("""https://cdn\d*\.nonprofit\.asia/.*""")

        return object : Interceptor {
            override fun intercept(chain: Interceptor.Chain): Response {
                val request = chain.request()
                var response: Response? = null
                try {
                    response = chain.proceed(request)
                    val url = request.url.toString()

                    // Ensure regex matches correctly and response is successful
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
                                logError("Error processing video interceptor for $url: $processE")
                                response.close() // Ensure original response is closed on error
                                throw IOException("Failed to process video interceptor for $url", processE)
                            }
                        } ?: run {
                            // Body is null, shouldn't happen with isSuccessful, but handle defensively
                           throw IOException("Successful response but null body for $url")
                        }
                    }
                    // Return original response if no processing needed or conditions not met
                    return response

                } catch (networkE: IOException) { // Catch specific IOExceptions for network issues
                    response?.close()
                    logError("Network error during video interception for ${request.url}: $networkE")
                    throw networkE // Re-throw network errors
                } catch (e: Exception) { // Catch other potential exceptions
                    response?.close()
                    logError("Unexpected error during video interception for ${request.url}: $e")
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

        // Find the first occurrence of the sync byte at the start of a packet
        var firstSyncByte = -1
        for (i in byteArray.indices) {
            if (byteArray[i] == syncByte && (i % tsPacketSize == 0)) {
                firstSyncByte = i
                break
            }
        }

        // If no sync byte found at a valid position, return original array (or handle error)
        if (firstSyncByte == -1) {
            logError("TS Sync Byte (0x47) not found at expected packet start.")
            return byteArray // Or throw? Returning original is safer.
        }

        // If the first sync byte is not at the beginning, trim the start
        if (firstSyncByte > 0) {
            logError("Trimming ${firstSyncByte} bytes from TS stream start.")
            return byteArray.copyOfRange(firstSyncByte, byteArray.size)
        }

        // If sync byte is already at the beginning, no trimming needed
        byteArray
    } catch (e: Exception) {
        logError("Error in skipByteErrorRaw: $e")
        byteArray // Return original array on error
    }
}
