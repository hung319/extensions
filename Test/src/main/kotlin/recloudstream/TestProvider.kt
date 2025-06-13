// File: IhentaiProvider/src/main/kotlin/recloudstream/IhentaiProvider.kt

package recloudstream

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import kotlinx.serialization.Serializable

class IhentaiProvider : MainAPI() {
    override var name = "iHentai"
    override var mainUrl = "https://ihentai.ws"
    override var hasMainPage = true
    override var lang = "vi"
    override val supportedTypes = setOf(TvType.NSFW)

    // Data classes (không thay đổi)
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

    private suspend fun getNuxtData(url: String): NuxtData? {
        val document = app.get(url).document
        val scriptData = document.selectFirst("#__NUXT_DATA__")?.data() ?: return null
        return parseJson<NuxtData>(scriptData)
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = if (page == 1) mainUrl else "$mainUrl?page=$page"
        val nuxtData = getNuxtData(url)
        
        val home = nuxtData?.data?.firstOrNull()?.latestAnimes?.data?.mapNotNull { item ->
            newAnimeSearchResponse(item.name, "$mainUrl/phim/${item.slug}", TvType.NSFW) {
                this.posterUrl = item.poster_url
            }
        // [FIX] Sửa lỗi type inference
        } ?: emptyList<SearchResponse>() 

        return newHomePageResponse("Mới Cập Nhật", home)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val url = "$mainUrl/tim-kiem?q=$query"
        val nuxtData = getNuxtData(url)

        // [FIX] Sửa lỗi type inference
        return nuxtData?.data?.firstOrNull()?.animes?.data?.mapNotNull { item ->
            newAnimeSearchResponse(item.name, "$mainUrl/phim/${item.slug}", TvType.NSFW) {
                this.posterUrl = item.poster_url
            }
        } ?: emptyList<SearchResponse>()
    }

    override suspend fun load(url: String): LoadResponse {
        val nuxtData = getNuxtData(url)
        // [FIX] Sửa lỗi type inference
        val anime = nuxtData?.data?.firstOrNull()?.anime ?: return newAnimeLoadResponse("", "", TvType.NSFW, emptyList<Episode>())
        val chapters = nuxtData.data.firstOrNull()?.chapters ?: emptyList<ChapterItem>()

        val episodes = chapters.map { chapter ->
            val episodeUrl = "$mainUrl/xem-phim/${anime.slug}/${chapter.slug}"
            newEpisode(episodeUrl) {
                this.name = chapter.name
            }
        }.reversed()

        return newAnimeLoadResponse(anime.name, url, TvType.NSFW, episodes) {
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
        val images = nuxtData?.data?.firstOrNull()?.chapter?.images ?: emptyList<ChapterImage>()

        images.forEach { image ->
            // [FIX] Sửa lỗi deprecated constructor
            callback(
                newExtractorLink(
                    source = this.name,
                    name = "iHentai Image",
                    url = image.image_url,
                    referer = "$mainUrl/",
                    quality = Qualities.Unknown.value,
                )
            )
        }
        return true
    }
}
