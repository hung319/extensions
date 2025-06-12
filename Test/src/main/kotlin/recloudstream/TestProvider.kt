// The package has been changed as requested.
package recloudstream

// We need to import the extensions and utilities
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.network.CloudflareKiller
import com.lagradost.cloudstream3.utils.AppUtils.parseJson

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

    // The supported types of content.
    override val supportedTypes = setOf(
        TvType.NSFW
    )

    // Interceptor to solve Cloudflare issues.
    // REMOVED "override" KEYWORD TO FIX THE BUILD ERROR.
    val interceptor = CloudflareKiller()


    // --- DATA CLASSES FOR JSON PARSING ---
    // These classes help to convert the JSON from the website into objects we can use.
    private data class NuxtItem(
        @JsonProperty("name") val name: String?,
        @JsonProperty("slug") val slug: String?,
        @JsonProperty("thumbnail") val thumbnail: String?,
        @JsonProperty("description") val description: String?,
        @JsonProperty("chapters") val chapters: List<NuxtChapter>?
    )

    private data class NuxtChapter(
        @JsonProperty("name") val name: String?,
        @JsonProperty("slug") val slug: String?
    )

    private data class NuxtData(
        @JsonProperty("items") val items: List<NuxtItem>?,
        @JsonProperty("item") val item: NuxtItem?
    )

    private data class NuxtPayload(
        @JsonProperty("data") val data: NuxtData?
    )

    private data class NuxtResponse(
        @JsonProperty("payload") val payload: NuxtPayload?
    )
    
    // --- UTILITY FUNCTION TO EXTRACT JSON ---
    private fun getNuxtData(html: String): String? {
        // This regex finds the __NUXT__ data block in the HTML source
        return Regex("window\\.__NUXT__=(\\{.*\\});<\\/script>").find(html)?.groupValues?.get(1)
    }
    
    // --- MAIN FUNCTIONS ---
    
    // Function to fetch and display content on the main page
    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = "$mainUrl/?page=$page"
        val document = app.get(url, interceptor = interceptor).text
        
        // Extract the JSON data from the HTML
        val nuxtJson = getNuxtData(document) ?: return HomePageResponse(emptyList())
        val response = parseJson<NuxtResponse>(nuxtJson)

        // Map the JSON data to SearchResponse objects that the app can display
        val homePageList = response.payload?.data?.items?.mapNotNull { item ->
            newAnimeSearchResponse(item.name ?: "", "$mainUrl/doc-truyen/${item.slug}") {
                posterUrl = item.thumbnail
            }
        } ?: emptyList()

        // Return the list of items under a specific category name
        return HomePageResponse(listOf(HomePageList("Mới cập nhật", homePageList)))
    }

    // Function to handle search queries
    override suspend fun search(query: String): List<SearchResponse> {
        val url = "$mainUrl/tim-kiem?q=$query"
        val document = app.get(url, interceptor = interceptor).text
        
        // Extract the JSON data from the HTML
        val nuxtJson = getNuxtData(document) ?: return emptyList()
        val response = parseJson<NuxtResponse>(nuxtJson)

        // Map the search results from JSON to SearchResponse objects
        return response.payload?.data?.items?.mapNotNull { item ->
            newAnimeSearchResponse(item.name ?: "", "$mainUrl/doc-truyen/${item.slug}") {
                posterUrl = item.thumbnail
            }
        } ?: emptyList()
    }

    // Function to load details of a specific anime/series
    override suspend fun load(url: String): LoadResponse {
        val document = app.get(url, interceptor = interceptor).text
        
        // Extract the JSON data from the HTML
        val nuxtJson = getNuxtData(document) ?: throw ErrorLoadingException("Không thể tải dữ liệu")
        val item = parseJson<NuxtResponse>(nuxtJson).payload?.data?.item ?: throw ErrorLoadingException("Không tìm thấy thông tin truyện")

        val title = item.name ?: "Không có tiêu đề"
        val posterUrl = item.thumbnail
        val plot = item.description

        // Map chapters from JSON to Episode objects
        // ADDED " ?: emptyList()" TO FIX THE NULLABILITY MISMATCH ERROR.
        val episodes = item.chapters?.mapNotNull { chapter ->
            newEpisode("$mainUrl/doc-truyen/${item.slug}/${chapter.slug}") {
                name = chapter.name
            }
        }?.reversed() ?: emptyList()

        return newTvSeriesLoadResponse(title, url, TvType.NSFW, episodes) {
            this.posterUrl = posterUrl
            this.plot = plot
        }
    }

    // The loadLinks function is responsible for extracting the image URLs for a chapter.
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
