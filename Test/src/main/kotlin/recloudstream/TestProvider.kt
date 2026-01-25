package recloudstream

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import com.lagradost.cloudstream3.utils.AppUtils.tryParseJson
import com.lagradost.cloudstream3.Score
import org.jsoup.Jsoup
import kotlin.random.Random

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

    // Data class cho Season/Episode parsing từ RSC
    data class RidoSeason(
        val seasonNumber: Int?,
        val episodes: List<RidoEpisode>?
    )
    data class RidoEpisode(
        val id: String?,
        val episodeNumber: Int?,
        val title: String?,
        val overview: String?,
        val fullSlug: String?,
        val releaseDate: String?
    )

    // Data classes cho Genre Response
    data class GenreResponseRoot(val listingRow: GenreListingRow?)
    data class GenreListingRow(val data: List<GenreItem>?)
    data class GenreItem(
        val title: String?,
        val originalTitle: String?,
        val fullSlug: String?,
        val posterPath: String?,
        val content: GenreContent? // Đôi khi nó nằm trong object content con
    )
    data class GenreContent(val title: String?, val fullSlug: String?, val type: String?)

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

        // 1. Clean response (Unescape quotes)
        val cleanResponse = rawResponse.replace("\\\"", "\"").replace("\\n", " ")

        // Variables
        var title: String = "Unknown"
        var description: String? = null
        var poster: String = ""
        var year: Int? = null
        var ratingValue: Double? = null
        var tags: List<String>? = null
        var actors: List<ActorData>? = null

        // 2. Metadata Extraction (JSON-LD)
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

        // Fallback for Metadata
        if (poster.isEmpty()) {
            val posterRegex = """\"src\":\"(https:[^"]+?\/uploads\/posters\/[^"]+?)\"""".toRegex()
            poster = posterRegex.find(cleanResponse)?.groupValues?.get(1) ?: ""
        }
        if (title == "Unknown") {
            val titleRegex = """\"title\":\"([^\"]+?)\"""".toRegex()
            title = titleRegex.find(cleanResponse)?.groupValues?.get(1) ?: "Unknown"
        }
        poster = fixUrl(poster)

        // 3. Episodes Extraction (Improved for RSC)
        val episodes = mutableListOf<Episode>()
        if (isTv) {
            try {
                // Tìm chuỗi JSON bắt đầu bằng "seasons":[
                // Do RSC response rất hỗn loạn, ta dùng logic tìm ngoặc vuông cân bằng
                val seasonStartIndex = cleanResponse.indexOf("\"seasons\":[")
                if (seasonStartIndex != -1) {
                    val jsonStart = seasonStartIndex + 10 // skip "seasons":
                    var balance = 0
                    var jsonEnd = jsonStart
                    
                    // Thuật toán tìm ngoặc đóng tương ứng ']'
                    for (i in jsonStart until cleanResponse.length) {
                        if (cleanResponse[i] == '[') balance++
                        else if (cleanResponse[i] == ']') {
                            balance--
                            if (balance == 0) {
                                jsonEnd = i + 1
                                break
                            }
                        }
                    }
                    
                    if (jsonEnd > jsonStart) {
                        val seasonsJson = cleanResponse.substring(jsonStart, jsonEnd)
                        val seasonsList = tryParseJson<List<RidoSeason>>(seasonsJson)
                        
                        seasonsList?.forEach { season ->
                            season.episodes?.forEach { ep ->
                                val epNum = ep.episodeNumber
                                val fullSlug = ep.fullSlug ?: ""
                                if (epNum != null && fullSlug.isNotEmpty()) {
                                    episodes.add(newEpisode("$mainUrl/$fullSlug") {
                                        this.season = season.seasonNumber
                                        this.episode = epNum
                                        this.name = ep.title ?: "Episode $epNum"
                                        this.overview = ep.overview
                                        this.data = "$mainUrl/$fullSlug" // Data cho loadLinks
                                        ep.releaseDate?.let { addDate(it) }
                                    })
                                }
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }

        // 4. Recommendations (Random from Genre)
        var recommendations = emptyList<SearchResponse>()
        try {
            // Tìm tất cả các link thể loại có trong trang: href="/genre/..."
            val genreRegex = """href":"\/genre\/([a-zA-Z0-9-]+)" """.toRegex()
            val genres = genreRegex.findAll(cleanResponse)
                .map { it.groupValues[1] }
                .distinct()
                .toList()

            if (genres.isNotEmpty()) {
                // Chọn ngẫu nhiên 1 thể loại
                val randomGenre = genres[Random.nextInt(genres.size)]
                val genreUrl = "$mainUrl/genre/$randomGenre"
                
                // Gọi request lấy list phim của thể loại đó (RSC)
                val genreResponse = app.get(genreUrl, headers = headers).text
                val cleanGenreResponse = genreResponse.replace("\\\"", "\"")
                
                // Parse tìm "listingRow" hoặc quét data thủ công
                // Trong log bạn gửi: listingRow nằm trong chuỗi JSON lớn.
                // Ta dùng regex quét mảng data chứa phim: "id":...,"originalTitle":...
                // Regex đơn giản để bắt các object phim trong response thể loại
                val movieItemRegex = """\{"id":"\d+","contentId".*?"originalTitle":"(.*?)".*?"fullSlug":"(.*?)".*?"posterPath":"(.*?)".*?"releaseYear":"?(\d+)"?.*?"id":.*?"type":"(movie|tv)"""".toRegex()
                
                val recs = mutableListOf<SearchResponse>()
                movieItemRegex.findAll(cleanGenreResponse).forEach { match ->
                    val recTitle = match.groupValues[1]
                    val recSlug = match.groupValues[2]
                    val recPoster = match.groupValues[3]
                    val recYear = match.groupValues[4].toIntOrNull()
                    // val recTypeStr = match.groupValues[5] // movie or tv - regex này hơi strict, có thể chỉnh lỏng hơn
                    
                    val recUrl = "$mainUrl/$recSlug"
                    if (recUrl != url) { // Không recommend chính phim đang xem
                         recs.add(newMovieSearchResponse(recTitle, recUrl, TvType.Movie) {
                            this.posterUrl = fixUrl(recPoster)
                            this.year = recYear
                        })
                    }
                }
                
                // Fallback nếu Regex trên trượt (do format thay đổi): Quét lỏng hơn
                if (recs.isEmpty()) {
                     // Pattern đơn giản hơn tìm slug và title
                     val simpleRegex = """fullSlug":"(movies\/[^"]+|tv\/[^"]+)","title":"([^"]+)".*?posterPath":"([^"]+)"""".toRegex()
                     simpleRegex.findAll(cleanGenreResponse).forEach { m ->
                         val s = m.groupValues[1]
                         val t = m.groupValues[2]
                         val p = m.groupValues[3]
                         recs.add(newMovieSearchResponse(t, "$mainUrl/$s", TvType.Movie) {
                             this.posterUrl = fixUrl(p)
                         })
                     }
                }
                
                recommendations = recs.distinctBy { it.url }.shuffled().take(10)
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }

        // Return Result
        if (isTv) {
            return newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
                this.posterUrl = poster
                this.year = year
                this.plot = description
                this.tags = tags
                this.score = ratingValue?.let { Score.from10(it) }
                this.actors = actors
                this.recommendations = recommendations
            }
        } else {
            return newMovieLoadResponse(title, url, TvType.Movie, url) {
                this.posterUrl = poster
                this.year = year
                this.plot = description
                this.tags = tags
                this.score = ratingValue?.let { Score.from10(it) }
                this.actors = actors
                this.recommendations = recommendations
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
            
            try {
                val jsonResponse = parseJson<ApiLinkResponse>(jsonText)
                jsonResponse.data?.forEach { item ->
                    val iframeHtml = item.url ?: return@forEach
                    val doc = Jsoup.parse(iframeHtml)
                    val src = doc.select("iframe").attr("data-src")
                    if (src.isNotEmpty()) loadExtractor(src, subtitleCallback, callback)
                }
            } catch (e: Exception) { }

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
