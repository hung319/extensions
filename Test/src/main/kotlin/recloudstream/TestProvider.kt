// Tên file: NguonCProvider_JsonInspector.kt
// Phiên bản đặc biệt: In toàn bộ JSON trả về từ API ra phần mô tả phim.

package com.lagradost.cloudstream3.movieprovider

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.SerializationFeature
import org.jsoup.Jsoup
import java.net.URI
import androidx.annotation.Keep

// --- CÁC LỚP DỮ LIỆU (DATA CLASS) ---
// (Giữ nguyên như phiên bản cuối cùng)
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

    // Các hàm khác giữ nguyên
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

    // HÀM LOAD ĐÃ ĐƯỢC CHỈNH SỬA ĐỂ IN RA JSON
    override suspend fun load(url: String): LoadResponse {
        
        var jsonLog = ""
        var originalPlot = "Không thể tải thông tin phim."
        var episodes = listOf<Episode>()
        var finalType: TvType = TvType.Movie // Giá trị mặc định
        var title = ""
        var poster: String? = null
        var year: Int? = null
        var tags: List<String>? = null
        var showStatus: ShowStatus? = null
        var actors: List<ActorData>? = null
        
        try {
            // Lấy dữ liệu thô và parse
            val rawResponse = app.get(url).text
            val response = app.parseJson<NguonCDetail>(rawResponse)
            
            // Định dạng lại JSON để dễ đọc
            val mapper = ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT)
            val formattedJson = mapper.writeValueAsString(response)
            jsonLog = "\n\n====================\n" +
                      "--- RAW JSON RESPONSE ---\n" +
                      "====================\n" +
                      formattedJson

            // Nếu parse thành công, tiếp tục xử lý như bình thường
            val movie = response.movie ?: throw Exception("Đối tượng 'movie' là null.")
            
            title = movie.name
            poster = movie.poster_url ?: movie.thumb_url
            originalPlot = movie.description?.let { Jsoup.parse(it).text() } ?: "Không có mô tả."
            
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
            showStatus = if (movie.current_episode?.contains("Hoàn tất", ignoreCase = true) == true || movie.current_episode?.contains("FULL", ignoreCase = true) == true) ShowStatus.Completed else ShowStatus.Ongoing
            actors = movie.casts?.split(",")?.map { ActorData(Actor(it.trim())) }

            val tempEpisodes = mutableListOf<Episode>()
            val episodeServerList = response.episodes ?: listOf()
            for (server in episodeServerList) {
                val itemList = server.items ?: continue
                for (ep in itemList) {
                    val episodeData = ep.embed ?: ep.m3u8
                    if (episodeData != null) {
                        tempEpisodes.add(Episode(data = episodeData, name = "Tập ${ep.name}"))
                    }
                }
            }
            episodes = tempEpisodes
            
            val totalEpisodes = movie.total_episodes ?: episodes.size
            val baseType = if (isDefinitelySeries || totalEpisodes > 1 || (totalEpisodes == 1 && showStatus == ShowStatus.Ongoing)) TvType.TvSeries else TvType.Movie
            finalType = if (isAnime) TvType.Anime else baseType

        } catch (e: Exception) {
            // Nếu có lỗi, cũng in lỗi ra
            jsonLog = "\n\n====================\n" +
                      "--- EXCEPTION LOG ---\n" +
                      "====================\n" +
                      "URL: $url\n" +
                      "Lỗi: ${e.message}\n" +
                      "Stacktrace: ${e.stackTraceToString().take(500)}"
        }

        val finalPlot = originalPlot + jsonLog

        // Luôn trả về TvSeriesLoadResponse để đảm bảo hiển thị được
        return TvSeriesLoadResponse(
            name = title.ifBlank { url },
            url = url,
            apiName = this.name,
            type = finalType,
            episodes = episodes,
            posterUrl = poster,
            year = year,
            plot = finalPlot,
            tags = tags,
            showStatus = showStatus,
            actors = actors
        )
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
