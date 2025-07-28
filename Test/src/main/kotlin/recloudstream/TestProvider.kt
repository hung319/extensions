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
        val url = "$mainUrl${request.data}$page"
        val document = app.get(url).document

        val movies = document.select("div.list-films.film-new li.item").mapNotNull {
            it.toSearchResult()
        }
        
        val hasNextPage = movies.isNotEmpty()

        return newHomePageResponse(request.name, movies, hasNextPage)
    }

    // Hàm tiện ích để chuyển đổi một phần tử HTML thành đối tượng MovieSearchResponse
    private fun Element.toSearchResult(): MovieSearchResponse? {
        val href = this.selectFirst("a")?.attr("href") ?: return null
        
        // Sửa lỗi: Tạo selector linh hoạt hơn để lấy tiêu đề từ các cấu trúc HTML khác nhau
        val title = this.selectFirst("div.name span, div.name a, a.title")?.text()?.trim()
            ?: this.attr("title").trim().takeIf { it.isNotEmpty() }
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

        // Phân tích cú pháp kết quả tìm kiếm và ánh xạ chúng
        return document.select("div.list-films.film-new li.item").mapNotNull {
            it.toSearchResult()
        }
    }

    // Hàm để tải thông tin chi tiết cho một bộ phim
    override suspend fun load(url: String): LoadResponse? {
        val document = app.get(url).document

        // Trích xuất các chi tiết phim
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

        // Xác định loại TvType (phim lẻ hay phim bộ)
        val tvType = if (document.select("dd.theloaidd a:contains(TV Series - Phim bộ)").isNotEmpty()) {
            TvType.TvSeries
        } else {
            TvType.Movie
        }

        // Trả về đối tượng LoadResponse phù hợp
        return if (tvType == TvType.TvSeries) {
            // Lấy danh sách tập phim
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
            // Loại bỏ các liên kết server không mong muốn
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
        // Hiện tại chưa triển khai
        // Logic để trích xuất các liên kết video sẽ được thêm vào đây
        throw NotImplementedError("Chức năng loadLinks chưa được triển khai.")
    }
}
