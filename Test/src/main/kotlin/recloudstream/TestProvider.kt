package recloudstream

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import com.lagradost.cloudstream3.utils.AppUtils.tryParseJson
import com.lagradost.cloudstream3.utils.AppUtils.toJson
import com.lagradost.cloudstream3.Score
import android.util.Base64
import android.util.Log
import java.nio.charset.Charset
import kotlin.random.Random

class RidoMoviesProvider : MainAPI() {
    override var mainUrl = "https://ridomovies.tv"
    override var name = "RidoMovies"
    override val hasMainPage = true
    override var lang = "en"
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)
    
    private val TAG = "RidoMovies"
    private val commonUserAgent = "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36"

    // --- DTOs & API ---
    data class RidoLinkData(val url: String, val title: String? = null, val poster: String? = null, val year: Int? = null, val type: String? = null)
    data class RidoEpResponse(val data: List<RidoEpData>?)
    data class RidoEpData(val url: String?)
    data class ApiResponse(val data: ApiData?)
    data class ApiData(val items: List<ApiItem>?)
    data class ApiItem(val title: String?, val fullSlug: String?, val type: String?, val posterPath: String?, val releaseYear: String?, val content: ApiNestedContent?, val contentable: ApiContentable?)
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
            val headers = mapOf("Referer" to "$mainUrl/", "X-Requested-With" to "XMLHttpRequest", "Accept" to "application/json")
            val response = app.get(url, headers = headers)
            val json = parseJson<ApiResponse>(response.text)
            val homeList = json.data?.items?.mapNotNull { item ->
                val title = item.title ?: item.content?.title ?: return@mapNotNull null
                val slug = item.fullSlug ?: item.content?.fullSlug ?: return@mapNotNull null
                val rawType = item.type ?: item.content?.type
                val href = fixUrl(slug)
                val poster = fixUrl(item.posterPath ?: "")
                val type = if (rawType?.contains("tv") == true) TvType.TvSeries else TvType.Movie
                val year = item.releaseYear?.toIntOrNull() ?: item.contentable?.releaseYear?.toIntOrNull()
                val data = RidoLinkData(href, title, poster, year, rawType)
                newMovieSearchResponse(title, data.toJson(), type) {
                    this.posterUrl = poster
                    this.year = year
                }
            } ?: emptyList()
            newHomePageResponse(request.name, homeList, true)
        } catch (e: Exception) { newHomePageResponse(request.name, emptyList()) }
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
                val data = RidoLinkData(fixUrl(slug), title, poster, year)
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
        val title = linkData?.title ?: "RidoMovies"
        val year = linkData?.year
        val poster = linkData?.poster
        
        val headers = mapOf("rsc" to "1", "Referer" to "$mainUrl/", "User-Agent" to commonUserAgent)
        val responseText = app.get(realUrl, headers = headers).text
        val isTv = realUrl.contains("/tv/") || linkData?.type?.contains("tv") == true
        val cleanText = responseText.replace("\\\"", "\"").replace("\\n", " ")

        val descRegex = """className":"post-overview","text":"(.*?)"""".toRegex()
        val rawDesc = descRegex.find(cleanText)?.groupValues?.get(1)
        val description = rawDesc?.replace("\\u003c", "<")?.replace("\\u003e", ">")?.replace(Regex("<.*?>"), "") 
        val ratingVal = """ratingValue":([\d.]+)""".toRegex().find(cleanText)?.groupValues?.get(1)?.toDoubleOrNull()

        // Recommendations (Logic Gốc)
        val recommendations = mutableListOf<SearchResponse>()
        try {
            val genreRegex = """href":"\/genre\/([a-zA-Z0-9-]+)"""".toRegex()
            val genres = genreRegex.findAll(cleanText).map { it.groupValues[1] }.distinct().filter { !it.contains("search") }.toList()
            if (genres.isNotEmpty()) {
                val randomGenre = genres[Random.nextInt(genres.size)]
                val genreUrl = "$mainUrl/genre/$randomGenre"
                val genreRes = app.get(genreUrl, headers = mapOf("Referer" to "$mainUrl/")).text
                val cleanGenreRes = genreRes.replace("\\\"", "\"")
                val recRegex = """originalTitle":"(.*?)".*?fullSlug":"(.*?)".*?posterPath":"(.*?)"""".toRegex()
                recRegex.findAll(cleanGenreRes).take(10).forEach { m ->
                    val rTitle = m.groupValues[1]; val rSlug = m.groupValues[2]; val rPoster = fixUrl(m.groupValues[3]); val rUrl = fixUrl(rSlug)
                    if (!rUrl.contains(realUrl)) {
                        val recData = RidoLinkData(rUrl, rTitle, rPoster)
                        recommendations.add(newMovieSearchResponse(rTitle, recData.toJson(), TvType.Movie) { this.posterUrl = rPoster })
                    }
                }
            }
        } catch (e: Exception) { }

        val episodes = mutableListOf<Episode>()
        val finalUrl: String
        if (isTv) {
            val epRegex = """\{"id":"(\d+)","slug":"([^"]*?\/season-(\d+)\/episode-(\d+)[^"]*)"""".toRegex()
            val addedIds = mutableSetOf<String>()
            epRegex.findAll(cleanText).forEach { match ->
                val epId = match.groupValues[1]; val season = match.groupValues[3].toIntOrNull() ?: 1; val episode = match.groupValues[4].toIntOrNull() ?: 1
                if (addedIds.add(epId)) {
                    val apiLink = "$mainUrl/api/episodes/$epId"
                    episodes.add(newEpisode(apiLink) { this.season = season; this.episode = episode; this.name = "Episode $episode"; this.data = apiLink })
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
                this.posterUrl = poster; this.year = year; this.plot = description
                this.score = ratingVal?.let { Score.from10(it) }; this.recommendations = recommendations
            }
        } else {
            newMovieLoadResponse(title, realUrl, TvType.Movie, finalUrl) {
                this.posterUrl = poster; this.year = year; this.plot = description
                this.score = ratingVal?.let { Score.from10(it) }; this.recommendations = recommendations
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
        try {
            val headers = mapOf("Authority" to "ridomovies.tv", "X-Requested-With" to "XMLHttpRequest", "Referer" to "$mainUrl/")
            val jsonText = app.get(data, headers = headers).text
            val jsonResponse = tryParseJson<RidoEpResponse>(jsonText)
            val iframeHtml = jsonResponse?.data?.firstOrNull()?.url ?: ""
            val srcRegex = """data-src=["'](https:.*?)["']""".toRegex()
            val embedUrl = srcRegex.find(iframeHtml)?.groupValues?.get(1)
            
            if (embedUrl != null) {
                Log.d(TAG, "Embed: $embedUrl")
                if (embedUrl.contains("closeload")) {
                    extractCloseload(embedUrl, callback)
                } else if (embedUrl.contains("ridoo")) {
                    extractRidoo(embedUrl, callback)
                } else {
                    loadExtractor(embedUrl, subtitleCallback, callback)
                }
            }
        } catch (e: Exception) { Log.e(TAG, "Err LoadLinks", e) }
        return true
    }

    // --- EXTRACTOR: CLOSELOAD (FIXED DECRYPTION) ---
    private suspend fun extractCloseload(url: String, callback: (ExtractorLink) -> Unit) {
        try {
            val headers = mapOf("Referer" to "$mainUrl/", "User-Agent" to commonUserAgent)
            val text = app.get(url, headers = headers).text
            
            val packedRegex = """(?s)eval\(function\(p,a,c,k,e,d\).*?\.split\('\|'\).*?\)""".toRegex()
            val match = packedRegex.find(text)
            
            if (match != null) {
                val unpacked = JsUnpacker(match.value).unpack() ?: ""
                
                val keyRegex = """(\d+)%\(i\+5\)""".toRegex()
                val key = keyRegex.find(unpacked)?.groupValues?.get(1)?.toLongOrNull() ?: 399756995L
                
                val arrayRegex = """var\s+\w+\s*=\s*\w+\(\[(.*?)\]\)""".toRegex()
                val arrayRaw = arrayRegex.find(unpacked)?.groupValues?.get(1)
                
                if (arrayRaw != null) {
                    val masterUrl = decryptCloseload(arrayRaw, key)
                    
                    if (masterUrl != null && masterUrl.startsWith("http")) {
                        Log.d(TAG, "Closeload Stream: $masterUrl") // URL sẽ chuẩn ở đây
                        callback.invoke(
                            newExtractorLink(name, "Closeload", masterUrl, ExtractorLinkType.M3U8) {
                                this.referer = "https://closeload.top/"
                                this.quality = Qualities.Unknown.value
                            }
                        )
                    } else {
                        Log.e(TAG, "Decryption produced invalid URL: $masterUrl")
                    }
                }
            }
        } catch (e: Exception) { Log.e(TAG, "Closeload Err", e) }
    }

    // --- THUẬT TOÁN GIẢI MÃ (FIX KÝ TỰ LẠ) ---
    private fun decryptCloseload(input: String, key: Long): String? {
        try {
            // 1. Clean & Reverse
            val joined = input.replace("\"", "").replace(",", "")
            val reversed = joined.reversed()
            
            // 2. Decode Base64 lần 1 (ra ByteArray)
            val b64_1 = Base64.decode(reversed, Base64.DEFAULT)
            
            // QUAN TRỌNG: Chuyển ByteArray thành String 1-1 (ISO_8859_1) để giữ nguyên giá trị byte
            // Không được dùng UTF-8 vì sẽ làm hỏng các byte > 127
            val b64_1_str = String(b64_1, Charsets.ISO_8859_1)
            
            // 3. Decode Base64 lần 2 (ra ByteArray cuối cùng để tính toán)
            val b64_2 = Base64.decode(b64_1_str, Base64.DEFAULT)
            
            val sb = StringBuilder()
            for (i in b64_2.indices) {
                // QUAN TRỌNG: Ép kiểu Byte (có dấu) thành Int (không dấu 0-255)
                val charCode = b64_2[i].toInt() and 0xFF
                
                val shift = (key % (i + 5)).toInt()
                val result = (charCode - shift + 256) % 256
                sb.append(result.toChar())
            }
            return sb.toString()
        } catch (e: Exception) {
            Log.e(TAG, "Decryption Failed", e)
            return null
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
        } catch (e: Exception) { Log.e(TAG, "Ridoo Err", e) }
    }

    private fun fixUrl(url: String): String {
        if (url.isEmpty()) return ""
        if (url.startsWith("http")) return url
        val cleanUrl = if (url.startsWith("/")) url else "/$url"
        return "$mainUrl$cleanUrl"
    }
}
