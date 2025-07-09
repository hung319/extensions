package recloudstream

import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import com.lagradost.cloudstream3.utils.AppUtils.toJson
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
    
    private fun RelatedAjaxItem.toSearchResponse(): SearchResponse {
        val slug = this.guid.ifEmpty { this.code }
        return newMovieSearchResponse(this.name, "$mainUrl/$slug", TvType.Movie) {
            posterUrl = this.poster
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val document = app.get("$mainUrl/search/$query").document
        return document.select("div.tray-item").mapNotNull { it.toSearchResult() }
    }
    
    override suspend fun load(url: String): LoadResponse? {
        val document = app.get(url).document
        val title = document.selectFirst("h1.film-info-title")?.text()?.trim() ?: return null
        
        // Trích xuất các thông tin cần thiết từ trang
        val filmId = document.selectFirst("script:containsData(filmId)")?.data()
            ?.let { Regex("""filmId\s*=\s*['"]?(\d+)['"]?""").find(it)?.groupValues?.get(1) } ?: return null
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

        // Tạo Authorization header động
        val authKey = "Basic " + Base64.encodeToString(("$token:$socket").toByteArray(), Base64.NO_WRAP)
        
        // Lấy phim liên quan bằng Ajax
        val recommendations = app.post(
            "$mainUrl/ajax/relatedAjax",
             headers = mapOf("Authorization" to authKey, "Referer" to url),
             data = mapOf("pg" to "1", "filmId" to filmId)
        ).parsed<List<RelatedAjaxItem>>().map { it.toSearchResponse() }

        // Tạo danh sách server (episode)
        val episodes = document.select("div.episode-list button.episode").mapIndexedNotNull { index, it ->
            val serverName = it.text().trim()
            val episodeData = EpisodeData(filmId, index.toString(), token, socket)
            newEpisode(episodeData.toJson()) { // Chuyển data class thành chuỗi JSON
                name = serverName
            }
        }
        
        if (episodes.isEmpty()) throw RuntimeException("No free servers found for this movie")

        return newMovieLoadResponse(title, url, TvType.Movie, episodes) {
            this.posterUrl = poster
            this.year = year
            this.plot = plot
            this.tags = tags
            this.actors = cast
            this.recommendations = recommendations
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        // Giải mã chuỗi JSON để lấy lại thông tin
        val episodeData = parseJson<EpisodeData>(data)
        
        // Tạo lại Authorization header động
        val authKey = "Basic " + Base64.encodeToString(("${episodeData.token}:${episodeData.socket}").toByteArray(), Base64.NO_WRAP)
        val referer = "$mainUrl/anything"

        val res = app.post(
            "$mainUrl/ajax/player",
            headers = mapOf("Authorization" to authKey, "Referer" to referer),
            data = mapOf("episode" to episodeData.episodeIndex, "filmId" to episodeData.filmId)
        ).parsed<PlayerResponse>()

        val iframeSrc = res.player?.let { Regex("""src="(.*?)"""").find(it)?.groupValues?.get(1) } ?: return false
        
        return loadExtractor(iframeSrc, referer, subtitleCallback, callback)
    }

    // Data class để truyền dữ liệu giữa `load` và `loadLinks`
    data class EpisodeData(
        val filmId: String,
        val episodeIndex: String,
        val token: String,
        val socket: String
    )
    
    data class RelatedAjaxItem(
        @JsonProperty("id") val id: String,
        @JsonProperty("code") val code: String,
        @JsonProperty("guid") val guid: String,
        @JsonProperty("name") val name: String,
        @JsonProperty("poster") val poster: String
    )
    
    data class PlayerResponse(
        @JsonProperty("player") val player: String?
    )
}
