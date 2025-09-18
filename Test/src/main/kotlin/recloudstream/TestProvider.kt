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

// =================== V1 API DATA CLASSES ===================

// Dùng chung cho Homepage và Search
data class OphimApiV1ListResponse(
    @JsonProperty("data") val data: OphimApiV1ListData
)

data class OphimApiV1ListData(
    @JsonProperty("items") val items: List<OphimApiV1Item>,
    @JsonProperty("APP_DOMAIN_CDN_IMAGE") val appDomainCdnImage: String?
)

data class OphimApiV1Item(
    @JsonProperty("name") val name: String,
    @JsonProperty("slug") val slug: String,
    @JsonProperty("poster_url") val posterUrl: String?,
    @JsonProperty("thumb_url") val thumbUrl: String?,
    @JsonProperty("year") val year: Int?,
    @JsonProperty("type") val type: String?, // "single", "series", "hoathinh"
    @JsonProperty("tmdb") val tmdb: TmdbInfoV1?
)

data class TmdbInfoV1(
    @JsonProperty("type") val type: String? // "movie", "tv"
)

// Dùng cho trang chi tiết (Load)
data class OphimDetailV1Response(
    @JsonProperty("data") val data: OphimDetailV1Data
)

data class OphimDetailV1Data(
    @JsonProperty("item") val item: OphimDetailV1Item,
    @JsonProperty("APP_DOMAIN_CDN_IMAGE") val appDomainCdnImage: String?
)

data class OphimDetailV1Item(
    @JsonProperty("name") val name: String,
    @JsonProperty("content") val content: String?,
    @JsonProperty("poster_url") val posterUrl: String?,
    @JsonProperty("thumb_url") val thumbUrl: String?,
    @JsonProperty("year") val year: Int?,
    @JsonProperty("actor") val actor: List<String>?,
    @JsonProperty("category") val category: List<CategoryV1>?,
    @JsonProperty("country") val country: List<CountryV1>?,
    @JsonProperty("type") val type: String,
    @JsonProperty("episodes") val episodes: List<EpisodeServerV1>?
)

data class CategoryV1(
    @JsonProperty("name") val name: String
)

data class CountryV1(
    @JsonProperty("name") val name: String
)

data class EpisodeServerV1(
    @JsonProperty("server_name") val serverName: String,
    @JsonProperty("server_data") val serverData: List<EpisodeDataV1>
)

data class EpisodeDataV1(
    @JsonProperty("name") val name: String,
    @JsonProperty("slug") val slug: String,
    @JsonProperty("link_m3u8") val linkM3u8: String
)

// =================== PROVIDER CLASS ===================

class OphimProvider : MainAPI() {
    override var mainUrl = "https://ophim1.com"
    override var name = "Ophim"
    override var lang = "vi"
    override val hasMainPage = true
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries, TvType.Anime)

    private fun getUrl(path: String): String {
        return if (path.startsWith("http")) path else "$mainUrl/$path"
    }

    private fun getTvTypeV1(item: OphimApiV1Item): TvType {
        return when (item.type) {
            "hoathinh" -> TvType.Anime
            "series" -> TvType.TvSeries
            "single" -> TvType.Movie
            else -> {
                if (item.tmdb?.type == "tv") TvType.TvSeries else TvType.Movie
            }
        }
    }

    override val mainPage = mainPageOf(
        "/v1/api/danh-sach/phim-moi?page=" to "Phim Mới Cập Nhật",
        "/v1/api/danh-sach/phim-bo?page=" to "Phim Bộ",
        "/v1/api/danh-sach/phim-le?page=" to "Phim Lẻ",
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = getUrl(request.data + page)
        val response = app.get(url).text
        val apiResponse = parseJson<OphimApiV1ListResponse>(response)

        // [FIXED] Bổ sung /uploads/movies/ vào đường dẫn ảnh
        val imageBasePath = (apiResponse.data.appDomainCdnImage ?: "https://img.ophim.live") + "/uploads/movies"

        val results = apiResponse.data.items.mapNotNull { item ->
            val imageUrl = if (item.thumbUrl.isNullOrBlank()) item.posterUrl else item.thumbUrl

            newMovieSearchResponse(
                name = item.name,
                url = getUrl("phim/${item.slug}"),
                type = getTvTypeV1(item),
            ) {
                this.posterUrl = "$imageBasePath/${imageUrl?.trim()}"
                this.year = item.year
            }
        }
        return newHomePageResponse(request.name, results, hasNext = results.isNotEmpty())
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val searchUrl = "$mainUrl/v1/api/tim-kiem?keyword=$query"

        return try {
            val response = app.get(searchUrl).text
            val apiResponse = parseJson<OphimApiV1ListResponse>(response)

            // [FIXED] Bổ sung /uploads/movies/ vào đường dẫn ảnh
            val imageBasePath = (apiResponse.data.appDomainCdnImage ?: "https://img.ophim.live") + "/uploads/movies"

            apiResponse.data.items.map { item ->
                val imageUrl = if (item.posterUrl.isNullOrBlank()) item.thumbUrl else item.posterUrl

                newMovieSearchResponse(
                    name = item.name,
                    url = getUrl("phim/${item.slug}"),
                    type = getTvTypeV1(item)
                ) {
                    this.posterUrl = "$imageBasePath/${imageUrl?.trim()}"
                    this.year = item.year
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
            emptyList()
        }
    }

    override suspend fun load(url: String): LoadResponse {
        val slug = url.substringAfterLast("/").trim()
        val apiUrl = "$mainUrl/v1/api/phim/$slug"

        val response = app.get(apiUrl).text
        val apiResponse = parseJson<OphimDetailV1Response>(response)
        val movieInfo = apiResponse.data.item

        // [FIXED] Bổ sung /uploads/movies/ vào đường dẫn ảnh
        val imageBasePath = (apiResponse.data.appDomainCdnImage ?: "https://img.ophim.live") + "/uploads/movies"

        val title = movieInfo.name
        val posterUrl = "$imageBasePath/${movieInfo.posterUrl?.trim()}"
        val backgroundPosterUrl = "$imageBasePath/${movieInfo.thumbUrl?.trim()}"
        val year = movieInfo.year
        val plot = Jsoup.parse(movieInfo.content ?: "").text()
        val tags = movieInfo.category?.map { it.name }
        val actors = movieInfo.actor?.mapNotNull { if(it.isNotBlank()) ActorData(Actor(it)) else null }

        return when (movieInfo.type) {
            "hoathinh", "series" -> {
                val episodes = movieInfo.episodes?.flatMap { server ->
                    server.serverData.map { episodeData ->
                        newEpisode(data = episodeData.linkM3u8) {
                            val rawName = episodeData.name.trim()
                            
                            // [CHANGED] Thêm logic định dạng tên tập
                            this.name = if (rawName.toIntOrNull() != null) {
                                "Tập $rawName"
                            } else {
                                rawName
                            }
                            this.episode = rawName.filter { it.isDigit() }.toIntOrNull()
                        }
                    }
                } ?: emptyList()

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
                    movieInfo.episodes?.firstOrNull()?.serverData?.firstOrNull()?.linkM3u8
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
            val headers = mapOf("Referer" to mainUrl)

            val masterM3u8Url = data
            val masterM3u8Content = app.get(masterM3u8Url, headers = headers).text
            val variantPath = masterM3u8Content.lines().lastOrNull { it.isNotBlank() && !it.startsWith("#") }
                ?: throw Exception("Không tìm thấy luồng M3U8 con")
            val masterPathBase = masterM3u8Url.substringBeforeLast("/")
            val variantM3u8Url = if (variantPath.startsWith("http")) variantPath else "$masterPathBase/$variantPath"

            val finalPlaylistContent = app.get(variantM3u8Url, headers = headers).text
            val variantPathBase = variantM3u8Url.substringBeforeLast("/")

            val cleanedLines = mutableListOf<String>()
            val lines = finalPlaylistContent.lines()
            var i = 0
            while (i < lines.size) {
                val line = lines[i].trim()
                if (line == "#EXT-X-DISCONTINUITY") {
                    val nextInfoLine = lines.getOrNull(i + 1)?.trim()
                    val isAdPattern = nextInfoLine != null && (
                            nextInfoLine.startsWith("#EXTINF:3.92") ||
                            nextInfoLine.startsWith("#EXTINF:0.76")
                        )
                    if (isAdPattern) {
                        var adBlockEndIndex = i
                        for (j in (i + 1) until lines.size) {
                            if (lines[j].trim() == "#EXT-X-DISCONTINUITY") {
                                adBlockEndIndex = j
                                break
                            }
                            adBlockEndIndex = j
                        }
                        i = adBlockEndIndex + 1
                        continue
                    }
                }
                if (line.isNotEmpty()) {
                    if (!line.startsWith("#")) {
                        cleanedLines.add("$variantPathBase/$line")
                    } else {
                        cleanedLines.add(line)
                    }
                }
                i++
            }
            val cleanedM3u8 = cleanedLines.joinToString("\n")

            val requestBody = cleanedM3u8.toRequestBody("application/vnd.apple.mpegurl".toMediaType())
            val finalUrl = app.post(
                "https://pacebin.onrender.com/ophim.m3u8",
                requestBody = requestBody
            ).text.trim()

            if (!finalUrl.startsWith("http")) throw Exception("Tải M3U8 lên dịch vụ paste thất bại")

            callback.invoke(
                newExtractorLink(
                    source = this.name,
                    name = this.name,
                    url = finalUrl,
                    type = ExtractorLinkType.M3U8,
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
