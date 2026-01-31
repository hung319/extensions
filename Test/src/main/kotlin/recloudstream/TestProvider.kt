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
        val year: Int? = null,
        val type: String? = null // Thêm type để xử lý đúng ở Load
    )

    data class RidoEpResponse(val data: List<RidoEpData>?)
    data class RidoEpData(val url: String?)

    // --- MAIN PAGE API ---
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
                val href = "$mainUrl/$slug" // Giữ URL gốc
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
        } catch (e: Exception) {
            newHomePageResponse(request.name, emptyList())
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val url = "$mainUrl/core/api/search?q=$query"
        return try {
            val response = app.get(url, headers = mapOf("Referer" to "$mainUrl/"))
            // Cần parse SearchRoot giống file gốc nếu cấu trúc khác
            // Ở đây giả định cấu trúc giống MainPage cho gọn
            val text = response.text
            // Logic parse search (tùy chỉnh nếu API search khác)
            parseJson<ApiResponse>(text).data?.items?.mapNotNull { item ->
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

    // --- LOAD (LOGIC GỐC + FIX DESCRIPTION) ---
    override suspend fun load(url: String): LoadResponse {
        val linkData = tryParseJson<RidoLinkData>(url)
        val realUrl = linkData?.url ?: url
        
        var title = linkData?.title ?: "Unknown"
        var poster = linkData?.poster ?: ""
        var year = linkData?.year
        
        // Header thường để lấy HTML cho Recs, nhưng Header RSC để lấy ID tập
        // Ta dùng header thường, và parse RSC từ bên trong HTML
        val headers = mapOf("Referer" to "$mainUrl/", "User-Agent" to commonUserAgent)
        val responseText = app.get(realUrl, headers = headers).text
        val isTv = realUrl.contains("/tv/") || (linkData?.type?.contains("tv") == true)

        val cleanResponse = responseText.replace("\\\"", "\"").replace("\\n", " ")

        // --- 1. METADATA (FIX DESCRIPTION) ---
        var description: String? = null
        
        // Cách 1: RSC Payload (Thường chứa full description)
        val rscDescRegex = """className":"post-overview","text":"(.*?)"""".toRegex()
        val rscMatch = rscDescRegex.find(cleanResponse)?.groupValues?.get(1)
        if (rscMatch != null) {
            description = rscMatch.replace(Regex("<.*?>"), "") // Xóa thẻ HTML <p>
            Log.d(TAG, "Got Description from RSC")
        }

        // Cách 2: Logic gốc (HTML Paragraph)
        if (description.isNullOrEmpty()) {
            val textRegex = """text":"<p>(.*?)</p>""".toRegex()
            description = textRegex.find(cleanResponse)?.groupValues?.get(1)
        }

        // Cách 3: Meta Tag (Fallback cuối cùng)
        if (description.isNullOrEmpty()) {
            val metaRegex = """name="description" content="(.*?)"""".toRegex()
            description = metaRegex.find(responseText)?.groupValues?.get(1)
        }

        val ratingMatch = """ratingValue":([\d.]+)""".toRegex().find(cleanResponse)
        val ratingValue = ratingMatch?.groupValues?.get(1)?.toDoubleOrNull()

        // --- 2. API GENERATION (Movie dùng Slug, TV dùng ID) ---
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
            // MOVIE FIX: Dùng SLUG
            val slug = realUrl.split("/").lastOrNull { it.isNotEmpty() }?.substringBefore("?") ?: ""
            finalUrl = "$mainUrl/api/movies/$slug"
            Log.d(TAG, "Movie API generated: $finalUrl")
        }

        // --- 3. RECOMMENDATIONS (LOGIC GỐC) ---
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
                val genreRes = app.get(genreUrl, headers = mapOf("Referer" to "$mainUrl/")).text
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
        } catch (e: Exception) { Log.e(TAG, "Rec Error: ${e.message}") }

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
            // Truyền finalUrl (API Link) vào data
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
        Log.d(TAG, "LoadLinks fetching: $data")
        try {
            val headers = mapOf("Authority" to "ridomovies.tv", "X-Requested-With" to "XMLHttpRequest", "Referer" to "$mainUrl/")
            val response = app.get(data, headers = headers)
            val jsonText = response.text
            
            // Log raw response để debug nếu API fail
            if (response.code != 200) {
                Log.e(TAG, "API Failed with code: ${response.code}")
                return false
            }

            val jsonResponse = tryParseJson<RidoEpResponse>(jsonText)
            val iframeHtml = jsonResponse?.data?.firstOrNull()?.url ?: ""
            
            val srcRegex = """data-src=["'](https:.*?)["']""".toRegex()
            val embedUrl = srcRegex.find(iframeHtml)?.groupValues?.get(1)
            
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
                Log.e(TAG, "Embed URL NOT found in JSON: ${jsonText.take(100)}")
            }
        } catch (e: Exception) { Log.e(TAG, "Err LoadLinks", e) }
        return true
    }

    // --- EXTRACTOR 1: CLOSELOAD (DEBUG LOGGING) ---
    private suspend fun extractCloseload(url: String, callback: (ExtractorLink) -> Unit) {
        try {
            val headers = mapOf("Referer" to "$mainUrl/", "User-Agent" to commonUserAgent)
            val response = app.get(url, headers = headers)
            val text = response.text
            
            Log.d(TAG, "Closeload Status: ${response.code}")
            // Log 500 ký tự đầu để xem có phải bị chặn Cloudflare không
            Log.d(TAG, "Closeload HTML Head: ${text.take(500)}")

            // Regex mạnh mẽ hơn, dùng DOT_MATCHES_ALL (?s)
            val packedRegex = """(?s)eval\(function\(p,a,c,k,e,d\).*?\.split\('\|'\).*?\)""".toRegex()
            val match = packedRegex.find(text)
            
            if (match != null) {
                Log.d(TAG, "Closeload: Packed JS FOUND")
                val packedJs = match.value
                val unpacked = JsUnpacker(packedJs).unpack() ?: ""
                
                // Tìm link file
                val fileMatch = """file\s*:\s*["']([^"']+)["']""".toRegex().find(unpacked)
                val masterUrl = fileMatch?.groupValues?.get(1)
                
                if (masterUrl != null && !masterUrl.contains("playmix.uno")) {
                    Log.d(TAG, "Closeload Stream URL: $masterUrl")
                    callback.invoke(
                        newExtractorLink(name, "Closeload", masterUrl, ExtractorLinkType.M3U8) {
                            this.referer = "https://closeload.top/"
                            this.quality = Qualities.Unknown.value
                        }
                    )
                } else {
                    Log.e(TAG, "Closeload: URL blocked or not found in unpacked JS")
                }
            } else {
                Log.e(TAG, "Closeload: No Packed JS found with regex (?s)")
            }
        } catch (e: Exception) { Log.e(TAG, "Closeload Err", e) }
    }

    // --- EXTRACTOR 2: RIDOO ---
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
