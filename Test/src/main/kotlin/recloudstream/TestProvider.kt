package recloudstream

import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.loadExtractor
import org.jsoup.nodes.Element

// Thêm các import còn thiếu
import com.lagradost.cloudstream3.Quality
import com.lagradost.cloudstream3.ActorData

// Đặt tên cho plugin của bạn
class SextbProvider : MainAPI() {
    // Tên hiển thị trong ứng dụng
    override var name = "Sextb"
    // URL chính của trang web
    override var mainUrl = "https://sextb.net"
    // Ngôn ngữ hỗ trợ
    override var lang = "en"
    // Các loại nội dung hỗ trợ (Phim)
    override val supportedTypes = setOf(TvType.Movie)
    // Bỏ qua chứng chỉ SSL không hợp lệ
    override val hasMainPage = true

    // Danh sách các trang chính để duyệt
    override val mainPage = mainPageOf(
        "" to "Home",
        "censored" to "Censored",
        "uncensored" to "Uncensored",
        "subtitle" to "Subtitle",
        "amateur" to "Amateur"
    )

    // Hàm tiện ích để chuyển đổi chuỗi chất lượng thành enum
    private fun getQualityFromString(quality: String?): Quality? {
        return when (quality?.trim()?.uppercase()) {
            "HD" -> Quality.HD
            "FHD" -> Quality.FullHd
            "4K" -> Quality.UHD
            else -> null
        }
    }

    // Hàm lấy danh sách phim từ trang chủ hoặc các danh mục
    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {
        val url = if (page == 1) {
            "$mainUrl/${request.data}"
        } else {
            // Xử lý trang cho các danh mục khác nhau
            val pageType = if(request.data.isBlank()) "page" else "${request.data}/page"
            "$mainUrl/$pageType/$page"
        }
        val document = app.get(url, referer = "$mainUrl/").document
        val home = document.select("div.tray-item").mapNotNull {
            it.toSearchResult()
        }
        return newHomePageResponse(request.name, home)
    }

    // Hàm chuyển đổi một phần tử HTML thành đối tượng SearchResponse
    private fun Element.toSearchResult(): SearchResponse? {
        val href = this.selectFirst("a")?.attr("href") ?: return null
        val title = this.selectFirst("div.tray-item-title")?.text() ?: return null
        val posterUrl = this.selectFirst("img")?.attr("data-src")
        val quality = this.select("div.tray-item-quality span.hd").text().trim()

        return newMovieSearchResponse(title, href, TvType.Movie) {
            this.posterUrl = posterUrl
            this.quality = getQualityFromString(quality)
        }
    }

    // Hàm tìm kiếm phim
    override suspend fun search(query: String): List<SearchResponse> {
        val url = "$mainUrl/search/$query"
        val document = app.get(url).document
        return document.select("div.tray-item").mapNotNull {
            it.toSearchResult()
        }
    }

    // Hàm lấy thông tin chi tiết của một bộ phim
    override suspend fun load(url: String): LoadResponse? {
        val document = app.get(url).document
        val title = document.selectFirst("h1.film-info-title")?.text()?.trim() ?: return null
        
        // Lấy filmId từ một script tag trong HTML
        val filmId = document.selectFirst("script:containsData(filmId)")?.data()
            ?.let { scriptData ->
                Regex("""var filmId = (\d+);""").find(scriptData)?.groupValues?.get(1)
            } ?: return null

        val poster = document.selectFirst("div.covert img")?.attr("data-src")
        
        // SỬA LỖI: Chuyển đổi danh sách tên diễn viên thành List<ActorData>
        val cast = document.select("div.description:contains(Cast) a").map {
            ActorData(it.text())
        }
        
        val plot = document.selectFirst("span.full-text-desc")?.text()?.trim()
        val tags = document.select("div.description:contains(Genre) a").map { it.text() }
        val year = document.select("div.description:contains(Release Date)").text()
            .substringAfter("Release Date:").trim().let {
                it.split(".").lastOrNull()?.toIntOrNull()
            }
        val recommendations = document.select("div#related div.tray-item").mapNotNull {
            it.toSearchResult()
        }

        // Lấy danh sách các server/tập phim, num là index của server
        val episodes = document.select("div.episode-list button.episode").mapIndexedNotNull { index, it ->
            val serverName = it.text().trim()
            // Dữ liệu truyền cho loadLinks sẽ là "filmId/chỉ_số_tập"
            newEpisode(data = "$filmId/$index") {
                name = serverName
            }
        }

        return newMovieLoadResponse(title, url, TvType.Movie, episodes) {
            this.posterUrl = poster
            this.year = year
            this.plot = plot
            this.tags = tags
            this.actors = cast
            this.recommendations = recommendations
        }
    }

    // Hàm lấy link video trực tiếp từ server
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        // data ở đây sẽ có dạng "filmId/episodeIndex"
        val (filmId, episodeIndex) = data.split("/").let { it[0] to it[1] }
        val referer = "$mainUrl/anything" // Referer là cần thiết

        // Gửi yêu cầu POST tới endpoint ajax để lấy trình phát
        val res = app.post(
            "$mainUrl/ajax/player",
            headers = mapOf(
                // Header Authorization này là tĩnh, được lấy từ cURL bạn cung cấp
                "Authorization" to "Basic WW5jMVdVbzNNM0JOYkhOeE1rbHZUV2wxWmt4Vlp6MDk6VWtOaGJHOXRORGgxUjBnMVNIcDVURGM0V2tOMVVUMDk=",
                "Referer" to referer
            ),
            data = mapOf(
                "episode" to episodeIndex,
                "filmId" to filmId
            )
        ).parsed<PlayerResponse>()

        // Phân tích chuỗi iframe để lấy src
        val iframeSrc = res.player?.let {
            Regex("""src="(.*?)"""").find(it)?.groupValues?.get(1)
        } ?: return false
        
        // Gọi extractor của CloudStream để xử lý link iframe
        return loadExtractor(iframeSrc, referer, subtitleCallback, callback)
    }

    // Data class để parse JSON trả về từ AJAX
    data class PlayerResponse(
        @JsonProperty("player") val player: String?
    )
}
