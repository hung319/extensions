package recloudstream

import android.util.Base64
import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.core.type.TypeReference
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import com.lagradost.cloudstream3.CommonActivity.showToast
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import android.widget.Toast

class KKPhimProvider : MainAPI() {
    override var name = "KKPhim"
    override var mainUrl = "https://kkphim.com"
    override val hasMainPage = true
    override var lang = "vi"

    private val apiDomain = "https://phimapi.com"
    private val imageCdnDomain = "https://phimimg.com"

    private val categories = mapOf(
    "Phim Mới Cập Nhật" to "danh-sach/phim-moi-cap-nhat", // Sửa lại slug cho đúng
    "Phim Bộ" to "danh-sach/phim-bo",
    "Phim Lẻ" to "danh-sach/phim-le",
    "Hoạt Hình" to "danh-sach/hoat-hinh",
    "TV Shows" to "danh-sach/tv-shows"
    )

    override var supportedTypes = setOf(
        TvType.Movie,
        TvType.TvSeries,
        TvType.Anime
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse? {
    // Chỉ hiển thị toast ở lần tải trang đầu tiên (page <= 1)
    if (page <= 1) {
        withContext(Dispatchers.Main) {
            CommonActivity.activity?.let { activity ->
                showToast(activity, "Free Repo From SIX [H4RS]\nTelegram/Discord: hung319", Toast.LENGTH_LONG)
            }
        }
    }

    val homePageLists = coroutineScope {
        categories.map { (title, slug) ->
            async {
                // Logic được sửa lại để xử lý đúng từng trường hợp
                val isNewMoviesCategory = slug == "danh-sach/phim-moi-cap-nhat"

                // 1. Tạo URL chính xác cho từng loại danh mục
                val url = if (isNewMoviesCategory) {
                    "$apiDomain/$slug?page=$page"
                } else {
                    "$apiDomain/v1/api/$slug?page=$page"
                }

                try {
                    // 2. Phân tích JSON response chính xác cho từng loại API
                    val items = if (isNewMoviesCategory) {
                        app.get(url).parsed<ApiResponse>().items
                    } else {
                        app.get(url).parsed<SearchApiResponse>().data?.items
                    } ?: emptyList()

                    val searchResults = items.mapNotNull { toSearchResponse(it) }
                    HomePageList(title, searchResults)
                } catch (e: Exception) {
                    HomePageList(title, emptyList())
                }
            }
        }.map { it.await() }
    }

    // Luôn trả về `hasNext = true` để đảm bảo có thể cuộn để tải thêm.
    return newHomePageResponse(
        homePageLists.filter { it.list.isNotEmpty() },
        hasNext = true
    )
}

    private fun toSearchResponse(item: MovieItem): SearchResponse? {
        val movieUrl = "$mainUrl/phim/${item.slug}"
        val tvType = when (item.type) {
            "series" -> TvType.TvSeries
            "single" -> TvType.Movie
            "hoathinh" -> TvType.Anime
            else -> when (item.tmdb?.type) {
                "movie" -> TvType.Movie
                "tv" -> TvType.TvSeries
                else -> null
            }
        } ?: return null

        val poster = item.posterUrl?.let {
            if (it.startsWith("http")) it else "$imageCdnDomain/$it"
        }

        return newMovieSearchResponse(item.name, movieUrl, tvType) {
            this.posterUrl = poster
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val url = "$apiDomain/v1/api/tim-kiem?keyword=$query"
        val response = app.get(url).parsed<SearchApiResponse>()

        return response.data?.items?.mapNotNull { item ->
            toSearchResponse(item)
        } ?: listOf()
    }

    override suspend fun load(url: String): LoadResponse? {
    val slug = url.substringAfter("/phim/")
    val apiUrl = "$apiDomain/phim/$slug"
    val response = app.get(apiUrl).parsed<DetailApiResponse>()
    val movie = response.movie ?: return null

    // Lấy thông tin chung của phim (giữ nguyên)
    val title = movie.name
    val poster = movie.posterUrl
    val year = movie.year
    val description = movie.content
    val tags = movie.category?.map { it.name }
    val actors = movie.actor?.map { ActorData(Actor(it)) }

    var recommendations: List<SearchResponse>? = null
    if (movie.recommendations is List<*>) {
        try {
            val movieItems = mapper.convertValue(movie.recommendations, object : TypeReference<List<MovieItem>>() {})
            recommendations = movieItems.mapNotNull { toSearchResponse(it) }
        } catch (e: Exception) { /* Ignore conversion errors */ }
    }
    if (recommendations.isNullOrEmpty()) {
        movie.category?.firstOrNull()?.slug?.let { categorySlug ->
            try {
                val recUrl = "$apiDomain/v1/api/the-loai/$categorySlug"
                val recResponse = app.get(recUrl).parsed<SearchApiResponse>()
                recommendations = recResponse.data?.items
                    ?.mapNotNull { toSearchResponse(it) }
                    ?.filter { it.url != url }
            } catch (e: Exception) { /* Ignore loading errors */ }
        }
    }

    // Xác định loại phim
    val tvType = when (movie.type) {
        "series" -> TvType.TvSeries
        "hoathinh" -> TvType.Anime
        else -> TvType.Movie // "single" sẽ được coi là Movie
    }

    // Logic nhóm các server (Vietsub, Thuyết Minh,...)
    val episodesGroupedBySlug = mutableMapOf<String, MutableList<MultiLink>>()
    response.episodes?.forEach { episodeGroup ->
        episodeGroup.serverData.forEach { episodeData ->
            val serverName = episodeGroup.serverName.substringAfter("(", "").substringBefore(")", "").ifBlank { episodeGroup.serverName }
            val multiLink = MultiLink(serverName, episodeData)
            episodesGroupedBySlug.getOrPut(episodeData.slug) { mutableListOf() }.add(multiLink)
        }
    }

    // --- BẮT ĐẦU LOGIC SỬA LỖI ---
    // Phân luồng xử lý riêng cho phim lẻ và phim bộ
    if (tvType == TvType.Movie) {
        // **XỬ LÝ CHO PHIM LẺ**
        // Lấy danh sách tất cả các link server (VS, TM,...) của phim.
        val movieLinks = episodesGroupedBySlug.values.firstOrNull()
        
        // Chuyển danh sách link thành chuỗi JSON để truyền cho nút "Play".
        val movieData = if (movieLinks != null) mapper.writeValueAsString(movieLinks) else null

        return newMovieLoadResponse(title, url, tvType, movieData) {
            this.posterUrl = poster; this.year = year; this.plot = description; this.tags = tags; this.actors = actors; this.recommendations = recommendations
        }
    } else {
        // **XỬ LÝ CHO PHIM BỘ / ANIME (logic cũ vẫn đúng)**
        val finalEpisodes = episodesGroupedBySlug.map { (episodeSlug, links) ->
            val serverTags = links.map {
                when {
                    it.serverName.contains("Vietsub") -> "VS"
                    it.serverName.contains("Thuyết Minh") -> "TM"
                    it.serverName.contains("Lồng Tiếng") -> "LT"
                    else -> ""
                }
            }.filter { it.isNotEmpty() }.joinToString("+")

            val episodeName = links.firstOrNull()?.episodeData?.name ?: episodeSlug
            val finalEpisodeName = if (serverTags.isNotBlank()) "$episodeName ($serverTags)" else episodeName
            val episodeDataJson = mapper.writeValueAsString(links)

            newEpisode(episodeDataJson) {
                this.name = finalEpisodeName
            }
        }
        return newTvSeriesLoadResponse(title, url, tvType, finalEpisodes) {
            this.posterUrl = poster; this.year = year; this.plot = description; this.tags = tags; this.actors = actors; this.recommendations = recommendations
        }
    }
    // --- KẾT THÚC LOGIC SỬA LỖI ---
}

    // Data class để chứa danh sách các link cho một tập phim (VS, TM, etc.)
    data class MultiLink(
        @JsonProperty("serverName") val serverName: String,
        @JsonProperty("episodeData") val episodeData: EpisodeData
    )
    
    override suspend fun loadLinks(
    data: String,
    isCasting: Boolean,
    subtitleCallback: (SubtitleFile) -> Unit,
    callback: (ExtractorLink) -> Unit
): Boolean {
    if (data.isBlank()) return false

    try {
        val links = mapper.readValue(data, object : TypeReference<List<MultiLink>>() {})

        // THAY THẾ `apmap` BẰNG `coroutineScope` VÀ `launch`
        coroutineScope {
            links.forEach { (serverName, episodeData) ->
                launch { // Chạy mỗi tác vụ xử lý link trên một coroutine riêng
                    try {
                        val headers = mapOf(
                            "Referer" to mainUrl,
                            "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/108.0.0.0 Safari/537.36"
                        )

                        // BƯỚC 1 & 2: LẤY VÀ LỌC NỘI DUNG M3U8
                        val masterM3u8Url = episodeData.linkM3u8
                        val masterContent = app.get(masterM3u8Url, headers = headers).text
                        val relativePlaylistUrl = masterContent.lines().lastOrNull { it.endsWith(".m3u8") }
                            ?: throw Exception("Không tìm thấy playlist con")
                        val masterUrlBase = masterM3u8Url.substringBeforeLast("/") + "/"
                        val finalPlaylistUrl = masterUrlBase + relativePlaylistUrl
                        val finalPlaylistContent = app.get(finalPlaylistUrl, headers = headers).text
                        var contentToProcess = finalPlaylistContent
                        if (!contentToProcess.contains('\n')) {
                            contentToProcess = contentToProcess.replace("#", "\n#").trim()
                        }
                        val lines = contentToProcess.lines()
                        val cleanedLines = mutableListOf<String>()
                        var i = 0
                        while (i < lines.size) {
                            val line = lines[i].trim()
                            if (line == "#EXT-X-DISCONTINUITY") {
                                var isAdBlock = false
                                var blockEndIndex = i
                                for (j in (i + 1) until lines.size) {
                                    val nextLine = lines[j]
                                    if (nextLine.contains("/v7/") || nextLine.contains("convertv7") || nextLine.contains("adjump")) {
                                        isAdBlock = true
                                    }
                                    if (j > i && nextLine.trim() == "#EXT-X-DISCONTINUITY") {
                                        blockEndIndex = j; break
                                    }
                                    if (j == lines.size - 1) {
                                        blockEndIndex = lines.size
                                    }
                                }
                                if (isAdBlock) {
                                    i = blockEndIndex; continue
                                }
                            }
                            if (line.isNotEmpty()) {
                                if (line.startsWith("#")) {
                                    cleanedLines.add(line)
                                } else if (!line.contains("/v7/") && !line.contains("convertv7") && !line.contains("adjump")) {
                                    val segmentUrl = if (line.startsWith("http")) line else (finalPlaylistUrl.substringBeforeLast("/") + "/" + line)
                                    cleanedLines.add(segmentUrl)
                                }
                            }
                            i++
                        }
                        val cleanedM3u8Content = cleanedLines.joinToString("\n")
                        if (cleanedM3u8Content.isBlank()) throw Exception("M3U8 rỗng sau khi lọc")

                        // BƯỚC 3: UPLOAD LÊN PACEBIN.ONRENDER.COM
                        val requestBody = cleanedM3u8Content.toRequestBody("text/plain".toMediaType())
                        val finalUrl = app.post(
                            url = "https://pacebin.onrender.com/kkphim.m3u8",
                            requestBody = requestBody
                        ).text.trim()

                        if (!finalUrl.startsWith("http")) {
                            throw Exception("Upload lên pacebin.onrender.com thất bại: $finalUrl")
                        }
                        
                        // BƯỚC 4: TRẢ LINK VỀ
                        callback.invoke(
                            ExtractorLink(
                                source = this.name, name = serverName, url = finalUrl,
                                referer = mainUrl, quality = Qualities.Unknown.value,
                                type = ExtractorLinkType.M3U8, headers = headers
                            )
                        )
                    } catch (e: Exception) {
                        e.printStackTrace()
                    }
                }
            }
        }
        return true
    } catch (e: Exception) {
        e.printStackTrace()
        return false
    }
}

    // --- DATA CLASSES ---
    data class ApiResponse(@JsonProperty("items") val items: List<MovieItem>? = null, @JsonProperty("pagination") val pagination: Pagination? = null)
    data class SearchApiResponse(@JsonProperty("data") val data: SearchData? = null)
    data class SearchData(@JsonProperty("items") val items: List<MovieItem>? = null, @JsonProperty("params") val params: SearchParams? = null)
    data class SearchParams(@JsonProperty("pagination") val pagination: Pagination? = null)
    data class MovieItem(@JsonProperty("name") val name: String, @JsonProperty("slug") val slug: String, @JsonProperty("poster_url") val posterUrl: String? = null, @JsonProperty("type") val type: String? = null, @JsonProperty("tmdb") val tmdb: TmdbInfo? = null)
    data class TmdbInfo(@JsonProperty("type") val type: String? = null)
    data class Pagination(@JsonProperty("currentPage") val currentPage: Int? = null, @JsonProperty("totalPages") val totalPages: Int? = null)
    data class DetailApiResponse(@JsonProperty("movie") val movie: DetailMovie? = null, @JsonProperty("episodes") val episodes: List<EpisodeGroup>? = null)
    data class DetailMovie(@JsonProperty("name") val name: String, @JsonProperty("content") val content: String? = null, @JsonProperty("poster_url") val posterUrl: String? = null, @JsonProperty("year") val year: Int? = null, @JsonProperty("type") val type: String? = null, @JsonProperty("category") val category: List<Category>? = null, @JsonProperty("actor") val actor: List<String>? = null, @JsonProperty("chieurap") val recommendations: Any? = null)
    data class Category(@JsonProperty("name") val name: String, @JsonProperty("slug") val slug: String)
    data class EpisodeGroup(@JsonProperty("server_name") val serverName: String, @JsonProperty("server_data") val serverData: List<EpisodeData>)
    data class EpisodeData(@JsonProperty("name") val name: String, @JsonProperty("slug") val slug: String, @JsonProperty("link_m3u8") val linkM3u8: String, @JsonProperty("link_embed") val linkEmbed: String)
}
