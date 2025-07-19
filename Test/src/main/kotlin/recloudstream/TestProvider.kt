package recloudstream

import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.Actor
import com.lagradost.cloudstream3.ActorData
import com.lagradost.cloudstream3.HomePageList
import com.lagradost.cloudstream3.HomePageResponse
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MainPageRequest
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.mainPageOf
import com.lagradost.cloudstream3.newEpisode
import com.lagradost.cloudstream3.newMovieLoadResponse
import com.lagradost.cloudstream3.newMovieSearchResponse
import com.lagradost.cloudstream3.newTvSeriesLoadResponse
import com.lagradost.cloudstream3.newTvSeriesSearchResponse
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.apmap
import com.lagradost.cloudstream3.utils.loadExtractor
import org.jsoup.Jsoup

class NguonCProvider : MainAPI() {
    override var mainUrl = "https://phim.nguonc.com"
    override var name = "NguonC"
    override val hasMainPage = true
    override var lang = "vi"
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(
        TvType.Movie,
        TvType.TvSeries
    )

    companion object {
        const val API_URL = "https://phim.nguonc.com/api"
    }

    // ============================ Data Classes ============================
    data class ListApiResponse(
        @JsonProperty("items") val items: List<MediaItem>
    )

    data class FilmApiResponse(
        @JsonProperty("movie") val movie: MediaItem,
        @JsonProperty("episodes") val episodes: List<EpisodeServer>?
    )

    data class MediaItem(
        @JsonProperty("name") val name: String?,
        @JsonProperty("slug") val slug: String?,
        @JsonProperty("original_name") val originalName: String?,
        @JsonProperty("poster_url") val posterUrl: String?,
        @JsonProperty("total_episodes") val totalEpisodes: Int?,
        @JsonProperty("description") val description: String?,
        @JsonProperty("casts") val casts: String?,
    )

    data class EpisodeServer(
        @JsonProperty("server_name") val serverName: String?,
        @JsonProperty("items") val items: List<EpisodeData>
    )

    data class EpisodeData(
        @JsonProperty("name") val name: String?,
        @JsonProperty("embed") val embed: String?,
        @JsonProperty("m3u8") val m3u8: String?
    )

    // ============================ Helper Functions ============================
    private fun MediaItem.toSearchResult(): SearchResponse {
        val isTvSeries = (this.totalEpisodes ?: 1) > 1
        val url = "$mainUrl/phim/${this.slug}"

        return if (isTvSeries) {
            newTvSeriesSearchResponse(this.name ?: this.originalName ?: "", url) {
                posterUrl = this@toSearchResult.posterUrl
            }
        } else {
            newMovieSearchResponse(this.name ?: this.originalName ?: "", url) {
                posterUrl = this@toSearchResult.posterUrl
            }
        }
    }

    // ============================ Core Provider Functions ============================
    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse? {
        val sections = listOf(
            "Phim mới cập nhật" to "$API_URL/films/phim-moi-cap-nhat",
            "Phim lẻ" to "$API_URL/films/danh-sach/phim-le",
            "Phim bộ" to "$API_URL/films/danh-sach/phim-bo",
        )

        val homePageList = sections.apmap { (name, url) ->
            try {
                val response = app.get("$url?page=$page").parsed<ListApiResponse>()
                val items = response.items.mapNotNull { it.toSearchResult() }
                HomePageList(name, items)
            } catch (e: Exception) {
                null
            }
        }.filterNotNull()

        return HomePageResponse(homePageList)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val url = "$API_URL/films/search?keyword=$query"
        return try {
            app.get(url).parsed<ListApiResponse>().items.mapNotNull { it.toSearchResult() }
        } catch (e: Exception) {
            emptyList()
        }
    }

    override suspend fun load(url: String): LoadResponse? {
        val slug = url.substringAfterLast("/")
        val apiLink = "$API_URL/film/$slug"

        try {
            val res = app.get(apiLink).parsed<FilmApiResponse>()
            
            val movieInfo = res.movie
            val title = movieInfo.name ?: movieInfo.originalName ?: return null
            
            val poster = movieInfo.posterUrl
            val description = movieInfo.description?.let { Jsoup.parse(it).text() }
            val actors = movieInfo.casts?.split(",")?.map { ActorData(Actor(it.trim())) }

            val episodes = res.episodes?.flatMap { server ->
                server.items.map { episode ->
                    newEpisode(episode) {
                        this.name = "${server.serverName}: Tập ${episode.name}"
                        this.data = episode.m3u8 ?: episode.embed ?: ""
                    }
                }
            } ?: emptyList()

            return if ((movieInfo.totalEpisodes ?: 1) > 1) {
                newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
                    this.posterUrl = poster
                    this.plot = description
                    this.actors = actors
                }
            } else {
                newMovieLoadResponse(title, url, TvType.Movie, episodes.firstOrNull()?.data) {
                    this.posterUrl = poster
                    this.plot = description
                    this.actors = actors
                }
            }
        } catch (e: Exception) {
            return null
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        if (data.isBlank()) return false
        
        callback.invoke(
            ExtractorLink(
                this.name,
                this.name,
                data,
                mainUrl,
                Qualities.Unknown.value,
                isM3u8 = data.contains(".m3u8")
            )
        )
        return true
    }
}
