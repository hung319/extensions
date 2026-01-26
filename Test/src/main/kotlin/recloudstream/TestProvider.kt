package recloudstream

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import com.lagradost.cloudstream3.utils.AppUtils.tryParseJson
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

    data class ApiContentable(
        val releaseYear: String? = null, 
        val overview: String? = null
    )

    data class ApiNestedContent(
        val title: String?,
        val fullSlug: String?,
        val type: String?
    )

    data class ApiItem(
        val title: String? = null,
        val fullSlug: String? = null,
        val type: String? = null, 
        val posterPath: String? = null,
        val releaseYear: String? = null, 
        val content: ApiNestedContent? = null,
        val contentable: ApiContentable? = null 
    )

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

    // --- LOAD (FIXED REGEX & METADATA) ---
    override suspend fun load(url: String): LoadResponse {
        val headers = mapOf(
            "rsc" to "1",
            "Referer" to "$mainUrl/"
        )
        
        val rawResponse = app.get(url, headers = headers).text
        val isTv = url.contains("/tv/") || url.contains("season")

        // 1. Clean response: Unescape \" to " and remove newlines
        // Điều này giúp Regex hoạt động mượt hơn trên chuỗi JSON dài
        val cleanResponse = rawResponse.replace("\\\"", "\"").replace("\\n", " ")

        // --- EXTRACT METADATA ---
        var title: String = "Unknown"
        var description: String? = null
        var poster: String = ""
        var year: Int? = null
        var ratingValue: Double? = null
        var tags: List<String>? = null
        var actors: List<ActorData>? = null

        // Priority 1: JSON-LD (Dữ liệu chuẩn nhất)
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

        // Priority 2: Fallback Regex from RSC Data (Nếu JSON-LD thiếu)
        if (title == "Unknown") {
            // Tìm title trong RSC props
            val titleRegex = """\"title\":\"([^\"]+?)\"""".toRegex()
            title = titleRegex.find(cleanResponse)?.groupValues?.get(1) ?: "Unknown"
        }

        if (poster.isEmpty()) {
            val posterRegex = """(?:posterPath|src)["']\s*:\s*["']([^"']+\.(?:jpg|png|webp))["']""".toRegex()
            val matches = posterRegex.findAll(cleanResponse)
            for (match in matches) {
                val p = match.groupValues[1]
                if (p.contains("posters") || p.contains("backdrops")) {
                    poster = fixUrl(p)
                    break
                }
            }
        }
        
        if (description == null) {
            // Fallback 1: Tìm key overview trong JSON
            val overviewRegex = """\"overview\":\"([^\"]+?)\"""".toRegex()
            // Lấy match đầu tiên có độ dài > 20 ký tự (để tránh lấy phải overview rỗng hoặc rác của tập phim)
            description = overviewRegex.findAll(cleanResponse)
                .map { it.groupValues[1] }
                .firstOrNull { it.length > 20 }
            
            // Fallback 2: Meta description tag (nếu API trả về HTML đính kèm)
            if (description == null) {
                val metaDescRegex = """name="description" content="(.*?)"""".toRegex()
                description = metaDescRegex.find(rawResponse)?.groupValues?.get(1)
            }
        }

        // --- EXTRACT EPISODES (Robust Regex) ---
        val episodes = mutableListOf<Episode>()
        if (isTv) {
            // Chiến thuật: Chỉ tìm fullSlug (chuẩn nhất của Rido), sau đó dựng Episode
            // Pattern: /tv/ten-phim/season-X/episode-Y
            // Dùng Set để tránh trùng lặp
            val addedSlugs = mutableSetOf<String>()
            
            // Regex quét fullSlug
            val slugRegex = """\"fullSlug\":\"(tv\/[^\"]+?\/season-(\d+)\/episode-(\d+))\"""".toRegex()
            
            slugRegex.findAll(cleanResponse).forEach { match ->
                val fullSlug = match.groupValues[1]
                val seasonNum = match.groupValues[2].toIntOrNull() ?: 1
                val episodeNum = match.groupValues[3].toIntOrNull() ?: 1
                
                if (addedSlugs.add(fullSlug)) {
                    // Cố gắng tìm title và overview xung quanh slug (heuristic)
                    // Lấy 1 cửa sổ text xung quanh match để tìm title
                    val matchIndex = match.range.first
                    val searchWindow = cleanResponse.substring(
                        (matchIndex - 500).coerceAtLeast(0), 
                        (matchIndex + 500).coerceAtMost(cleanResponse.length)
                    )
                    
                    var epTitle = "Episode $episodeNum"
                    // Tìm title gần nhất trong window
                    val titleMatch = """\"title\":\"([^\"]+?)\"""".toRegex().find(searchWindow)
                    if (titleMatch != null) {
                        val potentialTitle = titleMatch.groupValues[1]
                        // Lọc bỏ title trùng với tên phim hoặc tên series
                        if (!potentialTitle.equals(title, ignoreCase = true) && !potentialTitle.contains("season", ignoreCase = true)) {
                            epTitle = potentialTitle
                        }
                    }
                    
                    val epDescMatch = """\"overview\":\"([^\"]+?)\"""".toRegex().find(searchWindow)
                    val epDesc = epDescMatch?.groupValues?.get(1)

                    episodes.add(newEpisode("$mainUrl/$fullSlug") {
                        this.season = seasonNum
                        this.episode = episodeNum
                        this.name = epTitle
                        this.description = epDesc
                        this.data = "$mainUrl/$fullSlug"
                    })
                }
            }
        }

        // --- EXTRACT RECOMMENDATIONS ---
        var recommendations = mutableListOf<SearchResponse>()
        try {
            val genreRegex = """href\":\"\/genre\/([a-zA-Z0-9-]+)\"""".toRegex()
            val genres = genreRegex.findAll(cleanResponse)
                .map { it.groupValues[1] }
                .distinct()
                .filter { !it.contains("search") }
                .toList()

            if (genres.isNotEmpty()) {
                val randomGenre = genres[Random.nextInt(genres.size)]
                val genreUrl = "$mainUrl/genre/$randomGenre"
                
                val genreResponse = app.get(genreUrl, headers = headers).text
                val cleanGenreResponse = genreResponse.replace("\\\"", "\"")
                
                val recRegex = """\{"id":".*?","contentId".*?"originalTitle":"(.*?)".*?"fullSlug":"(.*?)".*?"posterPath":"(.*?)".*?"releaseYear":"?(\d+)"?.*?"id":.*?"type":"(movie|tv)"""".toRegex()
                
                recRegex.findAll(cleanGenreResponse).forEach { match ->
                    val rTitle = match.groupValues[1]
                    val rSlug = match.groupValues[2]
                    val rPoster = match.groupValues[3]
                    val rYear = match.groupValues[4].toIntOrNull()
                    
                    val rUrl = "$mainUrl/$rSlug"
                    if (rUrl != url) {
                        recommendations.add(newMovieSearchResponse(rTitle, rUrl, TvType.Movie) {
                            this.posterUrl = fixUrl(rPoster)
                            this.year = rYear
                        })
                    }
                }
            }
        } catch (e: Exception) { e.printStackTrace() }

        val finalEpisodes = episodes.distinctBy { it.data }.sortedWith(compareBy({ it.season }, { it.episode }))
        val finalRecommendations = recommendations.distinctBy { it.url }.shuffled().take(10)

        if (isTv) {
            return newTvSeriesLoadResponse(title, url, TvType.TvSeries, finalEpisodes) {
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
        Log.d(TAG, "loadLinks Input: $data")
        val apiUrl = if (data.contains("/movies/")) {
            data.replace("/movies/", "/api/movies/")
        } else if (data.contains("/tv/")) {
            data.replace("/tv/", "/api/tv/")
        } else {
            data
        }

        try {
            val headers = mapOf("Referer" to "$mainUrl/")
            val jsonText = app.get(apiUrl, headers = headers).text
            
            // Regex 1: Tìm URL trong iframe HTML (RSC/JSON escape)
            val embedRegex = """src\\?":\\?"(https:.*?)(\\?.*?)?\\?"""".toRegex()
            embedRegex.findAll(jsonText).forEach { match ->
                 val url = match.groupValues[1].replace("\\", "")
                 if(!url.contains("google") && !url.contains("facebook")) {
                    loadExtractor(url, subtitleCallback, callback)
                 }
            }
            
            // Regex 2: Data-src attribute
            val dataSrcRegex = """data-src=\\?["'](https:.*?)(\\?.*?)?\\?["']""".toRegex()
            dataSrcRegex.findAll(jsonText).forEach { match ->
                val url = match.groupValues[1].replace("\\", "")
                if(!url.contains("google")) {
                    loadExtractor(url, subtitleCallback, callback)
                }
            }

        } catch (e: Exception) {
            e.printStackTrace()
        }
        return true
    }
    
    private fun fixUrl(url: String): String {
        return when {
            url.isEmpty() -> ""
            url.startsWith("http") -> url
            // Xử lý path tương đối
            url.startsWith("/") -> "$mainUrl$url"
            else -> "$mainUrl/$url"
        }
    }
    
    private fun String.capitalize(): String {
        return this.replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() }
    }
}
