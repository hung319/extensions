package recloudstream

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Element

// Xác định lớp provider chính
class BluPhimProvider : MainAPI() {
    // Ghi đè các thuộc tính cơ bản của API
    override var mainUrl = "https://bluphim.uk.com"
    override var name = "BluPhim"
    override val hasMainPage = true
    override var lang = "vi"
    override val hasDownloadSupport = false
    override val supportedTypes = setOf(
        TvType.Movie,
        TvType.TvSeries
    )

    // quickSearch sẽ tải các mục trên trang chủ không cần phân trang
    override suspend fun quickSearch(query: String): List<SearchResponse> {
        val document = app.get(mainUrl).document
        // Trích xuất các phần tử "Phim hot"
        val hotMoviesSection = document.select("div.list-films.film-hot ul#film_hot li.item")
        return hotMoviesSection.mapNotNull { it.toSearchResult() }
    }

    // mainPage định nghĩa các mục có phân trang
    override val mainPage = mainPageOf(
        "/the-loai/phim-moi-" to "Phim Mới",
        "/the-loai/phim-cap-nhat-" to "Phim Cập Nhật"
    )

    // getMainPage giờ chỉ xử lý các yêu cầu phân trang
    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {
        // request.data sẽ là "/the-loai/phim-moi-"
        val url = "$mainUrl${request.data}$page"
        val document = app.get(url).document

        // Selector này đúng cho các trang phân loại
        val movies = document.select("div.list-films.film-new li.item").mapNotNull {
            it.toSearchResult()
        }
        
        val hasNextPage = movies.isNotEmpty()

        return newHomePageResponse(request.name, movies, hasNextPage)
    }

    // Hàm tiện ích để chuyển đổi một phần tử HTML thành đối tượng MovieSearchResponse
    private fun Element.toSearchResult(): MovieSearchResponse? {
        val href = this.selectFirst("a")?.attr("href") ?: return null
        
        // Sửa lỗi: Tạo selector linh hoạt hơn để xử lý nhiều layout HTML
        val title = this.selectFirst("div.name span")?.text()?.trim() // Dành cho list phim thường
            ?: this.selectFirst("div.text span.title a")?.text()?.trim() // Dành cho list "Phim Hot"
            ?: this.attr("title").trim().takeIf { it.isNotEmpty() } // Fallback
            ?: return null

        val posterUrl = this.selectFirst("img")?.attr("src")

        // Xây dựng URL đầy đủ cho href và posterUrl nếu cần
        val fullHref = if (href.startsWith("http")) href else "$mainUrl$href"
        val fullPosterUrl = if (posterUrl?.startsWith("http") == true) posterUrl else posterUrl?.let { "$mainUrl$it" }

        return newMovieSearchResponse(title, fullHref) {
            this.posterUrl = fullPosterUrl
        }
    }


    // Hàm để xử lý các truy vấn tìm kiếm
    override suspend fun search(query: String): List<SearchResponse> {
        val url = "$mainUrl/search?k=$query"
        val document = app.get(url).document

        return document.select("div.list-films.film-new li.item").mapNotNull {
            it.toSearchResult()
        }
    }

    // Hàm để tải thông tin chi tiết cho một bộ phim
    override suspend fun load(url: String): LoadResponse? {
        val document = app.get(url).document

        val title = document.selectFirst("div.text h1 span.title")?.text()?.trim() ?: return null
        val poster = document.selectFirst("div.poster img")?.let {
            val src = it.attr("src")
            if (src.startsWith("http")) src else "$mainUrl$src"
        }
        val year = document.select("div.dinfo dl.col dt:contains(Năm sản xuất) + dd")
            .text().toIntOrNull()
        val description = document.selectFirst("div.detail div.tab")?.text()?.trim()
        val rating = document.select("div.dinfo dl.col dt:contains(Điểm IMDb) + dd a")
            .text().toRatingInt()
        val genres = document.select("dd.theloaidd a").map { it.text() }
        val recommendations = document.select("div.list-films.film-hot ul#film_related li.item").mapNotNull {
            it.toSearchResult()
        }

        val tvType = if (document.select("dd.theloaidd a:contains(TV Series - Phim bộ)").isNotEmpty()) {
            TvType.TvSeries
        } else {
            TvType.Movie
        }

        return if (tvType == TvType.TvSeries) {
            val watchUrl = document.selectFirst("a.btn-see.btn-stream-link")?.attr("href")
            val episodes = if (watchUrl != null) getEpisodes(watchUrl) else emptyList()

            newTvSeriesLoadResponse(title, url, tvType, episodes) {
                this.posterUrl = poster
                this.year = year
                this.plot = description
                this.rating = rating
                this.tags = genres
                this.recommendations = recommendations
            }
        } else {
            newMovieLoadResponse(title, url, tvType, url) {
                this.posterUrl = poster
                this.year = year
                this.plot = description
                this.rating = rating
                this.tags = genres
                this.recommendations = recommendations
            }
        }
    }

    // Hàm để lấy danh sách các tập
    private suspend fun getEpisodes(watchUrl: String): List<Episode> {
        val fullWatchUrl = if (watchUrl.startsWith("http")) watchUrl else "$mainUrl$watchUrl"
        val document = app.get(fullWatchUrl).document
        val episodeList = ArrayList<Episode>()

        document.select("div.list-episode a").forEach { element ->
            val href = element.attr("href")
            val name = element.text().trim()
            if (href.isNotEmpty() && !name.contains("Server", ignoreCase = true)) {
                episodeList.add(newEpisode(if (href.startsWith("http")) href else "$mainUrl$href") {
                    this.name = name
                })
            }
        }
        return episodeList
    }

    // Hàm giữ chỗ (placeholder) cho việc tải các liên kết
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        throw NotImplementedError("Chức năng loadLinks chưa được triển khai.")
    }
}
