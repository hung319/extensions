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
        try {
            val episodeData = mapper.readValue(data, EpisodeData::class.java)
            val headers = mapOf(
                "Referer" to mainUrl,
                "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/108.0.0.0 Safari/537.36"
            )

            val masterM3u8Url = episodeData.linkM3u8
            val masterContent = app.get(masterM3u8Url, headers = headers).text
            val relativePlaylistUrl = masterContent.lines().lastOrNull { it.endsWith(".m3u8") }
                ?: throw Exception("No child playlist found")
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
                    var isV7Block = false
                    var blockEndIndex = i
                    for (j in (i + 1) until lines.size) {
                        val nextLine = lines[j]
                        if (nextLine.contains("/v7/") || nextLine.contains("convertv7")) { isV7Block = true }
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
                    } else if (!line.contains("/v7/") && !line.contains("convertv7")) {
                        val segmentBaseUrl = finalPlaylistUrl.substringBeforeLast("/") + "/"
                        cleanedLines.add(segmentBaseUrl + line)
                    }
                }
                i++
            }
            
            val cleanedM3u8Content = cleanedLines.joinToString("\n")
            if (cleanedM3u8Content.isBlank()) throw Exception("M3U8 content is empty after filtering")
            
            val postData = mapOf("content" to cleanedM3u8Content, "lexer" to "_text")
            val dpasteJsonResponse = app.post("https://dpaste.org/api/", data = postData).text
            val dpasteUrl = dpasteJsonResponse.trim().removeSurrounding("\"")
            val rawDpasteUrl = "$dpasteUrl/raw"

            if (!rawDpasteUrl.startsWith("http")) {
                throw Exception("Failed to create valid dpaste URL: $rawDpasteUrl")
            }
            
            callback.invoke(
                ExtractorLink(
                    source = this.name,
                    name = "${this.name} (Cleaned)",
                    url = rawDpasteUrl,
                    referer = mainUrl,
                    quality = Qualities.Unknown.value,
                    type = ExtractorLinkType.M3U8,
                    headers = headers
                )
            )
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
