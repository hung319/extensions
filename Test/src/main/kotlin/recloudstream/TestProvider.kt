package recloudstream

import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.core.type.TypeReference
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import android.util.Log

class KKPhimProvider : MainAPI() {
    override var name = "KKPhim"
    override var mainUrl = "https://kkphim.com"
    override val hasMainPage = true
    override var lang = "vi"

    private val apiDomain = "https://phimapi.com"
    private val imageCdnDomain = "https://phimimg.com"

    private val categories = mapOf(
        "Phim Mới Cập Nhật" to "phim-moi-cap-nhat",
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
        val homePageLists = coroutineScope {
            categories.map { (title, slug) ->
                async {
                    val url = if (slug == "phim-moi-cap-nhat") {
                        "$apiDomain/$slug?page=1"
                    } else {
                        "$apiDomain/v1/api/$slug?page=1"
                    }
                    try {
                        val items = if (slug == "phim-moi-cap-nhat") {
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

        return newHomePageResponse(homePageLists.filter { it.list.isNotEmpty() })
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

        val title = movie.name
        val poster = movie.posterUrl
        val year = movie.year
        val description = movie.content
        val tags = movie.category?.map { it.name }
        val actors = movie.actor?.let { actorList ->
            actorList.map { ActorData(Actor(it)) }
        }

        var recommendations: List<SearchResponse>? = null
        if (movie.recommendations is List<*>) {
            try {
                val movieItems = mapper.convertValue(movie.recommendations, object : TypeReference<List<MovieItem>>() {})
                recommendations = movieItems.mapNotNull { toSearchResponse(it) }
            } catch (e: Exception) {
                // Ignore conversion errors
            }
        }
        if (recommendations.isNullOrEmpty()) {
            movie.category?.firstOrNull()?.slug?.let { categorySlug ->
                try {
                    val recUrl = "$apiDomain/v1/api/the-loai/$categorySlug"
                    val recResponse = app.get(recUrl).parsed<SearchApiResponse>()
                    recommendations = recResponse.data?.items
                        ?.mapNotNull { toSearchResponse(it) }
                        ?.filter { it.url != url }
                } catch (e: Exception) {
                    // Ignore loading errors
                }
            }
        }

        val tvType = when (movie.type) {
            "series" -> TvType.TvSeries
            "hoathinh" -> TvType.Anime
            else -> TvType.Movie
        }

        val episodes = response.episodes?.flatMap { episodeGroup ->
            episodeGroup.serverData.map { episodeData ->
                newEpisode(episodeData) {
                    this.name = episodeData.name
                }
            }
        } ?: emptyList()

        return when (tvType) {
            TvType.TvSeries, TvType.Anime -> newTvSeriesLoadResponse(title, url, tvType, episodes) {
                this.posterUrl = poster; this.year = year; this.plot = description; this.tags = tags; this.actors = actors; this.recommendations = recommendations
            }
            TvType.Movie -> newMovieLoadResponse(title, url, tvType, episodes.firstOrNull()?.data) {
                this.posterUrl = poster; this.year = year; this.plot = description; this.tags = tags; this.actors = actors; this.recommendations = recommendations
            }
            else -> null
        }
    }

    override suspend fun loadLinks(
    data: String,
    isCasting: Boolean,
    subtitleCallback: (SubtitleFile) -> Unit,
    callback: (ExtractorLink) -> Unit
): Boolean {
    // Không còn khối try-catch. Bất kỳ lỗi nào cũng sẽ được ném ra ngoài.

    val episodeData = mapper.readValue(data, EpisodeData::class.java)

    val headers = mapOf(
        "Referer" to mainUrl,
        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/108.0.0.0 Safari/537.36"
    )

    // --- BƯỚC 1: LẤY VÀ PHÂN TÍCH MASTER PLAYLIST ---
    val masterM3u8Url = episodeData.linkM3u8
    val masterContent = app.get(masterM3u8Url, headers = headers).text
    if (masterContent.isBlank()) {
        throw Exception("Lỗi bước 1: Nội dung master M3U8 nhận về bị trống.")
    }

    val relativePlaylistUrl = masterContent.lines().lastOrNull { it.endsWith(".m3u8") }
        ?: throw Exception("Lỗi bước 1: Không tìm thấy link playlist con trong master M3U8.")
    
    val masterUrlBase = masterM3u8Url.substringBeforeLast("/") + "/"
    val finalPlaylistUrl = masterUrlBase + relativePlaylistUrl

    // --- BƯỚC 2: LỌC BỎ CÁC KHỐI /v7/ KHÔNG MONG MUỐN ---
    val finalPlaylistContent = app.get(finalPlaylistUrl, headers = headers).text
    if (finalPlaylistContent.isBlank()) {
        throw Exception("Lỗi bước 2: Nội dung final playlist nhận về bị trống.")
    }

    // Thêm ký tự xuống dòng trước mỗi thẻ # để định dạng lại M3U8
    val formattedContent = finalPlaylistContent.replace("#", "\n#").trim()
    val lines = formattedContent.lines()
    val cleanedLines = mutableListOf<String>()
    var i = 0
    while (i < lines.size) {
        val line = lines[i].trim()
        if (line == "#EXT-X-DISCONTINUITY") {
            var isV7Block = false
            var blockEndIndex = i
            for (j in (i + 1) until lines.size) {
                val nextLine = lines[j]
                if (nextLine.contains("/v7/")) { isV7Block = true }
                if (j > i && nextLine.trim() == "#EXT-X-DISCONTINUITY") {
                    blockEndIndex = j
                    break
                }
                if (j == lines.size - 1) { blockEndIndex = lines.size }
            }
            if (isV7Block) {
                i = blockEndIndex
                continue
            }
        }
        if (line.isNotEmpty()) {
            if (line.startsWith("#")) {
                cleanedLines.add(line)
            } else if (!line.contains("/v7/")) {
                val segmentBaseUrl = finalPlaylistUrl.substringBeforeLast("/") + "/"
                cleanedLines.add(segmentBaseUrl + line)
            }
        }
        i++
    }
    
    val cleanedM3u8Content = cleanedLines.joinToString("\n")
    if (cleanedM3u8Content.isBlank() || !cleanedM3u8Content.contains("#EXTM3U")) {
            throw Exception("Lỗi bước 2: Nội dung M3U8 sau khi lọc bị trống hoặc không hợp lệ.")
    }

    // --- BƯỚC 3: UPLOAD LÊN DPASTE.ORG VÀ TRẢ VỀ LINK RAW ---
    val postData = mapOf("content" to cleanedM3u8Content, "lexer" to "_text")
    val dpasteJsonResponse = app.post("https://dpaste.org/api/", data = postData).text
    
    val dpasteUrl = dpasteJsonResponse.trim().removeSurrounding("\"")
    val rawDpasteUrl = "$dpasteUrl/raw"

    if (!rawDpasteUrl.startsWith("http")) {
        throw Exception("Lỗi bước 3: Không thể tạo URL hợp lệ từ dpaste: $rawDpasteUrl")
    }
    
    // --- BƯỚC 4: TRẢ LINK VỀ CHO TRÌNH PHÁT ---
    callback.invoke(
        ExtractorLink(
            source = this.name,
            name = "${this.name} (Dpaste)",
            url = rawDpasteUrl,
            referer = mainUrl,
            quality = Qualities.Unknown.value,
            type = ExtractorLinkType.M3U8,
            headers = headers
        )
    )
    
    // Chỉ trả về true nếu mọi thứ thành công
    return true
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
