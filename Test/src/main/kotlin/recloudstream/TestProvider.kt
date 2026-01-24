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

    // --- LOAD (FIXED) ---
    override suspend fun load(url: String): LoadResponse {
        val response = app.get(url).text
        val isTv = url.contains("/tv/") || url.contains("season")

        // === START FIX: REGEX EXTRACTION ===
        // Ưu tiên lấy dữ liệu từ raw string JSON trước khi parse HTML
        // Regex patterns (Handle escaped quotes and slashes)
        
        // 1. Poster: Tìm "posterPath":"/uploads/..."
        var poster = """\\?"posterPath\\?":\\?"([^"]+)\\?"""".toRegex().find(response)?.groupValues?.get(1)
        poster = poster?.replace("\\/", "/") // Fix escaped slashes
        poster = fixUrl(poster ?: "")

        // 2. Description: Tìm "overview":"..."
        var description = """\\?"overview\\?":\\?"([^"]+)\\?"""".toRegex().find(response)?.groupValues?.get(1)
        // Clean up description (remove escaped quotes if any)
        description = description?.replace("\\\"", "\"")

        // 3. Title & Year fallback
        var title = """\\?"title\\?":\\?"([^"]+)\\?"""".toRegex().find(response)?.groupValues?.get(1)
        title = title?.replace("Watch ", "")?.replace(" Online Free", "") ?: "Unknown"

        var year = """\\?"releaseYear\\?":\\?"(\d+)\\?"""".toRegex().find(response)?.groupValues?.get(1)?.toIntOrNull()
        var ratingValue = """\\?"imdbRating\\?":([\d.]+)""".toRegex().find(response)?.groupValues?.get(1)?.toDoubleOrNull()
        // === END FIX ===

        var tags: List<String>? = null
        var actors: List<ActorData>? = null

        // 4. Fallback: Parse JSON-LD (Metadata) nếu Regex thất bại hoặc thiếu thông tin
        // Chỉ chạy nếu thiếu title hoặc description
        if (title == "Unknown" || description == null) {
            try {
                val document = Jsoup.parse(response)
                val scripts = document.select("script[type=application/ld+json]")
                for (script in scripts) {
                    val jsonString = script.data()
                    if (jsonString.contains("\"@type\":\"Movie\"") || jsonString.contains("\"@type\":\"TVSeries\"")) {
                        val ldData = parseJson<LdJson>(jsonString)
                        if (title == "Unknown") title = ldData.name ?: title
                        if (description == null) description = ldData.description
                        if (poster.isEmpty()) poster = fixUrl(ldData.image ?: "")
                        if (year == null) year = ldData.dateCreated?.take(4)?.toIntOrNull()
                        
                        tags = ldData.genre
                        if (ratingValue == null) ratingValue = ldData.aggregateRating?.ratingValue?.toDoubleOrNull()
                        actors = ldData.actor?.mapNotNull { p -> p.name?.let { ActorData(Actor(it)) } }
                        break
                    }
                }
                
                // HTML Meta tags fallback cuối cùng
                if (title == "Unknown") title = document.selectFirst("meta[property=og:title]")?.attr("content") ?: title
                if (description == null) description = document.selectFirst("meta[name=description]")?.attr("content")
                if (poster.isEmpty()) poster = fixUrl(document.selectFirst("meta[property=og:image]")?.attr("content") ?: "")
                
            } catch (e: Exception) { e.printStackTrace() }
        }

        // 5. Recommendations
        val recommendations = mutableListOf<SearchResponse>()
        val recRegex = """href=["'](\/(movies|tv)\/[a-zA-Z0-9-]+)["']""".toRegex()
        val currentSlug = url.substringAfterLast("/")
        
        recRegex.findAll(response).forEach { match ->
            val href = match.groupValues[1]
            if (!href.contains(currentSlug) && !href.contains("genre") && !href.contains("search")) {
                val slugName = href.substringAfterLast("/").replace("-", " ").capitalize()
                val fullUrl = "$mainUrl$href"
                val type = if(href.contains("/tv/")) TvType.TvSeries else TvType.Movie
                recommendations.add(newMovieSearchResponse(slugName, fullUrl, type))
            }
        }

        if (isTv) {
            val episodes = mutableListOf<Episode>()
            
            // 6. Parse Episodes (TV Series)
            // Cách 1: JSON Block trong response
            val seasonJsonRegex = """\\?"seasons\\?":\s*\[.*?"episodes\\?":\s*\[.*?\]""".toRegex()
            val matchResults = seasonJsonRegex.findAll(response)
            
            for (match in matchResults) {
                try {
                    var cleanJson = match.value.replace("\\\"", "\"")
                    if (!cleanJson.startsWith("{")) cleanJson = "{$cleanJson"
                    if (!cleanJson.endsWith("}")) cleanJson = "$cleanJson}]}"

                    val epRegex = """\{"id".*?"episodeNumber":(\d+).*?"title":"(.*?)".*?"fullSlug":"(.*?)"(?:.*?"releaseDate":"(.*?)")?""".toRegex()
                    epRegex.findAll(cleanJson).forEach { epMatch ->
                        val epNum = epMatch.groupValues[1].toIntOrNull()
                        val epTitle = epMatch.groupValues[2]
                        val epSlug = epMatch.groupValues[3]
                        val epDate = epMatch.groupValues[4]
                        
                        if (epNum != null) {
                            episodes.add(newEpisode("$mainUrl/$epSlug") {
                                val seasonNum = Regex("""season-(\d+)""").find(epSlug)?.groupValues?.get(1)?.toIntOrNull() ?: 1
                                this.season = seasonNum
                                this.episode = epNum
                                this.name = epTitle.ifEmpty { "Episode $epNum" }
                                this.data = "$mainUrl/$epSlug"
                                this.addDate(epDate)
                            })
                        }
                    }
                } catch (e: Exception) { }
            }
            
            // Cách 2: Regex Backup
            if (episodes.isEmpty()) {
                 val linkRegex = """["'](\/tv\/[^\/]+\/season-(\d+)\/episode-(\d+)(?:-[^\/"']+)?)["']""".toRegex()
                 linkRegex.findAll(response).forEach { match ->
                    val href = match.groupValues[1]
                    val s = match.groupValues[2].toIntOrNull()
                    val e = match.groupValues[3].toIntOrNull()
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

            val uniqueEpisodes = episodes.distinctBy { it.data }

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
        // data: https://ridomovies.tv/movies/slug hoặc URL web
        // API URL conversion
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
            
            // Cố gắng parse JSON trước
            try {
                val jsonResponse = parseJson<ApiLinkResponse>(jsonText)
                jsonResponse.data?.forEach { item ->
                    val iframeHtml = item.url ?: return@forEach
                    val doc = Jsoup.parse(iframeHtml)
                    val src = doc.select("iframe").attr("data-src")
                    if (src.isNotEmpty()) loadExtractor(src, subtitleCallback, callback)
                }
            } catch (e: Exception) {
                // Fallback nếu API trả về HTML hoặc format khác
            }

            // Fallback Regex tìm src embed trong response (bao gồm cả trường hợp API trả về HTML)
            val embedRegex = """src\\?":\\?"(https:.*?)(\\?.*?)?\\?"""".toRegex()
            embedRegex.findAll(jsonText).forEach { match ->
                 val url = match.groupValues[1].replace("\\", "")
                 if(!url.contains("google")) // Filter junk
                    loadExtractor(url, subtitleCallback, callback)
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
            else -> "$mainUrl$url"
        }
    }
    
    private fun String.capitalize(): String {
        return this.replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() }
    }
}
