package recloudstream

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import com.lagradost.cloudstream3.utils.AppUtils.tryParseJson
import com.lagradost.cloudstream3.utils.AppUtils.toJson
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

    // --- DATA TRANSFER OBJECT (Thêm title vào để truyền sang Load) ---
    data class RidoLinkData(
        val url: String,
        val title: String? = null,
        val poster: String? = null,
        val year: Int? = null
    )

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
    
    data class ApiContentable(
        val releaseYear: String? = null, 
        val overview: String? = null
    )

    data class ApiLinkResponse(val data: List<ApiLinkItem>?)
    data class ApiLinkItem(val url: String?)

    data class LdJson(
        val name: String?,
        val description: String?,
        val image: String?,
        val dateCreated: String?,
        val genre: List<String>?,
        val aggregateRating: LdRating?
    )
    data class LdRating(val ratingValue: String?)

    // --- MAIN PAGE ---
    override val mainPage = mainPageOf(
        "$mainUrl/core/api/movies/latest?page%5Bnumber%5D=" to "Latest Movies",
        "$mainUrl/core/api/series/latest?page%5Bnumber%5D=" to "Latest TV Series"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = request.data + page
        return try {
            val headers = mapOf(
                "Referer" to "$mainUrl/",
                "X-Requested-With" to "XMLHttpRequest",
                "Accept" to "application/json"
            )
            val response = app.get(url, headers = headers)
            val json = parseJson<ApiResponse>(response.text)
            val items = json.data?.items ?: emptyList()

            val homeList = items.mapNotNull { item ->
                val title = item.title ?: item.content?.title ?: return@mapNotNull null
                val slug = item.fullSlug ?: item.content?.fullSlug ?: return@mapNotNull null
                val rawType = item.type ?: item.content?.type
                
                val href = "$mainUrl/$slug"
                val poster = fixUrl(item.posterPath ?: "")
                val type = if (rawType?.contains("tv") == true) TvType.TvSeries else TvType.Movie
                val year = item.releaseYear?.toIntOrNull() ?: item.contentable?.releaseYear?.toIntOrNull()

                // Đóng gói URL + Title + Poster + Year vào JSON
                val data = RidoLinkData(href, title, poster, year)
                val dataStr = toJson(data)

                newMovieSearchResponse(title, dataStr, type) {
                    this.posterUrl = poster
                    this.year = year
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
            val headers = mapOf("Referer" to "$mainUrl/")
            val text = app.get(url, headers = headers).text
            val json = parseJson<SearchRoot>(text)
            val items = json.data?.items ?: return emptyList()

            items.mapNotNull { item ->
                val title = item.title ?: item.content?.title ?: return@mapNotNull null
                val slug = item.fullSlug ?: item.content?.fullSlug ?: return@mapNotNull null
                val type = if (item.type?.contains("tv") == true) TvType.TvSeries else TvType.Movie
                val poster = fixUrl(item.posterPath ?: "")
                val year = item.releaseYear?.toIntOrNull() ?: item.contentable?.releaseYear?.toIntOrNull()

                // Đóng gói data truyền đi
                val data = RidoLinkData("$mainUrl/$slug", title, poster, year)
                val dataStr = toJson(data)

                newMovieSearchResponse(title, dataStr, type) {
                    this.posterUrl = poster
                    this.year = year
                }
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    // --- LOAD (USE PASSED DATA FIRST) ---
    override suspend fun load(url: String): LoadResponse {
        // 1. Giải nén dữ liệu từ MainPage/Search
        val linkData = tryParseJson<RidoLinkData>(url)
        val realUrl = linkData?.url ?: url
        
        // Ưu tiên lấy dữ liệu đã truyền qua (Chính xác 100%)
        // Nếu không có (null/empty) thì gán giá trị mặc định để lát tìm sau
        var title = if (!linkData?.title.isNullOrEmpty()) linkData!!.title!! else "Unknown"
        var poster = linkData?.poster ?: ""
        var year = linkData?.year
        
        val headers = mapOf("rsc" to "1", "Referer" to "$mainUrl/")
        val responseText = app.get(realUrl, headers = headers).text
        val isTv = realUrl.contains("/tv/") || realUrl.contains("season")

        // Clean response
        val cleanResponse = responseText.replace("\\\"", "\"").replace("\\n", " ")

        // --- A. EXTRACT METADATA (Fallback only) ---
        var description: String? = null
        var ratingValue: Double? = null
        var tags: List<String>? = null

        // 1. JSON-LD Extraction (Scan for details we might miss)
        val jsonLdRegex = """\{"@context":"https://schema.org".*?"@type":"(Movie|TVSeries)".*?\}""".toRegex()
        val jsonLdMatch = jsonLdRegex.find(cleanResponse)?.value
        if (jsonLdMatch != null) {
            try {
                val ldData = parseJson<LdJson>(jsonLdMatch)
                // Chỉ override nếu dữ liệu hiện tại chưa có hoặc là "Unknown"
                if (title == "Unknown") title = ldData.name ?: title
                description = ldData.description
                if (poster.isEmpty()) poster = fixUrl(ldData.image ?: "")
                if (year == null) year = ldData.dateCreated?.take(4)?.toIntOrNull()
                tags = ldData.genre
                ratingValue = ldData.aggregateRating?.ratingValue?.toDoubleOrNull()
            } catch (e: Exception) { }
        }

        // 2. Regex Fallback (Nếu vẫn thiếu thông tin)
        if (title == "Unknown") {
            // Tìm title trong thẻ H1 nếu vẫn chưa có tên
            val h1Match = """\["\$","h1",null,\{"children":"(.*?)"\}\]""".toRegex().find(cleanResponse)
            if (h1Match != null) {
                title = h1Match.groupValues[1]
            } else {
                val origTitle = """\"originalTitle\":\"([^\"]+?)\"""".toRegex().find(cleanResponse)
                if (origTitle != null) title = origTitle.groupValues[1]
            }
        }
        
        if (poster.isEmpty()) {
            val absUrlRegex = """\"(https:[^\"]*?\/uploads\/(?:posters|backdrops)\/[^\"]*?\.(?:webp|jpg|png))\"""".toRegex()
            var pMatch = absUrlRegex.find(cleanResponse)
            if (pMatch != null) {
                poster = pMatch.groupValues[1]
            } else {
                val relUrlRegex = """\"(\/uploads\/(?:posters|backdrops)\/[^\"]*?\.(?:webp|jpg|png))\"""".toRegex()
                pMatch = relUrlRegex.find(cleanResponse)
                if (pMatch != null) poster = fixUrl(pMatch.groupValues[1])
            }
        }

        if (description.isNullOrEmpty()) {
            val textRegex = """\"text\":\"<p>(.*?)<\/p>\"""".toRegex()
            description = textRegex.find(cleanResponse)?.groupValues?.get(1)
            
            if (description == null) {
                val overviewRegex = """\"overview\":\"([^\"]{20,}?)\"""".toRegex()
                description = overviewRegex.find(cleanResponse)?.groupValues?.get(1)
            }
        }

        // --- B. EXTRACT EPISODES ---
        val episodes = mutableListOf<Episode>()
        if (isTv) {
            val slugRegex = """\"fullSlug\":\"(tv\/[^\"]+?\/season-(\d+)\/episode-(\d+)[^\"]*)\"""".toRegex()
            val addedEps = mutableSetOf<String>()
            
            slugRegex.findAll(cleanResponse).forEach { match ->
                val fullSlug = match.groupValues[1]
                val seasonNum = match.groupValues[2].toIntOrNull() ?: 1
                val episodeNum = match.groupValues[3].toIntOrNull() ?: 1
                
                if (addedEps.add(fullSlug)) {
                    val epTitle = "Episode $episodeNum"
                    episodes.add(newEpisode("$mainUrl/$fullSlug") {
                        this.season = seasonNum
                        this.episode = episodeNum
                        this.name = epTitle
                        this.data = "$mainUrl/$fullSlug"
                    })
                }
            }
        }

        // --- C. RECOMMENDATIONS (Pack Data for Next Load) ---
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
                val genreRes = app.get(genreUrl, headers = headers).text
                val cleanGenreRes = genreRes.replace("\\\"", "\"")
                
                val recRegex = """\"originalTitle\":\"(.*?)\".*?\"fullSlug\":\"(.*?)\".*?\"posterPath\":\"(.*?)\"""".toRegex()
                
                recRegex.findAll(cleanGenreRes).forEach { m ->
                    val rTitle = m.groupValues[1]
                    val rSlug = m.groupValues[2]
                    val rPoster = fixUrl(m.groupValues[3])
                    
                    if (!rSlug.contains(realUrl)) {
                        // Đóng gói thông tin cho phim gợi ý luôn
                        val recData = RidoLinkData("$mainUrl/$rSlug", rTitle, rPoster)
                        val recDataStr = toJson(recData)
                        
                        recommendations.add(newMovieSearchResponse(rTitle, recDataStr, TvType.Movie) {
                            this.posterUrl = rPoster
                        })
                    }
                }
            }
        } catch (e: Exception) { }

        val finalEpisodes = episodes.sortedWith(compareBy({ it.season }, { it.episode }))
        val finalRecs = recommendations.distinctBy { it.url }.shuffled().take(10)

        // Return Result (Sử dụng realUrl làm URL định danh)
        if (isTv) {
            return newTvSeriesLoadResponse(title, realUrl, TvType.TvSeries, finalEpisodes) {
                this.posterUrl = poster
                this.year = year
                this.plot = description
                this.tags = tags
                this.score = ratingValue?.let { Score.from10(it) }
                this.recommendations = finalRecs
            }
        } else {
            return newMovieLoadResponse(title, realUrl, TvType.Movie, realUrl) {
                this.posterUrl = poster
                this.year = year
                this.plot = description
                this.tags = tags
                this.score = ratingValue?.let { Score.from10(it) }
                this.recommendations = finalRecs
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
        // Data vào đây là Real URL (do LoadResponse trả về)
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
            
            val embedRegex = """src\\?":\\?"(https:.*?)(\\?.*?)?\\?"""".toRegex()
            embedRegex.findAll(jsonText).forEach { match ->
                 val url = match.groupValues[1].replace("\\", "")
                 if(!url.contains("google") && !url.contains("facebook")) {
                    loadExtractor(url, subtitleCallback, callback)
                 }
            }
            
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
        if (url.isEmpty()) return ""
        if (url.startsWith("http")) return url
        // Nếu bắt đầu bằng / thì nối mainUrl
        val cleanUrl = if (url.startsWith("/")) url else "/$url"
        return "$mainUrl$cleanUrl"
    }
    
    private fun String.capitalize(): String {
        return this.replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() }
    }
}
