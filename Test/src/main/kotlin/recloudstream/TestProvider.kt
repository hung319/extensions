// Thêm vào file: OnflixProvider.kt

package recloudstream

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import com.lagradost.cloudstream3.utils.AppUtils.toJson
import com.fasterxml.jackson.annotation.JsonProperty
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope

// =================== DATA CLASSES ===================

data class OnflixMeSearchResult(
    val name: String?,
    val slug: String?,
    @JsonProperty("thumb_url") val thumbUrl: String?
)

data class GetEpResponse(
    val vietsubEpisodes: List<EpisodeItem>?,
    val thuyetMinhEpisodes: List<EpisodeItem>?
)
data class EpisodeItem(
    val name: String?,
    @JsonProperty("link_m3u8") val linkM3u8: String?,
    @JsonProperty("link_embed") val linkEmbed: String?
)

data class LdJsonData(
    val name: String?,
    val description: String?,
    val image: String?,
    val genre: String?,
    val datePublished: String?
)

data class PlayerSubtitle(
    val language: String?,
    @JsonProperty("subtitle_file") val subtitleFile: String?
)

// SỬA LỖI: Dùng lại lớp Data trung gian để truyền thông tin an toàn đến `loadLinks`
private data class LinkData(
    val slug: String,
    val isMovie: Boolean,
    // Chứa JSON của EpisodeItem, chỉ dùng cho phim bộ
    val episodeItemJson: String? 
)

// =================== PROVIDER IMPLEMENTATION ===================

class OnflixProvider : MainAPI() {
    override var mainUrl = "https://onflix.me"
    override var name = "Onflix"
    override val hasMainPage = true
    override var lang = "vi"
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)

    private val servers = listOf("server1", "server2", "server3", "server5")

    override val mainPage = mainPageOf(
        "/phim.php?loai-phim=phim-le" to "Phim Lẻ Mới",
        "/phim.php?loai-phim=phim-bo" to "Phim Bộ Mới",
        "/anime" to "Anime Mới"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val document = app.get("$mainUrl${request.data}").document
        val homeList = document.select("div.movie-card").mapNotNull {
            val linkTag = it.selectFirst("a") ?: return@mapNotNull null
            newMovieSearchResponse(
                name = it.selectFirst("h6 a")?.text() ?: return@mapNotNull null,
                url = linkTag.attr("href")
            ) {
                 this.posterUrl = it.selectFirst("img")?.attr("src")
            }
        }
        return newHomePageResponse(request.name, homeList)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val url = "$mainUrl/search.php?term=$query"
        val response = app.get(url).parsedSafe<List<OnflixMeSearchResult>>() ?: return emptyList()

        return response.mapNotNull { item ->
            val slug = item.slug ?: return@mapNotNull null
            newMovieSearchResponse(
                name = item.name ?: return@mapNotNull null,
                url = "/phim/$slug"
            ) {
                this.posterUrl = item.thumbUrl
            }
        }
    }

    override suspend fun load(url: String): LoadResponse? = coroutineScope {
        val document = app.get(url).document
        
        val ldJsonText = document.select("script[type=\"application/ld+json\"]")
            .find { it.data().contains("\"@type\": \"Movie\"") }?.data()
        val ldJsonData = ldJsonText?.let { parseJson<LdJsonData>(it) }

        val title = ldJsonData?.name ?: "N/A"
        val plot = ldJsonData?.description
        val poster = ldJsonData?.image
        val year = ldJsonData?.datePublished?.take(4)?.toIntOrNull()
        val tags = ldJsonData?.genre?.split(',')?.map { it.trim() }
        val isMovie = ldJsonData?.genre?.contains("Phim bộ") != true
        
        val slug = url.substringAfterLast('/')
        
        val actorApiUrl = "$mainUrl/function/getactor.php?slug=$slug"
        val headers = mapOf("Referer" to url)
        val actorDocument = app.get(actorApiUrl, headers = headers).document
        val rating = actorDocument.selectFirst("span#ratingValue")?.text()?.toFloatOrNull()?.let { (it * 100).toInt() }
        val actors = actorDocument.select("div.actor-card").mapNotNull {
            ActorData(Actor(it.selectFirst("p.actor-name a")?.text() ?: return@mapNotNull null, it.selectFirst("img.actor-image")?.attr("src")))
        }

        if (isMovie) {
            val linkData = LinkData(slug = slug, isMovie = true, episodeItemJson = null)
            return@coroutineScope newMovieLoadResponse(title, url, TvType.Movie, linkData.toJson()) {
                this.posterUrl = poster; this.year = year; this.plot = plot; this.tags = tags; this.rating = rating; this.actors = actors
            }
        }

        val deferredEpisodes = servers.map { server ->
            async {
                app.get("$mainUrl/function/getep.php?slug=$slug&server=$server", headers = headers)
                   .parsedSafe<GetEpResponse>()?.let { resp ->
                        (resp.vietsubEpisodes ?: emptyList()) + (resp.thuyetMinhEpisodes ?: emptyList())
                   } ?: emptyList()
            }
        }
        
        val episodes = deferredEpisodes.awaitAll().flatten()
            .distinctBy { epItem -> epItem.name }
            .mapNotNull { epItem ->
                val linkData = LinkData(slug = slug, isMovie = false, episodeItemJson = epItem.toJson())
                newEpisode(linkData.toJson()) { 
                    this.name = "Tập ${epItem.name?.replace("Tập ", "")?.padStart(2,'0')}" 
                }
            }
            .sortedBy { ep -> ep.name?.filter { c -> c.isDigit() }?.toIntOrNull() }

        return@coroutineScope newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
            this.posterUrl = poster; this.year = year; this.plot = plot; this.tags = tags; this.rating = rating; this.actors = actors
        }
    }
    
    private val videoIdRegex = Regex("""const videoId = '(.*?)'""")
    private val subtitleDataRegex = Regex("""const subtitleData = (\[.*?\]);""")

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean = coroutineScope {
        // SỬA LỖI: Giải nén `LinkData` để lấy thông tin cần thiết
        val linkData = parseJson<LinkData>(data)
        val slug = linkData.slug
        
        val episodeItem = if(linkData.isMovie) {
            val headers = mapOf("Referer" to "$mainUrl/xem-phim/$slug")
            // Với phim lẻ, gọi song song tất cả các server và lấy link đầu tiên tìm thấy
            servers.map { server ->
                async {
                    app.get("$mainUrl/function/getep.php?slug=$slug&server=$server", headers = headers)
                        .parsedSafe<GetEpResponse>()?.let { (it.vietsubEpisodes ?: emptyList()) + (it.thuyetMinhEpisodes ?: emptyList()) }
                        ?.firstOrNull()
                }
            }.awaitAll().firstOrNull { it != null }
        } else {
             linkData.episodeItemJson?.let { parseJson<EpisodeItem>(it) }
        }

        if (episodeItem == null) return@coroutineScope false

        val providerName = this@OnflixProvider.name

        if (!episodeItem.linkM3u8.isNullOrBlank()) {
            callback(
                ExtractorLink(providerName, providerName, episodeItem.linkM3u8, mainUrl, Qualities.Unknown.value, type = ExtractorLinkType.M3U8)
            )
            return@coroutineScope true
        }

        val embedUrl = episodeItem.linkEmbed
        if (!embedUrl.isNullOrBlank()) {
            val embedHtml = app.get(embedUrl).text
            
            videoIdRegex.find(embedHtml)?.groupValues?.get(1)?.let { videoUrl ->
                callback(
                    ExtractorLink("$providerName (Embed)", "$providerName (Embed)", videoUrl, embedUrl, Qualities.Unknown.value, type = ExtractorLinkType.M3U8)
                )
            }

            subtitleDataRegex.find(embedHtml)?.groupValues?.get(1)?.let { subJson ->
                parseJson<List<PlayerSubtitle>>(subJson).forEach { sub ->
                    if (sub.subtitleFile != null && sub.language != null) {
                        subtitleCallback(SubtitleFile(sub.language, sub.subtitleFile))
                    }
                }
            }
            return@coroutineScope true
        }

        return@coroutineScope false
    }
}
