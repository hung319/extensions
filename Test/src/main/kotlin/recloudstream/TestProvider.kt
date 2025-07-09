package recloudstream

import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.loadExtractor
import org.jsoup.nodes.Element
import android.util.Base64

class SextbProvider : MainAPI() {
    override var name = "Sextb"
    override var mainUrl = "https://sextb.net"
    override var lang = "en"
    override val supportedTypes = setOf(TvType.Movie)
    override val hasMainPage = true

    override val mainPage = mainPageOf(
        "" to "Home",
        "censored" to "Censored",
        "uncensored" to "Uncensored",
        "subtitle" to "Subtitle",
        "amateur" to "Amateur"
    )

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {
        val url = if (page == 1) "$mainUrl/${request.data}" else "$mainUrl/${request.data}/page/$page"
        val document = app.get(url, referer = "$mainUrl/").document
        val home = document.select("div.tray-item").mapNotNull { it.toSearchResult() }
        return newHomePageResponse(request.name, home)
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val href = this.selectFirst("a")?.attr("href") ?: return null
        val title = this.selectFirst("div.tray-item-title")?.text() ?: return null
        val posterUrl = this.selectFirst("img")?.attr("data-src")
        return newMovieSearchResponse(title, href, TvType.Movie) {
            this.posterUrl = posterUrl
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val document = app.get("$mainUrl/search/$query").document
        return document.select("div.tray-item").mapNotNull { it.toSearchResult() }
    }
    
    // Hàm load giờ đây rất đơn giản, chỉ lấy thông tin hiển thị
    override suspend fun load(url: String): LoadResponse? {
        val document = app.get(url).document
        val title = document.selectFirst("h1.film-info-title")?.text()?.trim() ?: return null
        
        val poster = document.selectFirst("div.covert img")?.attr("data-src")
        val plot = document.selectFirst("span.full-text-desc")?.text()?.trim()
        val tags = document.select("div.description:contains(Genre) a").map { it.text() }
        val year = document.select("div.description:contains(Release Date)").text()
            .substringAfter("Release Date:").trim().let { it.split(".").lastOrNull()?.toIntOrNull() }
        val cast = document.select("div.description:contains(Cast) a").map { actorElement ->
            ActorData(Actor(name = actorElement.text()), roleString = "Actor")
        }
        
        // Không lấy phim liên quan ở đây nữa để tránh lỗi và tăng tốc độ
        // Thay vì truyền danh sách episode, ta truyền thẳng url của phim
        return newMovieLoadResponse(title, url, TvType.Movie, url) {
            this.posterUrl = poster
            this.year = year
            this.plot = plot
            this.tags = tags
            this.actors = cast
        }
    }

    // Toàn bộ logic lấy link được chuyển hết vào đây
    override suspend fun loadLinks(
        data: String, // `data` giờ là URL của phim
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        // Tải lại trang phim để lấy thông tin cần thiết
        val document = app.get(data).document

        // Trích xuất các key động từ trang
        val filmId = document.selectFirst("script:containsData(filmId)")?.data()
            ?.let { Regex("""filmId\s*=\s*['"]?(\d+)['"]?""").find(it)?.groupValues?.get(1) }
            ?: return false // Nếu không có filmId, thoát
        val token = document.selectFirst("meta[name=_token]")?.attr("value") ?: return false
        val socket = document.selectFirst("meta[name=_socket]")?.attr("value") ?: return false

        // Tự động chọn server đầu tiên (index = 0)
        val episodeIndex = "0"
        
        // Tạo Authorization header động
        val authKey = "Basic " + Base64.encodeToString(("$token:$socket").toByteArray(), Base64.NO_WRAP)
        val referer = data

        // Gọi API để lấy link player
        val res = app.post(
            "$mainUrl/ajax/player",
            headers = mapOf("Authorization" to authKey, "Referer" to referer),
            data = mapOf("episode" to episodeIndex, "filmId" to filmId)
        ).parsed<PlayerResponse>()

        val iframeSrc = res.player?.let { Regex("""src="(.*?)"""").find(it)?.groupValues?.get(1) } ?: return false
        
        return loadExtractor(iframeSrc, referer, subtitleCallback, callback)
    }
    
    data class PlayerResponse(
        @JsonProperty("player") val player: String?
    )
}
