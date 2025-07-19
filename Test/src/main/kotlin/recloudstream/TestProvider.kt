// Tên file: NguonCProvider.kt

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

// Data class để truyền dữ liệu từ load() -> loadLinks()
data class NguonCLoadData(
    @JsonProperty("slug") val slug: String,
    @JsonProperty("episodeNum") val episodeNum: Int
)

// Data class cho phản hồi từ API của server embed
data class StreamApiResponse(
    @JsonProperty("streamUrl") val streamUrl: String?
)

data class NguonCItem(
    @JsonProperty("name") val name: String,
    @JsonProperty("slug") val slug: String,
    @JsonProperty("thumb_url") val thumbUrl: String?,
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
    @JsonProperty("items") val items: List<NguonCEpisodeItem>
)

data class NguonCCategoryInfo(
    @JsonProperty("name") val name: String
)

data class NguonCCategoryGroup(
    @JsonProperty("list") val list: List<NguonCCategoryInfo>
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
    private val headers = mapOf("User-Agent" to userAgent)

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
        if (this.totalEpisodes <= 1) {
            return newMovieSearchResponse(
                name = this.name,
                url = "$mainUrl/phim/${this.slug}",
                type = TvType.Movie
            ) {
                this.posterUrl = this@toSearchResponse.posterUrl ?: this@toSearchResponse.thumbUrl
                this.year = year
            }
        } else {
            return newTvSeriesSearchResponse(
                name = this.name,
                url = "$mainUrl/phim/${this.slug}",
                type = TvType.TvSeries
            ) {
                this.posterUrl = this@toSearchResponse.posterUrl ?: this@toSearchResponse.thumbUrl
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

        val genres = movie.category?.values?.flatMap { it.list }?.map { it.name } ?: emptyList()
        val isAnime = genres.any { it.equals("Hoạt Hình", ignoreCase = true) }
        val type = if (isAnime) {
            if (movie.totalEpisodes <= 1) TvType.AnimeMovie else TvType.Anime
        } else {
            if (movie.totalEpisodes <= 1) TvType.Movie else TvType.TvSeries
        }

        val recommendations = mutableListOf<SearchResponse>()
        genres.firstOrNull()?.let { primaryGenre ->
            suspendSafeApiCall {
                val genreSlug = primaryGenre.toUrlSlug()
                app.get("$apiUrl/films/the-loai/$genreSlug?page=1", headers = headers).parsedSafe<NguonCListResponse>()
                    ?.items?.let { recItems ->
                        recommendations.addAll(
                            recItems.filter { it.slug != movie.slug }.mapNotNull { it.toSearchResponse() }
                        )
                    }
            }
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
            val totalEpisodes = movie.episodes.mapNotNull { it.items?.size }.maxOrNull() ?: movie.totalEpisodes
            val episodes = (1..totalEpisodes).map { epNum ->
                val loadData = NguonCLoadData(slug = slug, episodeNum = epNum).toJson()
                Episode(
                    data = loadData,
                    name = "Tập $epNum",
                    episode = epNum,
                    season = 1,
                    posterUrl = movie.thumbUrl
                )
            }
            return newTvSeriesLoadResponse(title, url, type, episodes) {
                this.posterUrl = poster
                this.plot = plot
                this.tags = genres
                this.recommendations = recommendations
            }
        }
    }

    // SỬA ĐỔI LỚN: Chuyển sang chế độ gỡ lỗi bằng Exception
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val debugLogs = mutableListOf<String>()
        try {
            val loadData = parseJson<NguonCLoadData>(data)
            debugLogs.add("1. OK: Phân tích dữ liệu: slug='${loadData.slug}', tập='${loadData.episodeNum}'")

            val movie = app.get("$apiUrl/film/${loadData.slug}", headers = headers)
                .parsedSafe<NguonCDetailResponse>()?.movie
            if(movie == null) {
                debugLogs.add("2. LỖI: Không lấy được chi tiết phim từ API NguonC.")
                throw Exception(debugLogs.joinToString("\n"))
            }
            debugLogs.add("2. OK: Lấy được chi tiết phim '${movie.name}'")

            if (movie.episodes.isNullOrEmpty()) {
                 debugLogs.add("3. LỖI: Phim không có server nào (mảng 'episodes' rỗng).")
            } else {
                 debugLogs.add("3. OK: Tìm thấy ${movie.episodes.size} server.")
            }

            // Dùng forEach để log tuần tự, dễ đọc hơn
            movie.episodes.forEach { server ->
                debugLogs.add("--- Đang xử lý Server: ${server.serverName} ---")

                val episodeItem = if (movie.totalEpisodes <= 1) server.items.firstOrNull()
                else server.items.find { it.name.toIntOrNull() == loadData.episodeNum }

                val embedUrl = episodeItem?.embed
                if (embedUrl.isNullOrBlank()) {
                    debugLogs.add("4. Lỗi: Không tìm thấy link embed cho server này.")
                    return@forEach
                }
                debugLogs.add("4. OK: Link embed: $embedUrl")

                val embedPageContent = app.get(embedUrl, headers = headers).text
                debugLogs.add("5. OK: Đã tải HTML trang embed, độ dài: ${embedPageContent.length}")

                val authToken = embedPageContent.substringAfter("const authToken = '").substringBefore("'")
                val hash = embedPageContent.substringAfter("data-hash=\"").substringBefore("\"")
                debugLogs.add("6. OK: Trích xuất: authToken (dài ${authToken.length}), hash (dài ${hash.length})")

                if (authToken.isBlank() || hash.isBlank()) {
                    debugLogs.add("7. LỖI: Không trích xuất được authToken hoặc hash.")
                    return@forEach
                }

                val streamApiUrl = "$embedUrl?api=stream"
                val embedOrigin = URI(embedUrl).let { "${it.scheme}://${it.host}" }
                
                val apiHeaders = mapOf(
                    "Content-Type" to "application/json",
                    "X-Requested-With" to "XMLHttpRequest",
                    "X-Embed-Auth" to authToken,
                    "User-Agent" to userAgent,
                    "Referer" to embedUrl,
                    "Origin" to embedOrigin,
                    "Cookie" to "__dtsu=4C301750475292A9643FBE50677C1A34"
                )
                debugLogs.add("8. Info: Gửi POST đến $streamApiUrl")
                
                val requestBody = mapOf("hash" to hash)
                val streamApiResponse = app.post(streamApiUrl, headers = apiHeaders, json = requestBody)
                debugLogs.add("9. OK: Yêu cầu POST hoàn tất, Status: ${streamApiResponse.code}")
                debugLogs.add("10. Body trả về: ${streamApiResponse.text.take(300)}")

                val base64StreamUrl = streamApiResponse.parsedSafe<StreamApiResponse>()?.streamUrl
                if (base64StreamUrl.isNullOrBlank()) {
                    debugLogs.add("11. LỖI: Không tìm thấy 'streamUrl' trong response.")
                    return@forEach
                }
                debugLogs.add("11. OK: Lấy được streamUrl Base64 (dài ${base64StreamUrl.length})")

                val cleanBase64 = base64StreamUrl.replace('-', '+').replace('_', '/')
                val padding = "=".repeat((4 - cleanBase64.length % 4) % 4)
                val finalM3u8Url = String(Base64.getDecoder().decode(cleanBase64 + padding))
                debugLogs.add("12. THÀNH CÔNG: Giải mã được link M3U8: $finalM3u8Url")
            }
        } catch (e: Exception) {
            debugLogs.add("LỖI NGHIÊM TRỌNG: ${e.javaClass.simpleName} - ${e.message}")
            e.printStackTrace()
        }
        
        // Ném ra lỗi chứa toàn bộ log
        throw Exception(debugLogs.joinToString("\n"))
    }
}
