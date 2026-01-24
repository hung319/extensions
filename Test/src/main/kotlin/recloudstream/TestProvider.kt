package recloudstream

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import com.lagradost.cloudstream3.mvvm.Resource
import com.lagradost.cloudstream3.Score // Import class Score
import org.jsoup.Jsoup
import java.net.URLDecoder

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
        val title: String,
        val fullSlug: String,
        val type: String?, 
        val posterPath: String?,
        val contentable: ApiContentable?
    )
    data class ApiContentable(val releaseYear: String?, val overview: String?)

    // Class cho Load (JSON-LD)
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

    // Class cho Episode (Next.js data)
    data class NextSeasons(val seasons: List<NextSeason>?)
    data class NextSeason(val seasonNumber: Int?, val episodes: List<NextEpisode>?)
    data class NextEpisode(
        val episodeNumber: Int?,
        val title: String?,
        val fullSlug: String?,
        val releaseDate: String?
    )

    // Class cho Link API
    data class ApiLinkResponse(val data: List<ApiLinkItem>?)
    data class ApiLinkItem(val url: String?)

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
        
        return try {
            val text = app.get(url).text
            val json = parseJson<ApiResponse>(text)
            val items = json.data?.items ?: emptyList()

            val homeList = items.mapNotNull { item ->
                val title = item.title
                val href = "$mainUrl/${item.fullSlug}"
                val poster = if (item.posterPath != null) "$mainUrl${item.posterPath}" else ""
                val type = if (item.type == "tv-series") TvType.TvSeries else TvType.Movie
                val year = item.contentable?.releaseYear?.toIntOrNull()

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
            newHomePageResponse(request.name, emptyList())
        }
    }

    // --- SEARCH ---
    data class SearchRoot(val data: SearchContainer?)
    data class SearchContainer(val items: List<ApiItem>?)

    override suspend fun search(query: String): List<SearchResponse> {
        val url = "$mainUrl/core/api/search?q=$query"
        return try {
            val text = app.get(url).text
            val json = parseJson<SearchRoot>(text)
            val items = json.data?.items ?: return emptyList()

            items.mapNotNull { item ->
                val title = item.title
                val href = "$mainUrl/${item.fullSlug}"
                val poster = if (item.posterPath != null) "$mainUrl${item.posterPath}" else ""
                val type = if (item.type == "tv-series") TvType.TvSeries else TvType.Movie
                val year = item.contentable?.releaseYear?.toIntOrNull()

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
        val response = app.get(url).text
        val isTv = url.contains("/tv/") || url.contains("season")

        // Parse JSON-LD
        val ldJsonRegex = """\{"@context":"https://schema.org".*?"@type":"(Movie|TVSeries)".*?\}""".toRegex()
        val ldJsonString = ldJsonRegex.find(response)?.value
        
        var title = "Unknown"
        var description: String? = null
        var poster: String? = null
        var year: Int? = null
        var ratingValue: Double? = null // Đổi thành Double để dùng cho Score
        var tags: List<String>? = null
        var actors: List<ActorData>? = null

        if (ldJsonString != null) {
            try {
                val ldData = parseJson<LdJson>(ldJsonString)
                title = ldData.name ?: title
                description = ldData.description
                poster = ldData.image
                year = ldData.dateCreated?.take(4)?.toIntOrNull()
                tags = ldData.genre
                // Lấy giá trị rating dạng Double (ví dụ 5.4)
                ratingValue = ldData.aggregateRating?.ratingValue?.toDoubleOrNull()
                actors = ldData.actor?.mapNotNull { p -> p.name?.let { ActorData(Actor(it)) } }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        } else {
            val doc = Jsoup.parse(response)
            title = doc.selectFirst("meta[property=og:title]")?.attr("content") ?: title
            description = doc.selectFirst("meta[name=description]")?.attr("content")
            poster = doc.selectFirst("meta[property=og:image]")?.attr("content")
        }

        val recommendations = Jsoup.parse(response).select("div.grid > a").mapNotNull {
            val recHref = it.attr("href")
            val recTitle = it.select("h3").text()
            val recPoster = it.select("img").attr("src")
            if (recHref.isEmpty()) return@mapNotNull null
            newMovieSearchResponse(recTitle, "$mainUrl$recHref", TvType.Movie) {
                this.posterUrl = recPoster
            }
        }

        if (isTv) {
            val episodes = mutableListOf<Episode>()
            val seasonJsonRegex = """\{"seasons":\[.*?"episodes":\[.*?\]\}\]\}""".toRegex()
            val seasonJsonString = seasonJsonRegex.find(response)?.value

            if (seasonJsonString != null) {
                try {
                    val seasonData = parseJson<NextSeasons>(seasonJsonString)
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
                } catch (e: Exception) { e.printStackTrace() }
            } 
            
            if (episodes.isEmpty()) {
                 val episodeRegex = """"fullSlug":"(tv/.*?/season-(\d+)/episode-(\d+))"""".toRegex()
                 episodeRegex.findAll(response).forEach { match ->
                    val slug = match.groupValues[1]
                    val s = match.groupValues[2].toIntOrNull()
                    val e = match.groupValues[3].toIntOrNull()
                    episodes.add(newEpisode("$mainUrl/$slug") {
                        this.season = s
                        this.episode = e
                        this.name = "Episode $e"
                        this.data = "$mainUrl/$slug"
                    })
                }
            }

            val uniqueEpisodes: List<Episode> = episodes.distinctBy { it.data }

            return newTvSeriesLoadResponse(title, url, TvType.TvSeries, uniqueEpisodes) {
                this.posterUrl = poster
                this.year = year
                this.plot = description
                this.tags = tags
                // UPDATE: Dùng Score.from10 nếu ratingValue (VD: 5.4) có giá trị
                this.rating = ratingValue?.let { Score.from10(it) }
                this.actors = actors
                this.recommendations = recommendations
            }
        } else {
            return newMovieLoadResponse(title, url, TvType.Movie, url) {
                this.posterUrl = poster
                this.year = year
                this.plot = description
                this.tags = tags
                // UPDATE: Dùng Score.from10
                this.rating = ratingValue?.let { Score.from10(it) }
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
        val apiUrl = if (data.contains("/movies/")) {
            data.replace("/movies/", "/api/movies/")
        } else if (data.contains("/tv/")) {
            data.replace("/tv/", "/api/tv/")
        } else {
            data
        }

        try {
            val jsonText = app.get(apiUrl).text
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
            val html = app.get(data).text
            val embedRegex = """src\\":\\"(https:.*?)(\\?.*?)?\\"""".toRegex()
            embedRegex.findAll(html).forEach { match ->
                 val url = match.groupValues[1].replace("\\", "")
                 loadExtractor(url, subtitleCallback, callback)
            }
        }
        return true
    }
}
