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

    data class AjaxResponse(val status: Int, val result: String)
    data class ServerResponse(val status: Int, val result: ServerResult?)
    data class ServerResult(val url: String?)

    private fun Element.toSearchResult(): SearchResponse? {
        val href = this.selectFirst("a")?.attr("href") ?: return null
        val title = this.selectFirst(".name.d-title")?.text() 
                 ?: this.selectFirst(".name")?.text() ?: "Unknown"
        val posterUrl = this.selectFirst("img")?.attr("src")
        val subText = this.selectFirst(".ep-status.sub span")?.text()
        val dubText = this.selectFirst(".ep-status.dub span")?.text()

        return newAnimeSearchResponse(title, fixUrl(href)) {
            this.posterUrl = posterUrl
            if (!subText.isNullOrEmpty()) addQuality("Sub $subText")
            if (!dubText.isNullOrEmpty()) addQuality("Dub $dubText")
        }
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val doc = app.get("$mainUrl/home").document
        val hotest = doc.select(".swiper-slide.item").mapNotNull { element ->
            val title = element.selectFirst(".title.d-title")?.text() ?: return@mapNotNull null
            val href = element.selectFirst("a.btn.play")?.attr("href") ?: return@mapNotNull null
            val bgImage = element.selectFirst(".image div")?.attr("style")?.substringAfter("url('")?.substringBefore("')")
            newAnimeSearchResponse(title, fixUrl(href)) { this.posterUrl = bgImage }
        }
        val recent = doc.select("#recent-update .ani.items .item").mapNotNull { it.toSearchResult() }
        val newRelease = doc.select("section[data-name='new-release'] .item").mapNotNull { 
            val title = it.selectFirst(".name")?.text() ?: return@mapNotNull null
            val href = it.attr("href")
            newAnimeSearchResponse(title, fixUrl(href)) { this.posterUrl = it.selectFirst("img")?.attr("src") }
        }
        return newHomePageResponse(listOf(HomePageList("Hot", hotest), HomePageList("Recently Updated", recent), HomePageList("New Release", newRelease)), hasNext = false)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val url = "$mainUrl/filter?keyword=$query"
        val doc = app.get(url).document
        return doc.select("div.ani.items > div.item").mapNotNull { it.toSearchResult() }
    }

    override suspend fun load(url: String): LoadResponse {
        val doc = app.get(url).document
        val title = doc.selectFirst("h1.title.d-title")?.text() ?: "Unknown"
        val description = doc.selectFirst(".synopsis .content")?.text()
        val poster = doc.selectFirst(".binfo .poster img")?.attr("src")
        val ratingText = doc.selectFirst(".meta .rating")?.text()
        val dataId = doc.selectFirst("#watch-main")?.attr("data-id") ?: throw ErrorLoadingException("Could not find Anime ID")
        
        val ajaxUrl = "$mainUrl/ajax/episode/list/$dataId"
        val jsonResponse = app.get(ajaxUrl, headers = ajaxHeaders).parsedSafe<AjaxResponse>() ?: throw ErrorLoadingException("Failed to fetch episodes JSON")
        val episodesDoc = Jsoup.parse(jsonResponse.result)
        
        val episodes = episodesDoc.select("ul.ep-range li a").mapNotNull { element ->
            val epName = element.select("span.d-title").text() ?: "Episode ${element.attr("data-num")}"
            val epNum = element.attr("data-num").toFloatOrNull() ?: 1f
            val epIds = element.attr("data-ids")
            if (epIds.isBlank()) return@mapNotNull null
            
            val epUrl = "$mainUrl/ajax/server/list?servers=$epIds"
            val isSub = element.attr("data-sub") == "1"
            val isDub = element.attr("data-dub") == "1"
            val typeInfo = if (isSub && isDub) "[Sub/Dub]" else if (isDub) "[Dub]" else ""
            
            newEpisode(epUrl) {
                this.name = if(typeInfo.isNotEmpty()) "$epName $typeInfo" else epName
                this.episode = epNum.toInt()
            }
        }
        return newTvSeriesLoadResponse(title, url, TvType.Anime, episodes) {
            this.posterUrl = poster
            this.plot = description
            val ratingNum = ratingText?.toDoubleOrNull()
            if (ratingNum != null) { this.score = Score.from10(ratingNum) }
            val recommendations = doc.select("#continue-watching .item, #top-anime .item").mapNotNull { it.toSearchResult() }
            this.recommendations = recommendations
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        // 1. Lấy HTML danh sách server
        val json = app.get(data, headers = ajaxHeaders).parsedSafe<AjaxResponse>()
        val serverHtml = json?.result ?: return false
        val doc = Jsoup.parse(serverHtml)

        // 2. Chuẩn bị danh sách task để chạy song song
        // Thu thập tất cả thẻ li có data-link-id
        val tasks = doc.select(".servers .type li").mapNotNull { server ->
            val linkId = server.attr("data-link-id")
            if (linkId.isBlank()) return@mapNotNull null
            
            val serverName = server.text()
            val type = server.parent()?.parent()?.attr("data-type") ?: "sub"
            
            // Trả về bộ 3 dữ liệu để xử lý
            Triple(linkId, serverName, type)
        }

        // 3. Chạy song song (Parallel Execution) dùng apmap
        // apmap (Async Parallel Map) giúp gọi tất cả API cùng lúc thay vì chờ từng cái
        tasks.apmap { (linkId, serverName, type) ->
            try {
                val resolveUrl = "$mainUrl/ajax/server?get=$linkId"
                
                // Gọi API giải mã
                val responseText = app.get(resolveUrl, headers = ajaxHeaders).text
                
                // Kiểm tra nếu không phải HTML lỗi
                if (!responseText.trim().startsWith("<")) {
                    val linkJson = AppUtils.parseJson<ServerResponse>(responseText)
                    val embedUrl = linkJson.result?.url
                    
                    if (!embedUrl.isNullOrBlank()) {
                        val safeServerName = "$serverName ($type)"
                        
                        // Load extractor (Cloudstream tự động xử lý lỗi nếu server chết)
                        loadExtractor(embedUrl, safeServerName, subtitleCallback, callback)
                    }
                }
            } catch (e: Exception) {
                // Nếu 1 server lỗi (VD: MegaPlay), catch block này sẽ giữ cho app không crash
                // và các server khác (Vidstream) vẫn chạy bình thường.
                e.printStackTrace()
            }
        }

        return true
    }
}
