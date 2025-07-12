package recloudstream

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Element
import com.lagradost.cloudstream3.models.ExtractorLink
import com.lagradost.cloudstream3.models.Qualities
import com.lagradost.cloudstream3.models.SubtitleFile
import com.lagradost.cloudstream3.models.TvType

class LongTiengPhimProvider : MainAPI() {
    // Thông tin cơ bản về provider
    override var mainUrl = "https://longtiengphim.com"
    override var name = "Lồng Tiếng Phim"
    override val hasMainPage = true
    override var lang = "vi"
    override val supportedTypes = setOf(
        TvType.Movie,
        TvType.TvSeries
    )

    // Danh sách trang chính
    override val mainPage = mainPageOf(
        "$mainUrl/tat-ca-phim/page/" to "Tất Cả Phim",
        "$mainUrl/phim-chieu-rap/page/" to "Phim Chiếu Rạp",
        "$mainUrl/phim-hoat-hinh/page/" to "Phim Hoạt Hình",
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val document = app.get(request.data + page).document
        val home = document.select("div.halim_box > article").mapNotNull {
            it.toSearchResult()
        }
        return newHomePageResponse(request.name, home)
    }

    // Hàm chuyển đổi element thành kết quả tìm kiếm
    private fun Element.toSearchResult(): SearchResponse? {
        val title = this.selectFirst("h2.entry-title")?.text()?.trim() ?: return null
        val href = this.selectFirst("a.halim-thumb")?.attr("href") ?: return null
        val posterUrl = this.selectFirst("figure.film-poster img, figure.lazy.img-responsive")?.attr("src") ?: this.selectFirst("img")?.attr("src")

        return newMovieSearchResponse(title, href, TvType.Movie) {
            this.posterUrl = posterUrl
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val document = app.get("$mainUrl/?s=$query").document
        return document.select("div.halim_box > article").mapNotNull {
            it.toSearchResult()
        }
    }

    override suspend fun load(url: String): LoadResponse? {
        val document = app.get(url).document

        val title = document.selectFirst("h1.entry-title")?.text()?.trim() ?: return null
        val poster = document.selectFirst("img.movie-thumb")?.attr("src")
        val plot = document.selectFirst("div.entry-content > article")?.text()?.trim()
        val year = document.selectFirst("a[href*=/release/]")?.text()?.trim()?.toIntOrNull()
        val tags = document.select("div.more-info a[rel=category-tag]").map { it.text() }
        val recommendations = document.select("div#halim_related_movies-3 article").mapNotNull {
            it.toSearchResult()
        }

        // Lấy danh sách tập phim
        val episodes = document.select("div#halim-list-server ul.halim-list-eps li.halim-episode a").map {
            Episode(it.attr("href"), it.text().trim())
        }.ifEmpty {
            // Xử lý trường hợp phim lẻ không có danh sách tập
            val watchUrl = url.replace(mainUrl, "$mainUrl/watch")
            listOf(Episode(watchUrl, "Xem phim"))
        }

        return newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
            this.posterUrl = poster
            this.plot = plot
            this.year = year
            this.tags = tags
            this.recommendations = recommendations
            addDubStatus(true)
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val watchPageSource = app.get(data).text

        // Trích xuất các giá trị cần thiết cho AJAX request
        val postId = Regex("""'postid':\s*'(\d+)'""").find(watchPageSource)?.groupValues?.get(1)
            ?: Regex("""post_id:\s*(\d+)""").find(watchPageSource)?.groupValues?.get(1)
            ?: return false
        val nonce = Regex(""""nonce":"([^"]+)"""").find(watchPageSource)?.groupValues?.get(1) ?: return false

        // Dữ liệu gửi đi trong POST request
        val postData = mapOf(
            "action" to "halim_ajax_player",
            "nonce" to nonce,
            "postid" to postId,
        )

        // Gửi POST request để lấy script của player
        val playerScript = app.post(
            "$mainUrl/wp-admin/admin-ajax.php",
            data = postData,
            referer = data,
            headers = mapOf("X-Requested-With" to "XMLHttpRequest")
        ).text

        // Dùng Regex để tìm URL video .mp4
        val videoUrl = Regex("""file":"(https?://[^"]+?\.mp4)"""")
            .find(playerScript)?.groupValues?.get(1)?.replace("\\", "")
            ?: return false

        callback(
            ExtractorLink(
                source = this.name,
                name = "Server Lồng Tiếng",
                url = videoUrl,
                referer = "$mainUrl/",
                quality = Qualities.Unknown.value,
                // Cập nhật: Sử dụng ExtractorLinkType.VIDEO cho file .mp4
                type = ExtractorLinkType.VIDEO,
            )
        )
        return true
    }
}
