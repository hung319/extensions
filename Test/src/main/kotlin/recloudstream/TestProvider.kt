package recloudstream

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.Jsoup
import org.jsoup.nodes.Element

class AnikotoProvider : MainAPI() {
    override var mainUrl = "https://anikoto.tv"
    override var name = "Anikoto"
    override val hasMainPage = true
    override var lang = "en"
    override val supportedTypes = setOf(TvType.Anime, TvType.Movie, TvType.OVA)

    private val ajaxHeaders = mapOf(
        "X-Requested-With" to "XMLHttpRequest",
        "Referer" to "$mainUrl/"
    )

    // Data class đơn giản để parse list server ban đầu
    data class AjaxResponse(val status: Int, val result: String)

    // ... (Các hàm toSearchResult, getMainPage, search, load GIỮ NGUYÊN code cũ của bạn) ...
    // Bạn có thể copy lại phần trên từ code cũ, quan trọng là phần loadLinks dưới đây:

    private fun Element.toSearchResult(): SearchResponse? {
        val href = this.selectFirst("a")?.attr("href") ?: return null
        val title = this.selectFirst(".name.d-title")?.text() 
                 ?: this.selectFirst(".name")?.text() ?: "Unknown"
        val posterUrl = this.selectFirst("img")?.attr("src")
        return newAnimeSearchResponse(title, fixUrl(href)) {
            this.posterUrl = posterUrl
        }
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val doc = app.get("$mainUrl/home").document
        val recent = doc.select("#recent-update .ani.items .item").mapNotNull { it.toSearchResult() }
        return newHomePageResponse(listOf(HomePageList("Recent", recent)), false)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val url = "$mainUrl/filter?keyword=$query"
        val doc = app.get(url).document
        return doc.select("div.ani.items > div.item").mapNotNull { it.toSearchResult() }
    }

    override suspend fun load(url: String): LoadResponse {
        val doc = app.get(url).document
        val title = doc.selectFirst("h1.title.d-title")?.text() ?: "Unknown"
        val dataId = doc.selectFirst("#watch-main")?.attr("data-id") ?: throw ErrorLoadingException("No ID")
        
        val ajaxUrl = "$mainUrl/ajax/episode/list/$dataId"
        val json = app.get(ajaxUrl, headers = ajaxHeaders).parsedSafe<AjaxResponse>()
        val epDoc = Jsoup.parse(json?.result ?: "")
        
        val episodes = epDoc.select("ul.ep-range li a").mapNotNull { 
            val epIds = it.attr("data-ids")
            if(epIds.isBlank()) return@mapNotNull null
            newEpisode("$mainUrl/ajax/server/list?servers=$epIds") {
                this.name = "Ep ${it.attr("data-num")}"
            }
        }
        
        return newTvSeriesLoadResponse(title, url, TvType.Anime, episodes)
    }

    // --- HÀM DEBUG QUAN TRỌNG ---
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        // 1. Lấy list server
        val json = app.get(data, headers = ajaxHeaders).parsedSafe<AjaxResponse>()
        val serverHtml = json?.result ?: throw ErrorLoadingException("Không lấy được HTML server list")
        val doc = Jsoup.parse(serverHtml)
        
        // 2. Lấy server đầu tiên tìm thấy để test
        val firstServer = doc.select(".servers .type li").firstOrNull() 
            ?: throw ErrorLoadingException("Không tìm thấy server nào trong HTML")

        val linkId = firstServer.attr("data-link-id")
        val serverName = firstServer.text()

        // 3. Gọi API resolve link
        // Thử endpoint: /ajax/server?get={linkId} (Dựa trên log cũ)
        val resolveUrl = "$mainUrl/ajax/server?get=$linkId"
        
        // 4. Lấy RAW TEXT về
        val responseText = app.get(resolveUrl, headers = ajaxHeaders).text

        // 5. IN RA MÀN HÌNH (Throw Exception để hiện popup đỏ)
        // Chúng ta sẽ in URL đã gọi và nội dung trả về
        throw ErrorLoadingException(
            """
            === DEBUG RESPONSE ===
            Server: $serverName
            URL: $resolveUrl
            ----------------------
            CONTENT:
            $responseText
            """.trimIndent()
        )
    }
}
