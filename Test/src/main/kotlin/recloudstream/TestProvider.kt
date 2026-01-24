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

    // Episode Data (Next.js) - Cấu trúc parse lỏng lẻo hơn
    data class NextSeasons(val seasons: List<NextSeason>?)
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

    // --- LOAD (FIXED) ---
    override suspend fun load(url: String): LoadResponse {
        val response = app.get(url).text
        val isTv = url.contains("/tv/") || url.contains("season")

        var title = "Unknown"
        var description: String? = null
        var poster: String? = null
        var year: Int? = null
        var ratingValue: Double? = null
        var tags: List<String>? = null
        var actors: List<ActorData>? = null

        // 1. Parse JSON-LD (Metadata)
        try {
            val document = Jsoup.parse(response)
            val scripts = document.select("script[type=application/ld+json]")
            for (script in scripts) {
                val jsonString = script.data()
                if (jsonString.contains("\"@type\":\"Movie\"") || jsonString.contains("\"@type\":\"TVSeries\"")) {
                    val ldData = parseJson<LdJson>(jsonString)
                    title = ldData.name ?: title
                    description = ldData.description
                    poster = ldData.image
                    year = ldData.dateCreated?.take(4)?.toIntOrNull()
                    tags = ldData.genre
                    ratingValue = ldData.aggregateRating?.ratingValue?.toDoubleOrNull()
                    actors = ldData.actor?.mapNotNull { p -> p.name?.let { ActorData(Actor(it)) } }
                    break
                }
            }
        } catch (e: Exception) { e.printStackTrace() }

        // 2. Fallback HTML Metadata
        if (title == "Unknown") {
            val doc = Jsoup.parse(response)
            title = doc.selectFirst("meta[property=og:title]")?.attr("content") ?: title
            description = doc.selectFirst("meta[name=description]")?.attr("content") ?: description
            poster = doc.selectFirst("meta[property=og:image]")?.attr("content") ?: poster
        }
        
        // FIX: Ensure Poster URL is absolute
        poster = fixUrl(poster ?: "")

        // 3. Recommendations (Regex Search for /movies/ or /tv/ links)
        // Tìm các link dạng href="/movies/slug" hoặc href="/tv/slug"
        val recommendations = mutableListOf<SearchResponse>()
        val recRegex = """href=["'](\/(movies|tv)\/[a-zA-Z0-9-]+)["']""".toRegex()
        val currentSlug = url.substringAfterLast("/")
        
        recRegex.findAll(response).forEach { match ->
            val href = match.groupValues[1]
            if (!href.contains(currentSlug) && !href.contains("genre") && !href.contains("search")) {
                // Heuristic: Lấy tên từ slug (ví dụ: /movies/the-matrix -> The Matrix)
                val slugName = href.substringAfterLast("/").replace("-", " ").capitalize()
                val fullUrl = "$mainUrl$href"
                // Tạo recommendation đơn giản
                val type = if(href.contains("/tv/")) TvType.TvSeries else TvType.Movie
                recommendations.add(newMovieSearchResponse(slugName, fullUrl, type))
            }
        }

        if (isTv) {
            val episodes = mutableListOf<Episode>()
            
            // 4. Parse Episodes (TV Series)
            // Cách 1: Tìm block JSON chứa seasons (xử lý escape quotes \")
            val seasonJsonRegex = """\\?"seasons\\?":\s*\[.*?"episodes\\?":\s*\[.*?\]""".toRegex()
            val matchResults = seasonJsonRegex.findAll(response)
            
            for (match in matchResults) {
                try {
                    // Clean chuỗi JSON: bỏ dấu gạch chéo ngược (unescape)
                    var cleanJson = match.value.replace("\\\"", "\"")
                    // Thêm ngoặc nhọn đóng/mở để thành JSON hợp lệ nếu regex cắt thiếu
                    if (!cleanJson.startsWith("{")) cleanJson = "{$cleanJson"
                    if (!cleanJson.endsWith("}")) cleanJson = "$cleanJson}]}" // Hacky fix

                    // Thử parse bằng regex con để an toàn hơn parse JSON full
                    val epRegex = """\{"id".*?"episodeNumber":(\d+).*?"title":"(.*?)".*?"fullSlug":"(.*?)"(?:.*?"releaseDate":"(.*?)")?""".toRegex()
                    epRegex.findAll(cleanJson).forEach { epMatch ->
                        val epNum = epMatch.groupValues[1].toIntOrNull()
                        val epTitle = epMatch.groupValues[2]
                        val epSlug = epMatch.groupValues[3]
                        val epDate = epMatch.groupValues[4]
                        
                        if (epNum != null) {
                            episodes.add(newEpisode("$mainUrl/$epSlug") {
                                // Tìm season từ slug (thường là season-1)
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
            
            // Cách 2: Brute Force Regex quét href (Backup)
            if (episodes.isEmpty()) {
                 // Tìm: /tv/slug/season-1/episode-1
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
        // data: https://ridomovies.tv/movies/slug
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
            // Fallback parse HTML gốc
            val html = app.get(data).text
            val embedRegex = """src\\":\\"(https:.*?)(\\?.*?)?\\"""".toRegex()
            embedRegex.findAll(html).forEach { match ->
                 val url = match.groupValues[1].replace("\\", "")
                 loadExtractor(url, subtitleCallback, callback)
            }
        }
        return true
    }
    
    // Helper function để xử lý URL poster
    private fun fixUrl(url: String): String {
        return when {
            url.isEmpty() -> ""
            url.startsWith("http") -> url
            else -> "$mainUrl$url"
        }
    }
    
    // Helper string extension
    private fun String.capitalize(): String {
        return this.replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() }
    }
}
