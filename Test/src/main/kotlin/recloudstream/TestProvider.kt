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

    // Metadata (JSON-LD)
    data class LdJson(
        val name: String?,
        val description: String?,
        val image: Any?,
        val dateCreated: String?,
        val genre: List<String>?,
        val aggregateRating: LdRating?
    )
    data class LdRating(val ratingValue: String?)

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

    // --- LOAD (FIXED THUMBNAIL) ---
    override suspend fun load(url: String): LoadResponse {
        println("$TAG: Loading Details for: $url")
        val response = app.get(url).text
        val isTv = url.contains("/tv/") || url.contains("season")

        var title = "Unknown"
        var description: String? = null
        var poster: String? = null
        var year: Int? = null
        var ratingValue: Double? = null
        var tags: List<String>? = null
        
        // 1. Metadata Extraction
        
        // Cách 1: JSON-LD (Ưu tiên)
        val ldRegex = """\{"@context":"https://schema\.org".*?"@type":"(Movie|TVSeries|VideoObject)".*?\}""".toRegex()
        val ldMatch = ldRegex.find(response)
        if (ldMatch != null) {
            try {
                var jsonStr = ldMatch.value
                if (jsonStr.contains("</script>")) jsonStr = jsonStr.substringBefore("</script>")
                jsonStr = jsonStr.replace("\\\"", "\"").replace("\\\\", "\\")
                
                val ldData = parseJson<LdJson>(jsonStr)
                title = ldData.name ?: title
                description = ldData.description
                poster = if (ldData.image is List<*>) (ldData.image as List<*>).firstOrNull()?.toString() else ldData.image?.toString()
                year = ldData.dateCreated?.take(4)?.toIntOrNull()
                tags = ldData.genre
                ratingValue = ldData.aggregateRating?.ratingValue?.toDoubleOrNull()
            } catch (e: Exception) { }
        }

        // Cách 2: Regex quét "posterPath" (Key đặc trưng của Next.js data trên RidoMovies)
        // Pattern tìm: "posterPath":"/uploads/..." hoặc \"posterPath\":\"\/uploads\/...\"
        if (poster.isNullOrEmpty()) {
            val posterRegex = """\\?"posterPath\\?":\\?"([^"]+)\\?"""".toRegex()
            val match = posterRegex.find(response)
            if (match != null) {
                // Unescape đường dẫn (VD: \/uploads\/... -> /uploads/...)
                val rawPath = match.groupValues[1].replace("\\/", "/")
                poster = fixUrl(rawPath)
            }
        }
        
        // Cách 3: Regex quét "og:image"
        if (poster.isNullOrEmpty()) {
            val ogImgRegex = """content=["\\]+(https:\/\/ridomovies\.tv\/.*?\.(?:jpg|png|webp))["\\]+""".toRegex()
            poster = ogImgRegex.find(response)?.groupValues?.get(1)
        }

        // Title Fallback
        if (title == "Unknown") {
            val titleRegex = """\\?"title\\?":\\?"([^"]+)\\?"""".toRegex() // Tìm key "title":"Name"
            // Bỏ qua title đầu tiên thường là "Ridomovies Official"
            val matches = titleRegex.findAll(response).map { it.groupValues[1] }.toList()
            // Lấy title dài nhất hoặc title thứ 2 (thường là tên phim)
            title = matches.firstOrNull { it != "Ridomovies Official" && it != "Unknown" } ?: "Unknown"
        }

        poster = fixUrl(poster ?: "")
        println("$TAG: Final Metadata -> Title: $title, Poster: $poster")

        // 2. Recommendations
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

        // 3. Episodes (Lấy ID cho loadLinks)
        if (isTv) {
            val episodes = mutableListOf<Episode>()
            // Regex quét JSON chứa ID tập phim
            val epRegex = """\{"id":"(\d+)","slug":"([^"]+)".*?"episodeNumber":(\d+).*?"title":"([^"]*)".*?"fullSlug":"([^"]+)".*?"releaseDate":"([^"]*)"""".toRegex()
            val cleanResponse = response.replace("\\\"", "\"")
            
            epRegex.findAll(cleanResponse).forEach { match ->
                val epId = match.groupValues[1]
                val epNum = match.groupValues[3].toIntOrNull()
                val epTitle = match.groupValues[4]
                val epSlug = match.groupValues[5]
                val epDate = match.groupValues[6]
                val seasonNum = Regex("""season-(\d+)""").find(epSlug)?.groupValues?.get(1)?.toIntOrNull() ?: 1

                if (epNum != null) {
                    episodes.add(newEpisode(epSlug) {
                        this.season = seasonNum
                        this.episode = epNum
                        this.name = if (epTitle.isNotEmpty()) epTitle else "Episode $epNum"
                        // Lưu API URL chứa ID
                        this.data = "$mainUrl/api/episodes/$epId" 
                        this.addDate(epDate)
                    })
                }
            }
            
            val uniqueEpisodes = episodes.distinctBy { it.data }.sortedWith(compareBy({ it.season }, { it.episode }))
            return newTvSeriesLoadResponse(title, url, TvType.TvSeries, uniqueEpisodes) {
                this.posterUrl = poster
                this.year = year
                this.plot = description
                this.tags = tags
                this.score = ratingValue?.let { Score.from10(it) }
                this.recommendations = recommendations.distinctBy { it.url }
            }
        } else {
            // Movie: Convert URL web sang API URL
            // Web: https://ridomovies.tv/movies/speed-faster
            // API: https://ridomovies.tv/api/movies/speed-faster
            val movieApiUrl = url.replace("$mainUrl/movies/", "$mainUrl/api/movies/")
            
            return newMovieLoadResponse(title, url, TvType.Movie, movieApiUrl) {
                this.posterUrl = poster
                this.year = year
                this.plot = description
                this.tags = tags
                this.score = ratingValue?.let { Score.from10(it) }
                this.recommendations = recommendations.distinctBy { it.url }
            }
        }
    }

    // --- LOAD LINKS (FIXED HEADERS & FALLBACK) ---
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        println("$TAG: Loading Links -> $data")
        
        // Xác định Referer chính xác (Quan trọng cho API)
        // Nếu data là /api/episodes/... -> Referer phải là link tập phim trên web
        // Tuy nhiên, ta không có link web ở đây dễ dàng. Thử dùng referer chung.
        val referer = "$mainUrl/" 

        try {
            val headers = mapOf("Referer" to referer, "X-Requested-With" to "XMLHttpRequest")
            val jsonText = app.get(data, headers = headers).text
            
            val jsonResponse = parseJson<ApiLinkResponse>(jsonText)
            val sources = jsonResponse.data ?: emptyList()

            if (sources.isNotEmpty()) {
                sources.forEach { item ->
                    val iframeHtml = item.url ?: return@forEach
                    val srcMatch = """data-src=["'](.*?)["']""".toRegex().find(iframeHtml)
                    val src = srcMatch?.groupValues?.get(1)
                    if (!src.isNullOrEmpty()) {
                        processSource(src, subtitleCallback, callback)
                    }
                }
                return true
            }
        } catch (e: Exception) {
            println("$TAG: API Link Error: ${e.message}")
        }

        // Fallback: Nếu API lỗi, thử parse HTML trang gốc (chỉ áp dụng nếu data không phải là API URL)
        if (!data.contains("/api/")) {
            try {
                val html = app.get(data).text
                val srcMatch = """data-src=["'](.*?)["']""".toRegex().findAll(html)
                srcMatch.forEach { m ->
                    val src = m.groupValues[1]
                    processSource(src, subtitleCallback, callback)
                }
            } catch (e: Exception) { }
        }
        return true
    }

    private suspend fun processSource(
        src: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        println("$TAG: Found Source: $src")
        if (src.contains("ridoo") || src.contains("closeload")) {
            invokeRidoo(src, subtitleCallback, callback)
        } else {
            loadExtractor(src, subtitleCallback, callback)
        }
    }

    // --- RIDOO EXTRACTOR (NEW) ---
    private suspend fun invokeRidoo(
        url: String, 
        subtitleCallback: (SubtitleFile) -> Unit, 
        callback: (ExtractorLink) -> Unit
    ) {
        val headers = mapOf(
            "Referer" to "https://ridomovies.tv/",
            "User-Agent" to "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/127.0.0.0 Mobile Safari/537.36",
            "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
            "Upgrade-Insecure-Requests" to "1",
            "Sec-Fetch-Dest" to "iframe",
            "Sec-Fetch-Mode" to "navigate",
            "Sec-Fetch-Site" to "cross-site"
        )

        try {
            val response = app.get(url, headers = headers).text
            
            // 1. Tìm trong JSON-LD (ContentUrl) - Ưu tiên
            val contentUrlRegex = """["']contentUrl["']:\s*["']([^"']+)["']""".toRegex()
            var m3u8Url = contentUrlRegex.find(response)?.groupValues?.get(1)
            
            // 2. Tìm trong JWPlayer config (file:)
            if (m3u8Url == null) {
                val fileRegex = """file\s*:\s*["'](https:.*?master\.m3u8.*?)["']""".toRegex()
                m3u8Url = fileRegex.find(response)?.groupValues?.get(1)
            }

            if (m3u8Url != null) {
                callback(
                    newExtractorLink(
                        source = "Ridoo",
                        name = "Ridoo",
                        url = m3u8Url,
                        type = ExtractorLinkType.M3U8,
                        initializer = {
                            this.referer = "https://ridoo.net/" 
                        }
                    )
                )
            }
        } catch (e: Exception) {
            println("$TAG: Ridoo Error: ${e.message}")
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
