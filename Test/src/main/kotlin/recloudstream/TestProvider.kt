// Bạn có thể cần thay đổi package này cho phù hợp với cấu trúc dự án của mình
package com.lagradost.cloudstream3.plugins.vi

// Thêm thư viện Jsoup để phân tích cú pháp HTML
import org.jsoup.nodes.Element

// SỬA LỖI: Import trực tiếp các lớp LoadResponse và SearchResponse cần thiết
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

        // Lấy danh sách phim từ các mục khác nhau trên trang chủ
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

        // SỬA LỖI: Sử dụng constructor AnimeSearchResponse trực tiếp
        return AnimeSearchResponse(
            title = title,
            url = href,
            type = TvType.Anime,
            posterUrl = posterUrl
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
        val recommendations = document.select("ul#film_related li.item").mapNotNull { it.toSearchResult() }

        // Trang web không có danh sách tập phim ở trang chính của phim,
        // chúng ta phải vào link "Xem phim" để lấy danh sách tập.
        val watchUrl = document.selectFirst("a.btn-stream-link")?.attr("href")?.let { fixUrl(it) } ?: url
        val watchDocument = app.get(watchUrl).document
        
        val episodes = watchDocument.select("div.episodes div.list-episode a").map {
            val epName = it.attr("title")
            val epUrl = it.attr("href")?.let { u -> fixUrl(u) } ?: ""
            Episode(data = epUrl, name = epName)
        }.reversed() // Đảo ngược để tập 1 ở đầu

        // Xác định loại TV (TvSeries hay Movie) dựa trên danh sách tập phim
        val isTvSeries = episodes.isNotEmpty()

        return if (isTvSeries) {
            // SỬA LỖI: Sử dụng constructor TvSeriesLoadResponse trực tiếp
            TvSeriesLoadResponse(
                name = title,
                url = url,
                type = TvType.TvSeries,
                episodes = episodes,
                posterUrl = poster,
                year = year,
                plot = description,
                tags = tags,
                recommendations = recommendations
            )
        } else {
            // SỬA LỖI: Sử dụng constructor MovieLoadResponse trực tiếp
            MovieLoadResponse(
                name = title,
                url = url,
                type = TvType.Movie,
                dataUrl = watchUrl, // Dùng watchUrl để loadLinks
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
        data: String, // Đây là URL của tập phim hoặc phim
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        // TODO: Implement this function
        // Trả về false để báo hiệu rằng chưa có link nào được tải.
        return false
    }
}
