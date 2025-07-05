// Tên file: NguonCProvider.kt
// Phiên bản gỡ lỗi đặc biệt: In toàn bộ thông tin xử lý ra phần mô tả phim.

package com.lagradost.cloudstream3.movieprovider

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.fasterxml.jackson.annotation.JsonProperty
import org.jsoup.Jsoup
import java.net.URI
import androidx.annotation.Keep

// --- CÁC LỚP DỮ LIỆU (DATA CLASS) ---
@Keep
data class NguonCItem(
    @JsonProperty("name") val name: String,
    @JsonProperty("slug") val slug: String,
    @JsonProperty("poster_url") val poster_url: String? = null,
    @JsonProperty("current_episode") val current_episode: String? = null
)

@Keep
data class NguonCMain(
    @JsonProperty("items") val items: List<NguonCItem>
)

@Keep
data class NguonCEpisodeData(
    @JsonProperty("name") val name: String,
    @JsonProperty("slug") val slug: String,
    @JsonProperty("m3u8") val m3u8: String? = null,
    @JsonProperty("embed") val embed: String? = null 
)

@Keep
data class NguonCServer(
    @JsonProperty("server_name") val server_name: String,
    @JsonProperty("items") val items: List<NguonCEpisodeData>? 
)

@Keep
data class NguonCCategoryItem(
    @JsonProperty("name") val name: String
)

@Keep
data class NguonCCategoryGroupInfo(
    @JsonProperty("name") val name: String
)

@Keep
data class NguonCCategoryGroup(
    @JsonProperty("group") val group: NguonCCategoryGroupInfo,
    @JsonProperty("list") val list: List<NguonCCategoryItem>
)

@Keep
data class NguonCDetailMovie(
    @JsonProperty("name") val name: String,
    @JsonProperty("description") val description: String?,
    @JsonProperty("poster_url") val poster_url: String?,
    @JsonProperty("thumb_url") val thumb_url: String?,
    @JsonProperty("casts") val casts: String?,
    @JsonProperty("director") val director: String?,
    @JsonProperty("category") val category: Map<String, NguonCCategoryGroup>?,
    @JsonProperty("current_episode") val current_episode: String? = null,
    @JsonProperty("total_episodes") val total_episodes: Int? = null 
)

@Keep
data class NguonCDetail(
    @JsonProperty("movie") val movie: NguonCDetailMovie?, 
    @JsonProperty("episodes") val episodes: List<NguonCServer>? 
)

@Keep
data class StreamApiResponse(
    @JsonProperty("streamUrl") val streamUrl: String
)


// --- LỚP PLUGIN CHÍNH ---

class NguonCProvider : MainAPI() {
    override var mainUrl = "https://phim.nguonc.com"
    override var name = "Nguồn C"
    override var lang = "vi"
    override val hasMainPage = true
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries, TvType.Anime)

    private fun toSearchResponse(item: NguonCItem): SearchResponse {
        val url = "$mainUrl/api/film/${item.slug}"
        val poster = item.poster_url
        val isMovie = item.current_episode.isNullOrBlank() || 
                      !item.current_episode.contains("Tập", ignoreCase = true) ||
                      item.name.contains("phim lẻ", ignoreCase = true)
        
        return if (isMovie) {
            MovieSearchResponse(item.name, url, this.name, TvType.Movie, poster, null)
        } else {
            TvSeriesSearchResponse(item.name, url, this.name, TvType.TvSeries, poster, null, null)
        }
    }
    
    override val mainPage = mainPageOf(
        "/api/films/phim-moi-cap-nhat?page=" to "Phim Mới Cập Nhật",
        "/api/films/danh-sach/phim-dang-chieu?page=" to "Phim Đang Chiếu",
        "/api/films/danh-sach/phim-le?page=" to "Phim Lẻ",
        "/api/films/danh-sach/phim-bo?page=" to "Phim Bộ",
        "/api/films/the-loai/hoat-hinh?page=" to "Phim Hoạt Hình",
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = "$mainUrl${request.data}$page"
        val response = app.get(url).parsed<NguonCMain>() 
        val home = response.items.map { toSearchResponse(it) }
        return newHomePageResponse(request.name, home)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val url = "$mainUrl/api/films/search?keyword=$query"
        return app.get(url).parsedSafe<NguonCMain>()?.items?.map { toSearchResponse(it) } ?: listOf()
    }

    override suspend fun load(url: String): LoadResponse {
        // FIX: Tạo một String Builder để ghi log
        val debugLog = StringBuilder()
        debugLog.append("\n\n====================\n")
        debugLog.append("--- DEBUG LOG START ---\n")
        debugLog.append("====================\n")
        debugLog.append("Timestamp: ${System.currentTimeMillis()}\n")
        debugLog.append("URL: $url\n\n")

        val response = try {
            app.get(url).parsedSafe<NguonCDetail>()
        } catch (e: Exception) {
            debugLog.append("!!! LỖI NGHIÊM TRỌNG KHI TẢI/PARSE !!!\n")
            debugLog.append("Lỗi: ${e.message}\n")
            return TvSeriesLoadResponse(url, url, this.name, TvType.TvSeries, emptyList(), null, null, debugLog.toString())
        }

        if (response == null) {
            debugLog.append("!!! LỖI: Phản hồi từ API là null sau khi dùng parsedSafe(). Link có thể hỏng hoặc không phải JSON.\n")
            return TvSeriesLoadResponse(url, url, this.name, TvType.TvSeries, emptyList(), null, null, debugLog.toString())
        }
        debugLog.append("-> Parse JSON ban đầu thành công.\n")
        
        val movie = response.movie
        if (movie == null) {
            debugLog.append("!!! LỖI: Đối tượng 'movie' trong JSON là null.\n")
            return TvSeriesLoadResponse(url, url, this.name, TvType.TvSeries, emptyList(), null, null, debugLog.toString())
        }
        debugLog.append("-> Lấy đối tượng 'movie' thành công: ${movie.name}\n")
        
        val title = movie.name
        val poster = movie.poster_url ?: movie.thumb_url
        val originalPlot = movie.description?.let { Jsoup.parse(it).text() } ?: "Không có mô tả."

        // ... (phần xử lý tags, year, status... giữ nguyên) ...
        var year: Int? = null
        var tags: List<String>? = null
        var isDefinitelySeries = false
        var isAnime = false
        movie.category?.values?.forEach { group ->
            when (group.group.name) {
                "Năm" -> year = group.list.firstOrNull()?.name?.toIntOrNull()
                "Thể loại" -> {
                    tags = group.list.map { it.name }
                    if (tags?.any { it.contains("Hoạt Hình", ignoreCase = true) } == true) isAnime = true
                }
                "Định dạng" -> {
                    if (group.list.any { it.name.contains("Phim bộ", ignoreCase = true) }) isDefinitelySeries = true
                }
            }
        }
        val showStatus = if (movie.current_episode?.contains("Hoàn tất", ignoreCase = true) == true || movie.current_episode?.contains("FULL", ignoreCase = true) == true) ShowStatus.Completed else ShowStatus.Ongoing
        val actors = movie.casts?.split(",")?.map { ActorData(Actor(it.trim())) }
        
        val episodeServerList = response.episodes ?: listOf()
        debugLog.append("-> Số lượng server tìm thấy: ${episodeServerList.size}\n")
        
        val episodes = mutableListOf<Episode>()
        for (server in episodeServerList) {
            val itemList = server.items ?: listOf()
            debugLog.append("-> Đang xử lý server '${server.server_name}': Tìm thấy ${itemList.size} tập.\n")
            for (ep in itemList) {
                val episodeData = ep.embed ?: ep.m3u8
                if (episodeData != null) {
                    episodes.add(Episode(data = episodeData, name = "Tập ${ep.name}"))
                }
            }
        }
        debugLog.append("-> TỔNG SỐ TẬP PHIM CUỐI CÙNG: ${episodes.size}\n")

        val totalEpisodes = movie.total_episodes ?: episodes.size
        val finalType = if (isAnime) TvType.Anime else if (isDefinitelySeries || totalEpisodes > 1 || (totalEpisodes == 1 && showStatus == ShowStatus.Ongoing)) TvType.TvSeries else TvType.Movie
        debugLog.append("-> Loại phim xác định: $finalType\n")
        debugLog.append("===================\n")
        debugLog.append("--- DEBUG LOG END ---\n")
        debugLog.append("===================\n")

        // Nối log vào mô tả phim
        val finalPlot = "$originalPlot\n${debugLog.toString()}"
        
        return if (finalType == TvType.TvSeries || finalType == TvType.Anime) {
             TvSeriesLoadResponse(
                name = title, url = url, apiName = this.name, type = finalType,
                episodes = episodes, posterUrl = poster, year = year, plot = finalPlot,
                tags = tags, showStatus = showStatus, actors = actors
            )
        } else {
            MovieLoadResponse(
                name = title, url = url, apiName = this.name, type = finalType,
                dataUrl = episodes.firstOrNull()?.data ?: "", posterUrl = poster, year = year,
                plot = finalPlot, tags = tags, actors = actors
            )
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        if (data.contains(".m3u8")) {
            callback.invoke(
                ExtractorLink(this.name, "Nguồn C (Dự phòng)", data, mainUrl, Qualities.Unknown.value, type = ExtractorLinkType.M3U8)
            )
            return true
        }

        val embedUrl = data
        val apiUrl = embedUrl.replace("?hash=", "?api=stream&hash=")
        val baseUrl = URI(embedUrl).let { "${it.scheme}://${it.host}" }
        val headers = mapOf("Referer" to embedUrl)
        
        val response = app.get(apiUrl, headers = headers).parsedSafe<StreamApiResponse>()
            ?: throw RuntimeException("Không thể lấy streamUrl từ: $apiUrl")

        val finalM3u8Url = if(response.streamUrl.startsWith("http")) {
            response.streamUrl
        } else {
            baseUrl + response.streamUrl
        }
        
        callback.invoke(
            ExtractorLink(
                source = this.name, name = "Nguồn C", url = finalM3u8Url,
                referer = embedUrl, quality = Qualities.Unknown.value, type = ExtractorLinkType.M3U8 
            )
        )
        
        return true
    }
}
