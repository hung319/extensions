package recloudstream

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
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

    // --- LOAD (HEAVILY FIXED) ---
    override suspend fun load(url: String): LoadResponse {
        val headers = mapOf(
            "rsc" to "1",
            "Referer" to "$mainUrl/"
        )
        
        val rawResponse = app.get(url, headers = headers).text
        val isTv = url.contains("/tv/") || url.contains("season")

        // 1. Clean response: Unescape \" to " and remove newlines for easier regex
        val cleanResponse = rawResponse.replace("\\\"", "\"").replace("\\n", " ")

        // --- EXTRACT METADATA ---
        var title: String = "Unknown"
        var description: String? = null
        var poster: String = ""
        var year: Int? = null
        var ratingValue: Double? = null
        var tags: List<String>? = null
        var actors: List<ActorData>? = null

        // Priority 1: JSON-LD (Usually contains best data)
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

        // Priority 2: Fallback Regex from RSC Data
        if (poster.isEmpty()) {
            // Log shows: "posterPath":"/posters/webp/..." or "src":".../uploads/..."
            // Tìm tất cả ảnh poster và lấy cái đầu tiên hợp lệ
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
            // Log shows: "overview":"..."
            val descRegex = """["']overview["']\s*:\s*["'](.*?)["']""".toRegex()
            description = descRegex.find(cleanResponse)?.groupValues?.get(1)
        }

        // --- EXTRACT EPISODES (Fixed for incomplete lists) ---
        val episodes = mutableListOf<Episode>()
        if (isTv) {
            // Regex quét từng object tập phim dựa trên key unique
            // Log mẫu: "id":"...","slug":"...","episodeNumber":1,"title":"..."
            // Pattern này quét từng cụm dữ liệu tập phim, bất kể nó nằm ở đâu trong JSON
            val epBlockRegex = """\{"id":"[^"]+","slug":"[^"]+".*?"episodeNumber":(\d+).*?"title":"(.*?)".*?"overview":"(.*?)".*?"fullSlug":"(.*?)"(?:,"releaseDate":"(.*?)")?.*?}""".toRegex()
            
            val foundUrls = mutableSetOf<String>()
            
            epBlockRegex.findAll(cleanResponse).forEach { match ->
                val epNum = match.groupValues[1].toIntOrNull()
                val epTitle = match.groupValues[2]
                val epOverview = match.groupValues[3] // Description
                val fullSlug = match.groupValues[4]
                val releaseDate = match.groupValues[5]
                
                if (epNum != null && fullSlug.isNotEmpty() && !foundUrls.contains(fullSlug)) {
                    foundUrls.add(fullSlug)
                    
                    // Extract Season Number from slug (e.g., /season-1/)
                    val seasonNum = Regex("""season-(\d+)""").find(fullSlug)?.groupValues?.get(1)?.toIntOrNull() ?: 1
                    
                    episodes.add(newEpisode("$mainUrl/$fullSlug") {
                        this.season = seasonNum
                        this.episode = epNum
                        this.name = if (epTitle.isNotEmpty()) epTitle else "Episode $epNum"
                        this.description = epOverview // Sửa lỗi Unresolved reference
                        this.data = "$mainUrl/$fullSlug"
                        if (releaseDate.isNotEmpty()) this.addDate(releaseDate)
                    })
                }
            }
        }

        // --- EXTRACT RECOMMENDATIONS (From Genre Links) ---
        var recommendations = mutableListOf<SearchResponse>()
        try {
            // 1. Tìm danh sách Genre ID
            val genreRegex = """href":"\/genre\/([a-zA-Z0-9-]+)" """.toRegex()
            val genres = genreRegex.findAll(cleanResponse)
                .map { it.groupValues[1] }
                .distinct()
                .filter { !it.contains("search") } // Lọc link rác
                .toList()

            if (genres.isNotEmpty()) {
                // 2. Chọn 1 genre random để tải
                val randomGenre = genres[Random.nextInt(genres.size)]
                val genreUrl = "$mainUrl/genre/$randomGenre"
                
                // 3. Tải RSC của trang Genre
                val genreResponse = app.get(genreUrl, headers = headers).text
                val cleanGenreResponse = genreResponse.replace("\\\"", "\"")
                
                // 4. Regex quét phim trong trang Genre (Log: "originalTitle":..., "fullSlug":..., "posterPath":...)
                val recRegex = """\{"id":".*?","contentId".*?"originalTitle":"(.*?)".*?"fullSlug":"(.*?)".*?"posterPath":"(.*?)".*?"releaseYear":"?(\d+)"?.*?"id":.*?"type":"(movie|tv)"""".toRegex()
                
                recRegex.findAll(cleanGenreResponse).forEach { match ->
                    val rTitle = match.groupValues[1]
                    val rSlug = match.groupValues[2]
                    val rPoster = match.groupValues[3]
                    val rYear = match.groupValues[4].toIntOrNull()
                    // val rType = match.groupValues[5]
                    
                    val rUrl = "$mainUrl/$rSlug"
                    if (rUrl != url) { // Không recommend chính phim đang xem
                        recommendations.add(newMovieSearchResponse(rTitle, rUrl, TvType.Movie) {
                            this.posterUrl = fixUrl(rPoster)
                            this.year = rYear
                        })
                    }
                }
            }
        } catch (e: Exception) { e.printStackTrace() }

        // Sắp xếp và lọc trùng
        val finalEpisodes = episodes.distinctBy { it.data }.sortedWith(compareBy({ it.season }, { it.episode }))
        val finalRecommendations = recommendations.distinctBy { it.url }.shuffled().take(10)

        // Trả kết quả
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
            // Log shows: src":"https://..."
            val embedRegex = """src\\?":\\?"(https:.*?)(\\?.*?)?\\?"""".toRegex()
            embedRegex.findAll(jsonText).forEach { match ->
                 val url = match.groupValues[1].replace("\\", "")
                 if(!url.contains("google") && !url.contains("facebook")) {
                    Log.d(TAG, "Extractor URL found: $url")
                    loadExtractor(url, subtitleCallback, callback)
                 }
            }
            
            // Regex 2: Data-src attribute
            val dataSrcRegex = """data-src=\\?["'](https:.*?)(\\?.*?)?\\?["']""".toRegex()
            dataSrcRegex.findAll(jsonText).forEach { match ->
                val url = match.groupValues[1].replace("\\", "")
                if(!url.contains("google")) {
                    Log.d(TAG, "Extractor Data-Src found: $url")
                    loadExtractor(url, subtitleCallback, callback)
                }
            }

        } catch (e: Exception) {
            Log.e(TAG, "Error in loadLinks: ${e.message}")
            e.printStackTrace()
        }
        return true
    }
    
    // Hàm fix URL mạnh mẽ hơn cho ảnh
    private fun fixUrl(url: String): String {
        if (url.isEmpty()) return ""
        if (url.startsWith("http")) return url
        
        // Trường hợp bắt đầu bằng /uploads -> cần thêm /core/uploads nếu mainUrl k trỏ tới core
        // Tuy nhiên log cho thấy ảnh thường là https://ridomovies.tv/core/uploads/...
        // Hoặc tương đối: /posters/...
        
        return if (!url.startsWith("/")) {
            "$mainUrl/$url"
        } else {
            // Nếu url là /posters/... hoặc /backdrops/... thường phải thêm /uploads hoặc /core/uploads
            // Nhưng đơn giản nhất là cứ ghép domain vào trước
            "$mainUrl$url"
        }
    }
    
    private fun String.capitalize(): String {
        return this.replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() }
    }
}
