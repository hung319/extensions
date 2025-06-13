// File: IHentai.kt
package recloudstream

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import com.lagradost.cloudstream3.utils.AppUtils.toJson
import com.lagradost.cloudstream3.utils.ExtractorLink
import org.jsoup.Jsoup

// Data classes to map the JSON structure from __NUXT_DATA__
// Note: Removed NuxtTray as its structure was too inconsistent.
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

        return try {
            // [REWRITE] Tự động tìm kiếm khối dữ liệu của trang chủ.
            // Dữ liệu trang chủ là một List, có phần tử đầu tiên cũng là một List.
            val rootList = parseJson<List<Any?>>(nuxtData)
            val traysContainer = rootList.find { it is List<*> } as? List<*> ?: return HomePageResponse(emptyList())

            val homePageList = traysContainer.mapNotNull { trayItem ->
                val trayMap = (trayItem as? List<*>)?.getOrNull(0) as? Map<*, *> ?: return@mapNotNull null
                
                val header = trayMap["name"] as? String ?: "Unknown Category"
                val itemsList = trayMap["items"] as? List<*> ?: return@mapNotNull null

                val list = itemsList.mapNotNull { item ->
                    val itemMap = item as? Map<*,*> ?: return@mapNotNull null
                    try {
                        parseJson<NuxtItem>(itemMap.toJson()).toSearchResponse()
                    } catch (e: Exception) { null }
                }

                if (list.isNotEmpty()) HomePageList(header, list) else null
            }
            HomePageResponse(homePageList)
        } catch (e: Exception) {
            HomePageResponse(emptyList())
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val url = "$mainUrl/tim-kiem?keyword=$query"
        val document = app.get(url, headers = headers).text
        val nuxtData = getNuxtData(document) ?: return emptyList()

        return try {
            // [REWRITE] Tự động tìm kiếm khối dữ liệu của trang tìm kiếm.
            // Dữ liệu trang tìm kiếm là một Map, chứa một value là List các item.
            val dataMap = parseJson<Map<String, Any?>>(nuxtData)
            val itemsList = dataMap.values.find {
                (it as? List<*>)?.getOrNull(0) is Map<*, *>
            } as? List<*> ?: return emptyList()

            itemsList.mapNotNull { item ->
                val itemMap = item as? Map<*, *> ?: return@mapNotNull null
                try {
                    parseJson<NuxtItem>(itemMap.toJson()).toSearchResponse()
                } catch (e: Exception) { null }
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    override suspend fun load(url: String): LoadResponse? {
        val document = app.get(url, headers = headers).text
        val nuxtData = getNuxtData(document) ?: return null

        return try {
            // [REWRITE] Tự động tìm kiếm khối dữ liệu của trang phim.
            // Dữ liệu trang phim là một Map, chứa một value là object anime chi tiết.
            val dataMap = parseJson<Map<String, Any?>>(nuxtData)
            val animeDataMap = dataMap.values.find {
                (it as? Map<*, *>)?.containsKey("episodes") == true
            } as? Map<*, *> ?: return null
            
            val animeData = parseJson<NuxtItem>(animeDataMap.toJson())
            
            val title = animeData.name ?: return null
            val slug = animeData.slug ?: return null
            val posterUrl = animeData.poster?.let { if (it.startsWith("/")) "$mainUrl$it" else it }

            val episodes = animeData.episodes?.mapNotNull { ep ->
                val epSlug = ep.slug ?: return@mapNotNull null
                val epName = ep.name ?: "Episode"
                val epUrl = "$mainUrl/xem-phim/$slug/$epSlug"
                newEpisode(epUrl) { this.name = epName }
            }?.reversed()

            if (!episodes.isNullOrEmpty()) {
                newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) { this.posterUrl = posterUrl }
            } else {
                newMovieLoadResponse(title, url, TvType.Movie, url) { this.posterUrl = posterUrl }
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
        // Placeholder
        return false
    }
}
