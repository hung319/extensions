package recloudstream

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import com.lagradost.cloudstream3.Score
import org.jsoup.Jsoup

class RidoMoviesProvider : MainAPI() {
    override var mainUrl = "https://ridomovies.tv"
    override var name = "RidoMovies"
    override val hasMainPage = true
    override var lang = "en"
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)
    
    private val TAG = "RidoMovies"

    // --- API DATA CLASSES ---
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

    data class ApiNestedContent(
        val title: String?,
        val fullSlug: String?,
        val type: String?
    )

    data class ApiContentable(val releaseYear: String?, val overview: String?)

    // Link API Response
    data class ApiLinkResponse(val data: List<ApiLinkItem>?)
    data class ApiLinkItem(val url: String?)

    // --- MAIN PAGE ---
    override val mainPage = mainPageOf(
        "$mainUrl/core/api/movies/latest?page%5Bnumber%5D=" to "Latest Movies",
        "$mainUrl/core/api/series/latest?page%5Bnumber%5D=" to "Latest TV Series"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = request.data + page
        val headers = mapOf(
            "Referer" to "$mainUrl/",
            "X-Requested-With" to "XMLHttpRequest",
            "Accept" to "application/json"
        )

        return try {
            val response = app.get(url, headers = headers)
            val json = parseJson<ApiResponse>(response.text)
            val items = json.data?.items ?: emptyList()

            val homeList = items.mapNotNull { item ->
                val title = item.title ?: item.content?.title ?: return@mapNotNull null
                val slug = item.fullSlug ?: item.content?.fullSlug ?: return@mapNotNull null
                val rawType = item.type ?: item.content?.type
                val poster = fixUrl(item.posterPath ?: "")
                val type = if (rawType?.contains("tv") == true) TvType.TvSeries else TvType.Movie
                
                newMovieSearchResponse(title, "$mainUrl/$slug", type) {
                    this.posterUrl = poster
                }
            }
            newHomePageResponse(request.name, homeList, true)
        } catch (e: Exception) {
            newHomePageResponse(request.name, emptyList())
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val url = "$mainUrl/core/api/search?q=$query"
        val headers = mapOf("Referer" to "$mainUrl/")
        return try {
            val text = app.get(url, headers = headers).text
            val json = parseJson<ApiResponse>(text)
            val items = json.data?.items ?: return emptyList()

            items.mapNotNull { item ->
                val title = item.title ?: item.content?.title ?: return@mapNotNull null
                val slug = item.fullSlug ?: item.content?.fullSlug ?: return@mapNotNull null
                val poster = fixUrl(item.posterPath ?: "")
                val rawType = item.type ?: item.content?.type
                val type = if (rawType?.contains("tv") == true) TvType.TvSeries else TvType.Movie

                newMovieSearchResponse(title, "$mainUrl/$slug", type) {
                    this.posterUrl = poster
                }
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    // --- LOAD (FIXED THUMBNAIL & DESCRIPTION VIA REGEX) ---
    override suspend fun load(url: String): LoadResponse {
        val response = app.get(url).text
        val isTv = url.contains("/tv/") || url.contains("season")

        // Sử dụng Regex để quét dữ liệu từ RSC payload (bất chấp định dạng JSON lỗi)
        // 1. Title: Tìm "title":"..." hoặc og:title
        var title = """\\?"title\\?":\\?"([^"]+)\\?"""".toRegex().find(response)?.groupValues?.get(1)
        if (title == null || title == "Ridomovies Official") {
             title = """property=["\\]+og:title["\\]+ content=["\\]+(.*?)["\\]+""".toRegex().find(response)?.groupValues?.get(1)
        }
        title = title?.replace("Watch ", "")?.replace(" Online Free", "") ?: "Unknown"

        // 2. Poster: Tìm "posterPath":"..." (Key đặc trưng của Rido)
        var poster = """\\?"posterPath\\?":\\?"([^"]+)\\?"""".toRegex().find(response)?.groupValues?.get(1)
        // Unescape: \/uploads\/... -> /uploads/...
        poster = poster?.replace("\\/", "/")
        poster = fixUrl(poster ?: "")

        // 3. Description (Overview): Tìm "overview":"..."
        var description = """\\?"overview\\?":\\?"([^"]+)\\?"""".toRegex().find(response)?.groupValues?.get(1)
        
        // 4. Rating & Year
        val year = """\\?"releaseYear\\?":\\?"(\d+)\\?"""".toRegex().find(response)?.groupValues?.get(1)?.toIntOrNull()
        val ratingValue = """\\?"imdbRating\\?":([\d.]+)""".toRegex().find(response)?.groupValues?.get(1)?.toDoubleOrNull()

        // 5. Episodes (Lấy ID & Slug)
        val episodes = mutableListOf<Episode>()
        if (isTv) {
            // Regex quét từng block tập phim trong JSON RSC
            // Cần bắt: id (để gọi API), episodeNumber, fullSlug
            val epRegex = """\{"id":"(\d+)","slug":"[^"]+".*?"episodeNumber":(\d+).*?"title":"([^"]*)".*?"fullSlug":"([^"]+)".*?"releaseDate":"([^"]*)"""".toRegex()
            
            // Clean response nhẹ để regex chạy mượt hơn
            val cleanResponse = response.replace("\\\"", "\"")
            
            epRegex.findAll(cleanResponse).forEach { match ->
                val epId = match.groupValues[1]
                val epNum = match.groupValues[2].toIntOrNull()
                val epTitle = match.groupValues[3]
                val epSlug = match.groupValues[4]
                val epDate = match.groupValues[5]
                
                val seasonNum = Regex("""season-(\d+)""").find(epSlug)?.groupValues?.get(1)?.toIntOrNull() ?: 1

                if (epNum != null) {
                    episodes.add(newEpisode(epSlug) {
                        this.season = seasonNum
                        this.episode = epNum
                        this.name = if (epTitle.isNotEmpty()) epTitle else "Episode $epNum"
                        // Lưu API URL chứa ID để loadLinks gọi
                        this.data = "$mainUrl/api/episodes/$epId" 
                        this.addDate(epDate)
                    })
                }
            }
        }

        // 6. Recommendations
        val recommendations = mutableListOf<SearchResponse>()
        val recRegex = """["']\/(movies|tv)\/([a-zA-Z0-9-]+)["']""".toRegex()
        recRegex.findAll(response).forEach { m ->
            val typeStr = m.groupValues[1]
            val slug = m.groupValues[2]
            if (!url.contains(slug) && !slug.contains("genre")) {
                val recName = slug.replace("-", " ").split(" ").joinToString(" ") { it.replaceFirstChar { c -> c.uppercase() } }
                val recType = if (typeStr == "tv") TvType.TvSeries else TvType.Movie
                recommendations.add(newMovieSearchResponse(recName, "$mainUrl/$typeStr/$slug", recType))
            }
        }

        val uniqueEpisodes = episodes.distinctBy { it.data }.sortedWith(compareBy({ it.season }, { it.episode }))

        // Trả về kết quả
        return if (isTv) {
            newTvSeriesLoadResponse(title, url, TvType.TvSeries, uniqueEpisodes) {
                this.posterUrl = poster
                this.year = year
                this.plot = description
                this.score = ratingValue?.let { Score.from10(it) }
                this.recommendations = recommendations.distinctBy { it.url }
            }
        } else {
            // Với Movie, convert URL web sang API URL cho loadLinks
            val movieApiUrl = url.replace("$mainUrl/movies/", "$mainUrl/api/movies/")
            newMovieLoadResponse(title, url, TvType.Movie, movieApiUrl) {
                this.posterUrl = poster
                this.year = year
                this.plot = description
                this.score = ratingValue?.let { Score.from10(it) }
                this.recommendations = recommendations.distinctBy { it.url }
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
        // data ở đây là API URL: /api/episodes/{id} hoặc /api/movies/{slug}
        // Hoặc URL web nếu fallback
        val apiUrl = if (!data.contains("/api/")) {
             if (data.contains("/movies/")) data.replace("/movies/", "/api/movies/")
             else data.replace("/tv/", "/api/tv/")
        } else data

        try {
            val headers = mapOf("Referer" to "$mainUrl/", "X-Requested-With" to "XMLHttpRequest")
            val jsonText = app.get(apiUrl, headers = headers).text
            
            val jsonResponse = parseJson<ApiLinkResponse>(jsonText)
            val sources = jsonResponse.data ?: emptyList()

            sources.forEach { item ->
                val iframeHtml = item.url ?: return@forEach
                // Regex tìm data-src trong iframe
                val srcMatch = """data-src=["'](.*?)["']""".toRegex().find(iframeHtml)
                val src = srcMatch?.groupValues?.get(1)
                
                if (!src.isNullOrEmpty()) {
                    if (src.contains("closeload") || src.contains("ridoo")) {
                        invokeRidooCloseload(src, subtitleCallback, callback)
                    } else {
                        loadExtractor(src, subtitleCallback, callback)
                    }
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return true
    }

    // --- RIDOO & CLOSELOAD EXTRACTOR ---
    private suspend fun invokeRidooCloseload(
        url: String, 
        subtitleCallback: (SubtitleFile) -> Unit, 
        callback: (ExtractorLink) -> Unit
    ) {
        val headers = mapOf(
            "Referer" to "https://ridomovies.tv/",
            "User-Agent" to "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/127.0.0.0 Mobile Safari/537.36",
            "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8",
            "Upgrade-Insecure-Requests" to "1",
            "Sec-Fetch-Dest" to "iframe",
            "Sec-Fetch-Mode" to "navigate",
            "Sec-Fetch-Site" to "cross-site"
        )

        try {
            val response = app.get(url, headers = headers).text
            var m3u8Url: String? = null
            
            // 1. Tìm trong JSON-LD (Dành cho Closeload)
            // "contentUrl": "https://..."
            val contentUrlRegex = """["']contentUrl["']:\s*["']([^"']+)["']""".toRegex()
            m3u8Url = contentUrlRegex.find(response)?.groupValues?.get(1)
            
            // 2. Tìm trong JWPlayer config (Dành cho Ridoo)
            // file:"https://..."
            if (m3u8Url == null) {
                val fileRegex = """file\s*:\s*["'](https:.*?master\.m3u8.*?)["']""".toRegex()
                m3u8Url = fileRegex.find(response)?.groupValues?.get(1)
            }

            if (m3u8Url != null) {
                val sourceName = if (url.contains("closeload")) "Closeload" else "Ridoo"
                // Xác định domain referer cho request m3u8
                val refUrl = if (url.contains("closeload")) "https://closeload.top/" else "https://ridoo.net/"

                // Sử dụng newExtractorLink
                callback(
                    newExtractorLink(
                        source = sourceName,
                        name = sourceName,
                        url = m3u8Url,
                        type = ExtractorLinkType.M3U8
                    ) {
                        this.referer = refUrl
                    }
                )
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
    
    private fun fixUrl(url: String): String {
        return when {
            url.isEmpty() -> ""
            url.startsWith("http") -> url
            else -> "$mainUrl$url"
        }
    }
}
