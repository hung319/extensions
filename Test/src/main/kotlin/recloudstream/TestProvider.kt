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

    // --- LOAD (UPDATED FOR RSC FORMAT) ---
    override suspend fun load(url: String): LoadResponse {
        // Thêm header rsc: 1 để đảm bảo server trả về format text stream như log bạn cung cấp
        val headers = mapOf(
            "rsc" to "1",
            "Referer" to "$mainUrl/"
        )
        
        val rawResponse = app.get(url, headers = headers).text
        val isTv = url.contains("/tv/") || url.contains("season")

        // 1. Làm sạch response (RSC format escape rất nhiều quote \")
        // Biến thành string dễ regex hơn
        val cleanResponse = rawResponse.replace("\\\"", "\"").replace("\\n", " ")

        // Khởi tạo biến
        var title: String = "Unknown"
        var description: String? = null
        var poster: String = ""
        var year: Int? = null
        var ratingValue: Double? = null
        var tags: List<String>? = null
        var actors: List<ActorData>? = null

        // 2. Chiến thuật chính: Tìm JSON-LD Block
        // Trong log của bạn có đoạn: "dangerouslySetInnerHTML":{"__html":"{"@context":"https://schema.org"..."
        // Regex này bắt cụm JSON bắt đầu bằng @context và @type
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
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }

        // 3. Chiến thuật phụ (Fallback nếu JSON-LD lỗi hoặc thiếu)
        
        // Tìm Poster: Tìm pattern "src":".../uploads/posters/..."
        if (poster.isEmpty()) {
            val posterRegex = """\"src\":\"(https:[^"]+?\/uploads\/posters\/[^"]+?)\"""".toRegex()
            val pMatch = posterRegex.find(cleanResponse)
            if (pMatch != null) {
                poster = pMatch.groupValues[1]
            } else {
                // Fallback tìm backdrops nếu không có poster
                val backdropRegex = """\"src\":\"(https:[^"]+?\/uploads\/backdrops\/[^"]+?)\"""".toRegex()
                poster = backdropRegex.find(cleanResponse)?.groupValues?.get(1) ?: ""
            }
        }

        // Tìm Description: Tìm trong block "className":"post-overview" text="..."
        if (description == null) {
            // Regex tìm value của text key đứng sau post-overview
            val descRegex = """post-overview".*?"text":"(.*?)"""".toRegex()
            val dMatch = descRegex.find(cleanResponse)
            if (dMatch != null) {
                // Remove HTML tags <p>, </p>
                description = dMatch.groupValues[1].replace("<[^>]*>".toRegex(), "")
            }
        }
        
        // Tìm Title (nếu chưa có)
        if (title == "Unknown") {
            val titleRegex = """\"title\":\"([^\"]+?)\"""".toRegex() // Basic fallback
            title = titleRegex.find(cleanResponse)?.groupValues?.get(1) ?: "Unknown"
        }

        // Fix URL poster lần cuối
        poster = fixUrl(poster)

        // 4. Recommendations
        val recommendations = mutableListOf<SearchResponse>()
        // Pattern: href="/movies/slug" inside the messy RSC string
        val recRegex = """\"href\":\"(\/(movies|tv)\/[a-zA-Z0-9-]+)\"""".toRegex()
        val currentSlug = url.substringAfterLast("/")
        
        recRegex.findAll(cleanResponse).forEach { match ->
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
            
            // Xử lý Episodes trong RSC
            // Tìm block chứa episodes. Thường nằm trong structure phức tạp. 
            // Ta quét pattern rộng: "episodeNumber":1 ... "fullSlug":"..."
            
            // Regex quét từng cụm episode
            val epRegex = """\{"id".*?"episodeNumber":(\d+).*?"title":"(.*?)".*?"fullSlug":"(.*?)"(?:.*?"releaseDate":"(.*?)")?""".toRegex()
            epRegex.findAll(cleanResponse).forEach { epMatch ->
                val epNum = epMatch.groupValues[1].toIntOrNull()
                val epTitle = epMatch.groupValues[2]
                val epSlug = epMatch.groupValues[3]
                val epDate = epMatch.groupValues[4]
                
                if (epNum != null && epSlug.contains("season")) {
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
            
            // Regex backup đơn giản hơn cho link
            if (episodes.isEmpty()) {
                 val linkRegex = """\"(\/tv\/[^\/]+\/season-(\d+)\/episode-(\d+)(?:-[^\/"']+)?)[\"']""".toRegex()
                 linkRegex.findAll(cleanResponse).forEach { match ->
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
            
            // Cố gắng parse JSON
            try {
                val jsonResponse = parseJson<ApiLinkResponse>(jsonText)
                jsonResponse.data?.forEach { item ->
                    val iframeHtml = item.url ?: return@forEach
                    val doc = Jsoup.parse(iframeHtml)
                    val src = doc.select("iframe").attr("data-src")
                    if (src.isNotEmpty()) loadExtractor(src, subtitleCallback, callback)
                }
            } catch (e: Exception) { }

            // Fallback Regex tìm src embed trong response
            val embedRegex = """src\\?":\\?"(https:.*?)(\\?.*?)?\\?"""".toRegex()
            embedRegex.findAll(jsonText).forEach { match ->
                 val url = match.groupValues[1].replace("\\", "")
                 if(!url.contains("google")) 
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
