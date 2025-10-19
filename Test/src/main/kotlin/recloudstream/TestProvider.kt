package recloudstream

// === Imports ===
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.network.CloudflareKiller
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
import com.lagradost.cloudstream3.LoadResponse.Companion.addTrailer
import com.lagradost.cloudstream3.mvvm.logError
// import com.lagradost.cloudstream3.mvvm.safeApiCall // Removed usage
import java.io.IOException // Added import
import java.util.concurrent.atomic.AtomicBoolean // For thread-safe flag

// === Provider Class ===
class Anime47Provider : MainAPI() {
    override var mainUrl = "https://anime47.best"
    private val apiBaseUrl = "https://anime47.love/api"

    override var name = "Anime47"
    override val hasMainPage = true
    override var lang = "vi"
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(TvType.Anime, TvType.AnimeMovie, TvType.OVA, TvType.Cartoon)

    // === FIX: THÊM POSTER DỰ PHÒNG ===
    private val defaultPoster = "https://placehold.co/600x400"
    // === KẾT THÚC FIX ===

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
        val images: ImagesInfo?
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
    // fixUrl: Vẫn lọc bỏ placeholder
    private fun fixUrl(url: String?): String? {
        if (url.isNullOrBlank()) return null
        
        // Lọc bỏ link placeholder không hợp lệ
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
            // Đảm bảo "TV" được map đúng sang TvType.Anime
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
            throw ErrorLoadingException("Không thể tải trang chủ: ${e.message}")
        }

        // mapNotNull vẫn an toàn, nó sẽ lọc item nếu link/title bị null
        val home = res.data.posts.mapNotNull { it.toSearchResult() }
        val hasNext = home.size >= 24
        return newHomePageResponse(request.name, home, hasNext = hasNext)
    }

    // === FIX: DÙNG POSTER DỰ PHÒNG ===
    private fun Post.toSearchResult(): SearchResponse? {
        val fullUrl = mainUrl + this.link
        if (fullUrl.isBlank()) return null
        
        // Cung cấp poster dự phòng, không trả về null
        val poster = fixUrl(this@toSearchResult.poster) ?: defaultPoster

        val epCount = this.episodes?.filter { it.isDigit() }?.toIntOrNull()
        val episodesMap: MutableMap<DubStatus, Int> = if (epCount != null) mutableMapOf(DubStatus.Subbed to epCount) else mutableMapOf()
        val tvType = this.toTvType()

        return newAnimeSearchResponse(this.title, fullUrl, tvType) {
            this.posterUrl = poster // Poster giờ sẽ không bao giờ null
            this.year = this@toSearchResult.year?.toIntOrNull()
            this.dubStatus = EnumSet.of(DubStatus.Subbed)
            this.episodes = episodesMap
        }
    }

    // === FIX: DÙNG POSTER DỰ PHÒNG ===
    private fun RecommendationItem.toSearchResult(): SearchResponse? {
        if (this.link.isNullOrBlank() || this.title.isNullOrBlank()) return null
        val fullUrl = mainUrl + this.link
        
        // Cung cấp poster dự phòng, không trả về null
        val poster = fixUrl(this@toSearchResult.poster) ?: defaultPoster
        
        val tvType = this.type.toRecommendationTvType()

        return newAnimeSearchResponse(this.title, fullUrl, tvType) {
             posterUrl = poster
             year = this@toSearchResult.year?.toIntOrNull()
        }
    }

    // === FIX: DÙNG POSTER DỰ PHÒNG ===
    private fun SearchItem.toSearchResult(): SearchResponse? {
        val fullUrl = mainUrl + this.link
        if (fullUrl.isBlank()) return null

        // Cung cấp poster dự phòng, không trả về null
        val poster = fixUrl(this@toSearchResult.image) ?: defaultPoster

        val epCount = this.episodes?.filter { it.isDigit() }?.toIntOrNull()
        val episodesMap: MutableMap<DubStatus, Int> = if (epCount != null) mutableMapOf(DubStatus.Subbed to epCount) else mutableMapOf()

        val tvType = this.type.toRecommendationTvType()

        return newAnimeSearchResponse(this.title, fullUrl, tvType) {
            this.posterUrl = poster
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
        
        // Giờ đây 'Blood-C' sẽ xuất hiện trong search (với ảnh placeholder)
        return res?.results?.mapNotNull { it.toSearchResult() } ?: emptyList()
    }

    override suspend fun quickSearch(query: String): List<SearchResponse> {
        // Tái sử dụng logic của search()
        return search(query)
    }

    // === HÀM load ĐÃ SỬA: DÙNG POSTER DỰ PHÒNG ===
    override suspend fun load(url: String): LoadResponse? {
        val animeId = url.substringAfterLast('-').trim()
        if (animeId.isBlank() || animeId.toIntOrNull() == null) {
             logError(IllegalArgumentException("Invalid anime ID extracted from URL: $url"))
             return null
        }

        val infoResult = runCatching {
            val infoUrl = "$apiBaseUrl/anime/info/$animeId?lang=vi"
            app.get(infoUrl, headers = apiHeaders, interceptor = interceptor, timeout = 15_000).parsedSafe<ApiDetailResponse>()
        }
        val episodesResult = runCatching {
            val episodesUrl = "$apiBaseUrl/anime/$animeId/episodes?lang=vi"
            app.get(episodesUrl, headers = apiHeaders, interceptor = interceptor, timeout = 15_000).parsedSafe<ApiEpisodeResponse>()
        }
        val recommendationsResult = runCatching {
            val recUrl = "$apiBaseUrl/anime/info/$animeId/recommendations?lang=vi"
            app.get(recUrl, headers = apiHeaders, interceptor = interceptor, timeout = 15_000).parsedSafe<ApiRecommendationResponse>()
        }

        val infoRes = infoResult.getOrNull() ?: run {
            logError(infoResult.exceptionOrNull() ?: IOException("Failed to load anime info for ID: $animeId"))
            return null
        }
         episodesResult.exceptionOrNull()?.let { logError(it) }
         recommendationsResult.exceptionOrNull()?.let { logError(it) }

        val post = infoRes.data
        val title = post.title ?: "Unknown Title $animeId"

        // === FIX: DÙNG POSTER DỰ PHÒNG, KHÔNG DÙNG NULL ===
        val poster = (fixUrl(post.images?.poster?.firstOrNull()?.url)
            ?: fixUrl(post.poster)) ?: defaultPoster // <-- Dùng poster dự phòng

        // Cover có thể là null, không quan trọng bằng poster
        val cover = fixUrl(post.cover) 
        // === KẾT THÚC FIX ===

        val plot = post.description
        val year = post.year?.toIntOrNull()
        val tags = post.genres?.mapNotNull { it.name }?.filter { it.isNotBlank() }
        
        val tvType = post.toTvType(default = TvType.Anime)

        val episodes = episodesResult.getOrNull()?.teams?.flatMap { team ->
            team.groups.flatMap { group ->
                group.episodes.mapNotNull { ep ->
                    val displayNum = ep.number ?: ep.title?.toIntOrNull()
                    val epName = ep.title?.let { if (it.toIntOrNull() != null) "Tập $it" else it }
                                     ?: displayNum?.let { "Tập $it" }
                                     ?: "Tập ${ep.id}"

                    newEpisode(ep.id.toString()) {
                        name = epName
                        episode = null
                    }
                }
            }
        }?.distinctBy { it.data } ?: emptyList()

         val trailers = infoRes.data.videos?.mapNotNull { fixUrl(it.url) }
         
         // Giờ đây recommendation cũng sẽ có ảnh dự phòng
        val recommendationsList = recommendationsResult.getOrNull()?.data?.mapNotNull { it.toSearchResult() }

        return when (tvType) {
            TvType.AnimeMovie, TvType.OVA -> {
                val movieData = episodes.firstOrNull()?.data ?: animeId
                newMovieLoadResponse(title, url, tvType, movieData) {
                    this.posterUrl = poster // <-- poster không còn null
                    this.year = year; this.plot = plot; this.tags = tags
                    addTrailer(trailers); this.recommendations = recommendationsList
                }
            }
            else -> { // Bao gồm TvType.Anime, TvType.Cartoon
                newTvSeriesLoadResponse(title, url, tvType, episodes) {
                    this.posterUrl = poster // <-- poster không còn null
                    this.backgroundPosterUrl = cover; this.year = year
                    this.plot = plot; this.tags = tags; addTrailer(trailers); this.recommendations = recommendationsList
                }
            }
        }
    }

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
                val episodesUrl = "$apiBaseUrl/anime/$id/episodes?lang=vi"
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

        // 5. Xử lý stream
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

        return loaded.get()
    }


    // Subtitle mapping (đã mở rộng)
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
