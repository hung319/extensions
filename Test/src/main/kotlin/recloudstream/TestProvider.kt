package com.lagradost.cloudstream3.hentai.providers

// =================================================================================
// --- IMPORT CẦN THIẾT (ĐÃ SỬA LẠI ĐƯỜNG DẪN) ---
// =================================================================================
import android.content.Context
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import com.lagradost.cloudstream3.extractors.Extractor // Sửa đường dẫn import
import com.lagradost.cloudstream3.utils.ExtractorApi // Sửa đường dẫn import
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.M3u8Helper
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import com.lagradost.cloudstream3.utils.AppUtils.fixUrlNull // Sửa đường dẫn import
import com.lagradost.cloudstream3.utils.AppUtils.fixUrl // Sửa đường dẫn import
import java.net.URLEncoder
import java.util.Base64
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

// =================================================================================
// --- DATA CLASSES (KHÔNG THAY ĐỔI) ---
// =================================================================================
@Serializable data class BunnyVideo(val id: Int? = null, val attributes: BunnyVideoAttributes? = null)
@Serializable data class BunnyVideoAttributes(val slug: String? = null, val title: String? = null, val views: Int? = null, val thumbnail: BunnyImage? = null, val bigThumbnail: BunnyImage? = null)
@Serializable data class BunnyImage(val data: BunnyImageData? = null)
@Serializable data class BunnyImageData(val id: Int? = null, val attributes: BunnyImageAttributes? = null)
@Serializable data class BunnyImageAttributes(val name: String? = null, val url: String? = null)
@Serializable data class BunnySearchResponse(val data: List<BunnyVideo>? = null)
@Serializable data class SonarApiResponse(val hls: List<SonarSource>? = null, val mp4: List<SonarSource>? = null)
@Serializable data class SonarSource(val url: String? = null, val label: String? = null)

val jsonParser = Json { ignoreUnknownKeys = true }

// =================================================================================
// --- PLUGIN (ĐỂ ĐĂNG KÝ) ---
// =================================================================================
@CloudstreamPlugin
class IHentaiPlugin: Plugin() { // Đổi tên để rõ ràng hơn
    override fun load(context: Context) {
        registerMainAPI(IHentaiProvider())
        registerExtractorAPI(IHentaiExtractor())
    }
}

// =================================================================================
// --- PROVIDER CHÍNH ---
// =================================================================================
class IHentaiProvider : MainAPI() {
    override var mainUrl = "https://ihentai.ws"
    override var name = "iHentai"
    override val hasMainPage = true
    override var lang = "vi"
    override val supportedTypes = setOf(TvType.NSFW)

    private val apiBaseUrl = "https://bunny-cdn.com"
    private val userAgent = "Mozilla/5.0 (Linux; Android 14; SM-S711B Build/UP1A.231005.007) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/135.0.7049.111 Mobile Safari/537.36"
    private val headers = mapOf("User-Agent" to userAgent)

    private fun fixBunnyUrl(url: String?): String? {
        if (url.isNullOrBlank()) return null
        if (url.startsWith("http")) return url
        return "$apiBaseUrl$url"
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val mainPageApiUrl = "$apiBaseUrl/api/videos?sort[0]=publishedAt:desc&sort[1]=slug:desc&pagination[page]=1&pagination[pageSize]=24&populate[0]=thumbnail"
        val homePageList = mutableListOf<HomePageList>()
        try {
            val response = app.get(mainPageApiUrl, headers = headers).text
            val mainPageData = jsonParser.decodeFromString<BunnySearchResponse>(response)
            val items = mainPageData.data?.mapNotNull { video ->
                parseBunnyVideo(video)
            }?.distinctBy { it.url } ?: emptyList()
            if (items.isNotEmpty()) {
                homePageList.add(HomePageList("Mới Cập Nhật", items))
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return HomePageResponse(homePageList)
    }

    private fun parseBunnyVideo(video: BunnyVideo): SearchResponse? {
        val attributes = video.attributes ?: return null
        val title = attributes.title ?: return null
        val slug = attributes.slug ?: return null
        val posterUrl = fixBunnyUrl(attributes.thumbnail?.data?.attributes?.url)
        if (slug.isBlank() || slug == "/") return null
        val seriesId = slug.substringBeforeLast("-", slug)
        if (seriesId.isBlank() || seriesId.contains('/')) return null
        return newAnimeSearchResponse(title, seriesId, TvType.NSFW) {
            this.posterUrl = posterUrl
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val encodedQuery = URLEncoder.encode(query, "UTF-8")
        val searchApiUrl = "$apiBaseUrl/api/videos?fields[0]=title&fields[1]=slug&filters[\$or][0][title][\$contains]=$encodedQuery&filters[\$or][1][otherTitles][\$contains]=$encodedQuery&sort[0]=publishedAt:desc&sort[1]=slug:desc&pagination[page]=1&pagination[pageSize]=50&populate[0]=thumbnail"
        return try {
            val response = app.get(searchApiUrl, headers = headers).text
            val searchData = jsonParser.decodeFromString<BunnySearchResponse>(response)
            searchData.data?.mapNotNull { video ->
                parseBunnyVideo(video)
            }?.distinctBy { it.url } ?: emptyList()
        } catch (e: Exception) {
            e.printStackTrace()
            emptyList()
        }
    }

    override suspend fun load(url: String): LoadResponse {
        val seriesId = url
        if (seriesId.isBlank() || seriesId.contains('/')) throw RuntimeException("Invalid seriesId: $seriesId")
        val encodedId = URLEncoder.encode(seriesId, "UTF-8")
        val episodeApiUrl = "$apiBaseUrl/api/videos?filters[slug][\$startsWith]=$encodedId&sort[0]=slug:asc&pagination[pageSize]=200&populate[0]=thumbnail&populate[1]=bigThumbnail"
        try {
            val response = app.get(episodeApiUrl, headers = headers).text
            val episodeData = jsonParser.decodeFromString<BunnySearchResponse>(response)
            val episodes = mutableListOf<Episode>()
            var seriesTitle = ""
            var posterUrl: String? = null
            var plot: String? = null
            var tags: List<String>? = null

            episodeData.data?.forEachIndexed { index, video ->
                val attributes = video.attributes ?: return@forEachIndexed
                val epTitle = attributes.title ?: return@forEachIndexed
                val epSlug = attributes.slug ?: return@forEachIndexed
                if (!epSlug.startsWith(seriesId) || epSlug == seriesId) return@forEachIndexed
                if (index == 0) {
                    seriesTitle = epTitle.replace(Regex("""\s+\d+$"""), "").trim().ifBlank { epTitle }
                    posterUrl = fixBunnyUrl(attributes.bigThumbnail?.data?.attributes?.url ?: attributes.thumbnail?.data?.attributes?.url)
                }
                val episodeNumber = Regex("""\s+(\d+)$""").find(epTitle)?.groupValues?.get(1)
                episodes.add(Episode(data = epSlug, name = epTitle, episode = episodeNumber?.toIntOrNull()))
            }
            if (episodes.isEmpty() && episodeData.data?.isNotEmpty() == true) {
                val singleVideo = episodeData.data.first(); val attributes = singleVideo.attributes
                if (attributes?.slug == seriesId) {
                    seriesTitle = attributes.title ?: seriesId
                    posterUrl = fixBunnyUrl(attributes.bigThumbnail?.data?.attributes?.url ?: attributes.thumbnail?.data?.attributes?.url)
                    episodes.add(Episode(data = attributes.slug, name = attributes.title ?: "Tập 1", episode = 1))
                }
            }
            if (episodes.isEmpty()) throw RuntimeException("No episodes found for series ID: $seriesId")
            return newAnimeLoadResponse(seriesTitle.ifBlank { seriesId }, url, TvType.NSFW) {
                this.posterUrl = posterUrl; this.plot = plot; this.tags = tags
                addEpisodes(DubStatus.Subbed, episodes.sortedBy { it.episode ?: Int.MAX_VALUE })
            }
        } catch (e: Exception) { throw RuntimeException("Failed to load details for $url: ${e.message}") }
    }

    @Suppress("DEPRECATION")
    override suspend fun loadLinks(data: String, isCasting: Boolean, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit): Boolean {
        try {
            val episodePageUrl = "$mainUrl/watch/$data"
            val iframeSrc = app.get(episodePageUrl, headers = headers).document.selectFirst("iframe.tw-w-full")?.attr("src")
                ?: throw RuntimeException("Iframe not found on page: $episodePageUrl")
            val videoId = iframeSrc.substringAfterLast("?v=", "").substringBefore("&")
            if (videoId.isBlank()) throw RuntimeException("Could not extract videoId from iframe: $iframeSrc")

            val masterM3u8Url = "https://s2.mimix.cc/$videoId/master.m3u8"
            val masterUrlEncoded = Base64.getEncoder().encodeToString(masterM3u8Url.toByteArray())
            val refererEncoded = Base64.getEncoder().encodeToString(iframeSrc.toByteArray())
            val virtualUrl = "https://ihentai.local/proxy.m3u8?url=$masterUrlEncoded&referer=$refererEncoded"
            
            callback(ExtractorLink(this.name, "iHentai Server", virtualUrl, data, Qualities.Unknown.value, true))
            return true
        } catch (e: Exception) {
            e.printStackTrace()
            return false
        }
    }
}

// ==================================================================
// --- Lớp Extractor xử lý link ảo .local ---
// ==================================================================
class IHentaiExtractor : Extractor() { // Kế thừa từ Extractor
    override val name = "IHentaiExtractor"
    override val mainUrl = "https://ihentai.local"
    override val requiresReferer = true

    @Suppress("DEPRECATION")
    override suspend fun getUrl(url: String, referer: String?, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit) {
        try {
            val masterUrlEncoded = url.substringAfter("url=").substringBefore("&")
            val refererEncoded = url.substringAfter("referer=")
            val masterUrl = String(Base64.getDecoder().decode(masterUrlEncoded))
            val iframeReferer = String(Base64.getDecoder().decode(refererEncoded))

            val m3u8Headers = mapOf(
                "Referer" to iframeReferer,
                "User-Agent" to "Mozilla/5.0 (Linux; Android 14; SM-S711B Build/UP1A.231005.007) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/135.0.7049.111 Mobile Safari/537.36"
            )

            // Sửa tên hàm và kiểu dữ liệu của lambda
            M3u8Helper.generateM3u8(
                this.name, masterUrl, iframeReferer, headers = m3u8Headers
            ).forEach { stream: ExtractorLink -> // Thêm kiểu dữ liệu rõ ràng
                callback.invoke(stream)
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
}
