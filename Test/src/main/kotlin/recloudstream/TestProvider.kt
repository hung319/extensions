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
import com.lagradost.cloudstream3.CommonActivity.showToast
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import android.widget.Toast

// =================== DATA CLASSES ===================

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

// Dùng cho Homepage
data class OphimHomepage(
    @JsonProperty("status") val status: Boolean,
    @JsonProperty("items") val items: List<OphimItem>,
    @JsonProperty("pathImage") val pathImage: String
)

// Dùng cho Search (API mới)
data class ApiSearchResponse(
    @JsonProperty("data") val data: ApiSearchData
)

data class ApiSearchData(
    @JsonProperty("items") val items: List<OphimItem>
)


// Dùng cho Load (chi tiết phim)
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
        // Tạm ẩn toast để không làm phiền người dùng
        // withContext(Dispatchers.Main) {
        //     CommonActivity.activity?.let { activity ->
        //         showToast(activity, "Free Repo From SIX [H4RS]\nTelegram/Discord: hung319", Toast.LENGTH_LONG)
        //     }
        // }
        val url = request.data + page
        val response = app.get(url).text
        val homepageData = parseJson<OphimHomepage>(response)

        val imageBasePath = homepageData.pathImage

        val results = homepageData.items.mapNotNull { item ->
            val imageUrl = if (item.thumbUrl.isNullOrBlank()) item.posterUrl else item.thumbUrl
            
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

    /**
     * Hàm search đã được cập nhật để sử dụng API mới.
     * - Nhanh hơn: Không cần tải và parse HTML.
     * - Ổn định hơn: API ít có khả năng thay đổi cấu trúc hơn so với layout HTML.
     */
    override suspend fun search(query: String): List<SearchResponse> {
        val searchUrl = "$mainUrl/v1/api/tim-kiem?keyword=$query"
        
        return try {
            val response = app.get(searchUrl).text
            val searchJson = parseJson<ApiSearchResponse>(response)
            val searchItems = searchJson.data.items

            searchItems.map { item ->
                val imageUrl = if (item.thumbUrl.isNullOrBlank()) item.posterUrl else item.thumbUrl
                
                newMovieSearchResponse(
                    name = item.name,
                    url = getUrl("phim/${item.slug}"),
                    type = getTvType(item),
                ) {
                    this.posterUrl = getImageUrl(imageUrl)
                    this.year = item.year
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
            emptyList()
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
            val headers = mapOf("Referer" to mainUrl)

            // BƯỚC 1: LẤY URL FILE CON TỪ FILE MASTER
            val masterM3u8Url = data
            val masterM3u8Content = app.get(masterM3u8Url, headers = headers).text
            val variantPath = masterM3u8Content.lines().lastOrNull { it.isNotBlank() && !it.startsWith("#") }
                ?: throw Exception("Không tìm thấy luồng M3U8 con")
            val masterPathBase = masterM3u8Url.substringBeforeLast("/")
            val variantM3u8Url = if (variantPath.startsWith("http")) variantPath else "$masterPathBase/$variantPath"

            // BƯỚC 2: TẢI FILE CON, LỌC QUẢNG CÁO VÀ TẠO LINK TUYỆT ĐỐI
            val finalPlaylistContent = app.get(variantM3u8Url, headers = headers).text
            val variantPathBase = variantM3u8Url.substringBeforeLast("/")

            val cleanedLines = mutableListOf<String>()
            val lines = finalPlaylistContent.lines()
            var i = 0
            while (i < lines.size) {
                val line = lines[i].trim()

                // Kiểm tra xem có phải là điểm bắt đầu của một khối quảng cáo không
                if (line == "#EXT-X-DISCONTINUITY") {
                    // Nhìn trước dòng tiếp theo để xem có khớp với mẫu quảng cáo không
                    val nextInfoLine = lines.getOrNull(i + 1)?.trim()
                    val isAdPattern = nextInfoLine != null && (
                            nextInfoLine.startsWith("#EXTINF:3.92") || 
                            nextInfoLine.startsWith("#EXTINF:0.76")
                        )

                    // Nếu đúng là khối quảng cáo
                    if (isAdPattern) {
                        // Tìm thẻ #EXT-X-DISCONTINUITY tiếp theo để biết điểm kết thúc
                        var adBlockEndIndex = i
                        for (j in (i + 1) until lines.size) {
                            if (lines[j].trim() == "#EXT-X-DISCONTINUITY") {
                                adBlockEndIndex = j
                                break
                            }
                            adBlockEndIndex = j // Trong trường hợp đây là khối cuối cùng
                        }
                        // Bỏ qua toàn bộ khối quảng cáo bằng cách nhảy chỉ số i
                        i = adBlockEndIndex + 1
                        continue // Bắt đầu vòng lặp tiếp theo từ sau khối quảng cáo
                    }
                }

                // Nếu không phải quảng cáo, xử lý và giữ lại dòng
                if (line.isNotEmpty()) {
                    if (!line.startsWith("#")) {
                        // Nếu là link segment, chuyển thành link tuyệt đối
                        cleanedLines.add("$variantPathBase/$line")
                    } else {
                        // Nếu là thẻ meta, giữ lại nguyên vẹn
                        cleanedLines.add(line)
                    }
                }
                
                i++ // Di chuyển đến dòng tiếp theo
            }

            val cleanedM3u8 = cleanedLines.joinToString("\n")

            // BƯỚC 3: UPLOAD VÀ TRẢ VỀ LINK
            val requestBody = cleanedM3u8.toRequestBody("application/vnd.apple.mpegurl".toMediaType())
            val finalUrl = app.post(
                "https://paste.swurl.xyz/ophim.m3u8",
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
