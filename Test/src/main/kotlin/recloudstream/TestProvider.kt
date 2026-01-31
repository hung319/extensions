package recloudstream

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import com.lagradost.cloudstream3.utils.AppUtils.tryParseJson
import com.lagradost.cloudstream3.utils.AppUtils.toJson
import com.lagradost.cloudstream3.Score
import android.util.Log
import kotlin.random.Random

class RidoMoviesProvider : MainAPI() {
    override var mainUrl = "https://ridomovies.tv"
    override var name = "RidoMovies"
    override val hasMainPage = true
    override var lang = "en"
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)
    
    private val TAG = "RidoMovies"
    
    // UserAgent cứng
    private val commonUserAgent = "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36"

    // --- DTOs ---
    data class RidoLinkData(
        val url: String,
        val title: String? = null,
        val poster: String? = null,
        val year: Int? = null,
        val type: String? = null
    )

    // DTO cho API trả về (Parse JSON chuẩn để tránh lỗi Regex)
    data class RidoEpResponse(val data: List<RidoEpData>?)
    data class RidoEpData(val url: String?) // Chứa đoạn HTML iframe

    // --- API STRUCTURES ---
    data class ApiResponse(val data: ApiData?)
    data class ApiData(val items: List<ApiItem>?)
    data class ApiItem(
        val title: String?, val fullSlug: String?, val type: String?, 
        val posterPath: String?, val releaseYear: String?,
        val content: ApiNestedContent?, val contentable: ApiContentable?
    )
    data class ApiNestedContent(val title: String?, val fullSlug: String?, val type: String?)
    data class ApiContentable(val releaseYear: String?)

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
                
                val href = fixUrl(slug)
                val poster = fixUrl(item.posterPath ?: "")
                val type = if (rawType?.contains("tv") == true) TvType.TvSeries else TvType.Movie
                val year = item.releaseYear?.toIntOrNull() ?: item.contentable?.releaseYear?.toIntOrNull()

                // LOGIC CHUẨN: Đóng gói data vào JSON String để truyền xuống Load
                val data = RidoLinkData(href, title, poster, year, rawType)
                newMovieSearchResponse(title, data.toJson(), type) {
                    this.posterUrl = poster
                    this.year = year
                }
            }
            newHomePageResponse(request.name, homeList, true)
        } catch (e: Exception) {
            newHomePageResponse(request.name, emptyList())
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val url = "$mainUrl/core/api/search?q=$query"
        return try {
            val response = app.get(url, headers = mapOf("Referer" to "$mainUrl/"))
            parseJson<ApiResponse>(response.text).data?.items?.mapNotNull { item ->
                val title = item.title ?: item.content?.title ?: return@mapNotNull null
                val slug = item.fullSlug ?: item.content?.fullSlug ?: return@mapNotNull null
                val type = if (item.type?.contains("tv") == true) TvType.TvSeries else TvType.Movie
                val poster = fixUrl(item.posterPath ?: "")
                val year = item.releaseYear?.toIntOrNull() ?: item.contentable?.releaseYear?.toIntOrNull()
                
                val data = RidoLinkData(fixUrl(slug), title, poster, year)
                newMovieSearchResponse(title, data.toJson(), type) {
                    this.posterUrl = poster
                    this.year = year
                }
            } ?: emptyList()
        } catch (e: Exception) { emptyList() }
    }

    // --- LOAD ---
    override suspend fun load(url: String): LoadResponse {
        // 1. Nhận Data từ MainPage (Parse JSON string)
        val linkData = tryParseJson<RidoLinkData>(url)
        val realUrl = linkData?.url ?: url
        val title = linkData?.title ?: "RidoMovies"
        val year = linkData?.year
        val poster = linkData?.poster
        
        // 2. Fetch RSC Data
        val headers = mapOf("rsc" to "1", "Referer" to "$mainUrl/", "User-Agent" to commonUserAgent)
        val responseText = app.get(realUrl, headers = headers).text
        val isTv = realUrl.contains("/tv/") || linkData?.type?.contains("tv") == true

        // 3. Extract Metadata (Description, Rating)
        // Description: Ưu tiên lấy từ post-overview trong RSC
        val descRegex = """className":"post-overview","text":"(.*?)"""".toRegex()
        val rawDesc = descRegex.find(responseText)?.groupValues?.get(1)
        val description = rawDesc?.replace(Regex("<.*?>"), "")?.replace("\\\"", "\"") 
            ?: """name":"description","content":"(.*?)"""".toRegex().find(responseText)?.groupValues?.get(1)

        val ratingRegex = """ratingValue":([\d.]+)""".toRegex()
        val ratingVal = ratingRegex.find(responseText)?.groupValues?.get(1)?.toDoubleOrNull()

        // 4. KHÔI PHỤC RECOMMENDATIONS (Logic Random Genre)
        val recommendations = mutableListOf<SearchResponse>()
        try {
            val genreRegex = """href\":\"\/genre\/([a-zA-Z0-9-]+)\"""".toRegex()
            val genres = genreRegex.findAll(responseText)
                .map { it.groupValues[1] }
                .distinct()
                .filter { !it.contains("search") }
                .toList()

            if (genres.isNotEmpty()) {
                val randomGenre = genres[Random.nextInt(genres.size)]
                val genreUrl = "$mainUrl/genre/$randomGenre"
                // Gọi API search/genre (dùng header thường)
                val genreRes = app.get(genreUrl, headers = mapOf("Referer" to "$mainUrl/")).text
                
                // Parse simple regex cho danh sách phim gợi ý
                val recRegex = """originalTitle":"(.*?)".*?fullSlug":"(.*?)".*?posterPath":"(.*?)"""".toRegex()
                recRegex.findAll(genreRes).take(10).forEach { m ->
                    val rTitle = m.groupValues[1]
                    val rSlug = m.groupValues[2]
                    val rPoster = fixUrl(m.groupValues[3])
                    val rUrl = fixUrl(rSlug)
                    
                    if (!rUrl.contains(realUrl)) {
                        val recData = RidoLinkData(rUrl, rTitle, rPoster)
                        recommendations.add(newMovieSearchResponse(rTitle, recData.toJson(), TvType.Movie) {
                            this.posterUrl = rPoster
                        })
                    }
                }
            }
        } catch (e: Exception) { Log.e(TAG, "Recs Error", e) }

        // 5. Logic Episodes & Movie API Link
        val episodes = mutableListOf<Episode>()
        val finalUrl: String

        if (isTv) {
            val epRegex = """\{"id":"(\d+)","slug":"([^"]*?\/season-(\d+)\/episode-(\d+)[^"]*)"""".toRegex()
            val addedIds = mutableSetOf<String>()
            
            epRegex.findAll(responseText).forEach { match ->
                val epId = match.groupValues[1]
                val season = match.groupValues[3].toIntOrNull() ?: 1
                val episode = match.groupValues[4].toIntOrNull() ?: 1
                
                if (addedIds.add(epId)) {
                    val apiLink = "$mainUrl/api/episodes/$epId"
                    episodes.add(newEpisode(apiLink) {
                        this.season = season
                        this.episode = episode
                        this.name = "Episode $episode"
                        this.data = apiLink 
                    })
                }
            }
            finalUrl = realUrl
        } else {
            // MOVIE: Dùng SLUG
            val slug = realUrl.split("/").lastOrNull { it.isNotEmpty() }?.substringBefore("?") ?: ""
            finalUrl = "$mainUrl/api/movies/$slug"
        }

        val sortedEpisodes = episodes.sortedWith(compareBy({ it.season }, { it.episode }))

        return if (isTv) {
            newTvSeriesLoadResponse(title, realUrl, TvType.TvSeries, sortedEpisodes) {
                this.posterUrl = poster
                this.year = year
                this.plot = description
                this.score = ratingVal?.let { Score.from10(it) }
                this.recommendations = recommendations
            }
        } else {
            // Truyền Link API vào data để loadLinks dùng ngay
            newMovieLoadResponse(title, realUrl, TvType.Movie, finalUrl) {
                this.posterUrl = poster
                this.year = year
                this.plot = description
                this.score = ratingVal?.let { Score.from10(it) }
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
        // data = https://ridomovies.tv/api/movies/grizzly-night
        try {
            val headers = mapOf(
                "Authority" to "ridomovies.tv",
                "X-Requested-With" to "XMLHttpRequest",
                "Referer" to "$mainUrl/"
            )
            
            val jsonText = app.get(data, headers = headers).text
            
            // FIX: Parse JSON thay vì Regex Raw String để tránh lỗi escape chars
            val jsonResponse = tryParseJson<RidoEpResponse>(jsonText)
            val iframeHtml = jsonResponse?.data?.firstOrNull()?.url
            
            if (iframeHtml != null) {
                // Regex tìm link trong thẻ iframe (đã unescape)
                val srcRegex = """data-src=["'](https:.*?)["']""".toRegex()
                val embedUrl = srcRegex.find(iframeHtml)?.groupValues?.get(1)
                
                if (embedUrl != null) {
                    Log.d(TAG, "Embed Found: $embedUrl")
                    if (embedUrl.contains("closeload")) {
                        extractCloseload(embedUrl, callback)
                    } else if (embedUrl.contains("ridoo")) {
                        extractRidoo(embedUrl, callback)
                    } else {
                        loadExtractor(embedUrl, subtitleCallback, callback)
                    }
                } else {
                    Log.e(TAG, "No data-src found in iframe HTML")
                }
            } else {
                Log.e(TAG, "No 'url' (iframe) found in API JSON")
            }
        } catch (e: Exception) {
            Log.e(TAG, "LoadLinks Error", e)
        }
        return true
    }

    // --- EXTRACTOR 1: CLOSELOAD (Dùng JsUnpacker & Referer chuẩn) ---
    private suspend fun extractCloseload(url: String, callback: (ExtractorLink) -> Unit) {
        try {
            val headers = mapOf(
                "Referer" to "$mainUrl/", // Referer khi vào trang embed phải là trang web gốc
                "User-Agent" to commonUserAgent
            )
            val text = app.get(url, headers = headers).text
            
            val packedRegex = """eval\(function\(p,a,c,k,e,d\).*?\.split\('\|'\)\)\)""".toRegex()
            val packedJs = packedRegex.find(text)?.value
            
            if (packedJs != null) {
                val unpacked = JsUnpacker(packedJs).unpack() ?: ""
                
                // Regex tìm file: "https://srv12..."
                val fileMatch = """file\s*:\s*["']([^"']+)["']""".toRegex().find(unpacked)
                val masterUrl = fileMatch?.groupValues?.get(1)
                
                if (masterUrl != null) {
                    callback.invoke(
                        newExtractorLink(name, "Closeload", masterUrl, ExtractorLinkType.M3U8) {
                            this.referer = "https://closeload.top/" // QUAN TRỌNG: Server check referer này
                            this.quality = Qualities.Unknown.value
                        }
                    )
                }
            } else {
                // Fallback nếu không có packed code
                val jsonLdRegex = """"contentUrl"\s*:\s*"([^"]+)"""".toRegex()
                val contentUrl = jsonLdRegex.find(text)?.groupValues?.get(1)
                if (contentUrl != null) {
                    callback.invoke(
                        newExtractorLink(name, "Closeload (Backup)", contentUrl, ExtractorLinkType.M3U8) {
                            this.referer = "https://closeload.top/"
                        }
                    )
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Closeload Error", e)
        }
    }

    // --- EXTRACTOR 2: RIDOO ---
    private suspend fun extractRidoo(url: String, callback: (ExtractorLink) -> Unit) {
        try {
            val text = app.get(url, headers = mapOf("Referer" to "$mainUrl/")).text
            val fileRegex = """file\s*:\s*["']([^"']+)["']""".toRegex()
            val m3u8 = fileRegex.find(text)?.groupValues?.get(1)
            
            if (m3u8 != null) {
                callback.invoke(
                    newExtractorLink(name, "RidoMovies", m3u8, ExtractorLinkType.M3U8) {
                        this.referer = url
                        this.quality = Qualities.Unknown.value
                    }
                )
            }
        } catch (e: Exception) { Log.e(TAG, "Ridoo Error", e) }
    }

    private fun fixUrl(url: String): String {
        if (url.isEmpty()) return ""
        if (url.startsWith("http")) return url
        val cleanUrl = if (url.startsWith("/")) url else "/$url"
        return "$mainUrl$cleanUrl"
    }
}
