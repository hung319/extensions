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

    // --- FIX: Đổi tên các Data Class để không trùng với Cloudstream Core ---
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

    // --- MAIN PAGE ---
    override suspend fun mainPage(): HomePageResponse {
        return try {
            // Gọi hàm search rỗng để lấy phim mới
            val searchResults = search("")
            newHomePageResponse(
                listOf(HomePageList("Latest Movies", searchResults))
            )
        } catch (e: Exception) {
            newHomePageResponse(emptyList())
        }
    }

    // --- SEARCH ---
    // Trả về List<SearchResponse> của Cloudstream (không phải class nội bộ)
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
