package recloudstream

// === Imports ===
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.network.CloudflareKiller
// import com.lagradost.cloudstream3.utils.AppUtils.parseJson // Not used directly
import java.net.URLEncoder
import okhttp3.Interceptor
import okhttp3.Response
import okhttp3.ResponseBody
import okhttp3.ResponseBody.Companion.toResponseBody
import java.util.EnumSet
import java.util.Locale
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.awaitAll // Needed for replacing apmap
import com.lagradost.cloudstream3.CommonActivity.showToast
// import kotlinx.coroutines.Dispatchers // Not used directly
// import kotlinx.coroutines.withContext // Not used directly
import android.widget.Toast
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.LoadResponse.Companion.addTrailer
import com.lagradost.cloudstream3.mvvm.logError
import com.lagradost.cloudstream3.mvvm.safeApiCall // Replaced normalSafeApiCall
import java.io.IOException // Added import

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

    private data class Stream(
        val url: String?,
        val server_name: String?,
        val player_type: String?
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
            this?.contains("movie", true) == true -> TvType.AnimeMovie
            this?.contains("ova", true) == true -> TvType.OVA
            this?.contains("special", true) == true -> TvType.ONA
            else -> TvType.Anime
        }
    }

    private fun Post.toTvType(): TvType {
        return when {
            this.type?.contains("movie", true) == true -> TvType.AnimeMovie
            this.type?.contains("ova", true) == true -> TvType.OVA
            (this.title.contains("Hoạt Hình Trung Quốc", true) || this.slug.contains("donghua", true)) -> TvType.Cartoon
            else -> TvType.Anime
        }
    }

    private fun DetailPost.toTvType(default: TvType = TvType.Anime): TvType {
        return when {
            this.type?.contains("movie", true) == true -> TvType.AnimeMovie
            this.type?.contains("ova", true) == true -> TvType.OVA
            (this.title?.contains("Hoạt Hình Trung Quốc", true) == true ) -> TvType.Cartoon
            else -> default
        }
    }


    // === Core Overrides ===
    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
         // Using safeApiCall on Main thread might still be problematic,
         // Consider moving toast logic outside or using a different approach if issues persist.
         safeApiCall { // Use safeApiCall instead of normalSafeApiCall
            CommonActivity.activity?.let {
                showToast(it, "Provider by H4RS", Toast.LENGTH_LONG)
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

    private fun Post.toSearchResult(): SearchResponse? {
        val fullUrl = mainUrl + this.link
        if (fullUrl.isBlank()) return null

        val epCount = this.episodes?.filter { it.isDigit() }?.toIntOrNull()
         // Fix: Use mutableMapOf for assignment type match
        val episodesMap: MutableMap<DubStatus, Int> = if (epCount != null) mutableMapOf(DubStatus.Subbed to epCount) else mutableMapOf()
        val tvType = this.toTvType()

        return newAnimeSearchResponse(this.title, fullUrl, tvType) {
            this.posterUrl = fixUrl(this@toSearchResult.poster)
            this.year = this@toSearchResult.year?.toIntOrNull()
            this.dubStatus = EnumSet.of(DubStatus.Subbed)
            this.episodes = episodesMap // Assign mutable map
        }
    }

    private fun RecommendationItem.toSearchResult(): SearchResponse? {
        if (this.link.isNullOrBlank() || this.title.isNullOrBlank()) return null
        val fullUrl = mainUrl + this.link
        val tvType = this.type.toRecommendationTvType()

        return newAnimeSearchResponse(this.title, fullUrl, tvType) {
             posterUrl = fixUrl(this@toSearchResult.poster)
             year = this@toSearchResult.year?.toIntOrNull()
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val searchUrl = "$apiBaseUrl/anime/filter?lang=vi&keyword=${URLEncoder.encode(query, "UTF-8")}"
        val res = try {
            app.get(
                searchUrl,
                headers = apiHeaders,
                interceptor = interceptor,
                 timeout = 15_000
            ).parsed<ApiFilterResponse>()
        } catch (e: Exception) {
            logError(e) // Fix: Pass exception object
            return emptyList()
        }
        return res.data.posts.mapNotNull { it.toSearchResult() }
    }

    override suspend fun load(url: String): LoadResponse? {
        val animeId = url.substringAfterLast('-').trim()
        if (animeId.isBlank() || animeId.toIntOrNull() == null) {
             logError(IllegalArgumentException("Invalid anime ID extracted from URL: $url")) // Fix: Pass exception object
             return null
        }

        return coroutineScope {
            val infoJob = async {
                try {
                    val infoUrl = "$apiBaseUrl/anime/info/$animeId?lang=vi"
                    app.get(infoUrl, headers = apiHeaders, interceptor = interceptor, timeout = 15_000).parsedSafe<ApiDetailResponse>()
                } catch (e: Exception) { null }
            }
            val episodesJob = async {
                try {
                    val episodesUrl = "$apiBaseUrl/anime/$animeId/episodes?lang=vi"
                    app.get(episodesUrl, headers = apiHeaders, interceptor = interceptor, timeout = 15_000).parsedSafe<ApiEpisodeResponse>()
                } catch (e: Exception) { null }
            }
            val recommendationsJob = async {
                try {
                    val recUrl = "$apiBaseUrl/anime/info/$animeId/recommendations?lang=vi"
                    app.get(recUrl, headers = apiHeaders, interceptor = interceptor, timeout = 15_000).parsedSafe<ApiRecommendationResponse>()
                } catch (e: Exception) { null }
            }

            val infoRes = infoJob.await() ?: run {
                logError(IOException("Failed to load anime info for ID: $animeId from URL: $url")) // Fix: Pass exception object
                return@coroutineScope null
            }
            val episodesRes = episodesJob.await()
            val recommendationsRes = recommendationsJob.await()

            val post = infoRes.data
            val title = post.title ?: "Unknown Title $animeId"
            val poster = fixUrl(post.poster)
            val cover = fixUrl(post.cover)
            val plot = post.description
            val year = post.year?.toIntOrNull()
            val tags = post.genres?.mapNotNull { it.name }?.filter { it.isNotBlank() }
            val tvType = post.toTvType(default = TvType.Anime)

            val episodes = episodesRes?.teams?.flatMap { team ->
                team.groups.flatMap { group ->
                    group.episodes.mapNotNull { ep ->
                        //val epNum = ep.number ?: ep.title?.filter { it.isDigit() }?.toIntOrNull()
                        val displayNum = ep.number ?: ep.title?.toIntOrNull()
                        val epName = ep.title?.let { if (it.toIntOrNull() != null) "Tập $it" else it }
                                      ?: displayNum?.let { "Tập $it" }
                                      ?: "Tập ${ep.id}"

                        newEpisode(ep.id.toString()) {
                            name = epName
                            episode = null // Set episode to null for auto-sorting
                        }
                    }
                }
            }?.distinctBy { it.data }
             //?.sortedBy { it.episode } // Remove manual sorting
             ?: emptyList()

             val trailers = infoRes.data.videos?.mapNotNull { fixUrl(it.url) }
            val recommendationsList = recommendationsRes?.data?.mapNotNull { it.toSearchResult() }

            when {
                tvType == TvType.AnimeMovie || tvType == TvType.OVA -> {
                    val movieData = episodes.firstOrNull()?.data ?: animeId
                    newMovieLoadResponse(title, url, tvType, movieData) {
                        this.posterUrl = poster
                        this.year = year
                        this.plot = plot
                        this.tags = tags
                        addTrailer(trailers)
                        this.recommendations = recommendationsList
                    }
                }
                episodes.isNotEmpty() -> {
                    newTvSeriesLoadResponse(title, url, tvType, episodes) {
                        this.posterUrl = poster
                        this.backgroundPosterUrl = cover
                        this.year = year
                        this.plot = plot
                        this.tags = tags
                         addTrailer(trailers)
                         this.recommendations = recommendationsList
                    }
                }
                else -> {
                    logError(IOException("No episodes found for $title (ID: $animeId), returning as MovieLoadResponse.")) // Fix: Pass exception object
                    newMovieLoadResponse(title, url, TvType.AnimeMovie, animeId) {
                        this.posterUrl = poster
                        this.year = year
                        this.plot = plot
                        this.tags = tags
                        addTrailer(trailers)
                        this.recommendations = recommendationsList
                    }
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
        val episodeId = data.toIntOrNull() ?: run {
             logError(IllegalArgumentException("loadLinks received non-numeric data '$data', assuming animeId.")) // Fix: Pass exception object
             try {
                val episodesUrl = "$apiBaseUrl/anime/$data/episodes?lang=vi"
                val episodesRes = app.get(episodesUrl, headers = apiHeaders, interceptor = interceptor, timeout = 10_000).parsedSafe<ApiEpisodeResponse>()
                episodesRes?.teams?.firstOrNull()?.groups?.firstOrNull()?.episodes?.firstOrNull()?.id
             } catch (e: Exception) { null }
         }

        if (episodeId == null) {
            logError(IOException("Could not determine episode ID to load links from data: '$data'.")) // Fix: Pass exception object
            return false
        }

        val watchUrl = "$apiBaseUrl/anime/watch/episode/$episodeId?lang=vi"
        val watchRes = try {
            app.get(watchUrl, headers = apiHeaders, interceptor = interceptor, timeout = 15_000).parsedSafe<ApiWatchResponse>()
        } catch (e: Exception) { null }

        if (watchRes?.streams == null) {
             logError(IOException("No streams found for episode ID: $episodeId")) // Fix: Pass exception object
             return false
        }

        var loaded = false
         // Fix: Replace deprecated apmap with map + awaitAll
        watchRes.streams.map { stream ->
            async { // Launch async block for each stream
                val streamUrl = stream.url
                val serverName = stream.server_name
                val playerType = stream.player_type
                // Use return@async inside the async block
                if (streamUrl.isNullOrBlank() || serverName.isNullOrBlank()) return@async
                if (serverName.equals("HY", ignoreCase = true)) return@async

                val sourceName = "$name - $serverName"
                val ref = "$mainUrl/"

                try {
                    if ((playerType == "jwplayer" || serverName == "FE") && streamUrl.endsWith(".m3u8", ignoreCase = true)) {
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
                        loaded = true // This needs careful handling with concurrency, consider AtomicBoolean if strict accuracy needed
                    } else if (serverName == "FE" && streamUrl.contains("vlogphim.net") && !streamUrl.endsWith(".m3u8", ignoreCase = true)) {
                        logError(IOException("FE server URL is not M3U8, handling needed: $streamUrl")) // Fix: Pass exception object
                    } else {
                        logError(IOException("Unhandled stream type or server for episode $episodeId: $serverName - $streamUrl (Player: $playerType)")) // Fix: Pass exception object
                    }
                } catch (e: Exception) {
                    logError(e) // Fix: Pass exception object
                }
            } // End async block
        }.awaitAll() // Wait for all async blocks to complete

        return loaded
    }

    // Subtitle mapping
    private val subtitleLanguageMap: Map<String, List<String>> = mapOf(
        "Vietnamese" to listOf("tiếng việt", "vietnamese", "vietsub", "viet", "vi"),
        "English" to listOf("tiếng anh", "english", "engsub", "eng", "en"),
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
        val vlogPhimRegex = Regex("""https://pl\.vlogphim\.net/""")

        return object : Interceptor {
            override fun intercept(chain: Interceptor.Chain): Response {
                val request = chain.request()
                var response: Response? = null
                try {
                    response = chain.proceed(request)
                    val url = request.url.toString()

                    if (vlogPhimRegex.containsMatchIn(url) && response.isSuccessful) {
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
                               logError(processE) // Fix: Pass exception object
                               throw IOException("Failed to process video interceptor for $url", processE) // Fix: IOException imported
                           }
                        }
                    }
                    return response
                } catch (networkE: Exception) {
                    response?.close()
                    logError(networkE) // Fix: Pass exception object
                    throw networkE
                }
            }
        }
    }
} // End class Anime47Provider


// Helper to fix potential TS stream byte errors
private fun skipByteErrorRaw(byteArray: ByteArray): ByteArray {
    return try {
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
        logError(e) // Fix: Pass exception object
        byteArray
    }
}
