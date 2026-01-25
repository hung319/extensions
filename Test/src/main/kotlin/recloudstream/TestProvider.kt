package recloudstream

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import com.lagradost.cloudstream3.Score
import org.jsoup.Jsoup
import android.util.Log
import kotlin.random.Random

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

    data class ApiLinkResponse(val data: List<ApiLinkItem>?)
    data class ApiLinkItem(val url: String?)

    // Metadata (JSON-LD)
    data class LdJson(
        val name: String?,
        val description: String?,
        val image: String?,
        val dateCreated: String?,
        val genre: List<String>?,
        val actor: List<LdPerson>?,
        val aggregateRating: LdRating?
    )
    data class LdPerson(val name: String?)
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
                
                val href = "$mainUrl/$slug"
                val poster = fixUrl(item.posterPath ?: "")
                
                val type = if (rawType == "tv-series" || rawType == "tv") TvType.TvSeries else TvType.Movie
                val year = item.releaseYear?.toIntOrNull() ?: item.contentable?.releaseYear?.toIntOrNull()

                if (type == TvType.Movie) {
                    newMovieSearchResponse(title, href, type) {
                        this.posterUrl = poster
                        this.year = year
                    }
                } else {
                    newTvSeriesSearchResponse(title, href, type) {
                        this.posterUrl = poster
                        this.year = year
                    }
                }
            }
            newHomePageResponse(request.name, homeList, true)
        } catch (e: Exception) {
            e.printStackTrace()
            newHomePageResponse(request.name, emptyList())
        }
    }

    // --- SEARCH ---
    data class SearchRoot(val data: SearchContainer?)
    data class SearchContainer(val items: List<ApiItem>?)

    override suspend fun search(query: String): List<SearchResponse> {
        val url = "$mainUrl/core/api/search?q=$query"
        val headers = mapOf("Referer" to "$mainUrl/")
        
        return try {
            val text = app.get(url, headers = headers).text
            val json = parseJson<SearchRoot>(text)
            val items = json.data?.items ?: return emptyList()

            items.mapNotNull { item ->
                val title = item.title ?: item.content?.title ?: return@mapNotNull null
                val slug = item.fullSlug ?: item.content?.fullSlug ?: return@mapNotNull null
                val rawType = item.type ?: item.content?.type

                val href = "$mainUrl/$slug"
                val poster = fixUrl(item.posterPath ?: "")
                val type = if (rawType == "tv-series") TvType.TvSeries else TvType.Movie
                val year = item.releaseYear?.toIntOrNull() ?: item.contentable?.releaseYear?.toIntOrNull()

                if (type == TvType.Movie) {
                    newMovieSearchResponse(title, href, type) {
                        this.posterUrl = poster
                        this.year = year
                    }
                } else {
                    newTvSeriesSearchResponse(title, href, type) {
                        this.posterUrl = poster
                        this.year = year
                    }
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
            emptyList()
        }
    }

    // --- LOAD ---
    override suspend fun load(url: String): LoadResponse {
        val headers = mapOf(
            "rsc" to "1",
            "Referer" to "$mainUrl/"
        )
        
        val rawResponse = app.get(url, headers = headers).text
        val isTv = url.contains("/tv/") || url.contains("season")

        // 1. Clean response
        val cleanResponse = rawResponse.replace("\\\"", "\"").replace("\\n", " ")

        // Variables
        var title: String = "Unknown"
        var description: String? = null
        var poster: String = ""
        var year: Int? = null
        var ratingValue: Double? = null
        var tags: List<String>? = null
        var actors: List<ActorData>? = null

        // 2. Metadata Extraction (JSON-LD is best)
        val jsonLdRegex = """\{"@context":"https://schema.org".*?"@type":"(Movie|TVSeries)".*?\}""".toRegex()
        val jsonLdMatch = jsonLdRegex.find(cleanResponse)?.value

        if (jsonLdMatch != null) {
            try {
                val ldData = parseJson<LdJson>(jsonLdMatch)
                title = ldData.name ?: title
                description = ldData.description
                poster = fixUrl(ldData.image ?: "")
                year = ldData.dateCreated?.take(4)?.toIntOrNull()
                tags = ldData.genre
                ratingValue = ldData.aggregateRating?.ratingValue?.toDoubleOrNull()
                actors = ldData.actor?.mapNotNull { p -> p.name?.let { ActorData(Actor(it)) } }
            } catch (e: Exception) { e.printStackTrace() }
        }

        // Fallback Metadata
        if (title == "Unknown") {
            val titleRegex = """\"title\":\"([^\"]+?)\"""".toRegex()
            title = titleRegex.find(cleanResponse)?.groupValues?.get(1) ?: "Unknown"
        }
        if (poster.isEmpty()) {
            // Regex tìm ảnh trong block phim
            val posterRegex = """\"posterPath\":\"([^\"]+?)\"""".toRegex()
            val path = posterRegex.find(cleanResponse)?.groupValues?.get(1)
            poster = if (path != null) fixUrl(path) else ""
        }

        // 3. Episodes Extraction (BRUTE FORCE REGEX)
        val episodes = mutableListOf<Episode>()
        if (isTv) {
            // Log của bạn: "slug":"nemesis/season-1/episode-1",... "episodeNumber":1, "title":"..."
            // Ta sẽ quét tất cả các block có pattern của tập phim
            // Pattern: "episodeNumber":DIGIT ... "fullSlug":"STRING"
            // Hoặc: "slug":"STRING" ... "episodeNumber":DIGIT
            
            // Regex quét fullSlug chứa "season-" và "episode-"
            val epRegex = """\"fullSlug\":\"(tv\/[^\"]+?\/season-(\d+)\/episode-(\d+))\"""".toRegex()
            
            // Sử dụng Set để tránh trùng lặp do text stream lặp lại dữ liệu
            val addedEpisodes = mutableSetOf<String>()

            epRegex.findAll(cleanResponse).forEach { match ->
                val fullSlug = match.groupValues[1]
                val seasonNum = match.groupValues[2].toIntOrNull() ?: 1
                val episodeNum = match.groupValues[3].toIntOrNull() ?: 1
                
                if (addedEpisodes.add(fullSlug)) {
                    // Cố gắng tìm Title xung quanh fullSlug (cách nhau tối đa 200 ký tự)
                    // Đây là heuristic để lấy tên tập
                    val index = match.range.first
                    val searchWindow = cleanResponse.substring((index - 300).coerceAtLeast(0), (index + 300).coerceAtMost(cleanResponse.length))
                    
                    var epTitle = "Episode $episodeNum"
                    val titleMatch = """\"title\":\"([^\"]+?)\"""".toRegex().find(searchWindow)
                    if (titleMatch != null && !titleMatch.groupValues[1].contains("Episode")) {
                         epTitle = titleMatch.groupValues[1]
                    }
                    
                    // Lấy overview nếu có
                    val overviewMatch = """\"overview\":\"([^\"]+?)\"""".toRegex().find(searchWindow)
                    val epDesc = overviewMatch?.groupValues?.get(1)

                    episodes.add(newEpisode("$mainUrl/$fullSlug") {
                        this.season = seasonNum
                        this.episode = episodeNum
                        this.name = epTitle
                        this.description = epDesc // Fix: 'overview' -> 'description'
                        this.data = "$mainUrl/$fullSlug"
                    })
                }
            }
        }

        // 4. Recommendations (Fix đường dẫn ảnh & Logic)
        var recommendations = mutableListOf<SearchResponse>()
        try {
            // Tìm các link genre: href="/genre/..."
            val genreRegex = """href\":\"(\/genre\/[a-zA-Z0-9-]+)\"""".toRegex()
            val genres = genreRegex.findAll(cleanResponse)
                .map { it.groupValues[1] }
                .distinct()
                .toList()

            if (genres.isNotEmpty()) {
                val randomGenre = genres[Random.nextInt(genres.size)]
                val genreUrl = "$mainUrl$randomGenre"
                
                // Fetch Genre Page
                val genreResponse = app.get(genreUrl, headers = headers).text
                val cleanGenreResponse = genreResponse.replace("\\\"", "\"")
                
                // Parse Movies in Genre (Relaxed Regex)
                // Log: "originalTitle":"...","fullSlug":"movies/...","posterPath":"/posters/..."
                val recRegex = """\"originalTitle\":\"(.*?)\".*?\"fullSlug\":\"(.*?)\".*?\"posterPath\":\"(.*?)\"""".toRegex()
                
                recRegex.findAll(cleanGenreResponse).forEach { match ->
                    val rTitle = match.groupValues[1]
                    val rSlug = match.groupValues[2]
                    val rPosterPath = match.groupValues[3]
                    
                    if (!rSlug.contains(url)) { // Exclude current
                        val fullRecUrl = "$mainUrl/$rSlug"
                        val fullPosterUrl = if(rPosterPath.startsWith("http")) rPosterPath else "$mainUrl/uploads$rPosterPath"
                        
                        recommendations.add(newMovieSearchResponse(rTitle, fullRecUrl, TvType.Movie) {
                            this.posterUrl = fullPosterUrl
                        })
                    }
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        
        // Randomize và giới hạn số lượng
        val finalRecommendations = recommendations.distinctBy { it.url }.shuffled().take(10)

        if (isTv) {
            val uniqueEpisodes = episodes.distinctBy { it.data }.sortedWith(compareBy({ it.season }, { it.episode }))
            
            return newTvSeriesLoadResponse(title, url, TvType.TvSeries, uniqueEpisodes) {
                this.posterUrl = poster
                this.year = year
                this.plot = description
                this.tags = tags
                this.score = ratingValue?.let { Score.from10(it) }
                this.actors = actors
                this.recommendations = finalRecommendations
            }
        } else {
            return newMovieLoadResponse(title, url, TvType.Movie, url) {
                this.posterUrl = poster
                this.year = year
                this.plot = description
                this.tags = tags
                this.score = ratingValue?.let { Score.from10(it) }
                this.actors = actors
                this.recommendations = finalRecommendations
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
        Log.d(TAG, "loadLinks: Input -> $data")
        
        val apiUrl = if (data.contains("/movies/")) {
            data.replace("/movies/", "/api/movies/")
        } else if (data.contains("/tv/")) {
            data.replace("/tv/", "/api/tv/")
        } else {
            data
        }
        
        Log.d(TAG, "loadLinks: API -> $apiUrl")

        try {
            val headers = mapOf("Referer" to "$mainUrl/")
            val jsonText = app.get(apiUrl, headers = headers).text
            
            // Log response ngắn để debug nếu cần
            // Log.d(TAG, "Response sample: ${jsonText.take(100)}")

            // Regex 1: Tìm src trong iframe escaped
            val embedRegex = """src\\?":\\?"(https:.*?)(\\?.*?)?\\?"""".toRegex()
            embedRegex.findAll(jsonText).forEach { match ->
                 val url = match.groupValues[1].replace("\\", "")
                 if(!url.contains("google")) {
                    Log.d(TAG, "Found Link (Regex): $url")
                    loadExtractor(url, subtitleCallback, callback)
                 }
            }
            
            // Regex 2: Tìm data-src trong iframe HTML
            val dataSrcRegex = """data-src=\\?["'](https:.*?)(\\?.*?)?\\?["']""".toRegex()
            dataSrcRegex.findAll(jsonText).forEach { match ->
                val url = match.groupValues[1].replace("\\", "")
                if(!url.contains("google")) {
                    Log.d(TAG, "Found Link (Data-Src): $url")
                    loadExtractor(url, subtitleCallback, callback)
                }
            }

        } catch (e: Exception) {
            Log.e(TAG, "Error loadLinks: ${e.message}")
            e.printStackTrace()
        }
        return true
    }
    
    private fun fixUrl(url: String): String {
        return when {
            url.isEmpty() -> ""
            url.startsWith("http") -> url
            // Nếu url bắt đầu bằng /uploads, thêm domain
            url.startsWith("/uploads") -> "$mainUrl$url"
            // Nếu url bắt đầu bằng /, kiểm tra xem có phải uploads ngầm không (thường Rido là /uploads/...)
            url.startsWith("/") -> "$mainUrl$url"
            else -> "$mainUrl/$url"
        }
    }
    
    private fun String.capitalize(): String {
        return this.replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() }
    }
}
