// Save this file as IHentaiProvider.kt
package recloudstream // <-- 1. Đã đổi package

import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import android.content.Context
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.loadExtractor
import org.jsoup.Jsoup

@CloudstreamPlugin
class IHentaiProvider : Plugin() {
    override fun load(context: Context) {
        registerMainAPI(IHentai())
    }
}

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

    // 2. Thêm User-Agent để gửi kèm trong header
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
        val document = app.get(mainUrl, headers = headers).text // <-- Thêm headers
        val nuxtData = getNuxtData(document) ?: return HomePageResponse(emptyList())
        
        val trays = parseJson<List<NuxtTray>>(
            (parseJson<Map<*,*>>(nuxtData)["data"] as? Map<*, *>)?.get("1")?.toString() ?: "[]"
        )
        
        val homePageList = trays.mapNotNull { tray ->
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
        val document = app.get(url, headers = headers).text // <-- Thêm headers
        val nuxtData = getNuxtData(document) ?: return emptyList()
        
        val items = parseJson<List<NuxtItem>>(
             (parseJson<Map<*,*>>(nuxtData)["data"] as? Map<*, *>)?.get("0")?.toString() ?: "[]"
        )

        return items.mapNotNull { it.toSearchResponse() }
    }

    override suspend fun load(url: String): LoadResponse? {
        val document = app.get(url, headers = headers).text // <-- Thêm headers
        val nuxtData = getNuxtData(document) ?: return null
        
        val animeData = parseJson<NuxtItem>(
            (parseJson<Map<*,*>>(nuxtData)["data"] as? Map<*, *>)?.get("0")?.toString() ?: "{}"
        )

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

        return if (!episodes.isNullOrEmpty()) {
            newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
                this.posterUrl = posterUrl
            }
        } else {
            newMovieLoadResponse(title, url, TvType.Movie, url) {
                this.posterUrl = posterUrl
            }
        }
    }

    // 3. Tạm thời vô hiệu hóa hàm loadLinks
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        // --- LOGIC CŨ ĐƯỢC COMMENT LẠI ---
        /*
        val document = app.get(data, headers = headers).text // <-- Thêm headers
        val nuxtData = getNuxtData(document) ?: return false

        val episodeData = parseJson<NuxtEpisodeData>(
             (parseJson<Map<*,*>>(nuxtData)["data"] as? Map<*, *>)?.get("0")?.toString() ?: "{}"
        )

        var hasLoaded = false
        episodeData.sources?.forEach { source ->
            val videoUrl = source.src ?: return@forEach
            loadExtractor(videoUrl, data, subtitleCallback, callback)
            hasLoaded = true
        }
        return hasLoaded
        */

        // Placeholder: Luôn trả về false để báo hiệu không tìm thấy link
        return false
    }
}
