// File: IHentai.kt
package recloudstream

import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import org.jsoup.Jsoup
import java.net.URLEncoder

// Data classes
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
    
    // Tự tạo một mapper nội bộ để không phụ thuộc vào thư viện ngoài
    private val mapper = jacksonObjectMapper()

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
            val data = mapper.readValue<Map<String, Any?>>(nuxtData)["data"] as? Map<*, *> ?: return HomePageResponse(emptyList())
            
            val homePageList = data.values.mapNotNull { trayItem ->
                val trayMap = (trayItem as? List<*>)?.getOrNull(0) as? Map<*, *> ?: return@mapNotNull null
                val header = trayMap["name"] as? String ?: return@mapNotNull null
                val itemsList = trayMap["items"] as? List<*> ?: return@mapNotNull null

                val list = itemsList.mapNotNull { item ->
                    try {
                        val itemMap = item as? Map<*, *> ?: return@mapNotNull null
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
        val encodedQuery = URLEncoder.encode(query, "UTF-8")
        val url = "$mainUrl/search?s=$encodedQuery"

        val document = app.get(url, headers = headers).text
        val nuxtData = getNuxtData(document) ?: return emptyList()

        return try {
            val data = mapper.readValue<Map<String, Any?>>(nuxtData)["data"] as? Map<*, *> ?: return emptyList()
            val itemsList = data["0"] as? List<*> ?: return emptyList()

            itemsList.mapNotNull { item ->
                try {
                    val itemMap = item as? Map<*, *> ?: return@mapNotNull null
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
            val data = mapper.readValue<Map<String, Any?>>(nuxtData)["data"] as? Map<*, *> ?: return null
            val animeDataMap = data["0"] as? Map<*, *> ?: return null
            val animeData = mapper.convertValue(animeDataMap, NuxtItem::class.java)

            val title = animeData.name ?: return null
            val slug = animeData.slug ?: return null
            val posterUrl = animeData.poster?.let { if (it.startsWith("/")) "$mainUrl$it" else it }

            val episodes = animeData.episodes?.mapNotNull { ep ->
                val epSlug = ep.slug ?: return@mapNotNull null
                val epName = ep.name ?: "Episode"
                val epUrl = "$mainUrl/xem-phim/$slug/$epSlug"
                newEpisode(epUrl) { this.name = epName }
            }?.reversed()

            if (episodes?.isNotEmpty() == true) {
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
        val document = app.get(data, headers = headers).text
        val nuxtData = getNuxtData(document) ?: return false

        try {
            val state = mapper.readValue<Map<String, Any?>>(nuxtData)["state"] as? Map<*, *>
            val player = state?.get("player") as? Map<*, *>
            val sourcesList = player?.get("sources") as? List<*> ?: return false

            sourcesList.forEach { sourceItem ->
                try {
                    val sourceMap = sourceItem as? Map<*, *> ?: return@forEach
                    val source = mapper.convertValue(sourceMap, NuxtSource::class.java)
                    val videoUrl = source.src ?: return@forEach
                    callback.invoke(
                        ExtractorLink(
                            this.name,
                            "iHentai Server",
                            videoUrl,
                            referer = mainUrl,
                            quality = Qualities.Unknown.value,
                            type = if (videoUrl.contains(".m3u8")) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                        )
                    )
                } catch (e: Exception) {
                    // Ignored
                }
            }
        } catch (e: Exception) {
            return false
        }
        return true
    }
}
