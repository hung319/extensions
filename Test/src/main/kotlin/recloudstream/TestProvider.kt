package recloudstream

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import org.jsoup.Jsoup

class RidoMoviesProvider : MainAPI() {
    override var mainUrl = "https://ridomovies.tv"
    override var name = "RidoMovies"
    override val hasMainPage = true
    override var lang = "en"
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)

    // Helper: Parse JSON data classes
    data class SearchResponse(val data: SearchData?)
    data class SearchData(val items: List<SearchItem>?)
    data class SearchItem(
        val title: String,
        val fullSlug: String,
        val type: String?,
        val posterPath: String?,
        val contentable: Contentable?
    )
    data class Contentable(val releaseYear: String?, val overview: String?)

    // Class cho API Links
    data class LinkResponse(val data: List<LinkItem>?)
    data class LinkItem(val url: String?) // Chứa chuỗi HTML iframe

    // --- MAIN PAGE ---
    override suspend fun mainPage(): HomePageResponse {
        // Gọi search rỗng để lấy list phim mới nhất thay vì parse HTML phức tạp
        return try {
            val search = search("")
            newHomePageResponse(
                listOf(HomePageList("Latest Movies", search))
            )
        } catch (e: Exception) {
            newHomePageResponse(emptyList())
        }
    }

    // --- SEARCH (Dùng API /core/api/search) ---
    override suspend fun search(query: String): List<SearchResponse> {
        val url = "$mainUrl/core/api/search?q=$query"
        val text = app.get(url).text
        
        val json = parseJson<SearchResponse>(text)
        val items = json.data?.items ?: return emptyList()

        return items.mapNotNull { item ->
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
    }

    // --- LOAD (Metadata - Parse HTML vì API Link thiếu info chi tiết) ---
    override suspend fun load(url: String): LoadResponse {
        val response = app.get(url).text
        val document = Jsoup.parse(response)

        val title = document.selectFirst("meta[property=og:title]")?.attr("content") ?: "Unknown"
        val description = document.selectFirst("meta[name=description]")?.attr("content")
        val poster = document.selectFirst("meta[property=og:image]")?.attr("content")
        val bgPoster = document.selectFirst("meta[property=og:image]")?.attr("content")
        
        val isTv = url.contains("/tv/") || url.contains("season")

        // Lấy danh sách phim đề xuất bên dưới
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
            // Regex tìm tập phim trong Next.js script
            val episodeRegex = """"fullSlug":"(tv/.*?/season-(\d+)/episode-(\d+))"""".toRegex()
            episodeRegex.findAll(response).forEach { match ->
                val slug = match.groupValues[1]
                val season = match.groupValues[2].toIntOrNull()
                val episode = match.groupValues[3].toIntOrNull()
                episodes.add(newEpisode("$mainUrl/$slug") {
                    this.season = season
                    this.episode = episode
                    this.name = "Episode $episode"
                })
            }
            
            return newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes.distinctBy { it.url }) {
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

    // --- LOAD LINKS (Dùng API /api/movies/...) ---
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        // data = https://ridomovies.tv/movies/speed-faster
        // Convert sang API endpoint
        val apiUrl = if (data.contains("/movies/")) {
            data.replace("/movies/", "/api/movies/")
        } else if (data.contains("/tv/")) {
            data.replace("/tv/", "/api/tv/") // Dự đoán fallback cho TV
        } else {
            data
        }

        try {
            val jsonText = app.get(apiUrl).text
            // API trả về mảng trực tiếp bên trong key "data"
            val jsonResponse = parseJson<LinkResponse>(jsonText)
            
            jsonResponse.data?.forEach { item ->
                val iframeHtml = item.url ?: return@forEach
                // Parse HTML string: <iframe ... data-src="...">
                val doc = Jsoup.parse(iframeHtml)
                val src = doc.select("iframe").attr("data-src")
                
                if (src.isNotEmpty()) {
                    loadExtractor(src, subtitleCallback, callback)
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
            // Fallback: Parse HTML trang gốc nếu API lỗi
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
