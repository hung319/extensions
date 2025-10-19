package recloudstream

// === Imports ===
import com.lagradost.cloudstream3.*
// import com.lagradost.cloudstream3.extractors.Hydrax // Bỏ import Hydrax
// import com.lagradost.cloudstream3.extractors.helper.AesHelper // Không cần giải mã nữa
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.network.CloudflareKiller
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
// import com.lagradost.cloudstream3.utils.AppUtils.toJson // Không cần toJson nữa
import java.net.URLEncoder
// Криптографические импорты больше не нужны
// import javax.crypto.Cipher
// import javax.crypto.spec.IvParameterSpec
// import javax.crypto.spec.SecretKeySpec
// import java.security.MessageDigest
// import android.util.Base64
import okhttp3.Interceptor
import okhttp3.Response
import okhttp3.ResponseBody
import okhttp3.ResponseBody.Companion.toResponseBody
import java.util.EnumSet
// import java.util.concurrent.atomic.AtomicBoolean // Không cần nữa
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
import com.lagradost.cloudstream3.mvvm.logError
import com.lagradost.cloudstream3.mvvm.normalSafeApiCall

// === Provider Class ===
class Anime47Provider : MainAPI() {
    // Frontend (trang web)
    override var mainUrl = "https://anime47.best"
    // Backend (API)
    private val apiBaseUrl = "https://anime47.love/api"

    override var name = "Anime47"
    override val hasMainPage = true
    override var lang = "vi"
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(TvType.Anime, TvType.AnimeMovie, TvType.OVA, TvType.Cartoon)

    // Cloudflare Killer có thể vẫn cần thiết để truy cập API
    private val interceptor = CloudflareKiller()

    // === Cấu trúc JSON cho API /filter ===
    private data class GenreInfo(
        val name: String?
    )

    private data class Post(
        val id: Int,
        val title: String,
        val slug: String,
        val link: String, // e.g., "/thong-tin/fullmetal-alchemist-brotherhood-1-1001"
        val poster: String?,
        val episodes: String?, // e.g., "63"
        val type: String?, // e.g., "tv", "movie"
        val year: String?
    )

    private data class ApiFilterData(
        val posts: List<Post>
    )

    private data class ApiFilterResponse(
        val data: ApiFilterData
    )

    // === Cấu trúc JSON cho API /info/{id} ===
     private data class VideoItem(
        val url: String?
    )
    private data class DetailPost(
        val id: Int,
        val title: String,
        val description: String?,
        val poster: String?,
        val cover: String?,
        val type: String?, // "TV", "Movie", etc.
        val year: String?, // e.g., "1999"
        val genres: List<GenreInfo>?,
        val videos: List<VideoItem>? // Thêm videos để lấy trailer
    )

    private data class ApiDetailResponse(
        val data: DetailPost
    )

    // === Cấu trúc JSON cho API /episodes ===
    private data class EpisodeListItem(
        val id: Int, // ID quan trọng cho loadLinks
        val number: Int?, // Số tập
        val title: String? // Tiêu đề phụ (có thể là số tập dạng string)
    )

    private data class EpisodeGroup(
        val name: String?, // e.g., "Group 1-100"
        val episodes: List<EpisodeListItem>
    )

    private data class EpisodeTeam(
        val team_name: String?, // Thường là "Unknown"
        val groups: List<EpisodeGroup>
    )

    private data class ApiEpisodeResponse(
        val teams: List<EpisodeTeam>
    )

    // === Cấu trúc JSON cho API /watch/episode/{id} ===
    private data class Stream(
        val url: String?,
        val server_name: String?, // e.g., "FE", "HY"
        val player_type: String? // e.g., "jwplayer", "unknown"
        // quality, code, subtitles không cần thiết lắm nếu dùng loadExtractor/newExtractorLink
    )

     private data class WatchEpisodeInfo(
        val id: Int,
        val number: String?, // Số tập dạng string
        val title: String? // Tiêu đề phụ
    )

    private data class WatchAnimeInfo(
        val id: Int,
        val title: String?,
        val slug: String?,
        val thumbnail: String?
    )
    private data class ApiWatchResponse(
        val id: Int?, // Episode ID
        val streams: List<Stream>?,
        val anime: WatchAnimeInfo? // Thông tin anime chứa tập này
        // next_episode, prev_episode không cần thiết cho Cloudstream
    )

    // === Cập nhật mainPage ===
    override val mainPage = mainPageOf(
        "/anime/filter?lang=vi&sort=latest" to "Anime Mới Cập Nhật",
        "/anime/filter?lang=vi&sort=rating" to "Top Đánh Giá",
        "/anime/filter?lang=vi&type=tv" to "Anime TV",
        "/anime/filter?lang=vi&type=movie" to "Anime Movie"
    )

    // Headers cần thiết để gọi API
    private val apiHeaders
        get() = mapOf(
            "Accept" to "application/json, text/plain, */*",
            "Origin" to mainUrl,
            "Referer" to "$mainUrl/"
        )

    private fun fixUrl(url: String?): String? {
        if (url.isNullOrBlank()) return null
        if (url.startsWith("http")) return url
        if (url.startsWith("//")) return "https:$url"
        val base = mainUrl // Dùng mainUrl làm base
        val relativePath = if (url.startsWith("/")) url else "/$url"
        // Đảm bảo URL có scheme (http hoặc https)
        return if (base.startsWith("http")) {
             base + relativePath
        } else {
             "https:" + base + relativePath // Mặc định https nếu base không có scheme
        }
    }


    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        withContext(Dispatchers.Main) {
            CommonActivity.activity?.let { activity ->
                 normalSafeApiCall { // Hiển thị toast an toàn trên Main thread
                    showToast(activity, "Provider by H4RS", Toast.LENGTH_LONG)
                }
            }
        }

        // Gọi API Filter
        val url = "$apiBaseUrl${request.data}&page=$page"
        val res = try {
            app.get(
                url,
                headers = apiHeaders,
                interceptor = interceptor,
                timeout = 15_000 // Tăng timeout
            ).parsed<ApiFilterResponse>()
        } catch (e: Exception) {
            logError(e)
            // Trả về lỗi để UI hiển thị
            throw ErrorLoadingException("Không thể tải trang chủ: ${e.message}")
            // return newHomePageResponse(request.name, emptyList())
        }

        // Map kết quả JSON sang SearchResponse
        val home = res.data.posts.mapNotNull { it.toSearchResult() }
        // Kiểm tra xem có trang tiếp theo không dựa trên số lượng kết quả trả về
        val hasNext = home.size >= 24 // Giả sử mỗi trang trả về 24 item
        return newHomePageResponse(request.name, home, hasNext = hasNext)
    }

    // Helper mới để parse JSON 'Post' từ API Filter
    private fun Post.toSearchResult(): SearchResponse? {
        val fullUrl = mainUrl + this.link // Link đã có dạng /thong-tin/...
        if (fullUrl.isBlank()) return null

        val epCount = this.episodes?.filter { it.isDigit() }?.toIntOrNull()
        val episodesMap = if (epCount != null) mapOf(DubStatus.Subbed to epCount) else emptyMap()

        val tvType = when {
            this.type?.contains("movie", true) == true -> TvType.AnimeMovie
            this.type?.contains("ova", true) == true -> TvType.OVA
             // Heuristic: Kiểm tra title hoặc slug nếu API type không đủ tin cậy
            (this.title.contains("Hoạt Hình Trung Quốc", true) || this.slug.contains("donghua", true)) -> TvType.Cartoon
            else -> TvType.Anime
        }

        return newAnimeSearchResponse(this.title, fullUrl, tvType) {
            this.posterUrl = fixUrl(this@toSearchResult.poster) // Sửa URL poster nếu cần
            this.year = this@toSearchResult.year?.toIntOrNull()
            this.dubStatus = EnumSet.of(DubStatus.Subbed) // Mặc định là Subbed
            this.episodes = episodesMap
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        // API tìm kiếm
        val searchUrl = "$apiBaseUrl/anime/filter?lang=vi&keyword=${URLEncoder.encode(query, "UTF-8")}"

        val res = try {
            app.get(
                searchUrl,
                headers = apiHeaders,
                interceptor = interceptor,
                 timeout = 15_000 // Tăng timeout
            ).parsed<ApiFilterResponse>()
        } catch (e: Exception) {
            logError(e)
            return emptyList()
        }

        return res.data.posts.mapNotNull { it.toSearchResult() }
    }


    override suspend fun load(url: String): LoadResponse? {
        // Lấy ID từ URL (e.g., ".../one-piece-6149" -> "6149")
        val animeId = url.substringAfterLast('-').trim()
        if (animeId.isBlank() || animeId.toIntOrNull() == null) {
             logError("Invalid anime ID extracted from URL: $url")
             return null
        }


        // Gọi API Info song song với API Episodes
        return coroutineScope {
            val infoJob = async {
                try {
                    val infoUrl = "$apiBaseUrl/anime/info/$animeId?lang=vi"
                    app.get(infoUrl, headers = apiHeaders, interceptor = interceptor, timeout = 15_000).parsedSafe<ApiDetailResponse>()
                } catch (e: Exception) {
                    logError(e)
                    null
                }
            }

            val episodesJob = async {
                try {
                    val episodesUrl = "$apiBaseUrl/anime/$animeId/episodes?lang=vi"
                    app.get(episodesUrl, headers = apiHeaders, interceptor = interceptor, timeout = 15_000).parsedSafe<ApiEpisodeResponse>()
                } catch (e: Exception) {
                    logError(e)
                    null
                }
            }

            val infoRes = infoJob.await()
            val episodesRes = episodesJob.await()

            if (infoRes == null) {
                logError("Failed to load anime info for ID: $animeId from URL: $url")
                return@coroutineScope null // Không lấy được thông tin cơ bản -> lỗi
            }

            val post = infoRes.data
            val title = post.title ?: "Unknown Title"
            val poster = fixUrl(post.poster)
            val cover = fixUrl(post.cover)
            val plot = post.description
            val year = post.year?.toIntOrNull()
            val tags = post.genres?.mapNotNull { it.name }?.filter { it.isNotBlank() } // Lọc tag rỗng

            val tvType = when {
                post.type?.contains("movie", true) == true -> TvType.AnimeMovie
                post.type?.contains("ova", true) == true -> TvType.OVA
                 (title.contains("Hoạt Hình Trung Quốc", true) ) -> TvType.Cartoon // Heuristic
                else -> TvType.Anime // Mặc định là Anime nếu không xác định được
            }

            // Xử lý danh sách tập phim từ episodesRes
            val episodes = episodesRes?.teams?.flatMap { team ->
                team.groups.flatMap { group ->
                    group.episodes.mapNotNull { ep ->
                        val epNum = ep.number ?: ep.title?.filter { it.isDigit() }?.toIntOrNull()
                        // Ưu tiên ep.number, sau đó là ep.title (nếu là số), cuối cùng là vị trí index+1
                        // Dùng ep.title làm tên nếu có, nếu không thì tạo tên "Tập X"
                        val displayNum = ep.number ?: ep.title?.toIntOrNull()
                        val epName = ep.title?.let { if (it.toIntOrNull() != null) "Tập $it" else it }
                                      ?: displayNum?.let { "Tập $it" }
                                      ?: "Tập ${ep.id}" // Fallback cuối cùng

                        // Data bây giờ chỉ là episode ID dạng String
                        newEpisode(ep.id.toString()) {
                            name = epName
                            episode = epNum // Giữ epNum để sắp xếp
                            // posterUrl = fixUrl(ep.thumbnail) // API không có thumbnail cho từng tập
                        }
                    }
                }
            }?.distinctBy { it.data } // Loại bỏ tập trùng ID (nếu API trả về trùng)
             ?.sortedBy { it.episode } // Sắp xếp lại theo số tập
             ?: emptyList() // Nếu episodesRes null thì trả list rỗng


             val trailers = infoRes.data.videos?.mapNotNull { fixUrl(it.url) }

            // Trả về kết quả
             if (tvType == TvType.AnimeMovie || tvType == TvType.OVA) {
                 // Nếu xác định là movie/ova, trả về MovieLoadResponse
                 // Data là ID của tập đầu tiên (hoặc duy nhất) nếu có
                val movieData = episodes.firstOrNull()?.data ?: animeId // Fallback dùng animeId nếu không có tập nào
                newMovieLoadResponse(title, url, tvType, movieData) {
                    this.posterUrl = poster
                    this.year = year
                    this.plot = plot
                    this.tags = tags
                    addTrailer(trailers)
                }
            } else if (episodes.isNotEmpty()) {
                 // Nếu có tập phim và không phải movie/ova -> là TV series
                newTvSeriesLoadResponse(title, url, tvType, episodes) {
                    this.posterUrl = poster
                    this.backgroundPosterUrl = cover // Thêm cover art
                    this.year = year
                    this.plot = plot
                    this.tags = tags
                     addTrailer(trailers)
                }
            } else {
                 // Trường hợp không xác định là movie/ova nhưng API episodes không trả về tập nào
                 // Có thể là phim lẻ chưa có link hoặc lỗi API episodes
                 // Trả về MovieLoadResponse với animeId làm data để thử loadLinks nếu người dùng bấm xem
                 logError("No episodes found for $title (ID: $animeId), returning as MovieLoadResponse.")
                 newMovieLoadResponse(title, url, TvType.AnimeMovie, animeId) { // Dùng TvType.AnimeMovie làm fallback
                    this.posterUrl = poster
                    this.year = year
                    this.plot = plot
                    this.tags = tags
                    addTrailer(trailers)
                }
            }
        } // end coroutineScope
    }

    // Ghi đè loadLinks để sử dụng API /watch/episode/{id}
    override suspend fun loadLinks(
        data: String, // Bây giờ data là episodeId (String) hoặc animeId (fallback)
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        // Thử coi data là episodeId trước
        val episodeId = data.toIntOrNull() ?: run {
             // Nếu data không phải số (là animeId), thử lấy tập đầu tiên
             logError("loadLinks received non-numeric data '$data', assuming it's animeId and trying to fetch first episode.")
             try {
                val episodesUrl = "$apiBaseUrl/anime/$data/episodes?lang=vi"
                val episodesRes = app.get(episodesUrl, headers = apiHeaders, interceptor = interceptor, timeout = 10_000).parsedSafe<ApiEpisodeResponse>()
                // Lấy ID tập đầu tiên từ kết quả
                episodesRes?.teams?.firstOrNull()?.groups?.firstOrNull()?.episodes?.firstOrNull()?.id
             } catch (e: Exception) {
                 logError("Failed to fetch first episode for animeId '$data': ${e.message}")
                 null
             }
         }

        if (episodeId == null) {
            logError("Could not determine episode ID to load links.")
            return false
        }


        val watchUrl = "$apiBaseUrl/anime/watch/episode/$episodeId?lang=vi"
        val watchRes = try {
            app.get(watchUrl, headers = apiHeaders, interceptor = interceptor, timeout = 15_000).parsedSafe<ApiWatchResponse>()
        } catch (e: Exception) {
            logError(e)
            null
        }

        if (watchRes?.streams == null) {
             logError("No streams found for episode ID: $episodeId")
             return false
        }


        var loaded = false
        watchRes.streams.apmap { stream -> // Sử dụng apmap để chạy song song
            val streamUrl = stream.url
            val serverName = stream.server_name
            val playerType = stream.player_type
            if (streamUrl.isNullOrBlank() || serverName.isNullOrBlank()) return@apmap

            // === BỎ QUA SERVER HY ===
            if (serverName.equals("HY", ignoreCase = true)) {
                 // println("Skipping HY server for episode $episodeId")
                 return@apmap // Bỏ qua server này
            }
            // ========================

            val sourceName = "$name - $serverName" // Tên nguồn đầy đủ
            val ref = "$mainUrl/" // Referer chung

            try {
                // 1. Xử lý link M3U8 trực tiếp (thường từ jwplayer hoặc FE)
                if ((playerType == "jwplayer" || serverName == "FE") && streamUrl.endsWith(".m3u8", ignoreCase = true)) {
                     // Sử dụng newExtractorLink với ExtractorLinkType.M3U8
                    val link = newExtractorLink(
                        source = this@Anime47Provider.name, // Nguồn gốc là tên provider
                        name = serverName, // Tên server (FE, ...)
                        url = streamUrl,
                        type = ExtractorLinkType.M3U8
                    ) {
                        this.referer = ref
                        this.quality = Qualities.Unknown.value // Chất lượng chưa biết
                    }
                    callback(link)
                    loaded = true
                    return@apmap
                }

                // 2. Xử lý các link cần request thêm hoặc extractor khác (Ví dụ vlogphim nếu không phải m3u8)
                // Chỗ này có thể mở rộng nếu cần hỗ trợ thêm extractor hoặc logic phức tạp
                if (serverName == "FE" && streamUrl.contains("vlogphim.net") && !streamUrl.endsWith(".m3u8", ignoreCase = true)) {
                    // Cần logic để lấy link m3u8 từ đây, ví dụ:
                    // val m3u8Link = app.get(streamUrl, referer = ref).url // Hoặc parse từ response body
                    // if (m3u8Link.endsWith(".m3u8")) { callback(newExtractorLink(...) { ... }) }
                     logError("FE server URL is not M3U8, handling needed: $streamUrl")
                    return@apmap
                }

                // 3. Các trường hợp khác chưa xử lý
                logError("Unhandled stream type or server for episode $episodeId: $serverName - $streamUrl (Player: $playerType)")


            } catch (e: Exception) {
                logError(e)
            }
        } // End apmap

        return loaded
    }


    // Các hàm mapSubtitleLabel và getVideoInterceptor giữ nguyên nếu cần
     // Defines a map for flexible subtitle language recognition.
    private val subtitleLanguageMap: Map<String, List<String>> = mapOf(
        "Vietnamese" to listOf("tiếng việt", "vietnamese", "vietsub", "viet", "vi"),
        "English" to listOf("tiếng anh", "english", "engsub", "eng", "en"),
        "Chinese" to listOf("tiếng trung", "chinese", "mandarin", "cn", "zh"),
        "Japanese" to listOf("tiếng nhật", "japanese", "jpn", "ja"),
        "Korean" to listOf("tiếng hàn", "korean", "kor", "ko"),
        // Thêm các ngôn ngữ khác nếu cần
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

     // Interceptor cho video stream (giữ lại phòng khi cần)
    override fun getVideoInterceptor(extractorLink: ExtractorLink): Interceptor? {
        // Ví dụ: kiểm tra nếu link chứa pattern nào đó cần xử lý byte lỗi
        val vlogPhimRegex = Regex("""https://pl\.vlogphim\.net/""") // Ví dụ cụ thể cho vlogphim

        return object : Interceptor {
            override fun intercept(chain: Interceptor.Chain): Response {
                val request = chain.request()
                val response = chain.proceed(request)
                val url = request.url.toString()

                 // Logic skipByteError giữ nguyên từ code cũ, áp dụng cho link vlogphim
                if (vlogPhimRegex.containsMatchIn(url) ) {
                    // Chỉ xử lý nếu response thành công
                    if (!response.isSuccessful) return response

                    response.body?.let { body ->
                        try {
                            // Quan trọng: Phải đọc hết body vào byte array TRƯỚC khi gọi skipByteError
                            val responseBytes = body.bytes() // Đọc hết bytes, body sẽ bị close sau đó
                             // Đóng body một cách tường minh sau khi đọc (mặc dù .bytes() thường tự đóng)
                             // body.close() // Bỏ dòng này vì .bytes() đã đóng

                            val fixedBytes = skipByteErrorRaw(responseBytes) // Hàm mới xử lý trực tiếp byte array
                            val newBody = fixedBytes.toResponseBody(body.contentType())
                            // Trả về response mới với body đã sửa và header Content-Length đúng
                            return response.newBuilder()
                                .removeHeader("Content-Length") // Xóa header cũ
                                .addHeader("Content-Length", fixedBytes.size.toString()) // Thêm header mới
                                .body(newBody)
                                .build()
                        } catch (e: Exception) {
                            logError(e)
                             // Nếu lỗi xảy ra khi xử lý, trả về response gốc
                             // Cần tạo lại body vì body gốc đã bị đọc và đóng bởi .bytes()
                             // Tuy nhiên, trả về response gốc khi lỗi xử lý byte là hợp lý hơn
                             // Nhưng để tránh lỗi 'closed', ta nên trả về lỗi hoặc response gốc mà không sửa body
                             logError("Error fixing bytes for $url, returning original response.")
                             // Không thể trả về response gốc dễ dàng vì body đã đóng.
                             // Cách tốt nhất là throw lỗi hoặc trả về lỗi 500.
                             // return response // Sẽ gây lỗi 'closed'
                             // Ném lỗi để báo hiệu quá trình intercept thất bại
                             throw IOException("Failed to process video interceptor for $url", e)
                        } finally {
                             // Đảm bảo body được đóng nếu chưa đóng (dù .bytes() thường tự đóng)
                             // response.body?.close() // Bỏ dòng này
                        }
                    } // ?: return response // Nếu body null thì trả về response gốc
                }
                return response
            }
        }
    }
} // End class Anime47Provider


// Hàm skipByteError cũ không còn phù hợp vì nó tự đọc và đóng body
/*
private fun skipByteError(responseBody: ResponseBody): ByteArray { ... }
*/

// Hàm skipByteErrorRaw mới, nhận byte array làm đầu vào
private fun skipByteErrorRaw(byteArray: ByteArray): ByteArray {
    return try {
        val length = byteArray.size - 188
        var start = 0
        if (length > 0) {
            for (i in 0 until length) {
                val nextIndex = i + 188
                if (nextIndex < byteArray.size && byteArray[i].toInt() == 71 && byteArray[nextIndex].toInt() == 71) { // 71 is 'G'
                    start = i; break
                }
            }
        }
        if (start > 0) byteArray.copyOfRange(start, byteArray.size) else byteArray
    } catch (e: Exception) {
        logError(e)
        byteArray // Trả về byte array gốc nếu có lỗi
    }
}
