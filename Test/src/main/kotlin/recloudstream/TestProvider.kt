package recloudstream

import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import com.lagradost.cloudstream3.utils.AppUtils.toJson
import org.jsoup.Jsoup
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.mvvm.suspendSafeApiCall
import java.net.URI
import java.text.Normalizer
import java.util.Base64

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import okhttp3.Interceptor
import okhttp3.Response
import okhttp3.ResponseBody
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.ResponseBody.Companion.toResponseBody
import com.lagradost.cloudstream3.CommonActivity.showToast
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import android.widget.Toast

// Data class để truyền dữ liệu từ load() -> loadLinks()
data class NguonCLoadData(
    @JsonProperty("slug") val slug: String,
    @JsonProperty("episodeNum") val episodeNum: Int
)

data class NguonCItem(
    @JsonProperty("name") val name: String,
    @JsonProperty("slug") val slug: String,
    @JsonProperty("thumb_url") val thumbUrl: Any?,
    @JsonProperty("poster_url") val posterUrl: String?,
    @JsonProperty("description") val description: String?,
    @JsonProperty("total_episodes") val totalEpisodes: Int,
    @JsonProperty("quality") val quality: String?,
    @JsonProperty("language") val language: String?,
    @JsonProperty("current_episode") val currentEpisode: String?,
    @JsonProperty("created") val created: String?
)

data class NguonCPaginate(
    @JsonProperty("current_page") val currentPage: Int,
    @JsonProperty("total_page") val totalPage: Int
)

data class NguonCListResponse(
    @JsonProperty("items") val items: List<NguonCItem>,
    @JsonProperty("paginate") val paginate: NguonCPaginate
)

data class NguonCEpisodeItem(
    @JsonProperty("name") val name: String,
    @JsonProperty("slug") val slug: String,
    @JsonProperty("embed") val embed: String?,
    @JsonProperty("m3u8") val m3u8: String?
)

data class NguonCServer(
    @JsonProperty("server_name") val serverName: String,
    @JsonProperty("items") val items: List<NguonCEpisodeItem>?
)

data class NguonCCategoryInfo(
    @JsonProperty("name") val name: String
)

data class NguonCCategoryGroup(
    @JsonProperty("list") val list: List<NguonCCategoryInfo>?
)

data class NguonCDetailMovie(
    @JsonProperty("name") val name: String,
    @JsonProperty("slug") val slug: String,
    @JsonProperty("original_name") val originalName: String?,
    @JsonProperty("thumb_url") val thumbUrl: String?,
    @JsonProperty("poster_url") val posterUrl: String?,
    @JsonProperty("description") val description: String?,
    @JsonProperty("total_episodes") val totalEpisodes: Int,
    @JsonProperty("time") val time: String?,
    @JsonProperty("quality") val quality: String?,
    @JsonProperty("language") val language: String?,
    @JsonProperty("director") val director: String?,
    @JsonProperty("casts") val casts: String?,
    @JsonProperty("episodes") val episodes: List<NguonCServer>,
    @JsonProperty("category") val category: Map<String, NguonCCategoryGroup>?
)

data class NguonCDetailResponse(
    @JsonProperty("movie") val movie: NguonCDetailMovie
)


// Lớp chính của Plugin
// ====================

class NguonCProvider : MainAPI() {
    override var mainUrl = "https://phim.nguonc.com"
    override var name = "Nguồn C"
    override val supportedTypes = setOf(TvType.TvSeries, TvType.Movie, TvType.Anime)
    override var lang = "vi"
    override val hasMainPage = true
    private val apiUrl = "$mainUrl/api"
    private val userAgent = "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/137.0.0.0 Mobile Safari/537.36"
    private val headers = mapOf("User-Agent" to userAgent, "Referer" to "https://phim.nguonc.com")

    override val mainPage = mainPageOf(
        "phim-moi-cap-nhat" to "Phim Mới Cập Nhật",
        "danh-sach/phim-le" to "Phim Lẻ Mới",
        "danh-sach/phim-bo" to "Phim Bộ Mới",
        "the-loai/hoat-hinh" to "Anime Mới"
    )

    private val nonLatin = "[^\\w-]".toRegex()
    private val whitespace = "\\s+".toRegex()
    private fun String.toUrlSlug(): String {
        val normalized = Normalizer.normalize(this, Normalizer.Form.NFD)
        val slug = nonLatin.replace(normalized, "")
        return whitespace.replace(slug, "-").lowercase()
    }

    private fun NguonCItem.toSearchResponse(): SearchResponse? {
        val year = this.created?.substringBefore("-")?.toIntOrNull()

        // Kiểm tra và làm sạch URL ảnh
        val poster = this.posterUrl?.takeIf { it.endsWith(".jpg") || it.endsWith(".png") || it.endsWith(".jpeg") } 
            ?: (this.thumbUrl as? String)?.takeIf { it.endsWith(".jpg") || it.endsWith(".png") || it.endsWith(".jpeg") }

        if (this.totalEpisodes <= 1) {
            return newMovieSearchResponse(
                name = this.name,
                url = "$mainUrl/phim/${this.slug}",
                type = TvType.Movie
            ) {
                this.posterUrl = poster
                this.year = year
            }
        } else {
            return newTvSeriesSearchResponse(
                name = this.name,
                url = "$mainUrl/phim/${this.slug}",
                type = TvType.TvSeries
            ) {
                this.posterUrl = poster
                this.year = year
            }
        }
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = "$apiUrl/films/${request.data}?page=$page"
        val response = app.get(url, headers = headers).parsedSafe<NguonCListResponse>() ?: return newHomePageResponse(request.name, emptyList())
        val items = response.items.mapNotNull { it.toSearchResponse() }
        return newHomePageResponse(request.name, items, hasNext = response.paginate.currentPage < response.paginate.totalPage)
    }
    
    override suspend fun search(query: String): List<SearchResponse> {
        val url = "$apiUrl/films/search?keyword=$query"
        return app.get(url, headers = headers).parsedSafe<NguonCListResponse>()?.items?.mapNotNull {
            it.toSearchResponse()
        } ?: emptyList()
    }

    override suspend fun load(url: String): LoadResponse {
        val slug = url.substringAfterLast('/')
        val res = app.get("$apiUrl/film/$slug", headers = headers).parsedSafe<NguonCDetailResponse>()
            ?: return newMovieLoadResponse(url.substringAfterLast("-"), url, TvType.Movie, url)

        val movie = res.movie
        val title = movie.name
        val poster = movie.posterUrl ?: movie.thumbUrl
        val plot = movie.description?.let { Jsoup.parse(it).text() }
        val genres = movie.category?.values?.flatMap { it.list ?: emptyList() }?.map { it.name } ?: emptyList()       
        val isAnime = genres.any { it.equals("Hoạt Hình", ignoreCase = true) }
        val type = if (isAnime) {
            if (movie.totalEpisodes <= 1) TvType.AnimeMovie else TvType.Anime
        } else {
            if (movie.totalEpisodes <= 1) TvType.Movie else TvType.TvSeries
        }

        val recommendations = mutableListOf<SearchResponse>()
        for (genreName in genres) {
            runCatching {
                val genreSlug = genreName.toUrlSlug()
                app.get("$apiUrl/films/the-loai/$genreSlug?page=1", headers = headers).parsedSafe<NguonCListResponse>()
                    ?.items?.let { recItems ->
                        if (recItems.isNotEmpty()) {
                            recommendations.addAll(
                                recItems.filter { it.slug != movie.slug }.mapNotNull { it.toSearchResponse() }
                            )
                        }
                    }
            }
            if (recommendations.isNotEmpty()) break
        }
        
        if (movie.totalEpisodes <= 1) {
            val loadData = NguonCLoadData(slug = slug, episodeNum = 1).toJson()
            return newMovieLoadResponse(title, url, type, loadData) {
                this.posterUrl = poster
                this.plot = plot
                this.tags = genres
                this.recommendations = recommendations
            }
        } else {
            val episodes = movie.episodes
                .flatMap { server ->
                    (server.items ?: emptyList()).map { item ->
                        Pair(item.name.toIntOrNull(), server.serverName)
                    }
                }
                .filter { it.first != null }
                .groupBy { it.first!! }
                .map { (epNum, sources) ->
                    val tags = sources.mapNotNull { (_, serverName) ->
                        when {
                            serverName.contains("Vietsub", ignoreCase = true) -> "VS"
                            serverName.contains("Thuyết minh", ignoreCase = true) -> "TM"
                            serverName.contains("Lồng Tiếng", ignoreCase = true) -> "LT"
                            else -> null
                        }
                    }.distinct().joinToString("-")

                    val displayName = "Tập $epNum" + if (tags.isNotBlank()) " [$tags]" else ""
                    val loadData = NguonCLoadData(slug = slug, episodeNum = epNum).toJson()

                    newEpisode(data = loadData) {
                        this.name = displayName
                        this.episode = epNum
                    }
                }
                .sortedBy { it.episode }

            return newTvSeriesLoadResponse(title, url, type, episodes) {
                this.posterUrl = poster
                this.plot = plot
                this.tags = genres
                this.recommendations = recommendations
            }
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val loadData = parseJson<NguonCLoadData>(data)
        val movie = app.get("$apiUrl/film/${loadData.slug}", headers = headers)
            .parsedSafe<NguonCDetailResponse>()?.movie ?: return false
        
        coroutineScope {
            movie.episodes.map { server ->
                async {
                    try {
                        val episodeItem = if (movie.totalEpisodes <= 1) {
                            server.items?.firstOrNull()
                        } else {
                            server.items?.find { it.name.toIntOrNull() == loadData.episodeNum }
                        }
                        
                        val embedUrl = episodeItem?.embed
                        if (embedUrl.isNullOrBlank()) return@async

                        // BƯỚC 1: Tải nội dung trang embed
                        val embedPageContent = app.get(embedUrl, headers = headers).text
                        
                        // BƯỚC 2: Trích xuất chuỗi `data-obf`
                        val doc = Jsoup.parse(embedPageContent)
                        val obfuscatedString = doc.selectFirst("#player")?.attr("data-obf")

                        if (!obfuscatedString.isNullOrBlank()) {
                            // BƯỚC 3: Dùng trực tiếp chuỗi `data-obf` để tạo URL M3U8 cuối cùng
                            val embedOrigin = URI(embedUrl).let { "${it.scheme}://${it.host}" }
                            val finalM3u8Url = "$embedOrigin/$obfuscatedString.m3u8"
                            
                            val playerHeaders = mapOf(
                                "Origin" to embedOrigin,
                                "Referer" to embedUrl,
                                "User-Agent" to userAgent
                            )

                            // Thay đổi cốt lõi: 
                            // Gọi callback trực tiếp với link M3U8 gốc và headers cần thiết.
                            // Không cần dùng dịch vụ trung gian nữa.
                            callback(
                                ExtractorLink(
                                    source = this@NguonCProvider.name,
                                    name = server.serverName,
                                    url = finalM3u8Url,
                                    referer = embedUrl,
                                    quality = Qualities.Unknown.value,
                                    type = ExtractorLinkType.M3U8,
                                    headers = playerHeaders
                                )
                            )
                        }
                    } catch (e: Exception) {
                        e.printStackTrace()
                    }
                }
            }.awaitAll()
        }
        
        return true
    }

    override fun getVideoInterceptor(extractorLink: ExtractorLink): Interceptor? {
        val hihihohoRegex = Regex("""hihihoho\d+\.top""")
        val amassRegex = Regex("""amass\d+\.top""")

        return object : Interceptor {
            override fun intercept(chain: Interceptor.Chain): Response {
                val request = chain.request()
                val response = chain.proceed(request)
                val url = request.url.toString()

                if (hihihohoRegex.containsMatchIn(url) || amassRegex.containsMatchIn(url)) {
                    response.body?.let { body ->
                        try {
                            val fixedBytes = skipByteError(body)
                            val newBody = fixedBytes.toResponseBody(body.contentType())
                            return response.newBuilder().body(newBody).build()
                        } catch (_: Exception) {
                        }
                    }
                }
                return response
            }
        }
    }
}

private fun skipByteError(responseBody: ResponseBody): ByteArray {
    val source = responseBody.source()
    source.request(Long.MAX_VALUE)
    val buffer = source.buffer.clone()
    source.close()

    val byteArray = buffer.readByteArray()
    val length = byteArray.size - 188
    var start = 0
    for (i in 0 until length) {
        val nextIndex = i + 188
        if (nextIndex < byteArray.size && byteArray[i].toInt() == 71 && byteArray[nextIndex].toInt() == 71) {
            start = i
            break
        }
    }
    return if (start > 0) byteArray.copyOfRange(start, byteArray.size) else byteArray
}
