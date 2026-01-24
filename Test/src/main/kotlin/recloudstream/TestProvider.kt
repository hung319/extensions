package recloudstream

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import com.lagradost.cloudstream3.mvvm.Resource
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

    // Episode Data (Next.js)
    data class NextSeasonsWrapper(val seasons: List<NextSeason>?)
    data class NextSeason(val seasonNumber: Int?, val episodes: List<NextEpisode>?)
    data class NextEpisode(
        val episodeNumber: Int?,
        val title: String?,
        val fullSlug: String?,
        val releaseDate: String?
    )

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

    // --- LOAD (RE-ENGINEERED FOR NEXT.JS RSC) ---
    override suspend fun load(url: String): LoadResponse {
        println("$TAG: Loading Details for: $url")
        val response = app.get(url).text
        
        // Xác định Type dựa vào URL (ổn định nhất)
        val isTv = url.contains("/tv/") || url.contains("season")

        var title = "Unknown"
        var description: String? = null
        var poster: String? = null
        var year: Int? = null
        var ratingValue: Double? = null
        var tags: List<String>? = null
        var actors: List<ActorData>? = null

        // 1. EXTRACT METADATA (JSON-LD)
        // Tìm block JSON-LD. Trong RSC, nó thường bắt đầu bằng T550,{ hoặc nằm trong script tag
        // Regex này tìm chuỗi bắt đầu bằng {"@context" và kết thúc bằng }
        val ldJsonRegex = """\{"@context":"https://schema\.org".*?"@type":"(Movie|TVSeries)".*?\}""".toRegex()
        val ldMatch = ldJsonRegex.find(response)
        
        if (ldMatch != null) {
            try {
                // Clean chuỗi JSON nếu nó bị dính các ký tự lạ của RSC protocol ở đầu
                val jsonString = ldMatch.value
                val ldData = parseJson<LdJson>(jsonString)
                
                title = ldData.name ?: title
                description = ldData.description
                poster = fixUrl(ldData.image ?: "")
                year = ldData.dateCreated?.take(4)?.toIntOrNull()
                tags = ldData.genre
                ratingValue = ldData.aggregateRating?.ratingValue?.toDoubleOrNull()
                actors = ldData.actor?.mapNotNull { p -> p.name?.let { ActorData(Actor(it)) } }
                println("$TAG: Metadata parsed via JSON-LD")
            } catch (e: Exception) {
                println("$TAG: JSON-LD Parse Error: ${e.message}")
            }
        } else {
            // Fallback: Parse HTML nếu RSC không chứa JSON-LD (ít xảy ra nhưng đề phòng)
            val doc = Jsoup.parse(response)
            title = doc.selectFirst("meta[property=og:title]")?.attr("content") ?: title
            description = doc.selectFirst("meta[name=description]")?.attr("content") ?: description
            poster = fixUrl(doc.selectFirst("meta[property=og:image]")?.attr("content") ?: "")
        }

        // 2. EXTRACT EPISODES (TV SERIES)
        val episodes = mutableListOf<Episode>()
        if (isTv) {
            // Pattern tìm chuỗi: "seasons":[{"id":...,"episodes":[...]}]
            // Chú ý: Next.js có thể escape dấu " thành \"
            // Regex này tìm key "seasons" theo sau là mảng, chứa key "episodes"
            val seasonRegex = """(\\?"seasons\\?":\s*\[.*?\\?"episodes\\?":\s*\[.*?\]\s*\}\s*\])""".toRegex()
            val match = seasonRegex.find(response)

            if (match != null) {
                try {
                    // Lấy chuỗi JSON thô, unescape nó
                    var rawJson = match.value
                    rawJson = rawJson.replace("\\\"", "\"") // Unescape \" -> "
                    
                    // Bọc vào ngoặc nhọn để thành JSON object hợp lệ: {"seasons": [...]}
                    if (!rawJson.trim().startsWith("{")) rawJson = "{$rawJson}"
                    if (!rawJson.trim().endsWith("}")) rawJson = "$rawJson}"

                    // Parse
                    val seasonData = parseJson<NextSeasonsWrapper>(rawJson)
                    seasonData.seasons?.forEach { s ->
                        val sNum = s.seasonNumber
                        s.episodes?.forEach { ep ->
                            ep.fullSlug?.let { slug ->
                                episodes.add(newEpisode("$mainUrl/$slug") {
                                    this.season = sNum
                                    this.episode = ep.episodeNumber
                                    this.name = ep.title ?: "Episode ${ep.episodeNumber}"
                                    this.data = "$mainUrl/$slug"
                                    this.addDate(ep.releaseDate)
                                })
                            }
                        }
                    }
                    println("$TAG: Parsed ${episodes.size} episodes from JSON")
                } catch (e: Exception) {
                    println("$TAG: Season JSON Parse Error: ${e.message}")
                }
            }
            
            // Fallback: Regex quét URL nếu JSON parse thất bại
            if (episodes.isEmpty()) {
                val linkRegex = """["'](\/tv\/[^\/]+\/season-(\d+)\/episode-(\d+)[^"']*)["']""".toRegex()
                linkRegex.findAll(response).forEach { m ->
                    val href = m.groupValues[1]
                    val s = m.groupValues[2].toIntOrNull()
                    val e = m.groupValues[3].toIntOrNull()
                    if (s != null && e != null) {
                        episodes.add(newEpisode("$mainUrl$href") {
                            this.season = s
                            this.episode = e
                            this.name = "Episode $e"
                            this.data = "$mainUrl$href"
                        })
                    }
                }
            }
        }

        // 3. EXTRACT RECOMMENDATIONS (Regex Brute Force)
        val recommendations = mutableListOf<SearchResponse>()
        // Tìm mọi chuỗi giống URL phim: /movies/slug hoặc /tv/slug
        // Loại bỏ các url hệ thống như /genre/, /search/, /year/
        val recRegex = """["']\/(movies|tv)\/([a-zA-Z0-9-]+)["']""".toRegex()
        val currentSlug = url.substringAfterLast("/")
        
        recRegex.findAll(response).forEach { m ->
            val typeStr = m.groupValues[1]
            val slug = m.groupValues[2]
            val fullHref = "/$typeStr/$slug"
            
            // Lọc trùng và lọc chính phim hiện tại
            if (slug != currentSlug && !slug.contains("genre") && !slug.contains("search")) {
                val name = slug.replace("-", " ").capitalize()
                val recType = if (typeStr == "tv") TvType.TvSeries else TvType.Movie
                recommendations.add(newMovieSearchResponse(name, "$mainUrl$fullHref", recType))
            }
        }

        val uniqueEpisodes = episodes.distinctBy { it.data }
        val uniqueRecs = recommendations.distinctBy { it.url }

        // Return Result
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
                if (src.isNotEmpty()) loadExtractor(src, subtitleCallback, callback)
            }
        } catch (e: Exception) {
            e.printStackTrace()
            // Fallback parse HTML
            val html = app.get(data).text
            val embedRegex = """src\\":\\"(https:.*?)(\\?.*?)?\\"""".toRegex()
            embedRegex.findAll(html).forEach { match ->
                 val url = match.groupValues[1].replace("\\", "")
                 loadExtractor(url, subtitleCallback, callback)
            }
        }
        return true
    }
    
    // Helpers
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
