// Đặt package của tệp là "recloudstream"
package recloudstream

// Import các thư viện từ package gốc "com.lagradost.cloudstream3"
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MainPageRequest
import com.lagradost.cloudstream3.HomePageResponse
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.newAnimeSearchResponse
import com.lagradost.cloudstream3.newEpisode
import com.lagradost.cloudstream3.newHomePageResponse
import com.lagradost.cloudstream3.newMovieLoadResponse
import com.lagradost.cloudstream3.newTvSeriesLoadResponse
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.getQualityFromName
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
        TvType.OVA
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val document = app.get(mainUrl).document
        val home = document.select("div.film_item").mapNotNull {
            it.toSearchResult()
        }
        return newHomePageResponse("Anime mới cập nhật", home)
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
     * SỬA LỖI: Hàm `load` đã được cập nhật để lấy danh sách tập từ trang xem phim.
     */
    override suspend fun load(url: String): LoadResponse? {
        // 1. Tải trang thông tin phim để lấy chi tiết cơ bản
        val document = app.get(url).document

        val title = document.selectFirst("h2.name-vi")?.text() ?: return null
        val poster = document.selectFirst("div.small_img img")?.attr("src")
        val description = document.selectFirst("div#tab-film-content div.content")?.text()
        val genres = document.select("li.has-color:contains(Thể loại) a").map { it.text() }

        // 2. Tìm nút "Xem phim" để lấy link đến trang chứa danh sách tập
        val watchPageUrl = document.selectFirst("a.btn.play-now")?.attr("href")

        // 3. Nếu tìm thấy link trang xem phim, tải trang đó để lấy danh sách tập
        val episodes = if (watchPageUrl != null) {
            val watchPageDocument = app.get(watchPageUrl).document
            watchPageDocument.select("div.eplist a.tapphim").mapNotNull { ep ->
                val epUrl = ep.attr("href")
                val epName = ep.attr("title")
                // Trích xuất số tập từ tiêu đề hoặc URL để sắp xếp
                val episodeNumber = ep.text().replace(Regex("[^0-9]"), "").toIntOrNull()
                newEpisode(epUrl) {
                    this.name = epName.removePrefix("Xem phim ") // Làm sạch tên tập
                    this.episode = episodeNumber
                }
            }.reversed() // Đảo ngược để tập 1 lên đầu
        } else {
            // Nếu không có link xem phim, đây có thể là phim lẻ, trả về danh sách rỗng
            listOf()
        }

        // 4. Kiểm tra xem có tập phim nào không để quyết định là phim bộ hay phim lẻ
        return if (episodes.isNotEmpty()) {
            newTvSeriesLoadResponse(title, url, TvType.Anime, episodes) {
                this.posterUrl = poster
                this.plot = description
                this.tags = genres
            }
        } else {
            // Nếu không có tập nào, coi nó là phim lẻ
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
