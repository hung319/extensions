package recloudstream

import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.loadExtractor
import org.jsoup.nodes.Element
import com.lagradost.cloudstream3.Actor
import com.lagradost.cloudstream3.ActorData

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
        val url = if (page == 1) {
            "$mainUrl/${request.data}"
        } else {
            val pageType = if(request.data.isBlank()) "page" else "${request.data}/page"
            "$mainUrl/$pageType/$page"
        }
        val document = app.get(url, referer = "$mainUrl/").document
        val home = document.select("div.tray-item").mapNotNull {
            it.toSearchResult()
        }
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
        return document.select("div.tray-item").mapNotNull {
            it.toSearchResult()
        }
    }

    //
    // HÀM `load` ĐÃ ĐƯỢC ĐƠN GIẢN HÓA
    //
    override suspend fun load(url: String): LoadResponse? {
        val document = app.get(url).document
        val title = document.selectFirst("h1.film-info-title")?.text()?.trim() ?: return null
        
        val poster = document.selectFirst("div.covert img")?.attr("data-src")
        
        val cast = document.select("div.description:contains(Cast) a").map { actorElement ->
            val actorName = actorElement.text()
            ActorData(Actor(name = actorName), roleString = "Actor")
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
        
        // Không cần tạo danh sách episodes ở đây nữa.
        // Thay vào đó, chúng ta truyền thẳng url của phim cho dataUrl.
        // CloudStream sẽ tự tạo một nút Play và truyền url này vào loadLinks.
        return newMovieLoadResponse(title, url, TvType.Movie, url) {
            this.posterUrl = poster
            this.year = year
            this.plot = plot
            this.tags = tags
            this.actors = cast
            this.recommendations = recommendations
        }
    }

    //
    // HÀM `loadLinks` GIỜ SẼ CHỨA TOÀN BỘ LOGIC LẤY LINK
    //
    override suspend fun loadLinks(
        data: String, // `data` bây giờ là URL của phim, vd: https://sextb.net/hawa-096-rm
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        // Tải lại trang chi tiết phim từ `data` (chính là url)
        val document = app.get(data).document

        // Trích xuất filmId từ trang
        val filmId = document.selectFirst("script:containsData(filmId)")?.data()
            ?.let { scriptData ->
                Regex("""filmId\s*=\s*['"]?(\d+)['"]?""").find(scriptData)?.groupValues?.get(1)
            } ?: return false // Nếu không có filmId, thoát

        // Tìm server đầu tiên có sẵn
        val firstServer = document.select("div.episode-list button.episode").firstOrNull()
            ?: throw RuntimeException("No free servers found") // Nếu không có server nào, báo lỗi

        // Lấy vị trí (index) của server đầu tiên, luôn là 0
        val episodeIndex = "0"
        
        val referer = data // Dùng chính url của phim làm referer

        val res = app.post(
            "$mainUrl/ajax/player",
            headers = mapOf(
                "Authorization" to "Basic WW5jMVdVbzNNM0JOYkhOeE1rbHZUV2wxWmt4Vlp6MDk6VWtOaGJHOXRORGgxUjBnMVNIcDVURGM0V2tOMVVUMDk=",
                "Referer" to referer
            ),
            data = mapOf(
                "episode" to episodeIndex,
                "filmId" to filmId
            )
        ).parsed<PlayerResponse>()

        val iframeSrc = res.player?.let {
            Regex("""src="(.*?)"""").find(it)?.groupValues?.get(1)
        } ?: return false
        
        return loadExtractor(iframeSrc, referer, subtitleCallback, callback)
    }

    data class PlayerResponse(
        @JsonProperty("player") val player: String?
    )
}
