// Đặt package của tệp là "recloudstream"
package recloudstream

// Import các thư viện từ package gốc "com.lagradost.cloudstream3"
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.getQualityFromName
import com.lagradost.cloudstream3.utils.parallelMap
import org.jsoup.nodes.Element

/**
 * Đây là lớp chính của plugin.
 */
class AnimeTVNProvider : MainAPI() {
    // Thông tin cơ bản của nhà cung cấp
    override var mainUrl = "https://animetvn4.com"
    override var name = "AnimeTVN"
    override val hasMainPage = true
    override var lang = "vi"
    override val hasDownloadSupport = true

    // Xác định các loại nội dung mà plugin hỗ trợ
    override val supportedTypes = setOf(
        TvType.Anime,
        TvType.AnimeMovie,
        TvType.OVA,
        TvType.Movie,
        TvType.TvSeries
    )

    /**
     * CẬP NHẬT: Thêm nhiều danh mục cho trang chính.
     */
    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        // Danh sách các danh mục và URL tương ứng
        val pages = listOf(
            Pair("$mainUrl/nhom/anime.html", "Anime Mới"),
            Pair("$mainUrl/nhom/phim-sap-chieu.html", "Phim Sắp Chiếu"),
            Pair("$mainUrl/nhom/japanese-drama.html", "Live Action"),
            Pair("$mainUrl/nhom/sieu-nhan.html", "Siêu Nhân"),
            Pair("$mainUrl/nhom/cartoon.html", "Cartoon")
        )

        // Sử dụng parallelMap để tải các danh mục song song, tăng tốc độ tải
        val all = pages.parallelMap { (url, name) ->
            try {
                val document = app.get(url).document
                val home = document.select("div.film_item").mapNotNull {
                    it.toSearchResult()
                }
                HomePageList(name, home)
            } catch (e: Exception) {
                // Bỏ qua nếu có lỗi tải một danh mục
                null
            }
        }.filterNotNull()

        return HomePageResponse(all)
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val titleElement = this.selectFirst("h3.title a") ?: return null
        val title = titleElement.text()
        val href = titleElement.attr("href")
        val posterUrl = this.selectFirst("img.thumb")?.attr("src")

        return newAnimeSearchResponse(title, href, TvType.Anime) {
            this.posterUrl = posterUrl
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val searchUrl = "$mainUrl/tim-kiem/${query}.html"
        val document = app.get(searchUrl).document

        return document.select("div.film_item").mapNotNull {
            it.toSearchResult()
        }
    }

    /**
     * CẬP NHẬT: Sửa lại cách đặt tên tập phim.
     */
    override suspend fun load(url: String): LoadResponse? {
        val document = app.get(url).document

        val title = document.selectFirst("h2.name-vi")?.text() ?: return null
        val poster = document.selectFirst("div.small_img img")?.attr("src")
        val description = document.selectFirst("div#tab-film-content div.content")?.text()
        val genres = document.select("li.has-color:contains(Thể loại) a").map { it.text() }

        val watchPageUrl = document.selectFirst("a.btn.play-now")?.attr("href")

        val episodes = if (watchPageUrl != null) {
            val watchPageDocument = app.get(watchPageUrl).document
            watchPageDocument.select("div.eplist a.tapphim").mapNotNull { ep ->
                val epUrl = ep.attr("href")
                // Lấy tên tập từ text của thẻ <a>, ví dụ: "01", "26_End"
                val epText = ep.text()
                // Trích xuất số từ text để sắp xếp
                val episodeNumber = epText.replace(Regex("[^0-9]"), "").toIntOrNull()
                newEpisode(epUrl) {
                    // Đặt tên theo định dạng "Tập X"
                    this.name = "Tập $epText"
                    this.episode = episodeNumber
                }
            }.reversed()
        } else {
            listOf()
        }

        return if (episodes.isNotEmpty()) {
            newTvSeriesLoadResponse(title, url, TvType.Anime, episodes) {
                this.posterUrl = poster
                this.plot = description
                this.tags = genres
            }
        } else {
            newMovieLoadResponse(title, url, TvType.AnimeMovie, url) {
                this.posterUrl = poster
                this.plot = description
                this.tags = genres
            }
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        // Hiện tại hàm này không làm gì cả (placeholder).
        return true
    }

    data class VideoResponse(
        val link: List<VideoLink>,
        val type: String
    )

    data class VideoLink(
        val file: String,
        val label: String,
        val type: String
    )
}
