package recloudstream

import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import com.lagradost.cloudstream3.utils.newExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import org.jsoup.Jsoup
import com.lagradost.cloudstream3.newEpisode
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody

data class OphimItem(
    @JsonProperty("name") val name: String,
    @JsonProperty("slug") val slug: String,
    @JsonProperty("poster_url") val posterUrl: String?,
    @JsonProperty("thumb_url") val thumbUrl: String?,
    @JsonProperty("year") val year: Int?,
    @JsonProperty("type") val type: String?,
    @JsonProperty("tmdb") val tmdb: TmdbInfo?
)

data class TmdbInfo(
    @JsonProperty("type") val type: String?
)

data class OphimHomepage(
    @JsonProperty("status") val status: Boolean,
    @JsonProperty("items") val items: List<OphimItem>,
    @JsonProperty("pathImage") val pathImage: String
)

data class OphimDetail(
    @JsonProperty("movie") val movie: MovieDetail,
    @JsonProperty("episodes") val episodes: List<EpisodeServer>
)

data class MovieDetail(
    @JsonProperty("name") val name: String,
    @JsonProperty("origin_name") val originName: String,
    @JsonProperty("content") val content: String,
    @JsonProperty("poster_url") val posterUrl: String,
    @JsonProperty("thumb_url") val thumbUrl: String,
    @JsonProperty("year") val year: Int?,
    @JsonProperty("actor") val actor: List<String>?,
    @JsonProperty("category") val category: List<Category>?,
    @JsonProperty("country") val country: List<Country>?,
    @JsonProperty("type") val type: String
)

data class Category(
    @JsonProperty("name") val name: String
)

data class Country(
    @JsonProperty("name") val name: String
)

data class EpisodeServer(
    @JsonProperty("server_name") val serverName: String,
    @JsonProperty("server_data") val serverData: List<EpisodeData>
)

data class EpisodeData(
    @JsonProperty("name") val name: String,
    @JsonProperty("slug") val slug: String,
    @JsonProperty("link_m3u8") val linkM3u8: String
)

data class NextData(
    @JsonProperty("props") val props: Props
)

data class Props(
    @JsonProperty("pageProps") val pageProps: PageProps
)

data class PageProps(
    @JsonProperty("data") val data: SearchData
)

data class SearchData(
    @JsonProperty("items") val items: List<OphimItem>
)

class OphimProvider : MainAPI() {
    override var mainUrl = "https://ophim1.com"
    override var name = "Ophim"
    override var lang = "vi"
    override val hasMainPage = true
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries, TvType.Anime)

    private fun getUrl(path: String): String {
        return if (path.startsWith("http")) path else "$mainUrl/$path"
    }
    
    private fun getImageUrl(path: String?, basePath: String? = null): String? {
        if (path.isNullOrBlank()) return null
        val trimmedPath = path.trim()
        return if (trimmedPath.startsWith("http")) {
            trimmedPath
        } 
        else {
            (basePath ?: "https://img.ophim.live/uploads/movies/") + trimmedPath
        }
    }

    private fun getTvType(item: OphimItem): TvType {
        return when (item.type) {
            "hoathinh" -> TvType.Anime
            "series" -> TvType.TvSeries
            else -> {
                if (item.tmdb?.type == "tv") TvType.TvSeries else TvType.Movie
            }
        }
    }

    override val mainPage = mainPageOf(
        "$mainUrl/danh-sach/phim-moi-cap-nhat?page=" to "Phim mới cập nhật"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = request.data + page
        val response = app.get(url).text
        val homepageData = parseJson<OphimHomepage>(response)

        val imageBasePath = homepageData.pathImage

        val results = homepageData.items.mapNotNull { item ->
            val imageUrl = if (item.thumbUrl.isNullOrBlank()) item.posterUrl else item.thumbUrl
            
            // SỬA: Quay lại dùng newMovieSearchResponse để hết cảnh báo
            newMovieSearchResponse(
                name = item.name,
                url = getUrl("phim/${item.slug}"),
                type = getTvType(item),
            ) {
                this.posterUrl = getImageUrl(imageUrl, imageBasePath)
                this.year = item.year
            }
        }
        return newHomePageResponse(request.name, results)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val searchDomain = "https://ophim16.cc"
        val searchUrl = "$searchDomain/tim-kiem?keyword=$query"
        val doc = app.get(searchUrl).document

        val scriptData = doc.selectFirst("script#__NEXT_DATA__")?.data()
            ?: return emptyList()

        val searchJson = parseJson<NextData>(scriptData)
        val searchItems = searchJson.props.pageProps.data.items

        return searchItems.map { item ->
            val imageUrl = if (item.thumbUrl.isNullOrBlank()) item.posterUrl else item.thumbUrl
            
            // SỬA: Quay lại dùng newMovieSearchResponse để hết cảnh báo
            newMovieSearchResponse(
                name = item.name,
                url = getUrl("phim/${item.slug}"),
                type = getTvType(item),
            ) {
                this.posterUrl = getImageUrl(imageUrl)
                this.year = item.year
            }
        }
    }

    override suspend fun load(url: String): LoadResponse {
        val response = app.get(url).text
        val detailData = parseJson<OphimDetail>(response)
        val movieInfo = detailData.movie

        val title = movieInfo.name
        val poster = if (movieInfo.posterUrl.isBlank()) movieInfo.thumbUrl else movieInfo.posterUrl
        
        val posterUrl = getImageUrl(poster)
        val backgroundPosterUrl = getImageUrl(movieInfo.thumbUrl)
        val year = movieInfo.year
        val plot = Jsoup.parse(movieInfo.content).text()
        val tags = movieInfo.category?.map { it.name }
        val actors = movieInfo.actor?.map { ActorData(Actor(it)) }

        return when (movieInfo.type) {
            "hoathinh", "series" -> {
                val episodes = detailData.episodes.flatMap { server ->
                    server.serverData.map { episodeData ->
                        newEpisode(data = episodeData.linkM3u8) {
                            this.name = "Tập ${episodeData.name}"
                        }
                    }
                }
                val tvType = if (movieInfo.type == "hoathinh") TvType.Anime else TvType.TvSeries
                newTvSeriesLoadResponse(title, url, tvType, episodes) {
                    this.posterUrl = posterUrl
                    this.backgroundPosterUrl = backgroundPosterUrl
                    this.year = year
                    this.plot = plot
                    this.tags = tags
                    this.actors = actors
                }
            }
            else -> { 
                newMovieLoadResponse(
                    title,
                    url,
                    TvType.Movie,
                    detailData.episodes.firstOrNull()?.serverData?.firstOrNull()?.linkM3u8
                ) {
                    this.posterUrl = posterUrl
                    this.backgroundPosterUrl = backgroundPosterUrl
                    this.year = year
                    this.plot = plot
                    this.tags = tags
                    this.actors = actors
                }
            }
        }
    }

    override suspend fun loadLinks(
    data: String,
    isCasting: Boolean,
    subtitleCallback: (SubtitleFile) -> Unit,
    callback: (ExtractorLink) -> Unit
): Boolean {
    if (!data.startsWith("http")) return false

    try {
        // BƯỚC 1: LẤY VÀ PHÂN TÍCH PLAYLIST
        val headers = mapOf("Referer" to mainUrl)
        val masterM3u8Url = data
        val masterM3u8Content = app.get(masterM3u8Url, headers = headers).text

        val variantPath = masterM3u8Content.lines().lastOrNull { it.isNotBlank() && !it.startsWith("#") }
            ?: throw Exception("Không tìm thấy luồng M3U8 con")

        val masterPathBase = masterM3u8Url.substringBeforeLast("/")
        val variantM3u8Url = if (variantPath.startsWith("http")) variantPath else "$masterPathBase/$variantPath"

        // BƯỚC 2: LỌC NỘI DUNG M3U8
        val finalPlaylistContent = app.get(variantM3u8Url, headers = headers).text
        val variantPathBase = variantM3u8Url.substringBeforeLast("/")

        var isInsideMovieBlock = false
        val movieSegments = mutableListOf<String>()

        finalPlaylistContent.lines().forEach { line ->
            val trimmedLine = line.trim()
            if (trimmedLine == "#EXT-X-DISCONTINUITY") {
                isInsideMovieBlock = !isInsideMovieBlock
                return@forEach
            }

            if (isInsideMovieBlock) {
                // Chỉ giữ lại nội dung khi ở trong khối phim
                if (trimmedLine.isNotEmpty()) {
                     movieSegments.add(line)
                }
            }
        }
        
        // NẾU KHÔNG CÓ KHỐI DISCONTINUITY, LẤY TOÀN BỘ NỘI DUNG
        if (movieSegments.isEmpty() && finalPlaylistContent.contains("#EXTINF")) {
            movieSegments.addAll(finalPlaylistContent.lines())
        }

        // BƯỚC 3: XÂY DỰNG LẠI FILE M3U8 HOÀN CHỈNH
        val rebuiltM3u8 = buildString {
            appendLine("#EXTM3U")
            appendLine("#EXT-X-VERSION:3")
            
            movieSegments.forEach { line ->
                val trimmedLine = line.trim()
                if (trimmedLine.isNotEmpty()) {
                    if (trimmedLine.startsWith("#")) {
                        // Giữ lại các thẻ metadata của segment
                        appendLine(line)
                    } else {
                        // Đảm bảo URL của segment là tuyệt đối
                        val segmentUrl = if (trimmedLine.startsWith("http")) trimmedLine else "$variantPathBase/$trimmedLine"
                        appendLine(segmentUrl)
                    }
                }
            }
            appendLine("#EXT-X-ENDLIST")
        }

        if (rebuiltM3u8.isBlank() || !rebuiltM3u8.contains("#EXTINF")) {
            throw Exception("Nội dung M3U8 trống sau khi lọc và xây dựng lại.")
        }
        
        // BƯỚC 4: UPLOAD VÀ TRẢ VỀ LINK
        val requestBody = rebuiltM3u8.toRequestBody("application/vnd.apple.mpegurl".toMediaType())
        val finalUrl = app.post(
            "https://paste.swurl.xyz/playlist.m3u8",
            requestBody = requestBody
        ).text.trim()

        if (!finalUrl.startsWith("http")) throw Exception("Tải M3U8 lên dịch vụ paste thất bại")
        
        callback.invoke(
            newExtractorLink(
                source = this.name,
                name = "${this.name} (Đã lọc)",
                url = finalUrl,
                type = ExtractorLinkType.M3U8
            ) {
                this.referer = mainUrl
                this.quality = Qualities.Unknown.value
            }
        )
        return true
    } catch (e: Exception) {
        e.printStackTrace()
        return false
    }
  }
}
