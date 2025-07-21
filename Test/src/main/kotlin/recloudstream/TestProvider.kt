package recloudstream

// Jsoup for HTML parsing
import org.jsoup.nodes.Element

// CloudStream specific imports
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.HomePageResponse
import com.lagradost.cloudstream3.HomePageList
import com.lagradost.cloudstream3.MainPageRequest
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.Episode
import com.lagradost.cloudstream3.ExtractorLink
import com.lagradost.cloudstream3.ExtractorLinkType
import com.lagradost.cloudstream3.Qualities
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app

// Extractor helper functions
import com.lagradost.cloudstream3.utils.ExtractorApiKt.newMovieLoadResponse
import com.lagradost.cloudstream3.utils.ExtractorApiKt.newMovieSearchResponse
import com.lagradost.cloudstream3.utils.ExtractorApiKt.newTvSeriesLoadResponse

// All providers must be a class that extends MainAPI
class MotchillProvider : MainAPI() {
    // Basic plugin information
    override var mainUrl = "https://www.motchill97.com"
    override var name = "Motchill"
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)
    override var lang = "vi"

    // Determines if the plugin has a main page.
    override val hasMainPage = true

    // Function to map HTML elements to search responses
    private fun Element.toSearchResponse(): SearchResponse? {
        val link = this.selectFirst("a")?.attr("href") ?: return null
        val title = this.selectFirst("h4.name a")?.text() ?: this.selectFirst(".name a")?.text() ?: return null
        val posterUrl = this.selectFirst("img")?.attr("src")

        return newMovieSearchResponse(title, link) {
            this.posterUrl = posterUrl
        }
    }

    // The core function to load the main page data.
    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val document = app.get(mainUrl).document
        val homePageList = ArrayList<HomePageList>()

        // Find all sections on the homepage, e.g., "Motchill Đề Cử", "Phim Bộ Mới"
        document.select("div.heading-phim").forEach { heading ->
            val title = heading.selectFirst("h2 a span")?.text()?.trim() ?: return@forEach
            // The list of films is the next sibling element
            val filmList = heading.nextElementSibling()?.select("ul.list-film li")
            
            if (!filmList.isNullOrEmpty()) {
                val movies = filmList.mapNotNull { it.toSearchResponse() }
                if (movies.isNotEmpty()) {
                    homePageList.add(HomePageList(title, movies))
                }
            }
        }
        
        return HomePageResponse(homePageList)
    }

    // Function to handle search queries.
    override suspend fun search(query: String): List<SearchResponse> {
        // The search URL format is /search/{query}/
        val searchUrl = "$mainUrl/search/$query/"
        val document = app.get(searchUrl).document

        // The results are in a list with the class 'list-film'
        return document.select("ul.list-film li").mapNotNull {
            val link = it.selectFirst("a")?.attr("href") ?: return@mapNotNull null
            val title = it.selectFirst("div.name a")?.text() ?: return@mapNotNull null
            val posterUrl = it.selectFirst("img")?.attr("src")

            newMovieSearchResponse(title, link) {
                this.posterUrl = posterUrl
            }
        }
    }

    // Function to load detailed information for a movie or TV show.
    override suspend fun load(url: String): LoadResponse {
        val document = app.get(url).document

        val title = document.selectFirst("h1.movie-title span.title-1")?.text()?.trim() ?: "Loading..."
        val poster = document.selectFirst("div.poster img")?.attr("src")
        val plot = document.selectFirst("div.detail-content-main")?.text()?.trim()
        val yearText = document.selectFirst("span.title-year")?.text()?.filter { it.isDigit() }
        val year = yearText?.toIntOrNull()

        // Extract episodes from the episode list
        val episodes = document.select("div.page-tap ul li a").map { epElement ->
            val epUrl = epElement.attr("href")
            val epName = "Tập ${epElement.selectFirst("span")?.text()?.trim()}"
            Episode(data = epUrl, name = epName)
        }

        // Determine if it's a TV Series or a Movie based on the number of episodes
        return if (episodes.size > 1) {
            newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
                this.posterUrl = poster
                this.plot = plot
                this.year = year
            }
        } else {
            // For movies, the data passed to loadLinks is the first (and only) episode URL
            newMovieLoadResponse(title, url, TvType.Movie, episodes.firstOrNull()?.data) {
                this.posterUrl = poster
                this.plot = plot
                this.year = year
            }
        }
    }

    // This is the crucial function to extract the actual video stream URL.
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        // 'data' is the episode URL from the `load` function.
        val episodeDocument = app.get(data).document
        
        // Find the script content containing the player setup
        val scriptContent = episodeDocument.select("script").html()

        // Use regex to find the `sources: [{file: "..."}]` pattern.
        val videoUrl = Regex("""sources:\s*\[\{file:\s*"(.*?)"''').find(scriptContent)?.groupValues?.get(1)

        if (videoUrl != null) {
            callback(
                ExtractorLink(
                    source = this.name, // "Motchill"
                    name = "Motchill Server", // Server name
                    url = videoUrl,
                    referer = data, // Referer should be the episode page
                    quality = Qualities.Unknown.value,
                    type = ExtractorLinkType.M3U8 // Specify the link type is M3U8
                )
            )
            return true
        }

        return false // Return false if no link was found
    }
}
