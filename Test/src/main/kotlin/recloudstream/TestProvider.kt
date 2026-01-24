package recloudstream

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import com.lagradost.cloudstream3.mvvm.Resource
import org.jsoup.Jsoup

class RidoMoviesProvider : MainAPI() { 
    override var mainUrl = "https://ridomovies.tv"
    override var name = "RidoMovies"
    
    // hasMainPage = true báo cho app biết Provider này có trang chủ
    override val hasMainPage = true
    override var lang = "en"
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)

    // --- DATA CLASSES (Thêm prefix Api để tránh xung đột) ---
    data class ApiSearchResponse(val data: ApiSearchData?)
    data class ApiSearchData(val items: List<ApiSearchItem>?)
    data class ApiSearchItem(
        val title: String,
        val fullSlug: String,
        val type: String?,
        val posterPath: String?,
        val contentable: ApiContentable?
    )
    data class ApiContentable(val releaseYear: String?, val overview: String?)

    data class ApiLinkResponse(val data: List<ApiLinkItem>?)
    data class ApiLinkItem(val url: String?)

    // --- SỬA ĐỔI: Dùng getMainPage thay vì mainPage ---
    // Override hàm này để trả về dữ liệu trang chủ
    override suspend fun getMainPage(
        page: Int, 
        request: MainPageRequest
    ): HomePageResponse {
        return try {
            // Gọi hàm search rỗng để lấy list phim mới nhất
            val searchResults = search("")
            
            // Trả về object HomePageResponse chuẩn
            newHomePageResponse(
                listOf(HomePageList("Latest Movies / Featured", searchResults))
            )
        } catch (e: Exception) {
            newHomePageResponse(emptyList())
        }
    }

    // --- SEARCH ---
    override suspend fun search(query: String): List<SearchResponse> {
        val url = "$mainUrl/core/api/search?q=$query"
        return try {
            val text = app.get(url).text
            val json = parseJson<ApiSearchResponse>(text)
            val items = json.data?.items ?: return emptyList()

            items.mapNotNull { item ->
                val title = item.title
                val href = "$mainUrl/${item.fullSlug}"
                val poster = if (item.posterPath != null) "$mainUrl${item.posterPath}" else ""
                val type = if (item.type == "movie") TvType.Movie else TvType.TvSeries
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
        val document = Jsoup.parse(response)

        val title = document.selectFirst("meta[property=og:title]")?.attr("content") ?: "Unknown"
        val description = document.selectFirst("meta[name=description]")?.attr("content")
        val poster = document.selectFirst("meta[property=og:image]")?.attr("content")
        val bgPoster = document.selectFirst("meta[property=og:image]")?.attr("content")
        
        val isTv = url.contains("/tv/") || url.contains("season")

        val recommendations = document.select("div.grid > a").mapNotNull {
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
            val episodeRegex = """"fullSlug":"(tv/.*?/season-(\d+)/episode-(\d+))"""".toRegex()
            
            episodeRegex.findAll(response).forEach { match ->
                val slug = match.groupValues[1]
                val season = match.groupValues[2].toIntOrNull()
                val episode = match.groupValues[3].toIntOrNull()
                
                // Url truyền vào đây sẽ được lưu vào biến 'data' của Episode
                episodes.add(newEpisode("$mainUrl/$slug") {
                    this.season = season
                    this.episode = episode
                    this.name = "Episode $episode"
                })
            }
            
            // SỬA ĐỔI QUAN TRỌNG:
            // 1. Dùng it.data thay vì it.url (Cloudstream Episode lưu link trong 'data')
            // 2. Định nghĩa kiểu List<Episode> rõ ràng để tránh lỗi "Cannot infer type"
            val uniqueEpisodes: List<Episode> = episodes.distinctBy { it.data }

            return newTvSeriesLoadResponse(title, url, TvType.TvSeries, uniqueEpisodes) {
                this.posterUrl = poster
                this.backgroundPosterUrl = bgPoster
                this.plot = description
                this.recommendations = recommendations
            }
        } else {
            return newMovieLoadResponse(title, url, TvType.Movie, url) {
                this.posterUrl = poster
                this.backgroundPosterUrl = bgPoster
                this.plot = description
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
        // data ở đây chính là url chúng ta đã lưu vào Episode hoặc Movie
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
            // Fallback: Parse HTML gốc
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
