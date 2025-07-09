package recloudstream

import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import com.lagradost.cloudstream3.utils.AppUtils.toJson
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
        
        val filmId = document.selectFirst("script:containsData(filmId)")?.data()
            ?.let { Regex("""filmId\s*=\s*['"]?(\d+)['"]?""").find(it)?.groupValues?.get(1) }
            ?: return null
        val token = document.selectFirst("meta[name=_token]")?.attr("value") ?: return null
        val socket = document.selectFirst("meta[name=_socket]")?.attr("value") ?: return null

        val poster = document.selectFirst("div.covert img")?.attr("data-src")
        val plot = document.selectFirst("span.full-text-desc")?.text()?.trim()
        val tags = document.select("div.description:contains(Genre) a").map { it.text() }
        val year = document.select("div.description:contains(Release Date)").text()
            .substringAfter("Release Date:").trim().let { it.split(".").lastOrNull()?.toIntOrNull() }
        val cast = document.select("div.description:contains(Cast) a").map { actorElement ->
            ActorData(Actor(name = actorElement.text()), roleString = "Actor")
        }
        
        val episodes = document.select("div.episode-list button.episode").mapNotNull {
            val serverName = it.text().trim()
            val episodeId = it.attr("data-id")
            if (episodeId.isBlank()) return@mapNotNull null
            val episodeData = EpisodeData(filmId, episodeId, token, socket)
            newEpisode(episodeData.toJson()) {
                name = serverName
            }
        }

        return newMovieLoadResponse(title, url, TvType.Movie, episodes) {
            this.posterUrl = poster
            this.year = year
            this.plot = plot
            this.tags = tags
            this.actors = cast
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val logBuilder = StringBuilder()
        fun log(message: String) {
            Log.d("SextbProvider", message)
            logBuilder.append(message).append("\n")
        }

        try {
            log("1. Bắt đầu loadLinks.")
            log("   - Dữ liệu thô nhận được: ${data.take(150)}...")

            // SỬA LỖI: Thêm kiểu dữ liệu <EpisodeData> vào các lệnh gọi parseJson
            val episodeData = try {
                log("2a. Thử phân tích data như một object đơn lẻ...")
                parseJson<EpisodeData>(data)
            } catch (e1: Exception) {
                log("2b. Thất bại. Thử phân tích data như một danh sách...")
                try {
                    data class TestWrapper(@JsonProperty("data") val innerData: String)
                    val innerData = parseJson<List<TestWrapper>>(data).first().innerData
                    log("   - Đã trích xuất được dữ liệu bên trong: ${innerData.take(150)}...")
                    parseJson<EpisodeData>(innerData)
                } catch (e2: Exception) {
                    throw Exception("Không thể phân tích dữ liệu episode theo cả hai cách. Lỗi: ${e2.message}")
                }
            }
            log("3. Phân tích EpisodeData thành công: filmId=${episodeData.filmId}, episodeId=${episodeData.episodeId}")

            val authKey = "Basic " + Base64.encodeToString(("${episodeData.token}:${episodeData.socket}").toByteArray(), Base64.NO_WRAP)
            log("4. Đã tạo AuthKey.")
            
            log("5. Đang gửi yêu cầu Ajax với episodeId=${episodeData.episodeId}...")
            val res = app.post(
                "$mainUrl/ajax/player",
                headers = mapOf("Authorization" to authKey, "Referer" to "$mainUrl/"),
                data = mapOf("episode" to episodeData.episodeId, "filmId" to episodeData.filmId)
            ).parsed<PlayerResponse>()
            log("6. Phản hồi từ server: ${res.player?.take(100)}...")

            val iframeSrc = res.player?.let { Regex("""src="(.*?)"""").find(it)?.groupValues?.get(1) } 
                ?: throw Exception("Không trích xuất được link iframe")
            log("7. Đã lấy được link iframe: $iframeSrc")
            
            log("8. Đang gọi Extractor...")
            val success = loadExtractor(iframeSrc, "$mainUrl/", subtitleCallback, callback)

            if (!success) {
                throw Exception("loadExtractor trả về false. Host video không được hỗ trợ hoặc link đã hết hạn.")
            }
            return true

        } catch (e: Exception) {
            throw RuntimeException(logBuilder.toString() + "\nLỖI: " + e.message)
        }
    }
    
    data class EpisodeData(
        val filmId: String,
        @JsonProperty("episodeld")
        val episodeId: String,
        val token: String,
        val socket: String
    )
    
    data class PlayerResponse(
        @JsonProperty("player") val player: String?
    )
}
