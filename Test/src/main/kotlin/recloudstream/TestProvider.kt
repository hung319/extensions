package recloudstream

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import com.lagradost.cloudstream3.utils.AppUtils.tryParseJson
import com.lagradost.cloudstream3.utils.AppUtils.toJson
import com.lagradost.cloudstream3.Score
import android.util.Log

class RidoMoviesProvider : MainAPI() {
    override var mainUrl = "https://ridomovies.tv"
    override var name = "RidoMovies"
    override val hasMainPage = true
    override var lang = "en"
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)
    
    private val TAG = "Rido" // Tag ngắn gọn
    
    // UserAgent cứng để giả lập request ổn định như curl
    private val commonUserAgent = "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/127.0.0.0 Mobile Safari/537.36"

    // --- DTOs ---
    // Class này dùng để truyền data xuyên suốt từ Main -> Load -> LoadLinks
    data class RidoLinkData(
        val url: String,
        val title: String? = null,
        val poster: String? = null,
        val year: Int? = null,
        val type: String? = null,
        val apiLink: String? = null // Lưu sẵn link API nếu có thể đoán trước
    )

    data class RidoEpResponse(val data: List<RidoEpData>?)
    data class RidoEpData(val url: String?)

    // --- API STRUCTURES ---
    data class ApiResponse(val data: ApiData?)
    data class ApiData(val items: List<ApiItem>?)
    data class ApiItem(
        val title: String?,
        val fullSlug: String?,
        val type: String?, 
        val posterPath: String?,
        val releaseYear: String?,
        val content: ApiNestedContent?,
        val contentable: ApiContentable?
    )
    data class ApiNestedContent(val title: String?, val fullSlug: String?, val type: String?)
    data class ApiContentable(val releaseYear: String?)

    // --- MAIN PAGE ---
    override val mainPage = mainPageOf(
        "$mainUrl/core/api/movies/latest?page%5Bnumber%5D=" to "Latest Movies",
        "$mainUrl/core/api/series/latest?page%5Bnumber%5D=" to "Latest TV Series"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = request.data + page
        return try {
            val headers = mapOf(
                "Referer" to "$mainUrl/",
                "X-Requested-With" to "XMLHttpRequest",
                "Accept" to "application/json"
            )
            val response = app.get(url, headers = headers)
            val json = parseJson<ApiResponse>(response.text)
            val items = json.data?.items ?: emptyList()

            val homeList = items.mapNotNull { item ->
                val title = item.title ?: item.content?.title ?: return@mapNotNull null
                val slug = item.fullSlug ?: item.content?.fullSlug ?: return@mapNotNull null
                val rawType = item.type ?: item.content?.type
                
                val href = "$mainUrl/$slug"
                val poster = fixUrl(item.posterPath ?: "")
                val type = if (rawType?.contains("tv") == true) TvType.TvSeries else TvType.Movie
                val year = item.releaseYear?.toIntOrNull() ?: item.contentable?.releaseYear?.toIntOrNull()

                // Logic đoán trước link API để giảm tải ở bước Load
                val apiLinkHint = if (type == TvType.Movie) {
                    // Movie slug thường khớp với API, nhưng ID chuẩn xác hơn nên để Load xử lý kỹ
                    null 
                } else null

                // Đóng gói data để truyền sang Load
                val data = RidoLinkData(href, title, poster, year, rawType, apiLinkHint)
                
                newMovieSearchResponse(title, data.toJson(), type) {
                    this.posterUrl = poster
                    this.year = year
                }
            }
            newHomePageResponse(request.name, homeList, true)
        } catch (e: Exception) {
            newHomePageResponse(request.name, emptyList())
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val url = "$mainUrl/core/api/search?q=$query"
        return try {
            val response = app.get(url, headers = mapOf("Referer" to "$mainUrl/"))
            parseJson<ApiResponse>(response.text).data?.items?.mapNotNull { item ->
                val title = item.title ?: item.content?.title ?: return@mapNotNull null
                val slug = item.fullSlug ?: item.content?.fullSlug ?: return@mapNotNull null
                val type = if (item.type?.contains("tv") == true) TvType.TvSeries else TvType.Movie
                val poster = fixUrl(item.posterPath ?: "")
                val year = item.releaseYear?.toIntOrNull() ?: item.contentable?.releaseYear?.toIntOrNull()
                
                val data = RidoLinkData("$mainUrl/$slug", title, poster, year)
                newMovieSearchResponse(title, data.toJson(), type) {
                    this.posterUrl = poster
                    this.year = year
                }
            } ?: emptyList()
        } catch (e: Exception) { emptyList() }
    }

    // --- LOAD ---
    override suspend fun load(url: String): LoadResponse {
        val linkData = tryParseJson<RidoLinkData>(url)
        val realUrl = linkData?.url ?: url
        
        // Header RSC:1 để lấy payload JSON chứa ID
        val headers = mapOf(
            "rsc" to "1", 
            "Referer" to "$mainUrl/",
            "User-Agent" to commonUserAgent
        )
        
        val responseText = app.get(realUrl, headers = headers).text
        val isTv = realUrl.contains("/tv/") || linkData?.type?.contains("tv") == true

        // Metadata cơ bản
        val descRegex = """name":"description","content":"(.*?)"""".toRegex()
        val description = descRegex.find(responseText)?.groupValues?.get(1)
        
        val posterRegex = """image":"(https:[^"]+?)"""".toRegex()
        val poster = posterRegex.find(responseText)?.groupValues?.get(1)?.let { fixUrl(it) } ?: linkData?.poster
        
        val ratingRegex = """ratingValue":([\d.]+)""".toRegex()
        val ratingVal = ratingRegex.find(responseText)?.groupValues?.get(1)?.toDoubleOrNull()

        // Xử lý Episodes / Movie Data
        val episodes = mutableListOf<Episode>()
        val finalDataUrl: String // Biến này sẽ chứa Link API chuẩn để truyền cho loadLinks

        if (isTv) {
            // Regex lấy ID tập phim từ RSC
            val epRegex = """\{"id":"(\d+)","slug":"([^"]*?\/season-(\d+)\/episode-(\d+)[^"]*)"""".toRegex()
            val addedIds = mutableSetOf<String>()
            
            epRegex.findAll(responseText).forEach { match ->
                val epId = match.groupValues[1]
                val season = match.groupValues[3].toIntOrNull() ?: 1
                val episode = match.groupValues[4].toIntOrNull() ?: 1
                
                if (addedIds.add(epId)) {
                    // Truyền thẳng Link API vào data của Episode
                    val apiLink = "$mainUrl/api/episodes/$epId"
                    episodes.add(newEpisode(apiLink) {
                        this.season = season
                        this.episode = episode
                        this.name = "Episode $episode"
                        this.data = apiLink // LoadLinks sẽ nhận cái này
                    })
                }
            }
            finalDataUrl = realUrl // TV Series dùng episode.data, biến này không quan trọng
        } else {
            // Movie: Tìm ID trong RSC (postid":"...") để tạo link API
            val movieIdRegex = """postid":"(\d+)"""".toRegex()
            val movieId = movieIdRegex.find(responseText)?.groupValues?.get(1)
            
            if (movieId != null) {
                finalDataUrl = "$mainUrl/api/movies/$movieId" // API ID chuẩn (Ưu tiên)
            } else {
                // Fallback: Dùng Slug từ URL
                val slug = realUrl.split("/").lastOrNull { it.isNotEmpty() }?.substringBefore("?") ?: ""
                finalDataUrl = "$mainUrl/api/movies/$slug"
            }
        }

        val sortedEpisodes = episodes.sortedWith(compareBy({ it.season }, { it.episode }))

        return if (isTv) {
            newTvSeriesLoadResponse(linkData?.title ?: "Unknown", realUrl, TvType.TvSeries, sortedEpisodes) {
                this.posterUrl = poster
                this.year = linkData?.year
                this.plot = description
                this.score = ratingVal?.let { Score.from10(it) }
                this.recommendations = emptyList()
            }
        } else {
            newMovieLoadResponse(linkData?.title ?: "Unknown", realUrl, TvType.Movie, finalDataUrl) {
                this.posterUrl = poster
                this.year = linkData?.year
                this.plot = description
                this.score = ratingVal?.let { Score.from10(it) }
                this.recommendations = emptyList()
            }
        }
    }

    // --- LOAD LINKS ---
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        // data ở đây chính là link API (VD: .../api/movies/12345)
        try {
            val headers = mapOf(
                "Authority" to "ridomovies.tv",
                "X-Requested-With" to "XMLHttpRequest",
                "Referer" to "$mainUrl/"
            )
            
            val jsonText = app.get(data, headers = headers).text
            // Parse nhanh bằng Regex để tránh lỗi parser nếu JSON phức tạp
            // Tìm data-src bên trong JSON
            val srcRegex = """data-src\s*=\s*["'](https:\/\/[^"']+)["']""".toRegex()
            val embedUrl = srcRegex.find(jsonText)?.groupValues?.get(1)
            
            if (embedUrl != null) {
                // Tùy theo domain mà chọn Extractor
                if (embedUrl.contains("closeload") || embedUrl.contains("closeload.top")) {
                    extractCloseload(embedUrl, callback)
                } else if (embedUrl.contains("ridoo")) {
                    extractRidoo(embedUrl, callback)
                } else {
                    loadExtractor(embedUrl, subtitleCallback, callback)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Err loadLinks: ${e.message}")
        }
        return true
    }

    // --- EXTRACTOR: CLOSELOAD (Đã tối ưu lấy link thực) ---
    private suspend fun extractCloseload(url: String, callback: (ExtractorLink) -> Unit) {
        try {
            val headers = mapOf(
                "Referer" to "$mainUrl/",
                "User-Agent" to commonUserAgent
            )
            val text = app.get(url, headers = headers).text
            
            // Tìm đoạn Packed JS: eval(function(p,a,c,k,e,d)...
            val packedRegex = """eval\(function\(p,a,c,k,e,d\).*?\.split\('\|'\)\)\)""".toRegex()
            val packedJs = packedRegex.find(text)?.value
            
            if (packedJs != null) {
                // Giải nén code
                val unpacked = JsUnpacker(packedJs).unpack() ?: ""
                
                // Tìm link file: "..." trong code đã giải nén
                // Đây là nơi chứa link srv12... như bạn thấy trong curl
                val fileMatch = """file\s*:\s*["']([^"']+)["']""".toRegex().find(unpacked)
                val masterUrl = fileMatch?.groupValues?.get(1)
                
                if (masterUrl != null) {
                    callback.invoke(
                        newExtractorLink(name, "Closeload (Unpacked)", masterUrl, ExtractorLinkType.M3U8) {
                            this.referer = "https://closeload.top/" // Header quan trọng
                            this.quality = Qualities.Unknown.value
                        }
                    )
                }
            } else {
                // Fallback: Nếu không thấy packed code (hiếm), thử tìm trong JSON-LD
                val jsonLdRegex = """"contentUrl"\s*:\s*"([^"]+)"""".toRegex()
                val contentUrl = jsonLdRegex.find(text)?.groupValues?.get(1)
                if (contentUrl != null) {
                    callback.invoke(
                        newExtractorLink(name, "Closeload (JSON)", contentUrl, ExtractorLinkType.M3U8) {
                            this.referer = "https://closeload.top/"
                        }
                    )
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Err Closeload: ${e.message}")
        }
    }

    // --- EXTRACTOR: RIDOO (Cho TV Series) ---
    private suspend fun extractRidoo(url: String, callback: (ExtractorLink) -> Unit) {
        try {
            val text = app.get(url, headers = mapOf("Referer" to "$mainUrl/")).text
            val fileRegex = """file\s*:\s*["']([^"']+)["']""".toRegex()
            val m3u8 = fileRegex.find(text)?.groupValues?.get(1)
            
            if (m3u8 != null) {
                callback.invoke(
                    newExtractorLink(name, "RidoMovies (HLS)", m3u8, ExtractorLinkType.M3U8) {
                        this.referer = url
                        this.quality = Qualities.Unknown.value
                    }
                )
            }
        } catch (e: Exception) {
            Log.e(TAG, "Err Ridoo: ${e.message}")
        }
    }

    private fun fixUrl(url: String): String {
        if (url.isEmpty()) return ""
        if (url.startsWith("http")) return url
        val cleanUrl = if (url.startsWith("/")) url else "/$url"
        return "$mainUrl$cleanUrl"
    }
}
