package recloudstream

// === Imports ===
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.network.CloudflareKiller
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import com.lagradost.cloudstream3.utils.AppUtils.toJson
// import org.jsoup.nodes.Element // Đã xóa (Không dùng Jsoup)
import java.net.URLEncoder
// --- Xóa Imports Crypto ---
// import javax.crypto.Cipher
// import javax.crypto.spec.IvParameterSpec
// import javax.crypto.spec.SecretKeySpec
// import java.security.MessageDigest
import android.util.Base64 // Giữ lại nếu cần, nhưng crypto đã bị xóa
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

// === Provider Class ===
class Anime47Provider : MainAPI() {
    // === FIX: Bỏ logic getBaseUrl, dùng link cố định ===
    override var mainUrl = "https://anime47.love"
    private var baseUrl = "https://anime47.love" 
    private val apiBaseUrl = "https://anime47.love/api"
    // private val secondaryRedirectUrl = "https://hoangsabelongtovn.site/" // Đã xóa
    // === Kết thúc FIX ===

    override var name = "Anime47"
    override val hasMainPage = true
    override var lang = "vi"
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(TvType.Anime, TvType.AnimeMovie, TvType.OVA, TvType.Cartoon)

    private val interceptor = CloudflareKiller()

    // === FIX: Chuyển mainPage sang API ===
    override val mainPage = mainPageOf(
        "/anime/filter?lang=vi&sort=latest" to "Anime Mới Cập Nhật",
        "/anime/filter?lang=vi&sort=rating" to "Top Đánh Giá",
        "/anime/filter?lang=vi&type=tv" to "Anime TV",
        "/anime/filter?lang=vi&type=movie" to "Anime Movie"
        // Các link cũ dùng HTML đã bị xóa
    )
    
    // private var domainResolutionAttempted = false // Đã xóa
    // private suspend fun getBaseUrl(): String { ... } // Đã xóa

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

    // Hàm fixUrl mới cho API (chỉ nhận 1 tham số)
    private fun fixUrl(url: String?): String? {
        if (url.isNullOrBlank()) return null
        if (url.contains("via.placeholder.com", ignoreCase = true)) return null
        if (url.startsWith("http")) return url
        if (url.startsWith("//")) return "https:$url"
        val base = baseUrl // Dùng baseUrl cố định
        val relativePath = if (url.startsWith("/")) url else "/$url"
        return if (base.startsWith("http")) {
            base + relativePath
        } else {
            "https:" + base + relativePath
        }
    }

    // === FIX: getMainPage chuyển sang API ===
    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        withContext(Dispatchers.Main) {
            CommonActivity.activity?.let { activity ->
                showToast(activity, "Free Repo From H4RS", Toast.LENGTH_LONG)
            }
        }
        
        val url = "$apiBaseUrl${request.data}&page=$page"
        
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
        val hasNext = home.size >= 24 // Giả định API trả 24 item/trang
        return newHomePageResponse(request.name, home, hasNext = hasNext)
    }

    // === Thêm các hàm helper cho API ===
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
        tvType: TvType,
        year: Int? = null,
        episodesStr: String? = null,
        otherName: String? = null // Thêm otherName
    ): SearchResponse {
        val epCount = episodesStr?.filter { it.isDigit() }?.toIntOrNull()
        val episodesMap: Map<DubStatus, Int> = if (epCount != null) mapOf(DubStatus.Subbed to epCount) else emptyMap()

        return newAnimeSearchResponse(title, url, tvType) {
            this.posterUrl = fixUrl(poster) // Dùng fixUrl (API)
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
            tvType = this.toTvType(),
            year = this.year?.toIntOrNull(),
            episodesStr = this.episodes,
            otherName = this.episodes // Giữ logic cũ: otherName = ribbon
        )
    }

    private fun SearchItem.toSearchResult(): SearchResponse? {
        if (this.link.isBlank()) return null
        val fullUrl = fixUrl(this.link) ?: return null
        return createSearchResponse(
            title = this.title,
            url = fullUrl,
            poster = this.image, // API dùng 'image'
            tvType = this.type.toRecommendationTvType(),
            year = null,
            episodesStr = this.episodes,
            otherName = this.episodes // Giữ logic cũ: otherName = ribbon
        )
    }
    // === Kết thúc các hàm helper ===

    // === FIX: search chuyển sang API ===
    override suspend fun search(query: String): List<SearchResponse> {
        val encodedQuery = URLEncoder.encode(query, "UTF-8")
        val url = "$apiBaseUrl/search/full/?lang=vi&keyword=$encodedQuery&page=1"

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

    // private data class EpisodeInfo(...) // Đã xóa, logic gộp mới dùng trực tiếp API

    // === FIX: load chuyển sang API + Gộp link ===
    override suspend fun load(url: String): LoadResponse? {
        val animeId = url.substringAfterLast('-').trim()
        if (animeId.isBlank() || animeId.toIntOrNull() == null) {
            throw IllegalArgumentException("Invalid anime ID extracted from URL: $url")
        }

        try {
            // Gọi API song song
            val (infoResult, episodesResult, recommendationsResult) = coroutineScope {
                val infoTask = async {
                    val infoUrl = "$apiBaseUrl/anime/info/$animeId?lang=vi"
                    runCatching { app.get(infoUrl, interceptor = interceptor, timeout = 15_000).parsedSafe<ApiDetailResponse>() }
                }
                val episodesTask = async {
                    val episodesUrl = "$apiBaseUrl/anime/$animeId/episodes?lang=vi"
                    runCatching { app.get(episodesUrl, interceptor = interceptor, timeout = 15_000).parsedSafe<ApiEpisodeResponse>() }
                }
                val recommendationsTask = async {
                    val recUrl = "$apiBaseUrl/anime/info/$animeId/recommendations?lang=vi"
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
            
            val poster = fixUrl(post.poster)
            val plot = post.description
            val tags = post.genres?.mapNotNull { it.name }?.filter { it.isNotBlank() }
            val year = post.year?.toIntOrNull()
            
            // Giữ logic tvType từ file gốc
            val tvType = if (tags?.any { it.contains("Hoạt hình Trung Quốc", ignoreCase = true) || it.contains("Tokusatsu", ignoreCase = true) } == true) {
                TvType.Cartoon
            } else {
                post.toTvType(default = TvType.Anime)
            }

            // === LOGIC GỘP TẬP (API) ===
            val allEpisodesRaw = episodesResponse?.teams?.flatMap { team ->
                team.groups.flatMap { it.episodes }
            } ?: emptyList()

            val episodes = allEpisodesRaw
                .filter { it.number != null } // Chỉ lấy tập có 'number'
                .groupBy { it.number!! } // Gộp theo 'number'
                .map { (epNum, epList) ->
                    
                    val epName = "Tập $epNum"
                    // Tạo JSON array chứa các ID
                    val idListJson = epList.map { it.id }.distinct().toJson()

                    newEpisode(idListJson) { // data là chuỗi JSON
                        this.name = epName
                        this.episode = epNum // Gán số tập để sắp xếp
                    }
                }
                .sortedBy { it.episode } // Sắp xếp theo số tập
            // === KẾT THÚC LOGIC GỘP TẬP ===

            // Logic cũ (HTML) kiểm tra if (episodesByNumber.isEmpty())
            // Logic API: nếu không có tập, kiểm tra xem có phải movie không
            if (episodes.isEmpty() && (tvType == TvType.AnimeMovie || tvType == TvType.OVA)) {
                 return newMovieLoadResponse(title, url, tvType, animeId) { // data là animeId
                    this.posterUrl = poster; this.plot = plot; this.tags = tags; this.year = year
                }
            }
            
            return newTvSeriesLoadResponse(title, url, tvType, episodes) {
                this.posterUrl = poster
                this.plot = plot
                this.tags = tags
                this.year = year
            }
        } catch (e: Exception) {
            logError(e)
            throw IOException("Error loading $url", e)
        }
    }
    
    // === Xóa Crypto Data Class và Hàm ===
    // private data class CryptoJsJson(...) // Đã xóa
    // private fun evpBytesToKey(...) // Đã xóa
    // private fun decryptSource(...) // Đã xóa
    // private fun hexStringToByteArray(...) // Đã xóa
    
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
        if (lowerLabel.isBlank()) return "Subtitle" // Default name for empty labels

        // Find the first matching language based on keywords
        for ((language, keywords) in subtitleLanguageMap) {
            if (keywords.any { keyword -> lowerLabel.contains(keyword) }) {
                return language
            }
        }

        // Fallback: If no match, capitalize the original label for clean presentation.
        return label.trim().replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale.ROOT) else it.toString() }
    }
    
    // === FIX: loadLinks chuyển sang API + Gộp link ===
    override suspend fun loadLinks(
        data: String, isCasting: Boolean, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit
    ): Boolean {
        
        // Thử parse data thành JSON list (logic gộp)
        val episodeIds: List<Int> = try {
            if (data.startsWith("[")) {
                // Đây là list ID (Series)
                parseJson(data)
            } else {
                // Đây là 1 ID (Movie/Fallback)
                listOf(data.toInt())
            }
        } catch (e: Exception) {
            logError(e)
            return false
        }

        if (episodeIds.isEmpty()) return false

        val loaded = AtomicBoolean(false)
        val ref = "$baseUrl/"

        coroutineScope {
            // Gọi API song song cho tất cả ID
            episodeIds.map { id ->
                async {
                    try {
                        val watchUrl = "$apiBaseUrl/anime/watch/episode/$id?lang=vi"
                        val watchRes = app.get(watchUrl, interceptor = interceptor, timeout = 10_000).parsedSafe<ApiWatchResponse>()
                        
                        watchRes?.streams?.forEach { stream ->
                            val streamUrl = stream.url
                            val serverName = stream.server_name
                            if (streamUrl.isNullOrBlank() || serverName.isNullOrBlank()) return@forEach
                            if (serverName.contains("HY", ignoreCase = true)) return@forEach // Bỏ qua server HY

                            // Logic API mới chỉ hỗ trợ JWPlayer/FE
                            if (serverName.contains("FE", ignoreCase = true) || stream.player_type.equals("jwplayer", ignoreCase = true)) {
                                val link = newExtractorLink(
                                    source = this@Anime47Provider.name,
                                    name = serverName, // Tên server từ API
                                    url = streamUrl,
                                    type = ExtractorLinkType.M3U8
                                ) {
                                    this.referer = ref
                                    this.quality = Qualities.Unknown.value
                                }
                                callback(link)
                                loaded.set(true)

                                // Xử lý Subtitles (chỉ load 1 lần)
                                stream.subtitles?.forEach { sub ->
                                    if (!sub.file.isNullOrBlank() && !sub.label.isNullOrBlank()) {
                                        try {
                                            subtitleCallback(
                                                // Dùng mapSubtitleLabel (giữ nguyên)
                                                SubtitleFile(
                                                    mapSubtitleLabel(sub.label),
                                                    sub.file // API link đã là tuyệt đối
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

    // === Giữ nguyên getVideoInterceptor (theo yêu cầu) ===
    override fun getVideoInterceptor(extractorLink: ExtractorLink): Interceptor? {
        val nonprofitRegex = Regex(""".*nonprofit.*""")
        
        return object : Interceptor {
            override fun intercept(chain: Interceptor.Chain): Response {
                val request = chain.request()
                val response = chain.proceed(request)
                val url = request.url.toString()

                if (nonprofitRegex.containsMatchIn(url) ) {
                    response.body?.let { body ->
                        try {
                            val fixedBytes = skipByteError(body)
                            val newBody = fixedBytes.toResponseBody(body.contentType())
                            return response.newBuilder().body(newBody).build()
                        } catch (_: Exception) {
                        }
                    }
                }
                return response
            }
        }
    }
}

// === Giữ nguyên skipByteError (theo yêu cầu) ===
private fun skipByteError(responseBody: ResponseBody): ByteArray {
    val source = responseBody.source()
    source.request(Long.MAX_VALUE)
    val buffer = source.buffer.clone()
    source.close()
    val byteArray = buffer.readByteArray()
    val length = byteArray.size - 188
    var start = 0
    for (i in 0 until length) {
        val nextIndex = i + 188
        if (nextIndex < byteArray.size && byteArray[i].toInt() == 71 && byteArray[nextIndex].toInt() == 71) {
            start = i; break
        }
    }
    return if (start > 0) byteArray.copyOfRange(start, byteArray.size) else byteArray
}
