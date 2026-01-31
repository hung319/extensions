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
    
    // UserAgent chuẩn
    private val commonUserAgent = "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36"

    // --- DTOs ---
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

                // Đóng gói data để truyền sang Load
                val data = RidoLinkData(href, title, poster, year, rawType)
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
        val isTv = realUrl.contains("/tv/") || linkData?.type?.contains("tv") == true
        
        // Headers giả lập browser để lấy RSC payload
        val headers = mapOf(
            "rsc" to "1", 
            "Referer" to "$mainUrl/",
            "User-Agent" to commonUserAgent
        )
        
        val responseText = app.get(realUrl, headers = headers).text

        // 1. FIX DESCRIPTION: Lấy từ 'post-overview' thay vì meta tags
        // Mẫu RSC: "className":"post-overview","text":"<p>Nội dung...</p>"
        val descRegex = """className":"post-overview","text":"(.*?)"""".toRegex()
        val rawDesc = descRegex.find(responseText)?.groupValues?.get(1)
        // Clean tags <p>, </p> và unescape
        val description = rawDesc?.replace(Regex("<.*?>"), "")?.replace("\\\"", "\"")

        val posterRegex = """image":"(https:[^"]+?)"""".toRegex()
        val poster = posterRegex.find(responseText)?.groupValues?.get(1)?.let { fixUrl(it) } ?: linkData?.poster
        val ratingRegex = """ratingValue":([\d.]+)""".toRegex()
        val ratingVal = ratingRegex.find(responseText)?.groupValues?.get(1)?.toDoubleOrNull()

        val episodes = mutableListOf<Episode>()
        val finalUrl: String

        if (isTv) {
            // TV Series: Cần ID số để gọi API (VD: /api/episodes/28245)
            val epRegex = """\{"id":"(\d+)","slug":"([^"]*?\/season-(\d+)\/episode-(\d+)[^"]*)"""".toRegex()
            val addedIds = mutableSetOf<String>()
            
            epRegex.findAll(responseText).forEach { match ->
                val epId = match.groupValues[1]
                val season = match.groupValues[3].toIntOrNull() ?: 1
                val episode = match.groupValues[4].toIntOrNull() ?: 1
                
                if (addedIds.add(epId)) {
                    val apiLink = "$mainUrl/api/episodes/$epId"
                    episodes.add(newEpisode(apiLink) {
                        this.season = season
                        this.episode = episode
                        this.name = "Episode $episode"
                        this.data = apiLink 
                    })
                }
            }
            finalUrl = realUrl
        } else {
            // MOVIE FIX: Dùng SLUG, KHÔNG dùng ID số
            // URL gốc: https://ridomovies.tv/movies/grizzly-night -> slug: grizzly-night
            // API đúng: https://ridomovies.tv/api/movies/grizzly-night
            val slug = realUrl.split("/").lastOrNull { it.isNotEmpty() }?.substringBefore("?") ?: ""
            finalUrl = "$mainUrl/api/movies/$slug"
            
            Log.d(TAG, "Movie API URL generated: $finalUrl")
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
            // Truyền finalUrl (API Link) vào data
            newMovieLoadResponse(linkData?.title ?: "Unknown", realUrl, TvType.Movie, finalUrl) {
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
        // data chính là Link API (vd: .../api/movies/grizzly-night)
        Log.d(TAG, "LoadLinks Fetching: $data")
        try {
            val headers = mapOf(
                "Authority" to "ridomovies.tv",
                "X-Requested-With" to "XMLHttpRequest",
                "Referer" to "$mainUrl/"
            )
            
            val response = app.get(data, headers = headers)
            Log.d(TAG, "API Response Code: ${response.code}") // Check log này!
            
            val jsonText = response.text
            
            // Tìm URL embed (iframe)
            val srcRegex = """data-src\s*=\s*["'](https:\/\/[^"']+)["']""".toRegex()
            val embedUrl = srcRegex.find(jsonText)?.groupValues?.get(1)
            
            if (embedUrl != null) {
                Log.d(TAG, "Embed Found: $embedUrl")
                if (embedUrl.contains("closeload")) {
                    extractCloseload(embedUrl, callback)
                } else if (embedUrl.contains("ridoo")) {
                    extractRidoo(embedUrl, callback)
                } else {
                    loadExtractor(embedUrl, subtitleCallback, callback)
                }
            } else {
                Log.e(TAG, "No embed URL found in API response. JSON: ${jsonText.take(100)}...")
            }
        } catch (e: Exception) {
            Log.e(TAG, "LoadLinks Error", e)
        }
        return true
    }

    private suspend fun extractCloseload(url: String, callback: (ExtractorLink) -> Unit) {
        try {
            val headers = mapOf(
                "Referer" to "$mainUrl/",
                "User-Agent" to commonUserAgent
            )
            val text = app.get(url, headers = headers).text
            
            // Tìm Packed JS
            val packedRegex = """eval\(function\(p,a,c,k,e,d\).*?\.split\('\|'\)\)\)""".toRegex()
            val packedJs = packedRegex.find(text)?.value
            
            if (packedJs != null) {
                // Giải nén
                val unpacked = JsUnpacker(packedJs).unpack() ?: ""
                
                // Tìm file link (Master URL)
                val fileMatch = """file\s*:\s*["']([^"']+)["']""".toRegex().find(unpacked)
                val masterUrl = fileMatch?.groupValues?.get(1)
                
                if (masterUrl != null) {
                    Log.d(TAG, "Closeload Master URL: $masterUrl")
                    callback.invoke(
                        newExtractorLink(name, "Closeload", masterUrl, ExtractorLinkType.M3U8) {
                            this.referer = "https://closeload.top/" // QUAN TRỌNG
                            this.quality = Qualities.Unknown.value
                        }
                    )
                }
            } else {
                Log.e(TAG, "Closeload: No Packed JS found")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Closeload Error", e)
        }
    }

    private suspend fun extractRidoo(url: String, callback: (ExtractorLink) -> Unit) {
        try {
            val text = app.get(url, headers = mapOf("Referer" to "$mainUrl/")).text
            val fileRegex = """file\s*:\s*["']([^"']+)["']""".toRegex()
            val m3u8 = fileRegex.find(text)?.groupValues?.get(1)
            
            if (m3u8 != null) {
                callback.invoke(
                    newExtractorLink(name, "RidoMovies", m3u8, ExtractorLinkType.M3U8) {
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
