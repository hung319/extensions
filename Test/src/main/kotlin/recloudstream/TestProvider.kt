// Changed the package as requested
package recloudstream

// Necessary imports from CloudStream and other libraries
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Element

// Define the provider class, inheriting from MainAPI
class Ihentai : MainAPI() {
    // ---- METADATA ----
    override var name = "iHentai"
    override var mainUrl = "https://ihentai.ws"
    override var lang = "vi"
    override val hasMainPage = true
    override val supportedTypes = setOf(TvType.NSFW)

    // Set the User-Agent that worked with your curl command.
    // This header will be sent with every request.
    private val headers = mapOf(
        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"
    )

    // --- UTILITY FUNCTION TO PARSE ITEMS ---
    // This function parses a single item from the homepage or search results.
    private fun Element.toSearchResponse(): SearchResponse? {
        // Find the 'a' tag which contains the link and title
        val linkElement = this.selectFirst("a") ?: return null
        val href = fixUrl(linkElement.attr("href"))
        
        // Extract title from the h2 tag inside v-card-text
        val title = this.selectFirst("div.v-card-text h2")?.text()?.trim() ?: return null
        
        // Extract poster image URL
        val posterUrl = this.selectFirst("img")?.attr("src")

        // Create a SearchResponse object that CloudStream can understand
        return newAnimeSearchResponse(title, href, TvType.NSFW) {
            this.posterUrl = posterUrl
        }
    }

    // ---- CORE FUNCTIONS ----

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = "$mainUrl/?page=$page"
        // Get the page HTML using the correct headers
        val document = app.get(url, headers = headers).document

        // Find all sections on the main page
        val sections = document.select("div.tw-mb-16")
        val homePageList = mutableListOf<HomePageList>()

        for (section in sections) {
            val sectionTitle = section.selectFirst("h1")?.text()?.trim() ?: continue
            val items = section.select("div.tw-grid > div.v-card").mapNotNull {
                it.toSearchResponse()
            }
            if (items.isNotEmpty()) {
                homePageList.add(HomePageList(sectionTitle, items))
            }
        }
        
        return HomePageResponse(homePageList)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val url = "$mainUrl/tim-kiem?q=$query"
        val document = app.get(url, headers = headers).document

        // Find all search result items and parse them
        return document.select("div.tw-grid > div.v-card").mapNotNull {
            it.toSearchResponse()
        }
    }

    override suspend fun load(url: String): LoadResponse {
        val document = app.get(url, headers = headers).document

        // Extract main info for the series
        val title = document.selectFirst("h1.film-title")?.text()?.trim() ?: "Không có tiêu đề"
        val posterUrl = document.selectFirst("div.film-thumbnail img")?.attr("src")
        val plot = document.selectFirst("div.film-description")?.text()?.trim()

        // Extract the list of episodes
        val episodes = document.select("div.episode-list a").mapNotNull { element ->
            val href = fixUrl(element.attr("href"))
            val name = element.selectFirst("span")?.text()?.trim() ?: "Tập"
            
            newEpisode(href) {
                this.name = name
            }
        }.reversed() // Reverse to show newest first

        // Return a TvSeriesLoadResponse with all the extracted data
        return newTvSeriesLoadResponse(title, url, TvType.NSFW, episodes) {
            this.posterUrl = posterUrl
            this.plot = plot
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        // Temporarily disabled as requested.
        return false
    }
}
