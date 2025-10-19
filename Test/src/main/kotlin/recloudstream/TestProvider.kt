package recloudstream

// === Imports ===
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.network.CloudflareKiller
import com.lagradost.cloudstream3.mvvm.Mvvm // Import for safeApiCall
import com.lagradost.cloudstream3.mvvm.safeApiCall // Import for safeApiCall explicitly
// import com.lagradost.cloudstream3.utils.AppUtils.parseJson // Not used directly
import java.net.URLEncoder
import okhttp3.Interceptor // Correct import
import okhttp3.Response
import okhttp3.ResponseBody
import okhttp3.ResponseBody.Companion.toResponseBody
import java.util.EnumSet
import java.util.Locale
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.awaitAll // Needed for replacing apmap
import com.lagradost.cloudstream3.CommonActivity.showToast
import kotlinx.coroutines.Dispatchers // Needed for withContext
import kotlinx.coroutines.withContext // Needed for withContext
import android.widget.Toast
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.LoadResponse.Companion.addTrailer // Keep for trailers
import com.lagradost.cloudstream3.mvvm.logError
// import com.lagradost.cloudstream3.mvvm.safeApiCall // Removed usage
import java.io.IOException // Added import
import java.util.concurrent.atomic.AtomicBoolean // For thread-safe flag
import java.lang.NumberFormatException // Import for score parsing exception

// === Provider Class ===
class Anime47Provider : MainAPI() {
    override var mainUrl = "https://anime47.best"
    private val apiBaseUrl = "https://anime47.love/api"

    override var name = "Anime47"
    override val hasMainPage = true
    override var lang = "vi"
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(TvType.Anime, TvType.AnimeMovie, TvType.OVA, TvType.Cartoon)

    private val interceptor = CloudflareKiller()

    // === Data Classes ===
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

    private data class ImageItem(val url: String?)
    private data class ImagesInfo(val poster: List<ImageItem>?)

    private data class DetailPost(
        val id: Int,
        val title: String?,
        val description: String?,
        val poster: String?,
        val cover: String?,
        val type: String?,
        val year: String?,
        val genres: List<GenreInfo>?,
        val videos: List<VideoItem>?,
        val images: ImagesInfo?,
        val score: Any? // API returns score as Int or Double, use Any? for flexibility
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
        val language: String?,
        val file: String?
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


    // === MainPage Requests (Remain unchanged) ===
    override val mainPage = mainPageOf(
        "/anime/filter?lang=vi&sort=latest" to "Anime Mới Cập Nhật",
        "/anime/filter?lang=vi&sort=rating" to "Top Đánh Giá",
        "/anime/filter?lang=vi&type=tv" to "Anime TV",
        "/anime/filter?lang=vi&type=movie" to "Anime Movie"
    )

    // === API Headers (Remain unchanged) ===
    private val apiHeaders
        get() = mapOf(
            "Accept" to "application/json, text/plain, */*",
            "Origin" to mainUrl,
            "Referer" to "$mainUrl/"
        )

    // === Helper Functions ===
    // === fixUrl ĐÃ GỠ BỎ LỌC PLACEHOLDER ===
    private fun fixUrl(url: String?): String? {
        if (url.isNullOrBlank()) return null
        // Dòng kiểm tra placeholder đã bị xóa
        if (url.startsWith("http")) return url
        if (url.startsWith("//")) return "https:$url"
        val base = mainUrl
        val relativePath = if (url.startsWith("/")) url else "/$url"
        return if (base.startsWith("http")) base + relativePath else "https:$base$relativePath"
    }

    private fun String?.toRecommendationTvType(): TvType {
        return when {
           this?.contains("movie", ignoreCase = true) == true -> TvType.AnimeMovie
           this?.contains("ova", ignoreCase = true) == true -> TvType.OVA
           this?.contains("special", ignoreCase = true) == true -> TvType.OVA
           else -> TvType.Anime
       }
   }

    private fun Post.toTvType(): TvType {
       return when {
           this.type?.contains("movie", ignoreCase = true) == true -> TvType.AnimeMovie
           this.type?.contains("ova", ignoreCase = true) == true -> TvType.OVA
           (this.title.contains("Hoạt Hình Trung Quốc", ignoreCase = true) || this.slug.contains("donghua", ignoreCase = true)) -> TvType.Cartoon
           else -> TvType.Anime
       }
   }

    private fun DetailPost.toTvType(default: TvType = TvType.Anime): TvType {
       return when {
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
            throw ErrorLoadingException("Không thể tải trang chủ: ${e.message}")
        }

        val home = res.data.posts.mapNotNull { it.toSearchResult() }
        val hasNext = home.size >= 24
        return newHomePageResponse(request.name, home, hasNext = hasNext)
    }

    // Đã chuyển sang newAnimeSearchResponse
    private fun Post.toSearchResult(): SearchResponse? {
        val fullUrl = mainUrl + this.link
        if (fullUrl.isBlank()) return null

        val epCount = this.episodes?.filter { it.isDigit() }?.toIntOrNull()
        val episodesMap: MutableMap<DubStatus, Int> = if (epCount != null) mutableMapOf(DubStatus.Subbed to epCount) else mutableMapOf()
        val tvType = this.toTvType()

        return newAnimeSearchResponse(this.title, fullUrl, tvType) {
            this.posterUrl = fixUrl(this@toSearchResult.poster)
            this.year = this@toSearchResult.year?.toIntOrNull()
            this.dubStatus = EnumSet.of(DubStatus.Subbed)
            this.episodes = episodesMap
        }
    }

    // Đã chuyển sang newAnimeSearchResponse
    private fun RecommendationItem.toSearchResult(): SearchResponse? {
        if (this.link.isNullOrBlank() || this.title.isNullOrBlank()) return null
        val fullUrl = mainUrl + this.link
        val tvType = this.type.toRecommendationTvType()

        return newAnimeSearchResponse(this.title, fullUrl, tvType) {
            this.posterUrl = fixUrl(this@toSearchResult.poster)
            this.year = this@toSearchResult.year?.toIntOrNull()
            this.dubStatus = EnumSet.of(DubStatus.Subbed)
            this.episodes = mutableMapOf()
        }
    }

    // Đã chuyển sang newAnimeSearchResponse
    private fun SearchItem.toSearchResult(): SearchResponse? {
        val fullUrl = mainUrl + this.link
        if (fullUrl.isBlank()) return null

        val epCount = this.episodes?.filter { it.isDigit() }?.toIntOrNull()
        val episodesMap: MutableMap<DubStatus, Int> = if (epCount != null) mutableMapOf(DubStatus.Subbed to epCount) else mutableMapOf()
        val tvType = this.type.toRecommendationTvType()

        return newAnimeSearchResponse(this.title, fullUrl, tvType) {
            this.posterUrl = fixUrl(this@toSearchResult.image)
            this.dubStatus = EnumSet.of(DubStatus.Subbed)
            this.episodes = episodesMap
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
            logError(e)
            return emptyList()
        }

        return res?.results?.mapNotNull { it.toSearchResult() } ?: emptyList()
    }

    override suspend fun quickSearch(query: String): List<SearchResponse> {
        // Tái sử dụng logic của search()
        return search(query)
    }

    // === HÀM load ĐÃ SỬA LỖI BIÊN DỊCH + LOGIC PLACEHOLDER ===
    override suspend fun load(url: String): LoadResponse? {
        val animeId = url.substringAfterLast('-').trim()
        if (animeId.isBlank() || animeId.toIntOrNull() == null) {
             logError(IllegalArgumentException("Invalid anime ID extracted from URL: $url"))
             return null
        }

        // --- API Calls ---
        // Using safeApiCall for better error handling and context switching
        val infoRes = safeApiCall { app.get("$apiBaseUrl/anime/info/$animeId?lang=vi", headers = apiHeaders, interceptor = interceptor, timeout = 15_000).parsedSafe<ApiDetailResponse>() }
        val episodesRes = safeApiCall { app.get("$apiBaseUrl/anime/$animeId/episodes?lang=vi", headers = apiHeaders, interceptor = interceptor, timeout = 15_000).parsedSafe<ApiEpisodeResponse>() } // Corrected URL
        val recommendationsRes = safeApiCall { app.get("$apiBaseUrl/anime/info/$animeId/recommendations?lang=vi", headers = apiHeaders, interceptor = interceptor, timeout = 15_000).parsedSafe<ApiRecommendationResponse>() }


        if (infoRes == null) {
            logError("Failed to load anime info for ID: $animeId (Request failed, timed out, or parsing error)")
            return null
        }
        // Log errors for optional calls but don't stop execution
        if (episodesRes == null) logError("Failed to load episodes for ID: $animeId")
        if (recommendationsRes == null) logError("Failed to load recommendations for ID: $animeId")


        val post = infoRes.data
        val title = post.title ?: "Unknown Title $animeId"

        // === Poster/Cover Logic (Ưu tiên ảnh thật) ===
        val imagePosterUrl = fixUrl(post.images?.poster?.firstOrNull()?.url)
        val topLevelPosterUrl = fixUrl(post.poster)
        val coverUrl = fixUrl(post.cover)

        fun isPlaceholder(checkUrl: String?): Boolean {
            return checkUrl?.contains("via.placeholder.com", ignoreCase = true) == true
        }

        val poster = if (!imagePosterUrl.isNullOrBlank() && !isPlaceholder(imagePosterUrl)) {
            imagePosterUrl
        } else if (!topLevelPosterUrl.isNullOrBlank() && !isPlaceholder(topLevelPosterUrl)) {
            topLevelPosterUrl
        } else if (!imagePosterUrl.isNullOrBlank()) {
            imagePosterUrl
        } else {
            topLevelPosterUrl
        }

        val cover = if (!coverUrl.isNullOrBlank() && !isPlaceholder(coverUrl)) {
            coverUrl
        } else {
            null
        }
        // === End Poster/Cover Fix ===

        // --- Basic Info (Lọc tag Unknown) ---
        val plot = post.description
        val year = post.year?.toIntOrNull()
        // === FIX: Simplified tags filtering ===
        val tags = post.genres?.mapNotNull { it.name }
            ?.filter { it.isNotBlank() && !it.equals("Unknown", ignoreCase = true) }
            ?.let { if (it.isEmpty()) null else it } // Return null if empty after filter
        // ================================
        val tvType = post.toTvType(default = TvType.Anime)

        // --- Process Episodes ---
        // Explicitly handle null response
        val episodeList = episodesRes?.teams?.flatMap { team ->
            team.groups.flatMap { group ->
                group.episodes.mapNotNull { ep ->
                    // Check if properties exist before accessing
                    val currentId = ep.id
                    val currentNumber = ep.number
                    val currentTitle = ep.title

                    val displayNum = currentNumber ?: currentTitle?.toIntOrNull()
                    val epName = currentTitle?.let { tit -> if (tit.toIntOrNull() != null) "Tập $tit" else tit }
                                     ?: displayNum?.let { num -> "Tập $num" } // Use num here
                                     ?: "Tập $currentId"

                    newEpisode(currentId.toString()) {
                        name = epName
                        episode = displayNum // Use displayNum which correctly falls back
                    }
                }
            }
        }?.distinctBy { it.data }?.sortedBy { it.episode } ?: emptyList() // Fallback to emptyList if episodesRes is null

        // --- Structure episodes for AnimeLoadResponse ---
        val episodesMap = mutableMapOf<DubStatus, List<Episode>>()
        if (episodeList.isNotEmpty()) {
            episodesMap[DubStatus.Subbed] = episodeList
        } else {
             logError("Warning: No episodes found or failed to parse for ID: $animeId")
        }

        // --- Trailers & Recommendations ---
        val trailers = infoRes.data.videos?.mapNotNull { fixUrl(it.url) }
        val recommendationsList = recommendationsRes?.data?.mapNotNull { it.toSearchResult() }

        // === Log Debug Info ===
        logError("Anime47 Load Debug - ID: $animeId")
        logError("  > Title: $title")
        logError("  > PosterURL: $poster")
        logError("  > CoverURL: $cover")
        logError("  > Year: $year")
        logError("  > Plot: ${plot?.take(50)}...")
        logError("  > Tags: $tags")
        logError("  > Episode Map Size: ${episodesMap.size}")
        logError("  > Subbed Episode Count: ${episodesMap[DubStatus.Subbed]?.size}")
        // =======================

        // --- Use newAnimeLoadResponse (REMOVE WHEN BLOCK) ---
        return try {
            newAnimeLoadResponse(
                name = title,
                url = url,
                type = tvType,
                // Set comingSoon explicitly based on episode presence, unless API has better status
                 comingSoon = episodeList.isEmpty() && post.status?.contains("Not yet aired", ignoreCase = true) == true // Example: Only if API says not aired AND no eps
                 // comingSoonIfNone = false // Alternatively, never mark as coming soon automatically
            ) {
                this.posterUrl = poster
                this.backgroundPosterUrl = cover
                this.year = year
                this.plot = plot
                this.tags = tags
                this.episodes = episodesMap

                addTrailer(trailers)
                this.recommendations = recommendationsList

                post.score?.let { apiScore ->
                     try {
                         val scoreDouble = apiScore.toString().toDoubleOrNull() ?: 0.0
                         val scoreOutOf10000 = (scoreDouble * 1000).toInt()
                         // === FIX: Use named arguments for Score constructor ===
                         this.score = Score(score = scoreOutOf10000, maxScore = 10000)
                         // ======================================================
                         logError("  > Score: ${this.score}")
                     } catch (e: NumberFormatException) {
                         logError(IOException("  > Failed to parse score: $apiScore", e))
                     }
                } ?: logError("  > Score: null (API didn't provide)")
            }
        } catch (e: Exception) {
            logError("!!! Anime47 ERROR creating AnimeLoadResponse for ID $animeId")
            // === FIX: Ensure correct logError overload ===
            logError(e.message ?: "Unknown error creating response") // Log message string
            logError(e) // Log the throwable itself for stack trace
            // ============================================
            null
        }
    } // End of load function

    // === HÀM loadLinks ĐÃ SỬA LỖI SUBTITLE + LOGIC CHECK SERVER ===
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {

        // 1. Xử lý 'data' có thể là URL (lỗi từ load()) hoặc ID
        val id = data.substringAfterLast('/')
        if (id.isBlank()) {
            logError(IOException("loadLinks received blank data: '$data'"))
            return false
        }

        var streams: List<Stream>? = null

        // 2. Thử tải stream trực tiếp, giả định 'id' là episodeId
        try {
            val watchUrl = "$apiBaseUrl/anime/watch/episode/$id?lang=vi"
            val watchRes = app.get(watchUrl, headers = apiHeaders, interceptor = interceptor, timeout = 10_000).parsedSafe<ApiWatchResponse>()
            streams = watchRes?.streams
        } catch (e: Exception) {
            logError(IOException("Failed to get streams directly with ID: $id", e))
        }

        // 3. Nếu thất bại (streams rỗng), giả định 'id' là animeId (cho phim)
        if (streams.isNullOrEmpty()) {
            logError(IOException("No streams found for ID: $id. Assuming it's an animeId, trying fallback..."))
            try {
                // Lấy episodeId đầu tiên từ animeId
                val episodesUrl = "$apiBaseUrl/anime/$id/episodes?lang=vi" // Corrected URL
                val episodesRes = app.get(episodesUrl, headers = apiHeaders, interceptor = interceptor, timeout = 10_000).parsedSafe<ApiEpisodeResponse>()
                val fallbackEpisodeId = episodesRes?.teams?.firstOrNull()?.groups?.firstOrNull()?.episodes?.firstOrNull()?.id

                if (fallbackEpisodeId != null) {
                    logError(IOException("Fallback success: Found episode $fallbackEpisodeId for anime $id"))
                    // Tải stream bằng episodeId vừa tìm được
                    val fallbackWatchUrl = "$apiBaseUrl/anime/watch/episode/$fallbackEpisodeId?lang=vi"
                    streams = app.get(fallbackWatchUrl, headers = apiHeaders, interceptor = interceptor, timeout = 10_000).parsedSafe<ApiWatchResponse>()?.streams
                } else {
                    logError(IOException("Fallback failed: No episodes found for animeId $id"))
                }
            } catch (e: Exception) {
                 logError(IOException("Fallback attempt failed for animeId: $id", e))
            }
        }

        // 4. Nếu vẫn không có stream, báo lỗi và thoát
        if (streams.isNullOrEmpty()) {
            logError(IOException("No streams found for data: '$data' (cleaned ID: '$id')"))
            return false
        }

        // 5. Xử lý stream và subtitle
        val loaded = AtomicBoolean(false)
        coroutineScope {
            streams.map { stream ->
                async {
                    val currentStream = stream
                    val streamUrl = currentStream.url
                    val serverName = currentStream.server_name
                    val playerType = currentStream.player_type

                    if (streamUrl.isNullOrBlank() || serverName.isNullOrBlank()) return@async
                    if (serverName.contains("HY", ignoreCase = true)) return@async

                    val ref = "$mainUrl/"

                    try {
                        // === Xử lý Link Stream ===
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
                            loaded.set(true) // Đánh dấu đã tải được ít nhất 1 link

                            // === FIX: Xử lý Subtitle ===
                            currentStream.subtitles?.forEach { sub ->
                                if (!sub.file.isNullOrBlank() && !sub.language.isNullOrBlank()) {
                                    try {
                                        subtitleCallback(
                                            SubtitleFile(
                                                mapSubtitleLabel(sub.language), // Dùng hàm map đã có
                                                sub.file
                                            )
                                        )
                                    } catch (e: Exception) {
                                        logError(e)
                                    }
                                }
                            }
                            // ========================

                        } else {
                            logError(IOException("Unhandled stream type or server for (ID: $id): $serverName - $streamUrl (Player: $playerType)"))
                        }
                    } catch (e: Exception) {
                        logError(e)
                    }
                }
            }.awaitAll()
        }

        if (!loaded.get()) {
            logError(IOException("No extractor links were loaded for ID: $id"))
        }

        return loaded.get() // Trả về true nếu ít nhất 1 link được load
    }


    // Subtitle mapping
    private val subtitleLanguageMap: Map<String, List<String>> = mapOf(
        "Vietnamese" to listOf("tiếng việt", "vietnamese", "vietsub", "viet", "vi"),
        "English" to listOf("tiếng anh", "english", "engsub", "eng", "en"),
    )
    private fun mapSubtitleLabel(label: String): String {
        val lowerLabel = label.trim().lowercase(Locale.ROOT)
        if (lowerLabel.isBlank()) return "Subtitle" // Trả về mặc định nếu rỗng
        for ((language, keywords) in subtitleLanguageMap) {
            if (keywords.any { keyword -> lowerLabel.contains(keyword) }) {
                return language
            }
        }
        // Trả về label gốc nếu không khớp keyword nào
        return label.trim().replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale.ROOT) else it.toString() }
    }

     // Video Interceptor (Remain unchanged)
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
                                response.close()
                                return response.newBuilder()
                                    .removeHeader("Content-Length")
                                    .addHeader("Content-Length", fixedBytes.size.toString())
                                    .body(newBody)
                                    .build()
                           } catch (processE: Exception) {
                               logError(processE)
                               response.close() // Close before throwing
                               throw IOException("Failed to process video interceptor for $url", processE)
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
} // End class Anime47Provider


// Helper skipByteErrorRaw (Remain unchanged)
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
