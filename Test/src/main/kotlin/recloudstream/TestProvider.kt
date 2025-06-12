// The package has been changed as requested.
package recloudstream

// We need to import the extensions and utilities
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.Jsoup

// Define the provider class, inheriting from MainAPI
class Ihentai : MainAPI() { // all providers must inherit from MainAPI
    // The name of the provider
    override var name = "iHentai"
    // The main URL of the website
    override var mainUrl = "https://ihentai.ws"
    // The language of the provider
    override var lang = "vi"
    
    // This tells the app that the provider has a main page and should be shown on the home screen.
    override val hasMainPage = true

    // The supported types of content. NSFW is for adult content.
    override val supportedTypes = setOf(
        TvType.NSFW
    )

    // Set a User-Agent header for all requests
    private val headers = mapOf(
        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/113.0.0.0 Safari/537.36"
    )

    // Helper function to parse search results, used by both getMainPage and search
    private fun toSearchResponse(element: org.jsoup.nodes.Element): SearchResponse? {
        val link = element.selectFirst("a") ?: return null
        val href = fixUrl(link.attr("href"))
        val title = link.selectFirst("h3")?.text() ?: return null
        val posterUrl = link.selectFirst("img")?.attr("src")

        return newAnimeSearchResponse(title, href, TvType.NSFW) {
            this.posterUrl = posterUrl
        }
    }

    // Function to fetch and display content on the main page
    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        // Construct the URL for the main page with pagination
        val url = "$mainUrl/?page=$page"
        val document = app.get(url, headers = headers).document

        // Find all items in the "Mới cập nhật" section
        val homePageList = document.select("div.film-list div.v-col").mapNotNull {
            toSearchResponse(it)
        }

        // Return the list of items under a specific category name
        return HomePageResponse(listOf(
            HomePageList(
                "Mới cập nhật",
                homePageList
            )
        ))
    }

    // Function to handle search queries
    override suspend fun search(query: String): List<SearchResponse> {
        // Construct the search URL
        val url = "$mainUrl/tim-kiem?q=$query"
        val document = app.get(url, headers = headers).document

        // Parse the search results using the same logic as the main page
        return document.select("div.film-list div.v-col").mapNotNull {
            toSearchResponse(it)
        }
    }

    // Function to load details of a specific anime/series
    override suspend fun load(url: String): LoadResponse {
        val document = app.get(url, headers = headers).document

        // Extract the main details of the show
        val title = document.selectFirst("h1.film-title")?.text()?.trim() ?: "Không có tiêu đề"
        val posterUrl = document.selectFirst("div.film-thumbnail img")?.attr("src")
        val plot = document.selectFirst("div.film-description")?.text()?.trim()

        // Extract the list of episodes
        val episodes = document.select("div.episode-list a").mapNotNull { element ->
            val href = fixUrl(element.attr("href"))
            val name = element.selectFirst("span")?.text()?.trim() ?: "Tập"

            // Create an Episode object for each item
            newEpisode(href) {
                this.name = name
            }
        }

        // Return a TvSeriesLoadResponse with all the extracted data
        return newTvSeriesLoadResponse(title, url, TvType.NSFW, episodes) {
            this.posterUrl = posterUrl
            this.plot = plot
        }
    }

    // The loadLinks function is responsible for extracting the video sources.
    // We will implement this in the next step.
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        // This function is intentionally left empty for now as requested.
        return true
    }
}
