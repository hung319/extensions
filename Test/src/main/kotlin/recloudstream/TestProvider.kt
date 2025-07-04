// Tên file: NguonCProvider.kt
// Phiên bản đã cập nhật logic lấy link phim mới thông qua API của trang embed.

package com.lagradost.cloudstream3.movieprovider

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.fasterxml.jackson.annotation.JsonProperty
import org.jsoup.Jsoup
import java.net.URI

// --- CÁC LỚP DỮ LIỆU (DATA CLASS) ---
data class NguonCItem(
    @JsonProperty("name") val name: String,
    @JsonProperty("slug") val slug: String,
    @JsonProperty("poster_url") val poster_url: String? = null,
    @JsonProperty("current_episode") val current_episode: String? = null
)

data class NguonCMain(
    @JsonProperty("items") val items: List<NguonCItem>
)

data class NguonCEpisodeData(
    @JsonProperty("name") val name: String,
    @JsonProperty("slug") val slug: String,
    @JsonProperty("m3u8") val m3u8: String? = null,
    // FIX: Thêm trường 'embed' để lấy link
    @JsonProperty("embed") val embed: String? = null 
)

data class NguonCServer(
    @JsonProperty("server_name") val server_name: String,
    @JsonProperty("items") val items: List<NguonCEpisodeData>
)

data class NguonCCategoryItem(
    @JsonProperty("name") val name: String
)

data class NguonCCategoryGroupInfo(
    @JsonProperty("name") val name: String
)

data class NguonCCategoryGroup(
    @JsonProperty("group") val group: NguonCCategoryGroupInfo,
    @JsonProperty("list") val list: List<NguonCCategoryItem>
)

data class NguonCDetailMovie(
    @JsonProperty("name") val name: String,
    @JsonProperty("description") val description: String?,
    @JsonProperty("poster_url") val poster_url: String?,
    @JsonProperty("thumb_url") val thumb_url: String?,
    @JsonProperty("casts") val casts: String?,
    @JsonProperty("director") val director: String?,
    @JsonProperty("category") val category: Map<String, NguonCCategoryGroup>?,
    @JsonProperty("current_episode") val current_episode: String? = null
)

data class NguonCDetail(
    @JsonProperty("movie") val movie: NguonCDetailMovie,
    @JsonProperty("episodes") val episodes: List<NguonCServer>? 
)

// FIX: Data class mới để parse phản hồi từ API của trang embed
data class StreamApiResponse(
    @JsonProperty("streamUrl") val streamUrl: String
)


// --- LỚP PLUGIN CHÍNH ---

class NguonCProvider : MainAPI() {
    override var mainUrl = "https://phim.nguonc.com"
    override var name = "Nguồn C"
    override var lang = "vi"
    override val hasMainPage = true
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)

    // ... các hàm toSearchResponse, mainPage, getMainPage, search giữ nguyên ...
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
        val response = app.get(url).parsed<NguonCDetail>()
        val movie = response.movie
        
        val title = movie.name
        val poster = movie.poster_url ?: movie.thumb_url
        val plot = movie.description?.let { Jsoup.parse(it).text() }

        var year: Int? = null
        var tags: List<String>? = null
        
        movie.category?.values?.forEach { group ->
            when (group.group.name) {
                "Năm" -> year = group.list.firstOrNull()?.name?.toIntOrNull()
                "Thể loại" -> tags = group.list.map { it.name }
            }
        }
        
        val showStatus = if (movie.current_episode?.contains("Hoàn tất", ignoreCase = true) == true || movie.current_episode?.contains("FULL", ignoreCase = true) == true) {
            ShowStatus.Completed
        } else {
            ShowStatus.Ongoing
        }

        val actors = movie.casts?.split(",")?.map { ActorData(Actor(it.trim())) }

        val episodes = response.episodes?.flatMap { server ->
            server.items.mapNotNull { ep ->
                // FIX: Ưu tiên lấy link embed, nếu không có thì mới lấy link m3u8 trực tiếp (dự phòng)
                val episodeData = ep.embed ?: ep.m3u8 ?: return@mapNotNull null
                Episode(
                    data = episodeData,
                    name = if (response.episodes.size > 1) "${server.server_name} - Tập ${ep.name}" else "Tập ${ep.name}"
                )
            }
        } ?: listOf()

        return if (episodes.size > 1) {
            TvSeriesLoadResponse(
                name = title, url = url, apiName = this.name, type = TvType.TvSeries,
                episodes = episodes, posterUrl = poster, year = year, plot = plot,
                tags = tags, showStatus = showStatus, actors = actors
            )
        } else {
            MovieLoadResponse(
                name = title, url = url, apiName = this.name, type = TvType.Movie,
                dataUrl = episodes.firstOrNull()?.data ?: "", posterUrl = poster, year = year,
                plot = plot, tags = tags, actors = actors
            )
        }
    }

    // FIX: VIẾT LẠI HOÀN TOÀN HÀM loadLinks ĐỂ XỬ LÝ LOGIC MỚI
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {

        // Nếu data là link m3u8 trực tiếp (trường hợp dự phòng), phát luôn
        if (data.contains(".m3u8")) {
            callback.invoke(
                ExtractorLink(this.name, "Nguồn C (Dự phòng)", data, mainUrl, Qualities.Unknown.value, type = ExtractorLinkType.M3U8)
            )
            return true
        }

        // Xử lý logic mới với embed URL
        val embedUrl = data
        
        // Tạo API URL từ embed URL
        val apiUrl = embedUrl.replace("?hash=", "?api=stream&hash=")
        
        // Lấy tên miền gốc của trang embed, ví dụ: https://embed10.streamc.xyz
        val baseUrl = URI(embedUrl).let { "${it.scheme}://${it.host}" }

        // Tạo headers, trong đó Referer là quan trọng nhất
        val headers = mapOf("Referer" to embedUrl)
        
        // Gọi API và parse kết quả
        val response = app.get(apiUrl, headers = headers).parsed<StreamApiResponse>()
        
        // Tạo link m3u8 cuối cùng
        val finalM3u8Url = baseUrl + response.streamUrl
        
        callback.invoke(
            ExtractorLink(
                source = this.name,
                name = "Nguồn C",
                url = finalM3u8Url,
                // Referer cho trình phát video là trang embed gốc
                referer = embedUrl,
                quality = Qualities.Unknown.value,
                type = ExtractorLinkType.M3U8 
            )
        )
        
        return true
    }
}
