package recloudstream

// === Wildcard Imports ===
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.network.CloudflareKiller
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import com.lagradost.cloudstream3.utils.AppUtils.toJson
import com.lagradost.cloudstream3.mvvm.logError
import com.lagradost.cloudstream3.CommonActivity.showToast

import okhttp3.Interceptor
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import java.io.IOException
import java.net.URL
import java.net.URLEncoder
import java.util.EnumSet
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import android.widget.Toast
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.awaitAll

class Anime47Provider : MainAPI() {
    override var mainUrl = "https://anime47.best"
    private val apiBaseUrl = "https://anime47.love/api"
    override var name = "Anime47"
    override val hasMainPage = true
    override var lang = "vi"
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(TvType.Anime)

    private val interceptor = CloudflareKiller()

    override val mainPage = mainPageOf(
        "/anime/filter?lang=vi&sort=latest" to "Anime Mới Cập Nhật",
        "/anime/filter?lang=vi&sort=rating" to "Top Đánh Giá",
        "/anime/filter?lang=vi&type=tv" to "Anime TV",
        "/anime/filter?lang=vi&type=movie" to "Anime Movie"
    )

    // === Subtitle Language Map (Đã phục hồi đầy đủ) ===
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

    // === Data Classes ===
    private data class GenreInfo(val name: String?)
    private data class Post(
        val id: Int, val title: String, val slug: String, val link: String,
        val poster: String?, val episodes: String?, val type: String?, val year: String?
    )
    private data class ApiFilterData(val posts: List<Post>)
    private data class ApiFilterResponse(val data: ApiFilterData)

    private data class VideoItem(val url: String?)
    private data class DetailPost(
        val id: Int, val title: String?, val description: String?, val poster: String?,
        val cover: String?, val type: String?, val year: String?,
        val genres: List<GenreInfo>?, val videos: List<VideoItem>?
    )
    private data class ApiDetailResponse(val data: DetailPost)

    private data class EpisodeListItem(val id: Int, val number: Int?, val title: String?)
    private data class EpisodeGroup(val name: String?, val episodes: List<EpisodeListItem>)
    private data class EpisodeTeam(val team_name: String?, val groups: List<EpisodeGroup>)
    private data class ApiEpisodeResponse(val teams: List<EpisodeTeam>)

    private data class SubtitleItem(val file: String?, val label: String?)
    private data class Stream(
        val url: String?, val server_name: String?, val player_type: String?,
        val subtitles: List<SubtitleItem>?
    )
    private data class WatchAnimeInfo(val id: Int, val title: String?, val slug: String?, val thumbnail: String?)
    private data class ApiWatchResponse(val id: Int?, val streams: List<Stream>?, val anime: WatchAnimeInfo?)

    private data class RecommendationItem(
        val id: Int, val title: String?, val link: String?, val poster: String?,
        val type: String?, val year: String?
    )
    private data class ApiRecommendationResponse(val data: List<RecommendationItem>?)

    private data class SearchItem(
        val id: Int, val title: String, val link: String, val image: String?,
        val type: String?, val episodes: String?
    )
    private data class ApiSearchResponse(val results: List<SearchItem>?, val has_more: Boolean?)

    // === Helper Methods ===
    private fun fixUrl(url: String?): String? {
        if (url.isNullOrBlank()) return null
        if (url.contains("via.placeholder.com", ignoreCase = true)) return null
        if (url.startsWith("http")) return url
        if (url.startsWith("//")) return "https:$url"
        val relativePath = if (url.startsWith("/")) url else "/$url"
        return if (mainUrl.startsWith("http")) mainUrl + relativePath else "https:$mainUrl$relativePath"
    }

    private fun DetailPost.toTvType(): TvType {
        return when {
            this.type?.equals("TV", ignoreCase = true) == true -> TvType.Anime
            this.type?.contains("movie", ignoreCase = true) == true -> TvType.AnimeMovie
            this.type?.contains("ova", ignoreCase = true) == true -> TvType.OVA
            (this.title?.contains("Hoạt Hình Trung Quốc", ignoreCase = true) == true) -> TvType.Cartoon
            else -> TvType.Anime
        }
    }

    private fun createSearchResponse(
        title: String, url: String, poster: String?,
        year: Int? = null, episodesStr: String? = null
    ): SearchResponse {
        val epCount = episodesStr?.filter { it.isDigit() }?.toIntOrNull()
        val episodesMap = if (epCount != null) mapOf(DubStatus.Subbed to epCount) else emptyMap()

        return newAnimeSearchResponse(title, url, TvType.Anime) {
            this.posterUrl = fixUrl(poster)
            this.year = year
            if (episodesMap.isNotEmpty()) {
                this.dubStatus = EnumSet.of(DubStatus.Subbed)
                this.episodes = episodesMap.toMutableMap()
            }
        }
    }

    // === Main Functions ===
    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        withContext(Dispatchers.Main) {
            try {
                CommonActivity.activity?.let {
                    showToast(it, "Free Repo From H4RS", Toast.LENGTH_LONG)
                }
            } catch (e: Exception) {
                logError(e)
            }
        }

        val url = "$apiBaseUrl${request.data}&page=$page"
        val res = try {
            app.get(url, interceptor = interceptor, timeout = 15_000).parsed<ApiFilterResponse>()
        } catch (e: Exception) {
            logError(e)
            throw ErrorLoadingException("Không thể tải trang chủ. Chi tiết: ${e.message}")
        }

        val home = res.data.posts.mapNotNull { post ->
            val link = fixUrl(post.link) ?: return@mapNotNull null
            createSearchResponse(post.title, link, post.poster, post.year?.toIntOrNull(), post.episodes)
        }
        return newHomePageResponse(request.name, home, hasNext = home.size >= 24)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val encodedQuery = URLEncoder.encode(query, "UTF-8")
        val url = "$apiBaseUrl/search/full/?lang=vi&keyword=$encodedQuery&page=1"
        return try {
            app.get(url, interceptor = interceptor, timeout = 10_000)
                .parsedSafe<ApiSearchResponse>()?.results
                ?.mapNotNull { item ->
                    val link = fixUrl(item.link) ?: return@mapNotNull null
                    createSearchResponse(item.title, link, item.image, null, item.episodes)
                } ?: emptyList()
        } catch (e: Exception) {
            logError(e)
            emptyList()
        }
    }

    override suspend fun load(url: String): LoadResponse? {
        val animeId = url.substringAfterLast('-').trim()
        if (animeId.isBlank() || animeId.toIntOrNull() == null) {
            throw IllegalArgumentException("Invalid anime ID from URL: $url")
        }

        try {
            val (infoResult, episodesResult, recommendationsResult) = coroutineScope {
                val infoTask = async {
                    runCatching { app.get("$apiBaseUrl/anime/info/$animeId?lang=vi", interceptor = interceptor, timeout = 15_000).parsedSafe<ApiDetailResponse>() }
                }
                val episodesTask = async {
                    runCatching { app.get("$apiBaseUrl/anime/$animeId/episodes?lang=vi", interceptor = interceptor, timeout = 15_000).parsedSafe<ApiEpisodeResponse>() }
                }
                val recsTask = async {
                    runCatching { app.get("$apiBaseUrl/anime/info/$animeId/recommendations?lang=vi", interceptor = interceptor, timeout = 15_000).parsedSafe<ApiRecommendationResponse>() }
                }
                Triple(infoTask.await(), episodesTask.await(), recsTask.await())
            }

            val infoRes = infoResult.getOrThrow() ?: throw IOException("Data is null for ID: $animeId")
            val episodesResponse = episodesResult.getOrThrow()
            val post = infoRes.data

            val title = post.title ?: "Unknown Title"
            val poster = fixUrl(post.poster)
            val plot = post.description
            val tags = post.genres?.mapNotNull { it.name }?.filter { it.isNotBlank() }
            val year = post.year?.toIntOrNull()
            val type = post.toTvType()

            val episodes = episodesResponse?.teams?.flatMap { it.groups }?.flatMap { it.episodes }
                ?.filter { it.number != null }
                ?.groupBy { it.number!! }
                ?.map { (epNum, epList) ->
                    val idListJson = epList.map { it.id }.distinct().toJson()
                    newEpisode(idListJson) {
                        this.name = "Tập $epNum"
                        this.episode = epNum
                    }
                }?.sortedBy { it.episode } ?: emptyList()

            val recommendations = recommendationsResult.getOrNull()?.data?.mapNotNull { rec ->
                val link = fixUrl(rec.link) ?: return@mapNotNull null
                createSearchResponse(rec.title ?: "", link, rec.poster, rec.year?.toIntOrNull())
            }

            return if (episodes.isEmpty() && (type == TvType.AnimeMovie || type == TvType.OVA)) {
                newMovieLoadResponse(title, url, TvType.Anime, animeId) {
                    this.posterUrl = poster; this.plot = plot; this.tags = tags; this.year = year
                    this.recommendations = recommendations
                }
            } else {
                newTvSeriesLoadResponse(title, url, TvType.Anime, episodes) {
                    this.posterUrl = poster; this.plot = plot; this.tags = tags; this.year = year
                    this.recommendations = recommendations
                }
            }
        } catch (e: Exception) {
            logError(e)
            throw IOException("Error loading $url", e)
        }
    }

    override suspend fun loadLinks(
        data: String, isCasting: Boolean, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit
    ): Boolean {
        val episodeIds = try {
            if (data.startsWith("[")) parseJson<List<Int>>(data) else listOf(data.toInt())
        } catch (e: Exception) { return false }
        if (episodeIds.isEmpty()) return false

        val loaded = AtomicBoolean(false)
        val referer = "$mainUrl/"

        coroutineScope {
            episodeIds.map { id ->
                async {
                    try {
                        val watchRes = app.get("$apiBaseUrl/anime/watch/episode/$id?lang=vi", interceptor = interceptor, timeout = 10_000).parsedSafe<ApiWatchResponse>()
                        watchRes?.streams?.forEach { stream ->
                            val streamUrl = stream.url
                            val serverName = stream.server_name
                            if (streamUrl.isNullOrBlank() || serverName.isNullOrBlank()) return@forEach
                            if (serverName.contains("HY", ignoreCase = true)) return@forEach

                            if (serverName.contains("FE", ignoreCase = true) || stream.player_type.equals("jwplayer", ignoreCase = true)) {
                                val headers = mutableMapOf(
                                    "Referer" to referer,
                                    "User-Agent" to "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36",
                                    "sec-ch-ua" to "\"Chromium\";v=\"120\", \"Not?A_Brand\";v=\"24\"",
                                    "sec-ch-ua-mobile" to "?1",
                                    "sec-ch-ua-platform" to "\"Android\""
                                )
                                if (streamUrl.contains("vlogphim.net")) {
                                    headers["Origin"] = referer
                                    try { headers["authority"] = URL(streamUrl).host } catch (_: Exception) { headers["authority"] = "pl.vlogphim.net" }
                                }

                                callback(newExtractorLink(this@Anime47Provider.name, serverName, streamUrl, ExtractorLinkType.M3U8) {
                                    this.referer = referer; this.headers = headers; this.quality = Qualities.Unknown.value
                                })
                                loaded.set(true)
                                stream.subtitles?.forEach { sub ->
                                    if (!sub.file.isNullOrBlank()) {
                                        subtitleCallback(SubtitleFile(mapSubtitleLabel(sub.label ?: "Vietnamese"), sub.file))
                                    }
                                }
                            }
                        }
                    } catch (e: Exception) { logError(e) }
                }
            }.awaitAll()
        }
        return loaded.get()
    }

    // === Updated: Logic map subtitle sử dụng map đầy đủ ===
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

    // === INTERCEPTOR FIX: Fix lỗi mã hóa 3001 & Range Request ===
    override fun getVideoInterceptor(extractorLink: ExtractorLink): Interceptor? {
        val nonprofitRegex = Regex("""nonprofit\.asia|cdn\d+\.nonprofit""")

        return object : Interceptor {
            override fun intercept(chain: Interceptor.Chain): Response {
                val request = chain.request()
                val url = request.url.toString()

                if (!nonprofitRegex.containsMatchIn(url)) {
                    return chain.proceed(request)
                }

                // Nếu là Range Request (tải đoạn giữa), không cắt gì cả để tránh lỗi 3001
                val rangeHeader = request.header("Range")
                val isPartialRequest = rangeHeader != null && !rangeHeader.startsWith("bytes=0")

                val newRequest = request.newBuilder()
                    .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
                    .header("Accept", "*/*")
                    .header("Origin", mainUrl)
                    .header("Referer", "$mainUrl/")
                    .build()

                val response = chain.proceed(newRequest)

                if (!response.isSuccessful) return response
                if (isPartialRequest) return response // Trả về luôn nếu là Partial Content

                val contentType = response.header("Content-Type")?.lowercase()
                if (contentType != null && (contentType.contains("html") || contentType.contains("text"))) {
                    return response
                }

                val contentLength = response.body?.contentLength() ?: 0L
                if (contentLength > 50 * 1024 * 1024) return response // Limit 50MB

                return try {
                    val body = response.body ?: return response
                    val bytes = body.bytes()
                    val cleanBytes = removeImageHeader(bytes)
                    val newBody = cleanBytes.toResponseBody(body.contentType())

                    response.newBuilder().body(newBody).build()
                } catch (e: Exception) {
                    logError(e)
                    response
                }
            }
        }
    }

    // Quét 50KB & Kiểm tra 3 gói tin liên tiếp
    private fun removeImageHeader(data: ByteArray): ByteArray {
        if (data.size < 188 * 3) return data
        val searchLimit = minOf(data.size - 188 * 3, 50 * 1024)

        for (i in 0 until searchLimit) {
            if (data[i] == 0x47.toByte() &&
                data[i + 188] == 0x47.toByte() &&
                data[i + 376] == 0x47.toByte()) {
                return data.copyOfRange(i, data.size)
            }
        }
        return data
    }
}
