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

    // Episode Data
    data class RscSeasonsRoot(val seasons: List<RscSeason>?)
    data class RscSeason(val seasonNumber: Int?, val episodes: List<RscEpisode>?)
    data class RscEpisode(
        val episodeNumber: Int?,
        val title: String?,
        val fullSlug: String?,
        val releaseDate: String?,
        val overview: String?
    )

    // --- MAIN PAGE ---
    override val mainPage = mainPageOf(
        "$mainUrl/core/api/movies/latest?page%5Bnumber%5D=" to "Latest Movies",
        "$mainUrl/core/api/series/latest?page%5Bnumber%5D=" to "Latest TV Series"
    )

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {
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
        println("$TAG: Loading Details for: $url")
        val response = app.get(url).text
        val isTv = url.contains("/tv/") || url.contains("season")

        var title = "Unknown"
        var description: String? = null
        var poster: String? = null
        var year: Int? = null
        var ratingValue: Double? = null
        var tags: List<String>? = null
        var actors: List<ActorData>? = null
        
        val episodes = mutableListOf<Episode>()
        val recommendations = mutableListOf<SearchResponse>()

        // Line-by-Line Parsing
        response.lines().forEach { line ->
            // 1. Metadata (JSON-LD)
            if (line.contains("https://schema.org") && (line.contains("\"Movie\"") || line.contains("\"TVSeries\""))) {
                try {
                    val startIndex = line.indexOf("{\"@context\"")
                    if (startIndex != -1) {
                        var jsonStr = line.substring(startIndex)
                        if (jsonStr.contains("</script>")) {
                            jsonStr = jsonStr.substringBefore("</script>")
                        }
                        
                        val ldData = parseJson<LdJson>(jsonStr)
                        title = ldData.name ?: title
                        description = ldData.description
                        poster = fixUrl(ldData.image ?: "")
                        year = ldData.dateCreated?.take(4)?.toIntOrNull()
                        tags = ldData.genre
                        ratingValue = ldData.aggregateRating?.ratingValue?.toDoubleOrNull()
                        actors = ldData.actor?.mapNotNull { p -> p.name?.let { ActorData(Actor(it)) } }
                    }
                } catch (e: Exception) {
                    println("$TAG: JSON-LD Parse Error: ${e.message}")
                }
            }

            // 2. Episodes (TV Series)
            if (isTv && line.contains("\"seasons\":") && line.contains("\"episodes\":")) {
                try {
                    val cleanLine = line.replace("\\\"", "\"")
                    val seasonBlockRegex = """\{"seasons":\[.*?"episodes":\[.*?\]\}\]\}""".toRegex()
                    val seasonMatch = seasonBlockRegex.find(cleanLine)
                    
                    if (seasonMatch != null) {
                        val seasonJson = seasonMatch.value
                        val seasonData = parseJson<RscSeasonsRoot>(seasonJson)
                        
                        seasonData.seasons?.forEach { s ->
                            val sNum = s.seasonNumber
                            s.episodes?.forEach { ep ->
                                val epNum = ep.episodeNumber
                                val epSlug = ep.fullSlug
                                val epTitle = ep.title ?: "Episode $epNum"
                                val epDate = ep.releaseDate
                                
                                if (epSlug != null && epNum != null) {
                                    episodes.add(newEpisode("$mainUrl/$epSlug") {
                                        this.season = sNum
                                        this.episode = epNum
                                        this.name = epTitle
                                        this.data = "$mainUrl/$epSlug"
                                        // FIX: Dùng this.description thay vì this.plot cho Episode
                                        this.description = ep.overview 
                                        this.addDate(epDate)
                                    })
                                }
                            }
                        }
                    }
                } catch (e: Exception) {
                    println("$TAG: Episodes Parse Error: ${e.message}")
                }
            }
            
            // 3. Recommendations
            val recRegex = """["']\/(movies|tv)\/([a-zA-Z0-9-]+)["']""".toRegex()
            recRegex.findAll(line).forEach { m ->
                val typeStr = m.groupValues[1]
                val slug = m.groupValues[2]
                val fullHref = "/$typeStr/$slug"
                if (!url.contains(slug) && !slug.contains("genre") && !slug.contains("search")) {
                    val recName = slug.replace("-", " ").capitalize()
                    val recType = if (typeStr == "tv") TvType.TvSeries else TvType.Movie
                    recommendations.add(newMovieSearchResponse(recName, "$mainUrl$fullHref", recType))
                }
            }
        }

        // Fallback HTML Metadata
        if (title == "Unknown") {
            val doc = Jsoup.parse(response)
            title = doc.selectFirst("meta[property=og:title]")?.attr("content") ?: title
            description = doc.selectFirst("meta[name=description]")?.attr("content") ?: description
            poster = fixUrl(doc.selectFirst("meta[property=og:image]")?.attr("content") ?: "")
        }
        
        // Fallback Episodes Regex
        if (isTv && episodes.isEmpty()) {
             val epSlugRegex = """(tv\/[a-zA-Z0-9-]+\/season-(\d+)\/episode-(\d+)(?:-[a-zA-Z0-9-]+)?)""".toRegex()
             epSlugRegex.findAll(response).forEach { match ->
                val fullSlug = match.groupValues[1]
                val s = match.groupValues[2].toIntOrNull()
                val e = match.groupValues[3].toIntOrNull()
                if (s != null && e != null) {
                    episodes.add(newEpisode("$mainUrl/$fullSlug") {
                        this.season = s
                        this.episode = e
                        this.name = "Episode $e"
                        this.data = "$mainUrl/$fullSlug"
                    })
                }
            }
        }

        val uniqueEpisodes = episodes.distinctBy { it.data }.sortedWith(compareBy({ it.season }, { it.episode }))
        val uniqueRecs = recommendations.distinctBy { it.url }

        return if (isTv) {
            newTvSeriesLoadResponse(title, url, TvType.TvSeries, uniqueEpisodes) {
                this.posterUrl = poster
                this.year = year
                this.plot = description
                this.tags = tags
                this.score = ratingValue?.let { Score.from10(it) }
                this.actors = actors
                this.recommendations = uniqueRecs
            }
        } else {
            newMovieLoadResponse(title, url, TvType.Movie, url) {
                this.posterUrl = poster
                this.year = year
                this.plot = description
                this.tags = tags
                this.score = ratingValue?.let { Score.from10(it) }
                this.actors = actors
                this.recommendations = uniqueRecs
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
        println("$TAG: Loading Links for: $data")
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
            val jsonResponse = parseJson<ApiLinkResponse>(jsonText)
            
            jsonResponse.data?.forEach { item ->
                val iframeHtml = item.url ?: return@forEach
                val doc = Jsoup.parse(iframeHtml)
                val src = doc.select("iframe").attr("data-src")
                if (src.isNotEmpty()) {
                    loadExtractor(src, subtitleCallback, callback)
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
            // Fallback
            val html = app.get(data).text
            val embedRegex = """src\\":\\"(https:.*?)(\\?.*?)?\\"""".toRegex()
            embedRegex.findAll(html).forEach { match ->
                 val url = match.groupValues[1].replace("\\", "")
                 loadExtractor(url, subtitleCallback, callback)
            }
        }
        return true
    }
    
    private fun fixUrl(url: String): String {
        return when {
            url.isEmpty() -> ""
            url.startsWith("http") -> url
            else -> "$mainUrl$url"
        }
    }
    
    private fun String.capitalize(): String {
        return this.replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() }
    }
}
