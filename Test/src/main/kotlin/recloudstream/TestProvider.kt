// Tên file: NguonCProvider.kt
// Phiên bản hoàn chỉnh, cập nhật lúc 21:24, ngày 04/07/2025

package com.lagradost.cloudstream3.movieprovider

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.fasterxml.jackson.annotation.JsonProperty
import org.jsoup.Jsoup

// --- ĐỊNH NGHĨA CẤU TRÚC DỮ LIỆU JSON CHÍNH XÁC ---

// Dành cho các mục trong danh sách (trang chủ, tìm kiếm)
data class NguonCItem(
    @JsonProperty("name") val name: String,
    @JsonProperty("slug") val slug: String,
    @JsonProperty("poster_url") val poster_url: String? = null,
    @JsonProperty("current_episode") val current_episode: String? = null
)

// Cấu trúc JSON gốc cho API trang chủ và tìm kiếm
data class NguonCMain(
    @JsonProperty("items") val items: List<NguonCItem>
)

// Dành cho API chi tiết phim
data class NguonCEpisodeData(
    @JsonProperty("name") val name: String,
    @JsonProperty("slug") val slug: String,
    @JsonProperty("m3u8") val m3u8: String? = null
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
    @JsonProperty("category") val category: Map<String, NguonCCategoryGroup>?
)

data class NguonCDetail(
    @JsonProperty("movie") val movie: NguonCDetailMovie,
    @JsonProperty("episodes") val episodes: List<NguonCServer>
)


// --- LỚP PLUGIN CHÍNH ---

class NguonCProvider : MainAPI() {
    override var mainUrl = "https://phim.nguonc.com"
    override var name = "Nguồn C"
    override var lang = "vi"
    override val hasMainPage = true
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)

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
        
        // Làm sạch HTML từ mô tả
        val plot = movie.description?.let { Jsoup.parse(it).text() }

        // Trích xuất dữ liệu từ đối tượng category
        var year: Int? = null
        var tags: List<String>? = null
        
        movie.category?.values?.forEach { group ->
            when (group.group.name) {
                "Năm" -> year = group.list.firstOrNull()?.name?.toIntOrNull()
                "Thể loại" -> tags = group.list.map { it.name }
            }
        }

        val actors = movie.casts?.split(",")?.map { ActorData(Actor(it.trim())) }
        val director = movie.director?.let { listOf(it) }

        val episodes = response.episodes.flatMap { server ->
            server.items.mapNotNull { ep ->
                val episodeUrl = ep.m3u8 ?: return@mapNotNull null
                Episode(
                    data = episodeUrl,
                    name = "Tập ${ep.name}",
                    displayName = if (response.episodes.size > 1) "${server.server_name} - Tập ${ep.name}" else "Tập ${ep.name}"
                )
            }
        }.distinctBy { it.data }

        return if (episodes.size > 1) {
            TvSeriesLoadResponse(
                title, url, this.name, TvType.TvSeries, episodes,
                poster, year, plot, tags, actors = actors, directors = director
            )
        } else {
            MovieLoadResponse(
                title, url, this.name, TvType.Movie, episodes.firstOrNull()?.data,
                poster, year, plot, tags, actors = actors, directors = director
            )
        }
    }

    override suspend fun loadLinks(
        data: String, // data là link .m3u8
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        callback.invoke(
            ExtractorLink(
                source = this.name,
                name = "Nguồn C",
                url = data,
                referer = "$mainUrl/",
                quality = Qualities.Unknown.value,
                // Sử dụng ExtractorLinkType.M3U8 theo yêu cầu mới nhất
                type = ExtractorLinkType.M3U8 
            )
        )
        return true
    }
}
