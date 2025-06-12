// Changed the package as requested
package recloudstream

// Necessary imports from CloudStream and other libraries
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.network.CloudflareKiller

// Define the provider class, inheriting from MainAPI
class Ihentai : MainAPI() {
    // ---- METADATA ----
    override var name = "iHentai"
    override var mainUrl = "https://ihentai.ws"
    override var lang = "vi"
    // Tell the app that this provider has a home page
    override val hasMainPage = true
    // Define the supported content types
    override val supportedTypes = setOf(
        TvType.NSFW
    )

    // Interceptor to bypass Cloudflare protection
    private val interceptor = CloudflareKiller()

    // ---- DATA CLASSES FOR JSON PARSING ----
    // These classes map the structure of the JSON data from the website.
    // Classes for chapter images are removed for now.

    // For Homepage and Search
    private data class Item(
        @JsonProperty("name") val name: String?,
        @JsonProperty("slug") val slug: String?,
        @JsonProperty("thumbnail") val thumbnail: String?
    )

    // For Load (Series Details)
    private data class ItemDetails(
        @JsonProperty("name") val name: String?,
        @JsonProperty("slug") val slug: String?,
        @JsonProperty("thumbnail") val thumbnail: String?,
        @JsonProperty("description") val description: String?,
        @JsonProperty("chapters") val chapters: List<Chapter>?
    )

    private data class Chapter(
        @JsonProperty("name") val name: String?,
        @JsonProperty("slug") val slug: String?
    )

    // Wrapper classes for the Nuxt.js data structure
    private data class NuxtData(
        @JsonProperty("items") val items: List<Item>?,
        @JsonProperty("item") val item: ItemDetails?
        // Removed 'chapter' property as it's not used without loadLinks
    )

    // The data is nested inside an array, so we need to get the first element.
    private data class NuxtPayload(
        @JsonProperty("data") val data: List<NuxtData>?
    )

    private data class NuxtResponse(
        @JsonProperty("payload") val payload: NuxtPayload?
    )
    
    // ---- UTILITY FUNCTION ----
    // Extracts the __NUXT__ JSON block from the HTML source
    private fun getNuxtJson(html: String): String? {
        // A more robust regex to handle potential extra spaces
        return Regex("""window\.__NUXT__\s*=\s*(\{.*?\});\s*</script>""").find(html)?.groupValues?.get(1)
    }
    
    // ---- CORE FUNCTIONS ----
    
    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = "$mainUrl/?page=$page"
        // Use the interceptor correctly in the app.get call
        val document = app.get(url, interceptors = listOf(interceptor)).text
        
        val nuxtJson = getNuxtData(document) ?: return HomePageResponse(emptyList())
        // The data is inside the first element of the 'data' array
        val items = parseJson<NuxtResponse>(nuxtJson).payload?.data?.firstOrNull()?.items
        
        val homePageList = items?.mapNotNull { item ->
            newAnimeSearchResponse(item.name ?: "", "$mainUrl/doc-truyen/${item.slug}") {
                posterUrl = item.thumbnail
            }
        } ?: emptyList()

        return HomePageResponse(listOf(HomePageList("Mới cập nhật", homePageList)))
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val url = "$mainUrl/tim-kiem?q=$query"
        val document = app.get(url, interceptors = listOf(interceptor)).text
        
        val nuxtJson = getNuxtData(document) ?: return emptyList()
        // The data is inside the first element of the 'data' array
        val items = parseJson<NuxtResponse>(nuxtJson).payload?.data?.firstOrNull()?.items

        return items?.mapNotNull { item ->
            newAnimeSearchResponse(item.name ?: "", "$mainUrl/doc-truyen/${item.slug}") {
                posterUrl = item.thumbnail
            }
        } ?: emptyList()
    }

    override suspend fun load(url: String): LoadResponse {
        val document = app.get(url, interceptors = listOf(interceptor)).text
        
        val nuxtJson = getNuxtData(document) ?: throw ErrorLoadingException("Không thể tải dữ liệu truyện")
        // The data is inside the first element of the 'data' array
        val item = parseJson<NuxtResponse>(nuxtJson).payload?.data?.firstOrNull()?.item
            ?: throw ErrorLoadingException("Không tìm thấy thông tin truyện")

        val title = item.name ?: "Không có tiêu đề"
        
        val episodes = item.chapters?.mapNotNull { chapter ->
            newEpisode("$mainUrl/doc-truyen/${item.slug}/${chapter.slug}") {
                name = chapter.name
            }
        }?.reversed() ?: emptyList() // Reverse to show newest first

        return newTvSeriesLoadResponse(title, url, TvType.NSFW, episodes) {
            this.posterUrl = item.thumbnail
            this.plot = item.description
        }
    }

    // This function is temporarily disabled as requested.
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        // Return false to indicate that no links were loaded.
        return false
    }
}
