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
        TvType.TvSeries
    )

    override val mainPage = mainPageOf(
        "$mainUrl/phim-le/" to "Phim Lẻ Mới",
        "$mainUrl/phim-bo/" to "Phim Bộ Mới",
        "$mainUrl/phim-moi/" to "Phim Mới Cập Nhật",
    )

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {
        val url = if (page == 1) request.data else "${request.data}page/$page/"
        val document = app.get(url).document
        // Sửa selector tại đây để khớp với cấu trúc HTML mới
        val home = document.select("div#page-list ul.list-film > li").mapNotNull {
            it.toSearchResult()
        }
        return newHomePageResponse(request.name, home)
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val href = this.selectFirst("a")?.attr("href") ?: return null
        val title = this.selectFirst(".name a")?.text() ?: return null
        val posterUrl = this.selectFirst("img")?.attr("data-original")
        val status = this.selectFirst(".status")?.text()

        return if (status?.contains("/") == true || status?.contains("Tập") == true) {
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

    override suspend fun load(url: String): LoadResponse? {
        val document = app.get(url).document

        val title = document.selectFirst("h1.title")?.text()?.trim() ?: return null
        val poster = document.selectFirst("div.poster img")?.attr("src")
        val plot = document.selectFirst("div.tab.text p")?.text()?.trim()
        val year = document.selectFirst("div.name2 .year")?.text()?.removeSurrounding("(", ")")?.toIntOrNull()
        val tags = document.select("dl.col1 dd a[href*=phim-]").map { it.text() }

        // Lấy danh sách tập phim
        val episodes = document.select("ul.episodelist li a").mapNotNull {
            val epName = it.text()
            // Bỏ qua các thẻ không phải là tập phim thực sự (ví dụ: link trailer)
            if (epName.isNullOrBlank()) return@mapNotNull null
            val epHref = it.attr("href")
            Episode(epHref, epName)
        }

        // Kiểm tra là phim lẻ hay phim bộ
        return if (document.selectFirst("ul.episodelistinfo") != null) {
            // Đây là trang phim lẻ (chỉ có list server, không có list tập)
            newMovieLoadResponse(title, url, TvType.Movie, url) {
                this.posterUrl = poster
                this.plot = plot
                this.year = year
                this.tags = tags
            }
        } else {
             // Đây là trang phim bộ
            newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
                this.posterUrl = poster
                this.plot = plot
                this.year = year
                this.tags = tags
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
