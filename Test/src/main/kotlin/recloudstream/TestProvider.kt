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

    // CẬP NHẬT 4: Sửa lại hàm toSearchResult để hoạt động chính xác ở mọi nơi
    private fun Element.toSearchResult(): SearchResponse? {
        val linkElement = this.selectFirst("a") ?: return null
        // Lấy title từ thuộc tính `title` của thẻ <a>, đây là cách ổn định nhất
        val title = linkElement.attr("title")
            .replace("Xem phim ", "")
            .replace(" online", "")
            .trim()
        
        if (title.isBlank()) return null

        val href = linkElement.attr("href")?.let { fixUrl(it) } ?: return null
        val posterUrl = this.selectFirst("img")?.attr("src")?.let { fixUrl(it) }

        return AnimeSearchResponse(
            name = title,
            url = href,
            type = TvType.Anime,
            posterUrl = posterUrl,
            apiName = this@Bluphim3Provider.name
        )
    }

    // Hàm tìm kiếm phim - giờ sẽ hoạt động đúng nhờ `toSearchResult` đã được sửa
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
        
        // CẬP NHẬT 3: Dùng selector tốt hơn để lấy rcm list
        val recommendations = watchDocument.select(".list-films.film-related li.item, .list-films.film-hot li.item").mapNotNull { it.toSearchResult() }

        // Lấy tất cả các thẻ <a> trong danh sách tập
        val episodeElements = watchDocument.select("div.episodes div.list-episode a:not(:contains(Server bên thứ 3))")

        // CẬP NHẬT 2: Kiểm tra văn bản hiển thị của tập đầu tiên
        val isMovieByEpisodeRule = episodeElements.size == 1 && episodeElements.first()?.text()?.contains("Tập Full", ignoreCase = true) == true
        
        if (isMovieByEpisodeRule) {
            // Nếu đúng là phim lẻ, tạo MovieLoadResponse
            val movieDataUrl = episodeElements.first()?.attr("href")?.let { fixUrl(it) } ?: watchUrl
            return MovieLoadResponse(
                name = title,
                url = url,
                apiName = this.name,
                type = TvType.Movie,
                dataUrl = movieDataUrl,
                posterUrl = poster,
                year = year,
                plot = description,
                tags = tags,
                recommendations = recommendations
            )
        } else {
            // Nếu là series, tạo danh sách tập
            // CẬP NHẬT 1: Thêm lại .reversed() để sắp xếp tập đúng thứ tự a-z
            val episodes = episodeElements.map {
                Episode(
                    data = fixUrl(it.attr("href")),
                    name = it.attr("title").ifBlank { it.text() }
                )
            }.reversed()

            return TvSeriesLoadResponse(
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
