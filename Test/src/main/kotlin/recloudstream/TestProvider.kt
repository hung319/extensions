// Changed the package as requested
package recloudstream

// Necessary imports from CloudStream and other libraries
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.utils.AppUtils.parseJson

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

    // ---- DATA CLASSES FOR JSON PARSING ----
    private data class Item(
        @JsonProperty("name") val name: String?,
        @JsonProperty("slug") val slug: String?,
        @JsonProperty("thumbnail") val thumbnail: String?
    )

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

    private data class NuxtData(
        @JsonProperty("items") val items: List<Item>?,
        @JsonProperty("item") val item: ItemDetails?
    )

    private data class NuxtPayload(
        @JsonProperty("data") val data: List<NuxtData>?
    )

    private data class NuxtResponse(
        @JsonProperty("payload") val payload: NuxtPayload?
    )

    // ---- UTILITY FUNCTION ----
    private fun getNuxtData(html: String): String? {
        return Regex("""window\.__NUXT__\s*=\s*(\{.*?\});\s*</script>""").find(html)?.groupValues?.get(1)
    }

    // ---- CORE FUNCTIONS ----
    
    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = "$mainUrl/?page=$page"
        // Add the headers map to the get request
        val document = app.get(url, headers = headers).text

        val nuxtJson = getNuxtData(document) ?: return HomePageResponse(emptyList())
        val response = parseJson<NuxtResponse>(nuxtJson)
        val items = response.payload?.data?.firstOrNull()?.items

        val homePageList = items?.mapNotNull { item: Item ->
            newAnimeSearchResponse(item.name ?: "", "$mainUrl/doc-truyen/${item.slug}") {
                posterUrl = item.thumbnail
            }
        } ?: emptyList()

        return HomePageResponse(listOf(HomePageList("Mới cập nhật", homePageList)))
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val url = "$mainUrl/tim-kiem?q=$query"
        // Add the headers map to the get request
        val document = app.get(url, headers = headers).text

        val nuxtJson = getNuxtData(document) ?: return emptyList()
        val response = parseJson<NuxtResponse>(nuxtJson)
        val items = response.payload?.data?.firstOrNull()?.items

        return items?.mapNotNull { item: Item ->
            newAnimeSearchResponse(item.name ?: "", "$mainUrl/doc-truyen/${item.slug}") {
                posterUrl = item.thumbnail
            }
        } ?: emptyList()
    }

    override suspend fun load(url: String): LoadResponse {
        // Add the headers map to the get request
        val document = app.get(url, headers = headers).text

        val nuxtJson = getNuxtData(document) ?: throw ErrorLoadingException("Không thể tải dữ liệu truyện")
        val response = parseJson<NuxtResponse>(nuxtJson)
        val item = response.payload?.data?.firstOrNull()?.item
            ?: throw ErrorLoadingException("Không tìm thấy thông tin truyện")

        val title = item.name ?: "Không có tiêu đề"

        val episodes = item.chapters?.mapNotNull { chapter: Chapter ->
            newEpisode("$mainUrl/doc-truyen/${item.slug}/${chapter.slug}") {
                name = chapter.name
            }
        }?.reversed() ?: emptyList()

        return newTvSeriesLoadResponse(title, url, TvType.NSFW, episodes) {
            this.posterUrl = item.thumbnail
            this.plot = item.description
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
