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
    private val commonUserAgent = "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36"

    // --- DTOs ---
    data class RidoLinkData(
        val url: String,
        val title: String? = null,
        val poster: String? = null,
        val year: Int? = null
    )

    // DTO cho API trả về iframe
    data class RidoEpResponse(val data: List<RidoEpData>?)
    data class RidoEpData(val url: String?)

    // --- API STRUCTURES (Dùng cho Main Page/Search nếu cần parse JSON) ---
    data class ApiResponse(val data: ApiData?)
    data class ApiData(val items: List<ApiItem>?)
    data class ApiItem(
        val title: String?, val fullSlug: String?, val type: String?, 
        val posterPath: String?, val releaseYear: String?,
        val content: ApiNestedContent?, val contentable: ApiContentable?
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
            val headers = mapOf("Referer" to "$mainUrl/", "X-Requested-With" to "XMLHttpRequest", "Accept" to "application/json")
            val response = app.get(url, headers = headers)
            val json = parseJson<ApiResponse>(response.text)
            
            val homeList = json.data?.items?.mapNotNull { item ->
                val title = item.title ?: item.content?.title ?: return@mapNotNull null
                val slug = item.fullSlug ?: item.content?.fullSlug ?: return@mapNotNull null
                val rawType = item.type ?: item.content?.type
                val href = "$mainUrl/$slug" // Logic gốc
                val poster = fixUrl(item.posterPath ?: "")
                val type = if (rawType?.contains("tv") == true) TvType.TvSeries else TvType.Movie
                val year = item.releaseYear?.toIntOrNull() ?: item.contentable?.releaseYear?.toIntOrNull()

                // Logic Gốc: Đóng gói JSON
                val data = RidoLinkData(href, title, poster, year)
                newMovieSearchResponse(title, data.toJson(), type) {
                    this.posterUrl = poster
                    this.year = year
                }
            } ?: emptyList()
            newHomePageResponse(request.name, homeList, true)
        } catch (e: Exception) {
            newHomePageResponse(request.name, emptyList())
        }
    }

    // --- SEARCH ---
    data class SearchRoot(val data: SearchContainer?)
    data class SearchContainer(val items: List<ApiItem>?)

    override suspend fun search(query: String): List<SearchResponse> {
        val url = "$mainUrl/core/api/search?q=$query"
        return try {
            val response = app.get(url, headers = mapOf("Referer" to "$mainUrl/"))
            parseJson<SearchRoot>(response.text).data?.items?.mapNotNull { item ->
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

    // --- LOAD (LOGIC GỐC KHÔI PHỤC) ---
    override suspend fun load(url: String): LoadResponse {
        val linkData = tryParseJson<RidoLinkData>(url)
        val realUrl = linkData?.url ?: url
        
        var title = linkData?.title ?: "Unknown"
        var poster = linkData?.poster ?: ""
        var year = linkData?.year
        
        // Dùng header thường (không RSC) để lấy HTML -> Recommendations hoạt động tốt
        val headers = mapOf("Referer" to "$mainUrl/", "User-Agent" to commonUserAgent)
        val responseText = app.get(realUrl, headers = headers).text
        val isTv = realUrl.contains("/tv/") || realUrl.contains("season")

        val cleanResponse = responseText.replace("\\\"", "\"").replace("\\n", " ")

        // Metadata
        var description: String? = null
        var ratingValue: Double? = null
        
        // 1. Title/Poster from HTML (Backup if linkData missing)
        if (title == "Unknown") {
            val h1Regex = """\["\$","h1",null,\{"children":"(.*?)"\}\]""".toRegex()
            title = h1Regex.find(cleanResponse)?.groupValues?.get(1) 
                ?: """originalTitle":"(.*?)" """.toRegex().find(cleanResponse)?.groupValues?.get(1) 
                ?: "Unknown"
        }
        
        if (poster.isEmpty()) {
            val pMatch = """(https:[^"]*?\/uploads\/(?:posters|backdrops)\/[^"]*?\.(?:webp|jpg|png))""".toRegex().find(cleanResponse)
            poster = pMatch?.value?.let { fixUrl(it) } ?: ""
        }

        // 2. Description & Rating
        val textRegex = """text":"<p>(.*?)</p>""".toRegex()
        description = textRegex.find(cleanResponse)?.groupValues?.get(1)
        
        val ratingMatch = """ratingValue":([\d.]+)""".toRegex().find(cleanResponse)
        ratingValue = ratingMatch?.groupValues?.get(1)?.toDoubleOrNull()

        // 3. Episodes Logic (Từ RSC trong HTML)
        val episodes = mutableListOf<Episode>()
        val finalUrl: String

        if (isTv) {
            val epRegex = """\{"id":"(\d+)","slug":"([^"]*?\/season-(\d+)\/episode-(\d+)[^"]*)"""".toRegex()
            val addedIds = mutableSetOf<String>()
            
            epRegex.findAll(cleanResponse).forEach { match ->
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
            // Movie: Tìm ID trong HTML để tạo API Link
            val movieIdRegex = """postid":"(\d+)"""".toRegex()
            val movieId = movieIdRegex.find(cleanResponse)?.groupValues?.get(1)
            
            if (movieId != null) {
                finalUrl = "$mainUrl/api/movies/$movieId" 
            } else {
                val slug = realUrl.split("/").lastOrNull { it.isNotEmpty() }?.substringBefore("?") ?: ""
                finalUrl = "$mainUrl/api/movies/$slug"
            }
        }

        // 4. RECOMMENDATIONS (LOGIC GỐC)
        val recommendations = mutableListOf<SearchResponse>()
        try {
            val genreRegex = """href":"\/genre\/([a-zA-Z0-9-]+)"""".toRegex()
            val genres = genreRegex.findAll(cleanResponse)
                .map { it.groupValues[1] }
                .distinct()
                .filter { !it.contains("search") }
                .toList()

            if (genres.isNotEmpty()) {
                val randomGenre = genres[Random.nextInt(genres.size)]
                val genreUrl = "$mainUrl/genre/$randomGenre"
                val genreRes = app.get(genreUrl, headers = headers).text
                val cleanGenreRes = genreRes.replace("\\\"", "\"")
                
                val recRegex = """originalTitle":"(.*?)".*?fullSlug":"(.*?)".*?posterPath":"(.*?)"""".toRegex()
                recRegex.findAll(cleanGenreRes).take(10).forEach { m ->
                    val rTitle = m.groupValues[1]
                    val rSlug = m.groupValues[2]
                    val rPoster = fixUrl(m.groupValues[3])
                    val rUrl = "$mainUrl/$rSlug"
                    
                    if (!rUrl.contains(realUrl)) {
                        val recData = RidoLinkData(rUrl, rTitle, rPoster)
                        recommendations.add(newMovieSearchResponse(rTitle, recData.toJson(), TvType.Movie) {
                            this.posterUrl = rPoster
                        })
                    }
                }
            }
        } catch (e: Exception) { }

        val sortedEpisodes = episodes.sortedWith(compareBy({ it.season }, { it.episode }))

        return if (isTv) {
            newTvSeriesLoadResponse(title, realUrl, TvType.TvSeries, sortedEpisodes) {
                this.posterUrl = poster
                this.year = year
                this.plot = description
                this.score = ratingValue?.let { Score.from10(it) }
                this.recommendations = recommendations
            }
        } else {
            newMovieLoadResponse(title, realUrl, TvType.Movie, finalUrl) {
                this.posterUrl = poster
                this.year = year
                this.plot = description
                this.score = ratingValue?.let { Score.from10(it) }
                this.recommendations = recommendations
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
            
            // Parse JSON để lấy iframe HTML sạch
            val jsonResponse = tryParseJson<RidoEpResponse>(jsonText)
            val iframeHtml = jsonResponse?.data?.firstOrNull()?.url ?: ""
            
            // Regex lấy src sạch từ iframe
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

    // --- EXTRACTOR: CLOSELOAD (FIXED with DOT_MATCHES_ALL) ---
    private suspend fun extractCloseload(url: String, callback: (ExtractorLink) -> Unit) {
        try {
            val headers = mapOf("Referer" to "$mainUrl/", "User-Agent" to commonUserAgent)
            val text = app.get(url, headers = headers).text
            
            // FIX: Sử dụng (?s) để Regex bắt qua dòng mới
            val packedRegex = """(?s)eval\(function\(p,a,c,k,e,d\).*?\.split\('\|'\).*?\)""".toRegex()
            val match = packedRegex.find(text)
            
            if (match != null) {
                val unpacked = JsUnpacker(match.value).unpack() ?: ""
                
                // Tìm link master
                val fileMatch = """file\s*:\s*["']([^"']+)["']""".toRegex().find(unpacked)
                val masterUrl = fileMatch?.groupValues?.get(1)
                
                if (masterUrl != null && !masterUrl.contains("playmix.uno")) {
                    Log.d(TAG, "Closeload Stream: $masterUrl")
                    callback.invoke(
                        newExtractorLink(name, "Closeload", masterUrl, ExtractorLinkType.M3U8) {
                            this.referer = "https://closeload.top/"
                            this.quality = Qualities.Unknown.value
                        }
                    )
                }
            } else {
                Log.e(TAG, "Closeload: No Packed JS found")
            }
        } catch (e: Exception) { Log.e(TAG, "Closeload Err", e) }
    }

    // --- EXTRACTOR: RIDOO ---
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
