// File: IHentai.kt
package recloudstream

import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import com.lagradost.cloudstream3.utils.AppUtils.toJson
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.mapper // [FIX] Bổ sung import còn thiếu cho 'mapper'
import org.jsoup.Jsoup

// Data classes to map the JSON structure from __NUXT_DATA__
data class NuxtItem(
    @JsonProperty("name") val name: String?,
    @JsonProperty("slug") val slug: String?,
    @JsonProperty("poster") val poster: String?,
    @JsonProperty("episodes") val episodes: List<NuxtEpisodeShort>?
)

data class NuxtEpisodeShort(
    @JsonProperty("slug") val slug: String?,
    @JsonProperty("name") val name: String?,
)

data class NuxtSource(
    @JsonProperty("src") val src: String?
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
        "User-Agent" to "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/116.0.0.0 Mobile Safari/537.36",
        "Referer" to "$mainUrl/"
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
            val state = parseJson<Map<String, Any?>>(nuxtData)["state"] as? Map<*, *>
            val home = state?.get("home") as? Map<*, *>
            val trays = home?.get("trays") as? List<*> ?: return HomePageResponse(emptyList())

            val homePageList = trays.mapNotNull { trayItem ->
                val trayMap = trayItem as? Map<*, *> ?: return@mapNotNull null
                val header = trayMap["name"] as? String ?: "Unknown Category"
                val itemsList = trayMap["items"] as? List<*> ?: return@mapNotNull null

                val list = itemsList.mapNotNull { item ->
                    val itemMap = item as? Map<*,*> ?: return@mapNotNull null
                    try {
                        mapper.convertValue(itemMap, NuxtItem::class.java).toSearchResponse()
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
            val state = parseJson<Map<String, Any?>>(nuxtData)["state"] as? Map<*, *>
            val search = state?.get("search") as? Map<*, *>
            val result = search?.get("result") as? Map<*, *>
            val itemsList = result?.get("items") as? List<*> ?: return emptyList()

            itemsList.mapNotNull { item ->
                val itemMap = item as? Map<*,*> ?: return@mapNotNull null
                try {
                    mapper.convertValue(itemMap, NuxtItem::class.java).toSearchResponse()
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
            val state = parseJson<Map<String, Any?>>(nuxtData)["state"] as? Map<*, *>
            val anime = state?.get("anime") as? Map<*, *>
            val animeDataMap = anime?.get("detail") as? Map<*, *> ?: return null
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
        // [PLACEHOLDER] Tạm thời vô hiệu hóa theo yêu cầu
        return false
    }
}
