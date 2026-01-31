package recloudstream

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import com.lagradost.cloudstream3.utils.AppUtils.tryParseJson
import com.lagradost.cloudstream3.utils.AppUtils.toJson
import com.lagradost.cloudstream3.Score
import android.util.Log
import kotlin.random.Random

class RidoMoviesProvider : MainAPI() {
    override var mainUrl = "https://ridomovies.tv"
    override var name = "RidoMovies"
    override val hasMainPage = true
    override var lang = "en"
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)
    
    private val TAG = "RidoMovies"
    
    // FIX 1: Tự định nghĩa UserAgent để tránh lỗi Unresolved reference từ AppUtils
    private val commonUserAgent = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"

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

    // --- MAIN PAGE ---
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
        val linkData = tryParseJson<RidoLinkData>(url)
        val realUrl = linkData?.url ?: url
        
        // FIX 1: Sử dụng commonUserAgent thay vì AppUtils.userAgent
        val headers = mapOf(
            "rsc" to "1", 
            "Referer" to "$mainUrl/",
            "User-Agent" to commonUserAgent
        )
        
        val responseText = app.get(realUrl, headers = headers).text
        val isTv = realUrl.contains("/tv/") || linkData?.type?.contains("tv") == true

        // 2. Parse Metadata
        val descRegex = """name":"description","content":"(.*?)"""".toRegex()
        val description = descRegex.find(responseText)?.groupValues?.get(1)
        
        val posterRegex = """image":"(https:[^"]+?)"""".toRegex()
        val poster = posterRegex.find(responseText)?.groupValues?.get(1)?.let { fixUrl(it) } ?: linkData?.poster
        
        val ratingRegex = """ratingValue":([\d.]+)""".toRegex()
        val ratingVal = ratingRegex.find(responseText)?.groupValues?.get(1)?.toDoubleOrNull()

        val title = linkData?.title ?: "RidoMovies"
        val year = linkData?.year

        // 3. Episodes / Movie Data
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
            val slug = realUrl.split("/").lastOrNull { it.isNotEmpty() }?.substringBefore("?") ?: ""
            finalUrl = "$mainUrl/api/movies/$slug"
        }

        val sortedEpisodes = episodes.sortedWith(compareBy({ it.season }, { it.episode }))

        return if (isTv) {
            newTvSeriesLoadResponse(title, realUrl, TvType.TvSeries, sortedEpisodes) {
                this.posterUrl = poster
                this.year = year
                this.plot = description
                
                // FIX 2: Thay thế rating (Int) bằng score (Score Object)
                // Rating deprecated, dùng score = Score.from10(...)
                this.score = ratingVal?.let { Score.from10(it) }
                
                this.recommendations = emptyList() 
            }
        } else {
            newMovieLoadResponse(title, realUrl, TvType.Movie, finalUrl) {
                this.posterUrl = poster
                this.year = year
                this.plot = description
                
                // FIX 2: Thay thế rating (Int) bằng score (Score Object)
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
        Log.d(TAG, "loadLinks Data: $data")
        try {
            val headers = mapOf(
                "Authority" to "ridomovies.tv",
                "X-Requested-With" to "XMLHttpRequest",
                "Referer" to "$mainUrl/"
            )
            
            val jsonText = app.get(data, headers = headers).text
            val json = tryParseJson<RidoEpResponse>(jsonText)
            val iframeHtml = json?.data?.firstOrNull()?.url
            
            if (iframeHtml != null) {
                val srcRegex = """data-src\s*=\s*["'](https:\/\/[^"']+)["']""".toRegex()
                val embedUrl = srcRegex.find(iframeHtml)?.groupValues?.get(1)
                
                if (embedUrl != null) {
                    Log.d(TAG, "Embed found: $embedUrl")
                    
                    if (embedUrl.contains("closeload") || embedUrl.contains("closeload.top")) {
                        extractCloseload(embedUrl, callback)
                    } else if (embedUrl.contains("ridoo")) {
                        extractRidoo(embedUrl, callback)
                    } else {
                        loadExtractor(embedUrl, subtitleCallback, callback)
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error loadLinks", e)
        }
        return true
    }

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

    private suspend fun extractCloseload(url: String, callback: (ExtractorLink) -> Unit) {
        try {
            val text = app.get(url, headers = mapOf("Referer" to "$mainUrl/")).text
            
            // A. JSON-LD Priority
            val jsonLdRegex = """"contentUrl"\s*:\s*"([^"]+)"""".toRegex()
            val contentUrl = jsonLdRegex.find(text)?.groupValues?.get(1)
            
            if (contentUrl != null) {
                callback.invoke(
                    newExtractorLink(name, "Closeload (HLS)", contentUrl, ExtractorLinkType.M3U8) {
                        this.referer = url
                        this.quality = Qualities.Unknown.value
                    }
                )
            } else {
                // B. Packed JS Fallback
                val packedRegex = """eval\(function\(p,a,c,k,e,d\).*?\.split\('\|'\)\)\)""".toRegex()
                val packedJs = packedRegex.find(text)?.value
                if (packedJs != null) {
                    
                    // FIX 3: Sửa cách gọi JsUnpacker.
                    // Hàm static unpackAndCombine không tồn tại, dùng constructor JsUnpacker(string).unpack()
                    val unpacked = JsUnpacker(packedJs).unpack() ?: ""
                    
                    val fileMatch = """file:"([^"]+)"""".toRegex().find(unpacked)
                    val m3u8 = fileMatch?.groupValues?.get(1)
                    
                    if (m3u8 != null) {
                        callback.invoke(
                            newExtractorLink(name, "Closeload (Packed)", m3u8, ExtractorLinkType.M3U8) {
                                this.referer = url
                                this.quality = Qualities.Unknown.value
                            }
                        )
                    }
                }
            }
        } catch (e: Exception) { Log.e(TAG, "Closeload Error", e) }
    }

    private fun fixUrl(url: String): String {
        if (url.isEmpty()) return ""
        if (url.startsWith("http")) return url
        val cleanUrl = if (url.startsWith("/")) url else "/$url"
        return "$mainUrl$cleanUrl"
    }
}
