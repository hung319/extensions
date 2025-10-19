package recloudstream

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.network.CloudflareKiller
import com.lagradost.cloudstream3.mvvm.safeApiCall
import java.net.URLEncoder
import okhttp3.Interceptor
import okhttp3.Response
import okhttp3.ResponseBody
import okhttp3.ResponseBody.Companion.toResponseBody
import java.util.EnumSet
import java.util.Locale
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.awaitAll
import com.lagradost.cloudstream3.CommonActivity.showToast
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import android.widget.Toast
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.LoadResponse.Companion.addTrailer
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean
import java.lang.NumberFormatException

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
        val score: Any?,
        val status: String?
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

    private fun String?.toShowStatus(): ShowStatus? {
       return when (this?.lowercase()) {
           "ongoing" -> ShowStatus.Ongoing
           "completed" -> ShowStatus.Completed
           else -> null
       }
   }


    // === Core Overrides ===
    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
         withContext(Dispatchers.Main) {
             try {
                 CommonActivity.activity?.let {
                     showToast(it, "Provider by H4RS", Toast.LENGTH_LONG)
                 }
             } catch (_: Exception) {}
         }

        val url = "$apiBaseUrl${request.data}&page=$page"
        val res = try {
            app.get(url, headers = apiHeaders, interceptor = interceptor, timeout = 15_000).parsed<ApiFilterResponse>()
        } catch (e: Exception) {
            throw ErrorLoadingException("Không thể tải trang chủ: ${e.message}")
        }

        val home = res.data.posts.mapNotNull { post -> post.toSearchResult() }
        val hasNext = home.size >= 24
        return newHomePageResponse(request.name, home, hasNext = hasNext)
    }

    private fun Post.toSearchResult(): SearchResponse? {
        val fullUrl = fixUrl(this.link) ?: return null
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

    private fun RecommendationItem.toSearchResult(): SearchResponse? {
        if (this.title.isNullOrBlank()) return null
        val fullUrl = fixUrl(this.link) ?: return null
        if (fullUrl.isBlank()) return null
        val tvType = this.type.toRecommendationTvType()

        return newAnimeSearchResponse(this.title, fullUrl, tvType) {
            this.posterUrl = fixUrl(this@toSearchResult.poster)
            this.year = this@toSearchResult.year?.toIntOrNull()
            this.dubStatus = EnumSet.of(DubStatus.Subbed)
            this.episodes = mutableMapOf()
        }
    }

    private fun SearchItem.toSearchResult(): SearchResponse? {
        val fullUrl = fixUrl(this.link) ?: return null
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
        val res = safeApiCall { app.get(url, headers = apiHeaders, interceptor = interceptor, timeout = 10_000).parsedSafe<ApiSearchResponse>() }
        return res?.results?.mapNotNull { item -> item.toSearchResult() } ?: emptyList()
    }

    override suspend fun quickSearch(query: String): List<SearchResponse> {
        return search(query)
    }

    override suspend fun load(url: String): LoadResponse? {
        val animeId = url.substringAfterLast('-').trim()
        if (animeId.isBlank() || animeId.toIntOrNull() == null) {
             return null
        }

        val infoRes = safeApiCall { app.get("$apiBaseUrl/anime/info/$animeId?lang=vi", headers = apiHeaders, interceptor = interceptor, timeout = 15_000).parsedSafe<ApiDetailResponse>() }
        val episodesRes = safeApiCall { app.get("$apiBaseUrl/anime/$animeId/episodes?lang=vi", headers = apiHeaders, interceptor = interceptor, timeout = 15_000).parsedSafe<ApiEpisodeResponse>() }
        val recommendationsRes = safeApiCall { app.get("$apiBaseUrl/anime/info/$animeId/recommendations?lang=vi", headers = apiHeaders, interceptor = interceptor, timeout = 15_000).parsedSafe<ApiRecommendationResponse>() }

        if (infoRes == null) return null

        val post = infoRes.data
        val title = post.title ?: "Unknown Title $animeId"

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

        val plot = post.description
        val year = post.year?.toIntOrNull()
        // FIX: Rút gọn tag processing - sử dụng let để đảm bảo post.genres không null
        val tags = post.genres?.mapNotNull { it.name }
                           ?.filter { it.isNotBlank() && !it.equals("Unknown", ignoreCase = true) }
                           .let { if (it.isNullOrEmpty()) null else it } // takeIf tương đương

        val tvType = post.toTvType(default = TvType.Anime)
        val showStatus = post.status.toShowStatus()

        // FIX: Tách logic xử lý episode thành biến trung gian
        val parsedEpisodes = mutableListOf<Episode>()
        episodesRes?.teams?.forEach { team ->
            team.groups.forEach { group ->
                group.episodes.forEach { ep ->
                    val currentId = ep.id
                    val currentNumber = ep.number
                    val currentTitle = ep.title

                    val displayNum = currentNumber ?: currentTitle?.toIntOrNull()
                    val epName = currentTitle?.let { tit -> if (tit.toIntOrNull() != null) "Tập $tit" else tit }
                                     ?: displayNum?.let { num -> "Tập $num" }
                                     ?: "Tập $currentId"

                    parsedEpisodes.add(newEpisode(currentId.toString()) {
                        name = epName
                        episode = displayNum
                    })
                }
            }
        }
        val episodeList = parsedEpisodes.distinctBy { it.data }.sortedBy { it.episode }


        val episodesMap = mutableMapOf<DubStatus, List<Episode>>()
        if (episodeList.isNotEmpty()) {
            episodesMap[DubStatus.Subbed] = episodeList
        }

        // FIX: Xử lý trailers và recommendations với kiểm tra null rõ ràng
        val trailers = post.videos?.mapNotNull { fixUrl(it.url) } ?: emptyList() // Fallback to empty list
        val recommendationsList = recommendationsRes?.data?.mapNotNull { it.toSearchResult() } ?: emptyList() // Fallback to empty list

        return try {
            newAnimeLoadResponse(
                name = title,
                url = url,
                type = tvType
            ) {
                this.posterUrl = poster
                this.backgroundPosterUrl = cover
                this.year = year
                this.plot = plot
                this.tags = tags
                this.episodes = episodesMap
                this.showStatus = showStatus
                this.comingSoon = episodeList.isEmpty() && this.showStatus != ShowStatus.Completed

                addTrailer(trailers) // Pass the non-null (possibly empty) list
                this.recommendations = recommendationsList // Pass the non-null (possibly empty) list

                post.score?.let { apiScore ->
                     try {
                         val scoreDouble = apiScore.toString().toDoubleOrNull() ?: 0.0
                         val scoreInt: Int = (scoreDouble * 1000).toInt()
                         // FIX: Use correct Score constructor with named arguments
                         this.score = Score(score = scoreInt, maxScore = 10000)
                     } catch (_: NumberFormatException) {}
                }
            }
        } catch (e: Exception) {
            // logError(e) // Removed log
            null
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {

        val id = data.substringAfterLast('/')
        if (id.isBlank()) return false

        var streams: List<Stream>? = null

        try {
            val watchUrl = "$apiBaseUrl/anime/watch/episode/$id?lang=vi"
            val watchRes = app.get(watchUrl, headers = apiHeaders, interceptor = interceptor, timeout = 10_000).parsedSafe<ApiWatchResponse>()
            streams = watchRes?.streams
        } catch (_: Exception) {}

        if (streams.isNullOrEmpty()) {
            try {
                val episodesUrl = "$apiBaseUrl/anime/$id/episodes?lang=vi"
                val episodesRes = app.get(episodesUrl, headers = apiHeaders, interceptor = interceptor, timeout = 10_000).parsedSafe<ApiEpisodeResponse>()
                val fallbackEpisodeId = episodesRes?.teams?.firstOrNull()?.groups?.firstOrNull()?.episodes?.firstOrNull()?.id

                if (fallbackEpisodeId != null) {
                    val fallbackWatchUrl = "$apiBaseUrl/anime/watch/episode/$fallbackEpisodeId?lang=vi"
                    streams = app.get(fallbackWatchUrl, headers = apiHeaders, interceptor = interceptor, timeout = 10_000).parsedSafe<ApiWatchResponse>()?.streams
                }
            } catch (_: Exception) {}
        }

        if (streams.isNullOrEmpty()) return false

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
                                if (!sub.file.isNullOrBlank() && !sub.language.isNullOrBlank()) {
                                    try {
                                        subtitleCallback(
                                            SubtitleFile( mapSubtitleLabel(sub.language), sub.file )
                                        )
                                    } catch (_: Exception) {}
                                }
                            }
                        }
                    } catch (_: Exception) {}
                }
            }.awaitAll()
        }

        return loaded.get()
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
                               response?.close()
                               throw IOException("Failed to process video interceptor for $url", processE)
                           }
                        }
                    }
                    return response ?: throw IOException("Proceed returned null response for $url")
                } catch (networkE: Exception) {
                    response?.close()
                    throw networkE
                } catch (e: Exception) {
                     response?.close()
                     throw e
                }
            }
        }
    }
} // End class Anime47Provider


// Helper skipByteErrorRaw
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
    } catch (_: Exception) {
        byteArray
    }
}
