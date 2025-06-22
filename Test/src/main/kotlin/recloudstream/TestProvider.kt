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

    /**
     * Hàm này được gọi để lấy nội dung cho trang chính của plugin.
     */
    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val document = app.get(mainUrl).document
        val home = document.select("div.film_item").mapNotNull {
            it.toSearchResult()
        }
        return newHomePageResponse("Anime mới cập nhật", home)
    }

    /**
     * Hàm này được dùng để chuyển đổi một phần tử HTML (Element) thành một kết quả tìm kiếm (SearchResult).
     * SỬA LỖI: Đã xóa thuộc tính 'nbSeasons' không hợp lệ.
     */
    private fun Element.toSearchResult(): SearchResponse? {
        val titleElement = this.selectFirst("h3.title a") ?: return null
        val title = titleElement.text()
        val href = titleElement.attr("href")
        val posterUrl = this.selectFirst("img.thumb")?.attr("src")
        // Vẫn lấy số tập nhưng không gán vào đâu cả vì SearchResponse không hỗ trợ
        // val episodes = this.selectFirst("span.time")?.text()?.let { epsString ->
        //     Regex("(\\d+)").find(epsString)?.groupValues?.get(1)?.toIntOrNull()
        // }

        return newAnimeSearchResponse(title, href, TvType.Anime) {
            this.posterUrl = posterUrl
            // DÒNG BỊ LỖI ĐÃ ĐƯỢC XÓA
            // if (episodes != null) {
            //     this.nbSeasons = episodes
            // }
        }
    }

    /**
     * Hàm này được gọi khi người dùng thực hiện tìm kiếm.
     */
    override suspend fun search(query: String): List<SearchResponse> {
        val searchUrl = "$mainUrl/tim-kiem/${query}.html"
        val document = app.get(searchUrl).document

        return document.select("div.film_item").mapNotNull {
            it.toSearchResult()
        }
    }

    /**
     * Hàm này được gọi khi người dùng chọn một bộ phim để xem thông tin chi tiết.
     */
    override suspend fun load(url: String): LoadResponse? {
        val document = app.get(url).document
        val title = document.selectFirst("h2.name-vi")?.text() ?: return null
        val poster = document.selectFirst("div.small_img img")?.attr("src")
        val episodes = document.select("div.eplist a.tapphim").mapNotNull {
            val epUrl = it.attr("href")
            val epName = it.attr("title")
            val episodeNumber = Regex("tap-(\\d+)").find(epUrl)?.groupValues?.get(1)
            newEpisode(epUrl) {
                this.name = epName
                this.episode = episodeNumber?.toIntOrNull()
            }
        }.reversed()
        val description = document.selectFirst("div#tab-film-content div.content")?.text()
        val genres = document.select("li.has-color:contains(Thể loại) a").map { it.text() }

        return newTvSeriesLoadResponse(title, url, TvType.Anime, episodes) {
            this.posterUrl = poster
            this.plot = description
            this.tags = genres
        }
    }

    /**
     * Hàm này được gọi khi người dùng chọn một tập phim để xem.
     * Hiện tại đang là placeholder (trình giữ chỗ).
     */
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        return true
    }

    // Các lớp dữ liệu này sẽ được dùng khi bạn hoàn thiện hàm loadLinks
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
