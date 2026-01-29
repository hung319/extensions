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
    
    data class ApiContentable(
        val releaseYear: String? = null, 
        val overview: String? = null
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

                newMovieSearchResponse(title, href, type) {
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

                newMovieSearchResponse(title, "$mainUrl/$slug", type) {
                    this.posterUrl = poster
                    this.year = year
                }
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    // --- LOAD (HEAVILY MODIFIED FOR ROBUSTNESS) ---
    override suspend fun load(url: String): LoadResponse {
        val headers = mapOf("rsc" to "1", "Referer" to "$mainUrl/")
        val responseText = app.get(url, headers = headers).text
        val isTv = url.contains("/tv/") || url.contains("season")

        // 1. Clean response (Unescape quotes for easier regex matching)
        // Thay thế \" thành " và xóa xuống dòng để regex 1 dòng hoạt động tốt
        val cleanResponse = responseText.replace("\\\"", "\"").replace("\\n", " ")

        // --- A. EXTRACT METADATA (CASCADING FALLBACK) ---
        var title: String = "Unknown"
        var description: String? = null
        var poster: String = ""
        var year: Int? = null
        var ratingValue: Double? = null
        var tags: List<String>? = null

        // Strategy 1: JSON-LD (Dữ liệu chuẩn nhất nếu có)
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
            } catch (e: Exception) { }
        }

        // Strategy 2: Regex Fallback (Nếu JSON-LD thiếu)
        // Title
        if (title == "Unknown") {
            title = """\"title\":\"([^\"]+?)\"""".toRegex().find(cleanResponse)?.groupValues?.get(1) ?: "Unknown"
        }
        
        // Poster (Tìm key posterPath hoặc src chứa ảnh)
        if (poster.isEmpty()) {
            val pRegex = """(?:posterPath|src)["']\s*:\s*["']([^"']+\.(?:jpg|png|webp))["']""".toRegex()
            poster = pRegex.findAll(cleanResponse)
                .map { it.groupValues[1] }
                .firstOrNull { it.contains("posters") || it.contains("backdrops") }
                ?.let { fixUrl(it) } ?: ""
        }

        // Description (Tìm nhiều pattern khác nhau)
        if (description.isNullOrEmpty()) {
            // Pattern 1: RSC "overview":"..."
            val overviewRegex = """\"overview\":\"([^\"]{20,}?)\"""".toRegex() // Lấy > 20 ký tự để tránh rác
            description = overviewRegex.find(cleanResponse)?.groupValues?.get(1)
            
            // Pattern 2: HTML Meta tag (nếu có trong response)
            if (description == null) {
                val metaDesc = """name="description" content="(.*?)"""".toRegex().find(responseText)
                description = metaDesc?.groupValues?.get(1)
            }
            
            // Pattern 3: RSC Text block (thường dùng cho description hiển thị trên web)
            if (description == null) {
                val textRegex = """\"text\":\"<p>(.*?)<\/p>\"""".toRegex()
                description = textRegex.find(cleanResponse)?.groupValues?.get(1)
            }
        }

        // --- B. EXTRACT EPISODES (SIMPLIFIED & ROBUST) ---
        val episodes = mutableListOf<Episode>()
        if (isTv) {
            // Thay vì cố bắt cả ID, Title, Overview cùng lúc (dễ lỗi syntax hoặc thiếu),
            // Ta chỉ bắt cái QUAN TRỌNG NHẤT: Full Slug (Link tập phim).
            // Pattern: "fullSlug":"tv/ten-phim/season-X/episode-Y"
            val slugRegex = """\"fullSlug\":\"(tv\/[^\"]+?\/season-(\d+)\/episode-(\d+))\"""".toRegex()
            
            val addedEps = mutableSetOf<String>()
            
            slugRegex.findAll(cleanResponse).forEach { match ->
                val fullSlug = match.groupValues[1]
                val seasonNum = match.groupValues[2].toIntOrNull() ?: 1
                val episodeNum = match.groupValues[3].toIntOrNull() ?: 1
                
                if (addedEps.add(fullSlug)) { // Tránh trùng lặp
                    // Mặc định tên là "Episode X" (An toàn nhất)
                    var epTitle = "Episode $episodeNum"
                    var epDesc: String? = null

                    // Tùy chọn: Thử tìm Title nằm GẦN link này (Heuristic scan)
                    // Lấy đoạn text xung quanh match (trước sau 300 ký tự)
                    try {
                        val startIndex = (match.range.first - 300).coerceAtLeast(0)
                        val endIndex = (match.range.last + 300).coerceAtMost(cleanResponse.length)
                        val contextBlock = cleanResponse.substring(startIndex, endIndex)
                        
                        // Tìm "title":"..." trong block này
                        val titleMatch = """\"title\":\"([^\"]+?)\"""".toRegex().find(contextBlock)
                        if (titleMatch != null) {
                            val t = titleMatch.groupValues[1]
                            // Lọc bỏ nếu title trùng tên phim chính hoặc là tên Season
                            if (t != title && !t.contains("Season", true)) {
                                epTitle = t
                            }
                        }
                        
                        // Tìm "overview":"..." trong block này (nếu muốn lấy mô tả tập)
                        val descMatch = """\"overview\":\"([^\"]+?)\"""".toRegex().find(contextBlock)
                        if (descMatch != null) {
                            epDesc = descMatch.groupValues[1]
                        }
                    } catch (e: Exception) { }

                    episodes.add(newEpisode("$mainUrl/$fullSlug") {
                        this.season = seasonNum
                        this.episode = episodeNum
                        this.name = epTitle
                        this.description = epDesc
                        this.data = "$mainUrl/$fullSlug"
                    })
                }
            }
        }

        // --- C. RECOMMENDATIONS (FROM GENRE) ---
        var recommendations = mutableListOf<SearchResponse>()
        try {
            // Lấy 1 genre link bất kỳ có trong trang
            val genreRegex = """href\":\"\/genre\/([a-zA-Z0-9-]+)\"""".toRegex()
            val genres = genreRegex.findAll(cleanResponse)
                .map { it.groupValues[1] }
                .distinct()
                .filter { !it.contains("search") }
                .toList()

            if (genres.isNotEmpty()) {
                val randomGenre = genres[Random.nextInt(genres.size)]
                val genreUrl = "$mainUrl/genre/$randomGenre"
                
                // Gọi API lấy list phim của genre đó
                val genreRes = app.get(genreUrl, headers = headers).text
                val cleanGenreRes = genreRes.replace("\\\"", "\"")
                
                // Quét nhanh list phim: title + slug + poster
                // Pattern lỏng lẻo để bắt được nhiều phim nhất có thể
                val recRegex = """\"originalTitle\":\"(.*?)\".*?\"fullSlug\":\"(.*?)\".*?\"posterPath\":\"(.*?)\"""".toRegex()
                
                recRegex.findAll(cleanGenreRes).forEach { m ->
                    val rTitle = m.groupValues[1]
                    val rSlug = m.groupValues[2]
                    val rPoster = fixUrl(m.groupValues[3])
                    
                    if (!rSlug.contains(url)) { // Không recommend chính nó
                        recommendations.add(newMovieSearchResponse(rTitle, "$mainUrl/$rSlug", TvType.Movie) {
                            this.posterUrl = rPoster
                        })
                    }
                }
            }
        } catch (e: Exception) { }

        val finalEpisodes = episodes.sortedWith(compareBy({ it.season }, { it.episode }))
        val finalRecs = recommendations.distinctBy { it.url }.shuffled().take(10)

        if (isTv) {
            return newTvSeriesLoadResponse(title, url, TvType.TvSeries, finalEpisodes) {
                this.posterUrl = poster
                this.year = year
                this.plot = description
                this.tags = tags
                this.score = ratingValue?.let { Score.from10(it) }
                this.recommendations = finalRecs
            }
        } else {
            return newMovieLoadResponse(title, url, TvType.Movie, url) {
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
        // Log để debug url tập phim
        Log.d(TAG, "Loading Links for: $data")
        
        // Convert web url -> api url nếu cần
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
            
            // Regex quét link iframe (src="..." hoặc data-src="...")
            // Hỗ trợ cả JSON escaped (\") và HTML thường
            val urlRegex = """(?:src|data-src)=?\\?["'](https:[^"']*?)(?:\\?["']|\\)""".toRegex()
            
            urlRegex.findAll(jsonText).forEach { match ->
                var link = match.groupValues[1].replace("\\", "") // Bỏ escape
                
                if (!link.contains("google") && !link.contains("facebook") && !link.contains("twitter")) {
                    Log.d(TAG, "Found Iframe: $link")
                    loadExtractor(link, subtitleCallback, callback)
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
        // Nếu là relative path (/uploads/...) thì nối mainUrl
        return if (url.startsWith("/")) "$mainUrl$url" else "$mainUrl/$url"
    }
    
    private fun String.capitalize(): String {
        return this.replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() }
    }
}
