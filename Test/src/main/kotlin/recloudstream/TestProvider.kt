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
        TvType.Anime // Thêm hỗ trợ cho Anime
    )

    override val mainPage = mainPageOf(
        "$mainUrl/phim-moi/" to "Phim Mới Cập Nhật",
        "$mainUrl/phim-le/" to "Phim Lẻ Mới",
        "$mainUrl/phim-bo/" to "Phim Bộ Mới",
        "$mainUrl/phim-hoat-hinh/" to "Phim Hoạt Hình", // Thêm mục Anime
    )

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {
        val url = if (page == 1) request.data else "${request.data}page/$page/"
        val document = app.get(url).document
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

    override suspend fun load(url: String): LoadResponse? {
        val document = app.get(url).document

        val title = document.selectFirst("h1.title")?.text()?.trim() ?: return null
        val poster = document.selectFirst("div.poster img")?.attr("src")
        val plot = document.selectFirst("div.detail .content, div.tab.text p")?.text()?.trim()
        val year = document.selectFirst("div.name2 .year")?.text()?.removeSurrounding("(", ")")?.toIntOrNull()
        val tags = document.select("dt:contains(Thể loại) + dd a").map { it.text() }

        val episodes = document.select("ul.episodelistinfo li a").mapNotNull {
            val epName = it.text()
            if (epName.isNullOrBlank()) return@mapNotNull null
            val epHref = it.attr("href")
            Episode(epHref, epName)
        }

        if (episodes.isEmpty()) return null

        val isMovie = episodes.size == 1

        // Logic phân loại nội dung
        return when {
            // Trường hợp 1: Phim lẻ
            isMovie -> {
                newMovieLoadResponse(title, url, TvType.Movie, episodes.first().data) {
                    this.posterUrl = poster
                    this.plot = plot
                    this.year = year
                    this.tags = tags
                }
            }
            // Trường hợp 2: Anime (Kiểm tra thẻ "Phim Hoạt Hình")
            tags.any { it.contains("Hoạt Hình", ignoreCase = true) } -> {
                newAnimeLoadResponse(title, url, episodes) {
                    this.posterUrl = poster
                    this.plot = plot
                    this.year = year
                    this.tags = tags
                }
            }
            // Trường hợp 3: Mặc định là Phim bộ
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
