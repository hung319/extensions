// Đặt trong file: IhentaiProvider/src/main/kotlin/recloudstream/IhentaiProvider.kt

package recloudstream

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

class IhentaiProvider : MainAPI() {
    override var name = "iHentai"
    override var mainUrl = "https://ihentai.ws"
    override var hasMainPage = true
    override var lang = "vi"
    override val supportedTypes = setOf(TvType.NSFW)

    // User-Agent để giả lập trình duyệt, tăng tính ổn định
    private val userAgent = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/125.0.0.0 Safari/537.36"

    // Data classes để parse JSON
    @Serializable
    data class NuxtData(val data: List<NuxtStateData>? = null)
    @Serializable
    data class NuxtStateData(val latestAnimes: AnimeList? = null, val animes: AnimeList? = null, val anime: AnimeDetail? = null, val chapters: List<ChapterItem>? = null, val chapter: ChapterDetail? = null)
    @Serializable
    data class AnimeList(val data: List<AnimeItem> = emptyList())
    @Serializable
    data class AnimeItem(val name: String, val slug: String, val poster_url: String? = null)
    @Serializable
    data class AnimeDetail(val name: String, val slug: String, val description: String? = null, val poster_url: String? = null)
    @Serializable
    data class ChapterItem(val id: Int, val name: String, val slug: String)
    @Serializable
    data class ChapterDetail(val images: List<ChapterImage> = emptyList())
    @Serializable
    data class ChapterImage(val image_url: String)

    // Hàm helper để lấy và phân tích dữ liệu JSON từ __NUXT_DATA__
    private suspend fun getNuxtData(url: String): NuxtData? {
        val document = app.get(url, headers = mapOf("User-Agent" to userAgent)).document
        val scriptData = document.selectFirst("#__NUXT_DATA__")?.data() ?: return null

        return try {
            // Dữ liệu gốc là một Array, chúng ta cần lấy phần tử thứ 2 (index 1)
            val jsonArray = parseJson<List<JsonElement>>(scriptData)
            if (jsonArray.size > 1) {
                parseJson<NuxtData>(jsonArray[1].toString())
            } else {
                null
            }
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = if (page == 1) mainUrl else "$mainUrl?page=$page"
        val nuxtData = getNuxtData(url)
        
        val home = nuxtData?.data?.firstOrNull()?.latestAnimes?.data?.mapNotNull { item ->
            newTvSeriesSearchResponse(item.name, "$mainUrl/phim/${item.slug}", TvType.NSFW) {
                this.posterUrl = item.poster_url
            }
        } ?: emptyList()

        return newHomePageResponse("Mới Cập Nhật", home)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val url = "$mainUrl/tim-kiem?q=$query"
        val nuxtData = getNuxtData(url)

        return nuxtData?.data?.firstOrNull()?.animes?.data?.mapNotNull { item ->
            newTvSeriesSearchResponse(item.name, "$mainUrl/phim/${item.slug}", TvType.NSFW) {
                this.posterUrl = item.poster_url
            }
        } ?: emptyList()
    }

    override suspend fun load(url: String): LoadResponse {
        val nuxtData = getNuxtData(url)
        
        val anime = nuxtData?.data?.firstOrNull()?.anime
            ?: return newTvSeriesLoadResponse("", url, TvType.NSFW, emptyList())

        val chapters = nuxtData.data?.firstOrNull()?.chapters ?: emptyList()

        val episodes = chapters.map { chapter ->
            val episodeUrl = "$mainUrl/xem-phim/${anime.slug}/${chapter.slug}"
            newEpisode(episodeUrl) {
                this.name = chapter.name
            }
        }.reversed()

        return newTvSeriesLoadResponse(anime.name, url, TvType.NSFW, episodes) {
            this.posterUrl = anime.poster_url
            this.plot = anime.description
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val nuxtData = getNuxtData(data)
        val images = nuxtData?.data?.firstOrNull()?.chapter?.images ?: emptyList()

        images.forEach { image ->
            callback(
                newExtractorLink(
                    source = this.name,
                    name = "iHentai Image",
                    url = image.image_url
                ).apply {
                    this.referer = mainUrl
                }
            )
        }
        return true
    }
}
