package recloudstream

import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.loadExtractor
import org.jsoup.nodes.Element
import android.util.Base64
import android.util.Log

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
        
        return newMovieLoadResponse(title, url, TvType.Movie, url) {
            this.posterUrl = poster
            this.year = year
            this.plot = plot
            this.tags = tags
            this.actors = cast
        }
    }

    // SỬA ĐỔI: Chuyển log sang hộp thoại lỗi để dễ đọc hơn
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        // Dùng StringBuilder để xây dựng chuỗi log
        val logBuilder = StringBuilder()
        
        fun log(message: String) {
            Log.d("SextbProvider", message)
            logBuilder.append(message).append("\n")
        }

        try {
            log("1. Bắt đầu `loadLinks` với data: $data")

            val document = app.get(data).document
            log("2. Tải trang phim thành công.")

            val filmId = document.selectFirst("script:containsData(filmId)")?.data()
                ?.let { Regex("""filmId\s*=\s*['"]?(\d+)['"]?""").find(it)?.groupValues?.get(1) }
                ?: throw Exception("Không tìm thấy filmId")
            log("3. Đã tìm thấy filmId: $filmId")

            val token = document.selectFirst("meta[name=_token]")?.attr("value") 
                ?: throw Exception("Không tìm thấy _token")
            val socket = document.selectFirst("meta[name=_socket]")?.attr("value") 
                ?: throw Exception("Không tìm thấy _socket")
            log("4. Đã tìm thấy token và socket.")

            val authKey = "Basic " + Base64.encodeToString(("$token:$socket").toByteArray(), Base64.NO_WRAP)
            log("5. Đã tạo AuthKey: $authKey")
            
            log("6. Đang gửi yêu cầu Ajax đến server...")
            val res = app.post(
                "$mainUrl/ajax/player",
                headers = mapOf("Authorization" to authKey, "Referer" to data),
                data = mapOf("episode" to "0", "filmId" to filmId)
            ).parsed<PlayerResponse>()
            log("7. Phản hồi từ server: ${res.player?.take(100)}...")

            val iframeSrc = res.player?.let { Regex("""src="(.*?)"""").find(it)?.groupValues?.get(1) } 
                ?: throw Exception("Không trích xuất được link iframe từ phản hồi")
            log("8. Đã lấy được link iframe: $iframeSrc")
            
            log("9. Đang gọi Extractor để lấy link cuối cùng...")
            return loadExtractor(iframeSrc, data, subtitleCallback, callback)

        } catch (e: Exception) {
            // Ném ra một lỗi RuntimeException với nội dung là toàn bộ log
            // CloudStream sẽ tự động bắt lỗi này và hiển thị trên giao diện
            throw RuntimeException(logBuilder.toString() + "\nLỖI: " + e.message)
        }
    }
    
    data class PlayerResponse(
        @JsonProperty("player") val player: String?
    )
}
