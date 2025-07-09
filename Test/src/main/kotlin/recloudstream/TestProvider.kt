package recloudstream

import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.loadExtractor
import org.jsoup.nodes.Element
import android.util.Base64
import android.util.Log
// Thêm các import cần thiết cho Toast
import android.widget.Toast
import com.lagradost.cloudstream3.MainActivity
import com.lagradost.cloudstream3.utils.Coroutines.main

class SextbProvider : MainAPI() {
    override var name = "Sextb"
    override var mainUrl = "https://sextb.net"
    override var lang = "en"
    override val supportedTypes = setOf(TvType.Movie)
    override val hasMainPage = true

    private val logTag = "SextbProvider"

    override val mainPage = mainPageOf(
        "" to "Home",
        "censored" to "Censored",
        "uncensored" to "Uncensored",
        "subtitle" to "Subtitle",
        "amateur" to "Amateur"
    )

    // Hàm tiện ích để hiển thị Toast
    private fun showToast(message: String, duration: Int = Toast.LENGTH_SHORT) {
        main {
            Toast.makeText(MainActivity.activity, message, duration).show()
        }
    }

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

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        try {
            showToast("Bắt đầu lấy link...")
            Log.d(logTag, "loadLinks started with data: $data")

            val document = app.get(data).document
            showToast("Đã tải trang phim thành công")

            val filmId = document.selectFirst("script:containsData(filmId)")?.data()
                ?.let { Regex("""filmId\s*=\s*['"]?(\d+)['"]?""").find(it)?.groupValues?.get(1) }
                ?: run {
                    showToast("Lỗi: Không tìm thấy filmId", Toast.LENGTH_LONG)
                    return false
                }
            showToast("Đã tìm thấy filmId: $filmId")

            val token = document.selectFirst("meta[name=_token]")?.attr("value") ?: run {
                showToast("Lỗi: Không tìm thấy token", Toast.LENGTH_LONG)
                return false
            }

            val socket = document.selectFirst("meta[name=_socket]")?.attr("value") ?: run {
                showToast("Lỗi: Không tìm thấy socket", Toast.LENGTH_LONG)
                return false
            }
            showToast("Đã tìm thấy token & socket")

            val episodeIndex = "0"
            val authKey = "Basic " + Base64.encodeToString(("$token:$socket").toByteArray(), Base64.NO_WRAP)
            showToast("Đã tạo AuthKey")
            Log.d(logTag, "Generated AuthKey: $authKey")

            val referer = data
            
            showToast("Đang gửi yêu cầu lấy link...")
            val res = app.post(
                "$mainUrl/ajax/player",
                headers = mapOf("Authorization" to authKey, "Referer" to referer),
                data = mapOf("episode" to episodeIndex, "filmId" to filmId)
            ).parsed<PlayerResponse>()
            Log.d(logTag, "POST response received: ${res.player}")

            val iframeSrc = res.player?.let { Regex("""src="(.*?)"""").find(it)?.groupValues?.get(1) } ?: run {
                showToast("Lỗi: Không lấy được iframe từ phản hồi", Toast.LENGTH_LONG)
                return false
            }
            showToast("Đã lấy được link iframe: $iframeSrc")
            
            return loadExtractor(iframeSrc, referer, subtitleCallback, callback)

        } catch (e: Exception) {
            showToast("Lỗi nghiêm trọng: ${e.message}", Toast.LENGTH_LONG)
            Log.e(logTag, "Exception in loadLinks: ${e.message}")
            e.printStackTrace()
            return false
        }
    }
    
    data class PlayerResponse(
        @JsonProperty("player") val player: String?
    )
}
