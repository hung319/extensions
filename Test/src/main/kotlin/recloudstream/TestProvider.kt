package com.lagradost.cloudstream3.hentai.providers // Đảm bảo package này đúng

// Import các lớp cần thiết
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.extractors.Extractor // Sửa đường dẫn import
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.M3u8Helper
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import com.lagradost.cloudstream3.utils.fixUrl
import com.lagradost.cloudstream3.utils.fixUrlNull
import java.net.URLEncoder
import java.util.Base64
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

// --- Định nghĩa Data Classes cho các API ---
@Serializable
data class BunnyVideo(val id: Int? = null, val attributes: BunnyVideoAttributes? = null)
@Serializable
data class BunnyVideoAttributes(val slug: String? = null, val title: String? = null, val views: Int? = null, val thumbnail: BunnyImage? = null, val bigThumbnail: BunnyImage? = null)
@Serializable
data class BunnyImage(val data: BunnyImageData? = null)
@Serializable
data class BunnyImageData(val id: Int? = null, val attributes: BunnyImageAttributes? = null)
@Serializable
data class BunnyImageAttributes(val name: String? = null, val url: String? = null)
@Serializable
data class BunnySearchResponse(val data: List<BunnyVideo>? = null)
@Serializable
data class SonarApiResponse(val hls: List<SonarSource>? = null, val mp4: List<SonarSource>? = null)
@Serializable
data class SonarSource(val url: String? = null, val label: String? = null)

val jsonParser = Json { ignoreUnknownKeys = true }

// ======================================================================
// --- Lớp Provider chính: Lấy thông tin, tìm kiếm, tạo link ảo ---
// ======================================================================
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
        if (seriesId.isBlank() || seriesId.contains('/')) {
            throw RuntimeException("Invalid seriesId passed to load: $seriesId")
        }
        val encodedId = URLEncoder.encode(seriesId, "UTF-8")
        val episodeApiUrl = "$apiBaseUrl/api/videos?filters[slug][\$startsWith]=$encodedId&sort[0]=slug:asc&pagination[pageSize]=200&populate[0]=thumbnail&populate[1]=bigThumbnail"
        try {
            val response = app.get(episodeApiUrl, headers = headers).text
            val episodeData = jsonParser.decodeFromString<BunnySearchResponse>(response)
            val episodes = mutableListOf<Episode>()
            var seriesTitle = ""
            var posterUrl: String? = null
            var plot: String? = null // Cần lấy plot từ nguồn khác nếu có
            var tags: List<String>? = null // Cần lấy tags từ nguồn khác nếu có

            episodeData.data?.forEachIndexed { index, video ->
                val attributes = video.attributes ?: return@forEachIndexed
                val epTitle = attributes.title ?: return@forEachIndexed
                val epSlug = attributes.slug ?: return@forEachIndexed
                if (!epSlug.startsWith(seriesId)) return@forEachIndexed
                if (epSlug == seriesId) return@forEachIndexed
                if (index == 0) {
                    seriesTitle = epTitle.replace(Regex("""\s+\d+$"""), "").trim().ifBlank { epTitle }
                    posterUrl = fixBunnyUrl(attributes.bigThumbnail?.data?.attributes?.url ?: attributes.thumbnail?.data?.attributes?.url)
                }
                val episodeNumberMatch = Regex("""\s+(\d+)$""").find(epTitle)
                val episodeNumber = episodeNumberMatch?.groupValues?.get(1)
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
            if (episodes.isEmpty()) { throw RuntimeException("API call succeeded but found no valid episodes for series ID: $seriesId") }
            return newAnimeLoadResponse(seriesTitle.ifBlank { seriesId }, url, TvType.NSFW) {
                this.posterUrl = posterUrl; this.plot = plot; this.tags = tags
                addEpisodes(DubStatus.Subbed, episodes.sortedBy { it.episode ?: Int.MAX_VALUE })
            }
        } catch (e: Exception) { throw RuntimeException("Failed to load details for $url: ${e.message}") }
    }

    override suspend fun loadLinks(
        data: String, isCasting: Boolean, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit
    ): Boolean {
        try {
            val episodePageUrl = "$mainUrl/watch/$data"
            val document = app.get(episodePageUrl, headers = headers).document
            val iframeSrc = document.selectFirst("iframe.tw-w-full")?.attr("src")
                ?: throw RuntimeException("Không tìm thấy iframe video trên trang: $episodePageUrl")
            val videoId = iframeSrc.substringAfterLast("?v=", "").substringBefore("&")
            if (videoId.isBlank()) { throw RuntimeException("Không thể trích xuất videoId từ iframe src: $iframeSrc") }
            val masterM3u8Url = "https://s2.mimix.cc/$videoId/master.m3u8"
            val masterUrlEncoded = Base64.getEncoder().encodeToString(masterM3u8Url.toByteArray(Charsets.UTF_8))
            val refererEncoded = Base64.getEncoder().encodeToString(iframeSrc.toByteArray(Charsets.UTF_8))
            val virtualUrl = "https://ihentai.local/proxy.m3u8?url=$masterUrlEncoded&referer=$refererEncoded"
            callback(
                ExtractorLink(
                    this.name, "iHentai Server", virtualUrl, data, Qualities.Unknown.value, true
                )
            )
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
class IHentaiExtractor : Extractor() {
    override val name = "IHentaiExtractor"
    override val mainUrl = "https://ihentai.local"
    override val requiresReferer = true

    override suspend fun getUrl(
        url: String, referer: String?, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit
    ) {
        try {
            val masterUrlEncoded = url.substringAfter("url=").substringBefore("&")
            val refererEncoded = url.substringAfter("referer=")
            val masterUrl = String(Base64.getDecoder().decode(masterUrlEncoded))
            val iframeReferer = String(Base64.getDecoder().decode(refererEncoded))

            val m3u8Headers = mapOf(
                "Referer" to iframeReferer,
                "User-Agent" to "Mozilla/5.0 (Linux; Android 14; SM-S711B Build/UP1A.231005.007) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/135.0.7049.111 Mobile Safari/537.36"
            )

            // Dùng M3u8Helper.generateM3u8 thay vì m3u8Generation
            M3u8Helper.generateM3u8(
                this.name, masterUrl, iframeReferer, headers = m3u8Headers
            ).forEach { stream ->
                callback.invoke(stream)
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
}
