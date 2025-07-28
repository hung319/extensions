package recloudstream

import com.fasterxml.jackson.annotation.JsonProperty
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

    // Hàm để lấy dữ liệu cho trang chính
    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {
        // Phân tích cú pháp tài liệu HTML từ URL chính
        val document = app.get(mainUrl).document
        val homePageList = ArrayList<HomePageList>()

        // Trích xuất các phần tử "Phim hot"
        val hotMoviesSection = document.select("div.list-films.film-hot ul#film_hot li.item")
        if (hotMoviesSection.isNotEmpty()) {
            val hotMovies = hotMoviesSection.mapNotNull {
                it.toSearchResult()
            }
            homePageList.add(HomePageList("Phim Hot", hotMovies))
        }

        // Trích xuất các phần tử "Phim mới cập nhật"
        val newMoviesSection = document.select("div.list-films.film-new ul.film-moi li.item")
        if (newMoviesSection.isNotEmpty()) {
            val newMovies = newMoviesSection.mapNotNull {
                it.toSearchResult()
            }
            homePageList.add(HomePageList("Phim Mới Cập Nhật", newMovies))
        }

        // Trả về danh sách đã xây dựng
        return HomePageResponse(homePageList)
    }

    // Hàm tiện ích để chuyển đổi một phần tử HTML thành đối tượng MovieSearchResponse
    private fun Element.toSearchResult(): MovieSearchResponse? {
        val titleElement = this.selectFirst("div.name span a, a.blog-title, div.text span.title a")
        val title = titleElement?.attr("title")?.trim()
            ?: titleElement?.text()?.trim()
            ?: this.attr("title").trim().takeIf { it.isNotEmpty() }
            ?: return null

        val href = this.selectFirst("a")?.attr("href") ?: return null
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

        // Xác định loại TvType (phim lẻ hay phim bộ)
        val tvType = if (document.select("dd.theloaidd a:contains(TV Series - Phim bộ)").isNotEmpty()) {
            TvType.TvSeries
        } else {
            TvType.Movie
        }

        // Trả về đối tượng LoadResponse phù hợp
        return if (tvType == TvType.TvSeries) {
            newTvSeriesLoadResponse(title, url, tvType, emptyList()) {
                this.posterUrl = poster
                this.year = year
                this.plot = description
                this.rating = rating
                this.tags = genres
            }
        } else {
            newMovieLoadResponse(title, url, tvType, url) {
                this.posterUrl = poster
                this.year = year
                this.plot = description
                this.rating = rating
                this.tags = genres
            }
        }
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
