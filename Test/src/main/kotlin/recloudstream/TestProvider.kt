// Bạn có thể cần thay đổi package này cho phù hợp với cấu trúc dự án của mình
package com.lagradost.cloudstream3.plugins.vi

// Thêm thư viện Jsoup để phân tích cú pháp HTML
import org.jsoup.nodes.Element

// Import các lớp cần thiết từ API CloudStream
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*

// Định nghĩa lớp chính cho plugin
class Bluphim3Provider : MainAPI() {
    // Ghi đè các thuộc tính cơ bản của plugin
    override var mainUrl = "https://bluphim3.com"
    override var name = "Bluphim3"
    override val hasMainPage = true
    override var lang = "vi"
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(
        TvType.Movie,
        TvType.TvSeries,
        TvType.Anime
    )

    // Hàm lấy danh sách phim cho trang chính
    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val document = app.get(mainUrl).document
        val homePageList = mutableListOf<HomePageList>()

        document.select("div.list-films").forEach { block ->
            val title = block.selectFirst("h2.title-box")?.text()?.trim() ?: return@forEach
            val movies = block.select("li.item, li.film-item-ver").mapNotNull {
                it.toSearchResult()
            }
            if (movies.isNotEmpty()) {
                homePageList.add(HomePageList(title, movies))
            }
        }

        return HomePageResponse(homePageList)
    }

    // Hàm chuyển đổi một phần tử HTML thành đối tượng SearchResponse
    private fun Element.toSearchResult(): SearchResponse? {
        val title = this.selectFirst("div.name span a, a.title, p.name a")?.attr("title")?.replace("Xem phim ", "")?.replace(" online", "") ?: return null
        val href = this.selectFirst("a")?.attr("href")?.let { fixUrl(it) } ?: return null
        val posterUrl = this.selectFirst("img")?.attr("src")?.let { fixUrl(it) }

        return AnimeSearchResponse(
            name = title,
            url = href,
            type = TvType.Anime,
            posterUrl = posterUrl,
            apiName = this@Bluphim3Provider.name
        )
    }

    // Hàm tìm kiếm phim
    override suspend fun search(query: String): List<SearchResponse> {
        val searchUrl = "$mainUrl/search?k=$query"
        val document = app.get(searchUrl).document

        return document.select("div.list-films ul li.item").mapNotNull {
            it.toSearchResult()
        }
    }
    
    // Hàm tải thông tin chi tiết của phim/series
    override suspend fun load(url: String): LoadResponse? {
        val document = app.get(url).document

        val title = document.selectFirst("span.title")?.text()?.trim() ?: return null
        val poster = document.selectFirst("div.poster img")?.attr("src")?.let { fixUrl(it) }
        val year = document.select("div.dinfo dl.col dd").getOrNull(3)?.text()?.trim()?.toIntOrNull()
        val description = document.selectFirst("div.detail div.tab")?.text()?.trim()
        val tags = document.select("dd.theloaidd a").map { it.text() }

        val watchUrl = document.selectFirst("a.btn-stream-link")?.attr("href")?.let { fixUrl(it) } ?: url
        val watchDocument = app.get(watchUrl).document
        
        // CẬP NHẬT 3: Lấy danh sách phim đề cử từ trang xem phim (watchDocument) để đảm bảo luôn có dữ liệu.
        val recommendations = watchDocument.select("ul#film_related li.item, .list-films.film-related li.item").mapNotNull { it.toSearchResult() }

        // Lấy danh sách tập
        var episodes = watchDocument.select("div.episodes div.list-episode a:not(:contains(Server bên thứ 3))").map {
            val epName = it.attr("title")
            val epUrl = it.attr("href")?.let { u -> fixUrl(u) } ?: ""
            Episode(data = epUrl, name = epName)
        } // CẬP NHẬT 1: Đã bỏ .reversed()

        // CẬP NHẬT 2: Xử lý trường hợp phim có "Tập Full" => coi là phim lẻ
        val isMovieByEpisodeRule = episodes.size == 1 && episodes.first().name.contains("Tập Full", ignoreCase = true)
        if (isMovieByEpisodeRule) {
            episodes = emptyList() // Nếu là phim lẻ, xóa danh sách tập để nó được coi là Movie
        }
        
        val isTvSeries = episodes.isNotEmpty()

        return if (isTvSeries) {
            TvSeriesLoadResponse(
                name = title,
                url = url,
                apiName = this.name,
                type = TvType.TvSeries,
                episodes = episodes,
                posterUrl = poster,
                year = year,
                plot = description,
                tags = tags,
                recommendations = recommendations
            )
        } else {
            MovieLoadResponse(
                name = title,
                url = url,
                apiName = this.name,
                type = TvType.Movie,
                dataUrl = watchUrl,
                posterUrl = poster,
                year = year,
                plot = description,
                tags = tags,
                recommendations = recommendations
            )
        }
    }

    // Placeholder cho hàm loadLinks
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        // TODO: Implement this function
        return false
    }
}
