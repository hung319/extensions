package recloudstream

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.fasterxml.jackson.annotation.*
import java.net.*
import java.text.* // Import cho SimpleDateFormat
import java.util.* // Import cho Locale, TimeZone

class Phim4kProvider : MainAPI() {
    override var mainUrl = "https://stremio.phim4k.xyz"
    override var name = "Phim4K Stremio"
    override val hasMainPage = true
    override val hasQuickSearch = true
    override var lang = "vi"
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)

    private val configStr = "eyJ1c2VybmFtZSI6Imh1bmciLCJwYXNzd29yZCI6Imh1bmciLCJ0cyI6MTc2NDcyNTIxNDA1NX0"
    
    // User-Agent LG TV (Theo yêu cầu)
    private val customUserAgent = "Mozilla/5.0 (Web0S; Linux/SmartTV) AppleWebKit/538.2 (KHTML, like Gecko) Large Screen Safari/538.2 LG Browser/7.00.00(LGE; WEBOS2; 04.06.25; 1; DTV_W15U); webOS.TV-2015; LG NetCast.TV-2013 Compatible (LGE, WEBOS2, wireless)"

    private val headers = mapOf(
        "User-Agent" to customUserAgent,
        "Referer" to "https://web.stremio.com/"
    )

    // --- Data Models ---
    data class StremioCatalogResponse(
        @JsonProperty("metas") val metas: List<StremioMeta>?
    )

    data class StremioMetaSingleResponse(
        @JsonProperty("meta") val meta: StremioMeta
    )

    data class StremioMeta(
        @JsonProperty("id") val id: String,
        @JsonProperty("type") val type: String,
        @JsonProperty("name") val name: String,
        @JsonProperty("poster") val poster: String?,
        @JsonProperty("background") val background: String?,
        @JsonProperty("description") val description: String?,
        @JsonProperty("genres") val genres: List<String>?,
        @JsonProperty("cast") val cast: List<String>?,
        @JsonProperty("director") val director: List<String>?,
        @JsonProperty("videos") val videos: List<StremioVideo>?
    )

    data class StremioVideo(
        @JsonProperty("id") val id: String,
        @JsonProperty("title") val title: String,
        @JsonProperty("season") val season: Int,
        @JsonProperty("episode") val episode: Int,
        @JsonProperty("released") val released: String?
    )
    
    data class StremioStreamResponse(
        @JsonProperty("streams") val streams: List<StremioStream>?
    )

    data class StremioStream(
        @JsonProperty("url") val url: String?,
        @JsonProperty("title") val title: String?
    )

    private fun String.encodeUri(): String = URLEncoder.encode(this, "utf-8")

    // Helper: Parse chuỗi ngày ISO8601 sang Long timestamp
    private fun String.toLongDate(): Long? {
        return try {
            // Format mẫu: 2025-12-25T11:40:31.508Z
            val format = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US)
            format.timeZone = TimeZone.getTimeZone("UTC")
            format.parse(this)?.time
        } catch (e: Exception) {
            null
        }
    }

    // --- Core Logic ---

    private fun List<StremioMeta>.toSearchResponseList(): List<SearchResponse> {
        return this.mapNotNull { meta ->
            val isSeries = meta.type == "series"
            val fullUrl = "$mainUrl/$configStr/meta/${meta.type}/${meta.id.encodeUri()}.json"
            
            if (isSeries) {
                newTvSeriesSearchResponse(meta.name, fullUrl, TvType.TvSeries) {
                    this.posterUrl = meta.poster
                }
            } else {
                newMovieSearchResponse(meta.name, fullUrl, TvType.Movie) {
                    this.posterUrl = meta.poster
                }
            }
        }
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        if (page > 1) return newHomePageResponse(emptyList())
        
        val movieUrl = "$mainUrl/$configStr/catalog/movie/phim4k_movies.json"
        val movieResponse = app.get(movieUrl, headers = headers).parsedSafe<StremioCatalogResponse>()
        val movieList = movieResponse?.metas?.toSearchResponseList() ?: emptyList()

        val seriesUrl = "$mainUrl/$configStr/catalog/series/phim4k_series.json"
        val seriesResponse = app.get(seriesUrl, headers = headers).parsedSafe<StremioCatalogResponse>()
        val seriesList = seriesResponse?.metas?.toSearchResponseList() ?: emptyList()
        
        return newHomePageResponse(
            listOf(
                HomePageList("Phim Lẻ Mới Cập Nhật", movieList, isHorizontalImages = false),
                HomePageList("Phim Bộ Mới Cập Nhật", seriesList, isHorizontalImages = false)
            )
        )
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val encodedQuery = query.encodeUri()
        val url = "$mainUrl/$configStr/catalog/movie/phim4k_movies/search=$encodedQuery.json"
        val response = app.get(url, headers = headers).parsedSafe<StremioCatalogResponse>()
        return response?.metas?.toSearchResponseList() ?: emptyList()
    }

    override suspend fun load(url: String): LoadResponse {
        val response = app.get(url, headers = headers).parsedSafe<StremioMetaSingleResponse>()?.meta 
            ?: throw ErrorLoadingException("Không thể tải chi tiết phim")

        val isSeries = response.type == "series"
        val type = if (isSeries) TvType.TvSeries else TvType.Movie

        val loadResponse = if (isSeries) {
            val episodes = response.videos?.map { video ->
                newEpisode(video.id) {
                    this.name = video.title
                    this.season = video.season
                    this.episode = video.episode
                    // Fix lỗi gán String vào Long
                    this.date = video.released?.toLongDate()
                    this.posterUrl = response.poster
                }
            } ?: emptyList()

            newTvSeriesLoadResponse(response.name, url, type, episodes) { }
        } else {
            newMovieLoadResponse(response.name, url, type, url) { }
        }

        return loadResponse.apply {
            this.posterUrl = response.poster
            this.backgroundPosterUrl = response.background
            this.plot = response.description
            this.tags = response.genres
            
            this.actors = response.cast?.map { name ->
                ActorData(Actor(name, null))
            }
            
            response.director?.forEach { directorName ->
                 val directorActor = ActorData(Actor(directorName, null), roleString = "Director")
                 this.actors = (this.actors ?: emptyList()) + directorActor
            }
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val rawId = if (data.startsWith("http")) {
            data.substringAfterLast("/").substringBefore(".json")
        } else {
            data
        }

        val type = if (rawId.contains("movie")) "movie" else "series"
        val encodedId = rawId.encodeUri()
        val streamUrl = "$mainUrl/$configStr/stream/$type/$encodedId.json"

        val response = app.get(streamUrl, headers = headers).parsedSafe<StremioStreamResponse>()

        response?.streams?.forEach { stream ->
            val rawUrl = stream.url ?: return@forEach
            val titleRaw = stream.title ?: "Default"
            val linkType = if (rawUrl.contains(".m3u8")) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO

            callback.invoke(
                newExtractorLink(
                    source = this.name,
                    name = "Phim4K: $titleRaw",
                    url = rawUrl,
                    type = linkType
                ) {
                    this.headers = this@Phim4kProvider.headers
                    this.referer = "https://web.stremio.com/"
                    this.quality = getQualityFromName(titleRaw)
                }
            )
        }

        return true
    }
}
