// File: IHentai.kt
package recloudstream

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import com.lagradost.cloudstream3.utils.AppUtils.toJson
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

        return try {
            // [REWRITE] Logic mới cho getMainPage
            val dataMap = parseJson<Map<String, Any>>(nuxtData)
            val dataList = dataMap["data"] as? List<*> ?: return HomePageResponse(emptyList())

            val homePageList = dataList.mapNotNull { item ->
                val trayList = item as? List<*>
                val trayMap = trayList?.getOrNull(0) as? Map<*, *> ?: return@mapNotNull null

                val tray = try {
                    parseJson<NuxtTray>(mapper.writeValueAsString(trayMap))
                } catch (e: Exception) { return@mapNotNull null }

                val header = tray.name ?: "Unknown Category"
                val list = tray.items?.mapNotNull { it.toSearchResponse() }

                if (!list.isNullOrEmpty()) HomePageList(header, list) else null
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
            // [REWRITE] Logic mới cho search
            val dataMap = parseJson<Map<String, Any>>(nuxtData)
            val dataList = dataMap["data"] as? List<*> ?: return emptyList()

            dataList.mapNotNull { item ->
                val itemMap = item as? Map<*, *> ?: return@mapNotNull null
                val searchItem = try {
                    parseJson<NuxtItem>(itemMap.toJson())
                } catch (e: Exception) { return@mapNotNull null }
                searchItem.toSearchResponse()
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    override suspend fun load(url: String): LoadResponse? {
        val document = app.get(url, headers = headers).text
        val nuxtData = getNuxtData(document) ?: return null

        return try {
            // [REWRITE] Logic mới cho load
            val dataMap = parseJson<Map<String, Any>>(nuxtData)
            val dataList = dataMap["data"] as? List<*> ?: return null
            val animeDataMap = dataList.getOrNull(0) as? Map<*, *> ?: return null
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
        // Placeholder: Luôn trả về false để báo hiệu không tìm thấy link
        return false
    }
}
