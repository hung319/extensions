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
    
    private val TAG = "RidoMovies"
    
    // UserAgent cứng giả lập Chrome Android để request ổn định
    private val commonUserAgent = "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/127.0.0.0 Mobile Safari/537.36"

    // --- DTOs (Data Transfer Objects) ---
    data class RidoLinkData(
        val url: String,
        val title: String? = null,
        val poster: String? = null,
        val year: Int? = null,
        val type: String? = null
    )

    data class RidoEpResponse(val data: List<RidoEpData>?)
    data class RidoEpData(val url: String?)

    // --- API STRUCTURES ---
    data class ApiResponse(val data: ApiData?)
    data class ApiData(val items: List<ApiItem>?)
    data class ApiItem(
        val title: String? = null,
        val fullSlug: String? = null,
        val type: String? = null, 
        val posterPath: String? = null,
        val releaseYear: String? = null,
        val content: ApiNestedContent? = null,
        val contentable: ApiContentable? = null
    )
    data class ApiNestedContent(val title: String?, val fullSlug: String?, val type: String?)
    data class ApiContentable(val releaseYear: String? = null)

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

                // KHÔI PHỤC LOGIC GỐC: Đóng gói data thành JSON string
                val data = RidoLinkData(href, title, poster, year, rawType)
                val dataStr = data.toJson()

                newMovieSearchResponse(title, dataStr, type) {
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
            val headers = mapOf("Referer" to "$mainUrl/")
            val response = app.get(url, headers = headers)
            val json = parseJson<ApiResponse>(response.text)
            json.data?.items?.mapNotNull { item ->
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
        // KHÔI PHỤC LOGIC GỐC: Parse data từ JSON string truyền vào
        val linkData = tryParseJson<RidoLinkData>(url)
        val realUrl = linkData?.url ?: url
        val title = linkData?.title ?: "Unknown"
        val year = linkData?.year
        val poster = linkData?.poster
        
        // Gọi RSC để lấy ID tập/phim
        val headers = mapOf(
            "rsc" to "1", 
            "Referer" to "$mainUrl/",
            "User-Agent" to commonUserAgent
        )
        
        val responseText = app.get(realUrl, headers = headers).text
        val isTv = realUrl.contains("/tv/") || linkData?.type?.contains("tv") == true

        // Metadata bổ sung (Description, Rating)
        val descRegex = """name":"description","content":"(.*?)"""".toRegex()
        val description = descRegex.find(responseText)?.groupValues?.get(1)
        val ratingRegex = """ratingValue":([\d.]+)""".toRegex()
        val ratingVal = ratingRegex.find(responseText)?.groupValues?.get(1)?.toDoubleOrNull()

        val episodes = mutableListOf<Episode>()
        val finalUrl: String

        if (isTv) {
            val epRegex = """\{"id":"(\d+)","slug":"([^"]*?\/season-(\d+)\/episode-(\d+)[^"]*)"""".toRegex()
            val addedIds = mutableSetOf<String>()
            
            epRegex.findAll(responseText).forEach { match ->
                val epId = match.groupValues[1]
                val season = match.groupValues[3].toIntOrNull() ?: 1
                val episode = match.groupValues[4].toIntOrNull() ?: 1
                
                if (addedIds.add(epId)) {
                    val apiLink = "$mainUrl/api/episodes/$epId"
                    episodes.add(newEpisode(apiLink) { // apiLink này sẽ vào loadLinks
                        this.season = season
                        this.episode = episode
                        this.name = "Episode $episode"
                        this.data = apiLink 
                    })
                }
            }
            finalUrl = realUrl
        } else {
            // Movie: Tìm ID để tạo API Link
            val movieIdRegex = """postid":"(\d+)"""".toRegex()
            val movieId = movieIdRegex.find(responseText)?.groupValues?.get(1)
            
            if (movieId != null) {
                finalUrl = "$mainUrl/api/movies/$movieId" 
            } else {
                val slug = realUrl.split("/").lastOrNull { it.isNotEmpty() }?.substringBefore("?") ?: ""
                finalUrl = "$mainUrl/api/movies/$slug"
            }
        }

        val sortedEpisodes = episodes.sortedWith(compareBy({ it.season }, { it.episode }))

        return if (isTv) {
            newTvSeriesLoadResponse(title, realUrl, TvType.TvSeries, sortedEpisodes) {
                this.posterUrl = poster
                this.year = year
                this.plot = description
                this.score = ratingVal?.let { Score.from10(it) }
            }
        } else {
            // Truyền finalUrl (Link API) vào biến data của LoadResponse
            newMovieLoadResponse(title, realUrl, TvType.Movie, finalUrl) {
                this.posterUrl = poster
                this.year = year
                this.plot = description
                this.score = ratingVal?.let { Score.from10(it) }
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
        // data ở đây là API Link (vd: https://ridomovies.tv/api/movies/12345)
        Log.d(TAG, "LoadLinks API: $data") 
        try {
            val headers = mapOf(
                "Authority" to "ridomovies.tv",
                "X-Requested-With" to "XMLHttpRequest",
                "Referer" to "$mainUrl/"
            )
            
            val jsonText = app.get(data, headers = headers).text
            
            // Tìm URL embed trong JSON trả về
            val srcRegex = """data-src\s*=\s*["'](https:\/\/[^"']+)["']""".toRegex()
            val embedUrl = srcRegex.find(jsonText)?.groupValues?.get(1)
            
            if (embedUrl != null) {
                Log.d(TAG, "Found Embed: $embedUrl")
                if (embedUrl.contains("closeload")) {
                    extractCloseload(embedUrl, callback)
                } else if (embedUrl.contains("ridoo")) {
                    extractRidoo(embedUrl, callback)
                } else {
                    loadExtractor(embedUrl, subtitleCallback, callback)
                }
            } else {
                Log.e(TAG, "No embed URL found in API response")
            }
        } catch (e: Exception) {
            Log.e(TAG, "LoadLinks Error", e)
        }
        return true
    }

    // --- EXTRACTOR 1: CLOSELOAD (Đã thêm Log & Logic Unpacker) ---
    private suspend fun extractCloseload(url: String, callback: (ExtractorLink) -> Unit) {
        try {
            // Header giả lập browser để tránh bị chặn
            val headers = mapOf(
                "Referer" to "$mainUrl/",
                "User-Agent" to commonUserAgent,
                "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8"
            )
            
            val response = app.get(url, headers = headers)
            val text = response.text
            
            Log.d(TAG, "Closeload Code: ${response.code}")
            
            // 1. Tìm đoạn mã hóa (Packed JS)
            // Regex tìm đoạn: eval(function(p,a,c,k,e,d)...
            val packedRegex = """eval\(function\(p,a,c,k,e,d\).*?\.split\('\|'\)\)\)""".toRegex()
            val packedJs = packedRegex.find(text)?.value
            
            if (packedJs != null) {
                Log.d(TAG, "Found Packed JS")
                // Giải nén
                val unpacked = JsUnpacker(packedJs).unpack() ?: ""
                
                // Debug xem bên trong có gì (Tìm từ khóa 'file')
                Log.d(TAG, "Unpacked Content (partial): ${unpacked.take(200)}") 
                
                // Tìm link trong mã đã giải nén
                // Mẫu: file:"https://srv12..." hoặc file: "https://..."
                val fileMatch = """file\s*:\s*["']([^"']+)["']""".toRegex().find(unpacked)
                val masterUrl = fileMatch?.groupValues?.get(1)
                
                if (masterUrl != null) {
                    Log.d(TAG, "Found Master URL: $masterUrl")
                    callback.invoke(
                        newExtractorLink(name, "Closeload (Server 12)", masterUrl, ExtractorLinkType.M3U8) {
                            this.referer = "https://closeload.top/" // Header quan trọng
                            this.quality = Qualities.Unknown.value
                        }
                    )
                } else {
                    Log.e(TAG, "Unpacked but NO file link found")
                }
            } else {
                Log.e(TAG, "No Packed JS found in Closeload HTML")
                // Fallback: Thử tìm trong JSON-LD (Dù bạn bảo không dùng, nhưng để backup)
                val jsonLdRegex = """"contentUrl"\s*:\s*"([^"]+)"""".toRegex()
                val contentUrl = jsonLdRegex.find(text)?.groupValues?.get(1)
                if (contentUrl != null) {
                    Log.d(TAG, "Fallback to JSON-LD link")
                    callback.invoke(
                        newExtractorLink(name, "Closeload (JSON)", contentUrl, ExtractorLinkType.M3U8) {
                            this.referer = "https://closeload.top/"
                        }
                    )
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Closeload Extract Error", e)
        }
    }

    // --- EXTRACTOR 2: RIDOO ---
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
        } catch (e: Exception) { Log.e(TAG, "Ridoo Error", e) }
    }

    private fun fixUrl(url: String): String {
        if (url.isEmpty()) return ""
        if (url.startsWith("http")) return url
        val cleanUrl = if (url.startsWith("/")) url else "/$url"
        return "$mainUrl$cleanUrl"
    }
}
