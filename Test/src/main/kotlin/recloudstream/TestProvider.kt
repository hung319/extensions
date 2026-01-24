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

    // --- LOAD (RE-WRITTEN FOR STABILITY) ---
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

        // 1. EXTRACT METADATA (Hybrid: Regex JSON-LD + Jsoup Fallback)
        // Tìm chuỗi JSON-LD trong Next.js RSC stream
        // Pattern: ... T550,{"@context":"https://schema.org", ... } ...
        try {
            // Regex tìm block JSON bắt đầu bằng @context và kết thúc hợp lý
            // (?s) bật chế độ dot-matches-all
            val ldRegex = """(?s)\{["\\]+@context["\\]+:["\\]+https://schema\.org["\\]+.*?""".toRegex()
            val match = ldRegex.find(response)
            
            if (match != null) {
                // Lấy chuỗi raw, cố gắng cắt đến dấu ngoặc nhọn đóng cuối cùng của block
                var rawJson = match.value
                // Simple heuristic: Cắt đến khi gặp thẻ script đóng hoặc dòng mới của RSC
                if (rawJson.contains("</script>")) {
                    rawJson = rawJson.substringBefore("</script>")
                } else if (rawJson.length > 5000) {
                    rawJson = rawJson.take(5000) // Tránh quá dài
                }
                
                // Fix clean JSON vì RSC có thể escape nhiều lần
                // Tìm substring từ { đến } cuối cùng có thể
                val lastBrace = rawJson.lastIndexOf("}")
                if (lastBrace != -1) {
                    rawJson = rawJson.substring(0, lastBrace + 1)
                    // Unescape cơ bản
                    rawJson = rawJson.replace("\\\"", "\"").replace("\\\\", "\\")
                    
                    val ldData = parseJson<LdJson>(rawJson)
                    title = ldData.name ?: title
                    description = ldData.description
                    poster = fixUrl(ldData.image ?: "")
                    year = ldData.dateCreated?.take(4)?.toIntOrNull()
                    tags = ldData.genre
                    ratingValue = ldData.aggregateRating?.ratingValue?.toDoubleOrNull()
                    actors = ldData.actor?.mapNotNull { p -> p.name?.let { ActorData(Actor(it)) } }
                    println("$TAG: JSON-LD parsed successfully")
                }
            }
        } catch (e: Exception) {
            println("$TAG: JSON-LD Parse Error: ${e.message}")
        }

        // Fallback: Parse HTML nếu JSON fail
        if (title == "Unknown") {
            val doc = Jsoup.parse(response)
            title = doc.selectFirst("meta[property=og:title]")?.attr("content") ?: title
            description = doc.selectFirst("meta[name=description]")?.attr("content") ?: description
            poster = fixUrl(doc.selectFirst("meta[property=og:image]")?.attr("content") ?: "")
        }

        // 2. RECOMMENDATIONS (Regex Scan)
        val recommendations = mutableListOf<SearchResponse>()
        val recRegex = """["'](\/(movies|tv)\/[a-zA-Z0-9-]+)["']""".toRegex()
        val currentSlug = url.substringAfterLast("/")
        
        recRegex.findAll(response).forEach { m ->
            val href = m.groupValues[1]
            if (!href.contains(currentSlug) && !href.contains("genre") && !href.contains("search")) {
                val name = href.substringAfterLast("/").replace("-", " ").capitalize()
                val recType = if (href.contains("/tv/")) TvType.TvSeries else TvType.Movie
                recommendations.add(newMovieSearchResponse(name, "$mainUrl$href", recType))
            }
        }

        // 3. EPISODES (Regex Extraction - The Robust Way)
        if (isTv) {
            val episodes = mutableListOf<Episode>()
            
            // Tìm tất cả các chuỗi có dạng: "tv/ten-phim/season-X/episode-Y"
            // Cấu trúc này luôn xuất hiện trong "fullSlug" bất kể JSON structure thế nào
            val epSlugRegex = """["\\]+(tv\/[a-zA-Z0-9-]+\/season-(\d+)\/episode-(\d+)(?:-[a-zA-Z0-9-]+)?)["\\]+""".toRegex()
            
            epSlugRegex.findAll(response).forEach { match ->
                val fullSlug = match.groupValues[1]
                val seasonNum = match.groupValues[2].toIntOrNull()
                val episodeNum = match.groupValues[3].toIntOrNull()
                
                if (seasonNum != null && episodeNum != null) {
                    episodes.add(newEpisode("$mainUrl/$fullSlug") {
                        this.season = seasonNum
                        this.episode = episodeNum
                        this.name = "Episode $episodeNum"
                        this.data = "$mainUrl/$fullSlug"
                    })
                }
            }
            
            val uniqueEpisodes = episodes.distinctBy { it.data }.sortedWith(compareBy({ it.season }, { it.episode }))
            println("$TAG: Found ${uniqueEpisodes.size} episodes via Regex")

            return newTvSeriesLoadResponse(title, url, TvType.TvSeries, uniqueEpisodes) {
                this.posterUrl = poster
                this.year = year
                this.plot = description
                this.tags = tags
                this.score = ratingValue?.let { Score.from10(it) }
                this.actors = actors
                this.recommendations = recommendations.distinctBy { it.url }
            }
        } else {
            return newMovieLoadResponse(title, url, TvType.Movie, url) {
                this.posterUrl = poster
                this.year = year
                this.plot = description
                this.tags = tags
                this.score = ratingValue?.let { Score.from10(it) }
                this.actors = actors
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
        println("$TAG: Loading Links for: $data")
        // Convert to API URL
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
                    println("$TAG: Found source: $src")
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
