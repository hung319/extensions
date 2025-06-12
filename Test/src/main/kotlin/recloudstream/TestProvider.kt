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
    private val headers = mapOf(
        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"
    )

    // --- UTILITY FUNCTION TO PARSE ITEMS ---
    // This function parses a single item from the homepage or search results.
    private fun Element.toSearchResponse(): SearchResponse? {
        val linkElement = this.selectFirst("a") ?: return null
        val href = fixUrl(linkElement.attr("href"))
        
        val title = this.selectFirst("h2")?.text()?.trim() ?: return null
        
        val posterUrl = this.selectFirst("img")?.attr("src")

        return newAnimeSearchResponse(title, href, TvType.NSFW) {
            this.posterUrl = posterUrl
        }
    }

    // ---- CORE FUNCTIONS ----

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = "$mainUrl/?page=$page"
        val document = app.get(url, headers = headers).document

        val sections = document.select("div.container > div.tw-mb-16")
        val homePageList = mutableListOf<HomePageList>()

        for (section in sections) {
            val sectionTitle = section.selectFirst("h1")?.text()?.trim() ?: continue
            // The items are inside a div with class "v-card" within each section
            val items = section.select("div.v-card").mapNotNull {
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

        // Corrected the selector for search results based on your `ihentai_search_output.html` file.
        // The items are inside a div with class "film-list" and then each item is a "v-card".
        return document.select("div.film-list div.v-card").mapNotNull {
            it.toSearchResponse()
        }
    }

    override suspend fun load(url: String): LoadResponse {
        val document = app.get(url, headers = headers).document

        // Selectors based on your `ihentai_load_output.html` file.
        val title = document.selectFirst("h1.tw-text-3xl")?.text()?.trim() ?: "Không có tiêu đề"
        val posterUrl = document.selectFirst("div.grid > div:first-child img")?.attr("src")
        
        val plot = document.select("h3").find { it.text().contains("Nội dung") }
            ?.nextElementSibling()?.text()?.trim()

        // Episodes are in a div with id "episode-list"
        val episodes = document.select("div#episode-list a").mapNotNull { element ->
            val href = fixUrl(element.attr("href"))
            val name = element.text()?.trim() ?: "Tập"
            
            newEpisode(href) {
                this.name = name
            }
        }.reversed()

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
