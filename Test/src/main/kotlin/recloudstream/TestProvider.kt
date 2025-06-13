// File: IHentai.kt
package recloudstream

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.loadExtractor
import org.jsoup.Jsoup

// Data classes to map the JSON structure from __NUXT_DATA__
data class NuxtItem(
    val name: String?,
    val slug: String?,
    val poster: String?,
    val episodes: List<NuxtEpisodeShort>?
)

data class NuxtEpisodeShort(
    val slug: String?,
    val name: String?,
)

data class NuxtEpisodeData(
    val name: String?,
    val sources: List<NuxtSource>?
)

data class NuxtSource(
    val src: String?,
    val type: String?
)

data class NuxtTray(
    val name: String?,
    val items: List<NuxtItem>?
)

class IHentai : MainAPI() {
    override var name = "iHentai"
    override var mainUrl = "https://ihentai.ws"
    override var lang = "vi"
    override val hasMainPage = true
    override val hasChromecastSupport = true
    override val supportedTypes = setOf(
        TvType.NSFW
    )

    private val headers = mapOf(
        "User-Agent" to "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/116.0.0.0 Mobile Safari/537.36"
    )

    private fun getNuxtData(html: String): String? {
        return Jsoup.parse(html).selectFirst("script[id=__NUXT_DATA__]")?.data()
    }

    private fun NuxtItem.toSearchResponse(): SearchResponse? {
        val slug = this.slug ?: return null
        val title = this.name ?: "N/A"
        val href = "$mainUrl/xem-phim/$slug"
        val posterUrl = this.poster?.let { if (it.startsWith("/")) "$mainUrl$it" else it }

        return newAnimeSearchResponse(title, href) {
            this.posterUrl = posterUrl
            this.type = if ((this@toSearchResponse.episodes?.size ?: 0) > 1) TvType.TvSeries else TvType.Movie
        }
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val document = app.get(mainUrl, headers = headers).text
        val nuxtData = getNuxtData(document) ?: return HomePageResponse(emptyList())

        // [FIX] Sửa lỗi "ShallowReactive":
        // JSON trang chủ có chứa các phần tử rác dạng String.
        // Ta sẽ duyệt qua mảng gốc, chỉ lấy những phần tử là Map (đối tượng) và bỏ qua những phần tử khác.
        val rawList = parseJson<List<Any?>>(nuxtData)
        val homePageList = rawList.mapNotNull { item ->
            // Chỉ xử lý những item là một List (tức là [tray])
            val trayList = (item as? List<*>)?.getOrNull(0)
            // Và trong list đó, phần tử phải là một Map (tức là đối tượng tray)
            val trayMap = trayList as? Map<*, *> ?: return@mapNotNull null

            // Bây giờ mới parse nó thành NuxtTray một cách an toàn
            val tray = try {
                parseJson<NuxtTray>(mapper.writeValueAsString(trayMap))
            } catch (e: Exception) {
                return@mapNotNull null
            }

            val header = tray.name ?: "Unknown Category"
            val list = tray.items?.mapNotNull { it.toSearchResponse() }

            if (!list.isNullOrEmpty()) {
                HomePageList(header, list)
            } else {
                null
            }
        }

        return HomePageResponse(homePageList)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val url = "$mainUrl/tim-kiem?keyword=$query"
        val document = app.get(url, headers = headers).text
        val nuxtData = getNuxtData(document) ?: return emptyList()

        // Sử dụng try-catch để phòng trường hợp trang search không có kết quả hoặc lỗi
        return try {
            val dataMap = parseJson<Map<String, Any>>(nuxtData)
            val itemsJson = (dataMap["data"] as? Map<*, *>)?.get("0")?.toString() ?: "[]"
            val items = parseJson<List<NuxtItem>>(itemsJson)
            items.mapNotNull { it.toSearchResponse() }
        } catch (e: Exception) {
            // Nếu có lỗi (ví dụ: không có kết quả), trả về danh sách rỗng
            emptyList()
        }
    }

    override suspend fun load(url: String): LoadResponse? {
        val document = app.get(url, headers = headers).text
        val nuxtData = getNuxtData(document) ?: return null

        return try {
            val dataMap = parseJson<Map<String, Any>>(nuxtData)
            val animeDataJson = (dataMap["data"] as? Map<*, *>)?.get("0")?.toString() ?: "{}"
            val animeData = parseJson<NuxtItem>(animeDataJson)

            val title = animeData.name ?: return null
            val slug = animeData.slug ?: return null
            val posterUrl = animeData.poster?.let { if (it.startsWith("/")) "$mainUrl$it" else it }

            val episodes = animeData.episodes?.mapNotNull { ep ->
                val epSlug = ep.slug ?: return@mapNotNull null
                val epName = ep.name ?: "Episode"
                val epUrl = "$mainUrl/xem-phim/$slug/$epSlug"
                newEpisode(epUrl) {
                    this.name = epName
                }
            }?.reversed()

            if (!episodes.isNullOrEmpty()) {
                newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
                    this.posterUrl = posterUrl
                }
            } else {
                newMovieLoadResponse(title, url, TvType.Movie, url) {
                    this.posterUrl = posterUrl
                }
            }
        } catch (e: Exception) {
            null
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        // Placeholder: Luôn trả về false để báo hiệu không tìm thấy link
        return false
    }
}
