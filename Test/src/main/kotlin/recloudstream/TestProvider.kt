package recloudstream

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import org.jsoup.nodes.Element

class TvHayProvider : MainAPI() {
    override var mainUrl = "https://tvhay.fm"
    override var name = "TVHay"
    override val hasMainPage = true
    override var lang = "vi"
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(
        TvType.Movie,
        TvType.TvSeries,
        TvType.Anime
    )

    // BƯỚC 1: Thêm "Phim Đề Cử" và "Đã Hoàn Thành" vào trang chủ
    // Cả hai đều trỏ về trang chủ vì dữ liệu nằm ở đó
    override val mainPage = mainPageOf(
        "$mainUrl/" to "Phim Đề Cử",
        "$mainUrl/" to "Đã Hoàn Thành",
        "$mainUrl/phim-moi/" to "Phim Mới Cập Nhật",
        "$mainUrl/phim-le/" to "Phim Lẻ Mới",
        "$mainUrl/phim-bo/" to "Phim Bộ Mới",
        "$mainUrl/phim-hoat-hinh/" to "Phim Hoạt Hình",
    )

    // BƯỚC 3: Nâng cấp getMainPage để xử lý các yêu cầu khác nhau
    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {
        val document = app.get(request.data).document

        // Dùng when để chọn đúng selector cho từng mục
        val home = when (request.name) {
            "Phim Đề Cử" -> {
                // Các mục này không có phân trang, chỉ tải ở trang 1
                if (page > 1) return newHomePageResponse(request.name, emptyList())
                document.select("div#movie-recommend ul.phim-bo-moi > li").mapNotNull {
                    it.toSimpleSearchResult()
                }
            }
            "Đã Hoàn Thành" -> {
                if (page > 1) return newHomePageResponse(request.name, emptyList())
                document.select("div#movie-recommend ul.phim-bo-full > li").mapNotNull {
                    it.toSimpleSearchResult()
                }
            }
            else -> {
                // Logic cũ cho các trang có phân trang
                val url = if (page == 1) request.data else "${request.data}page/$page/"
                app.get(url).document.select("div#page-list ul.list-film > li").mapNotNull {
                    it.toSearchResult()
                }
            }
        }
        return newHomePageResponse(request.name, home)
    }

    // BƯỚC 2: Hàm phân tích mới cho các mục đề cử (cấu trúc đơn giản)
    private fun Element.toSimpleSearchResult(): SearchResponse? {
        val aTag = this.selectFirst("a") ?: return null
        val href = aTag.attr("href")
        if (href.isBlank()) return null
        val title = aTag.attr("title").removePrefix("Phim ").trim()
        val extraInfo = this.selectFirst("span")?.text()

        // Hầu hết trong danh sách này là phim bộ
        return newTvSeriesSearchResponse(title, href, TvType.TvSeries) {
            this.posterUrl = null // Không có poster trong danh sách này
            this.quality = getQualityFromString(extraInfo)
        }
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val href = this.selectFirst("a")?.attr("href") ?: return null
        val title = this.selectFirst(".name a")?.text()?.removePrefix("Xem phim ")?.trim() ?: return null
        val posterUrl = this.selectFirst("img")?.attr("data-original")
        val status = this.selectFirst(".status")?.text()

        return if (status?.contains("/") == true || status?.contains("Tập", true) == true) {
            newTvSeriesSearchResponse(title, href, TvType.TvSeries) {
                this.posterUrl = posterUrl
                this.quality = getQualityFromString(status)
            }
        } else {
            newMovieSearchResponse(title, href, TvType.Movie) {
                this.posterUrl = posterUrl
                this.quality = getQualityFromString(status)
            }
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val searchUrl = "$mainUrl/search/$query/"
        val document = app.get(searchUrl).document

        return document.select("div#page-list ul.list-film > li").mapNotNull {
            it.toSearchResult()
        }
    }

    private fun formatEpisodeName(name: String): String {
        return if (name.toIntOrNull() != null) "Tập $name" else name
    }

    override suspend fun load(url: String): LoadResponse? {
        val document = app.get(url).document

        val title = document.selectFirst("h1.title")?.text()?.trim() ?: return null
        val poster = document.selectFirst("div.poster img")?.attr("src")
        val plot = document.selectFirst("div.detail .content, div.tab.text p")?.text()?.trim()
        val year = document.selectFirst("div.name2 .year")?.text()?.removeSurrounding("(", ")")?.toIntOrNull()
        val tags = document.select("dt:contains(Thể loại) + dd a").map { it.text() }
        
        val watchPageUrl = document.selectFirst("a.btn-watch")?.attr("href")

        val episodes = if (watchPageUrl != null && watchPageUrl.startsWith("http")) {
            val watchPageDocument = app.get(watchPageUrl).document
            watchPageDocument.select("div#servers ul.episodelist li a").mapNotNull {
                var epName = it.text()
                if (epName.isNullOrBlank()) return@mapNotNull null
                epName = formatEpisodeName(epName)
                val epHref = it.attr("href")
                Episode(epHref, epName)
            }
        } else {
            document.select("ul.episodelistinfo li a").mapNotNull {
                var epName = it.text()
                if (epName.isNullOrBlank()) return@mapNotNull null
                epName = formatEpisodeName(epName)
                val epHref = it.attr("href")
                Episode(epHref, epName)
            }
        }

        if (episodes.isEmpty()) {
            return newMovieLoadResponse(title, url, TvType.Movie, url) {
                this.posterUrl = poster
                this.plot = plot
                this.year = year
                this.tags = tags
                this.comingSoon = true
            }
        }

        val isMovie = episodes.size == 1 && (
                episodes.first().name?.contains("Full", true) == true ||
                episodes.first().name?.contains("End", true) == true ||
                episodes.first().name?.contains("Thuyết Minh", true) == true
        )

        return when {
            isMovie -> {
                newMovieLoadResponse(title, url, TvType.Movie, episodes.first().data) {
                    this.posterUrl = poster
                    this.plot = plot
                    this.year = year
                    this.tags = tags
                }
            }
            tags.any { it.contains("Hoạt Hình", ignoreCase = true) } -> {
                newAnimeLoadResponse(title, url, TvType.Anime) {
                    this.posterUrl = poster
                    this.plot = plot
                    this.year = year
                    this.tags = tags
                    this.episodes = mutableMapOf(DubStatus.Dubbed to episodes)
                }
            }
            else -> {
                newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
                    this.posterUrl = poster
                    this.plot = plot
                    this.year = year
                    this.tags = tags
                }
            }
        }
    }
    
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        // Tạm thời vô hiệu hóa, sẽ hoàn thiện sau.
        return false
    }
}
