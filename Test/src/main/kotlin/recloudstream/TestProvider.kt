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
        val response = app.get(url).parsedSafe<NguonCListResponse>() ?: return newHomePageResponse(request.name, emptyList())
        val items = response.items.mapNotNull { it.toSearchResponse() }
        return newHomePageResponse(request.name, items, hasNext = response.paginate.currentPage < response.paginate.totalPage)
    }
    
    override suspend fun search(query: String): List<SearchResponse> {
        val url = "$apiUrl/films/search?keyword=$query"
        return app.get(url).parsedSafe<NguonCListResponse>()?.items?.mapNotNull {
            it.toSearchResponse()
        } ?: emptyList()
    }

    override suspend fun load(url: String): LoadResponse {
        val slug = url.substringAfterLast('/')
        val res = app.get("$apiUrl/film/$slug").parsedSafe<NguonCDetailResponse>()
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
                app.get("$apiUrl/films/the-loai/$genreSlug?page=1").parsedSafe<NguonCListResponse>()
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

    // SỬA ĐỔI LỚN: Chuyển hoàn toàn sang chế độ gỡ lỗi, hiển thị log ra UI
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        // Danh sách để lưu trữ các dòng log
        val debugLogs = mutableListOf<String>()

        try {
            val loadData = parseJson<NguonCLoadData>(data)
            debugLogs.add("1. OK: Phân tích dữ liệu: slug='${loadData.slug}', tập='${loadData.episodeNum}'")

            // Gọi API phim.nguonc.com
            val movieResponse = app.get("$apiUrl/film/${loadData.slug}", timeout = 20)
            debugLogs.add("2. API NguonC: Gọi URL '${movieResponse.url}', Status: ${movieResponse.code}")
            
            val movie = movieResponse.parsedSafe<NguonCDetailResponse>()?.movie
            if (movie == null) {
                debugLogs.add("3. LỖI: Không phân tích được chi tiết phim. Body: ${movieResponse.text.take(300)}")
                // In ra các log đã thu thập và thoát
                debugLogs.forEachIndexed { i, log -> callback(ExtractorLink(this.name, "LOG $i: $log", "https://debug.log/error", "", Qualities.Unknown.value, type = ExtractorLinkType.M3U8)) }
                return true
            }
            debugLogs.add("3. OK: Phân tích thành công phim '${movie.name}'")

            if (movie.episodes.isEmpty()) {
                debugLogs.add("4. LỖI: Phim không có server nào (mảng 'episodes' rỗng).")
            }

            // Lặp qua các server để lấy link
            movie.episodes.forEach { server ->
                debugLogs.add("--- Đang xử lý Server: ${server.serverName} ---")
                
                val episodeItem = if (movie.totalEpisodes <= 1) {
                    server.items.firstOrNull()
                } else {
                    server.items.find { it.name.toIntOrNull() == loadData.episodeNum }
                }
                
                val embedUrl = episodeItem?.embed
                if (embedUrl.isNullOrBlank()) {
                    debugLogs.add("5. Lỗi: Không tìm thấy link embed cho server này.")
                    return@forEach // Bỏ qua server này, tiếp tục với server khác
                }
                debugLogs.add("5. OK: Tìm thấy link embed: $embedUrl")

                val embedOrigin = URI(embedUrl).let { "${it.scheme}://${it.host}" }
                val streamApiUrl = embedUrl.replace("?", "?api=stream&")
                debugLogs.add("6. Info: Tạo link API embed: $streamApiUrl")
                
                val headers = mapOf(
                    "referer" to embedUrl,
                    "user-agent" to "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/127.0.0.0 Mobile Safari/537.36"
                )

                // Gọi API của server embed để lấy streamUrl
                try {
                    val streamApiResponse = app.get(streamApiUrl, headers = headers, timeout = 20)
                    debugLogs.add("7. API Embed: Gọi URL embed, Status: ${streamApiResponse.code}")
                    debugLogs.add("8. Embed Response Body: ${streamApiResponse.text.take(300)}")

                    val parsedStreamData = streamApiResponse.parsedSafe<StreamApiResponse>()
                    val relativeStreamUrl = parsedStreamData?.streamUrl

                    if (relativeStreamUrl.isNullOrBlank()) {
                        debugLogs.add("9. LỖI: Không tìm thấy 'streamUrl' trong response từ embed.")
                    } else {
                        val finalM3u8Url = if(relativeStreamUrl.startsWith("http")) relativeStreamUrl else "$embedOrigin$relativeStreamUrl"
                        debugLogs.add("10. THÀNH CÔNG: Link M3U8 cuối cùng là: $finalM3u8Url")
                    }
                } catch (e: Exception) {
                    debugLogs.add("LỖI khi gọi API embed: ${e.javaClass.simpleName} - ${e.message}")
                }
            }
        } catch (e: Exception) {
            debugLogs.add("LỖI nghiêm trọng trong loadLinks: ${e.javaClass.simpleName} - ${e.message}")
        }
        
        // Luôn hiển thị danh sách log đã thu thập được
        if (debugLogs.isEmpty()) {
            debugLogs.add("Không có log nào được tạo. Lỗi xảy ra rất sớm.")
        }
        
        debugLogs.forEachIndexed { index, log ->
            callback(
                ExtractorLink(
                    this.name,
                    "LOG ${index + 1}: $log", // Tên của link chính là dòng log
                    "https://debug.log/${index + 1}", // URL giả để link có thể bấm được
                    "",
                    Qualities.Unknown.value,
                    type = ExtractorLinkType.M3U8
                )
            )
        }
        
        return true
    }
}
