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

    // Data class để parse JSON response từ API episode list
    data class AjaxResponse(
        val status: Int,
        val result: String
    )

    data class ServerResponse(
        val status: Int,
        val html: String
    )

    data class SourceResponse(
        val type: String?,
        val link: String?
    )

    private fun Element.toSearchResult(): SearchResponse? {
        val href = this.selectFirst("a")?.attr("href") ?: return null
        val title = this.selectFirst(".name.d-title")?.text() 
                 ?: this.selectFirst(".name")?.text() ?: "Unknown"
        val posterUrl = this.selectFirst("img")?.attr("src")

        return newAnimeSearchResponse(title, fixUrl(href)) {
            this.posterUrl = posterUrl
            addQuality(this.selectFirst(".ep-status.sub span")?.text()?.let { "Sub $it" })
            addQuality(this.selectFirst(".ep-status.dub span")?.text()?.let { "Dub $it" })
        }
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val doc = app.get("$mainUrl/home").document
        
        val hotest = doc.select(".swiper-slide.item").mapNotNull { element ->
            val title = element.selectFirst(".title.d-title")?.text() ?: return@mapNotNull null
            val href = element.selectFirst("a.btn.play")?.attr("href") ?: return@mapNotNull null
            val bgImage = element.selectFirst(".image div")?.attr("style")
                ?.substringAfter("url('")?.substringBefore("')")
            
            newAnimeSearchResponse(title, fixUrl(href)) {
                this.posterUrl = bgImage
            }
        }

        val recent = doc.select("#recent-update .ani.items .item").mapNotNull { it.toSearchResult() }
        val newRelease = doc.select("section[data-name='new-release'] .item").mapNotNull { 
            val title = it.selectFirst(".name")?.text() ?: return@mapNotNull null
            val href = it.attr("href")
            newAnimeSearchResponse(title, fixUrl(href)) {
                this.posterUrl = it.selectFirst("img")?.attr("src")
            }
        }

        return newHomePageResponse(
            listOf(
                HomePageList("Hot", hotest),
                HomePageList("Recently Updated", recent),
                HomePageList("New Release", newRelease)
            ), 
            hasNext = false
        )
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
        val rating = doc.selectFirst(".meta .rating")?.text()
        
        // Lấy data-id từ HTML gốc
        val dataId = doc.selectFirst("#watch-main")?.attr("data-id")
            ?: throw ErrorLoadingException("Could not find Anime ID")

        // 1. Gọi API lấy list tập
        val ajaxUrl = "$mainUrl/ajax/episode/list/$dataId"
        
        // 2. Parse JSON response
        val jsonResponse = app.get(ajaxUrl, headers = mapOf("X-Requested-With" to "XMLHttpRequest"))
                              .parsedSafe<AjaxResponse>() 
                              ?: throw ErrorLoadingException("Failed to fetch episodes JSON")

        // 3. Parse HTML bên trong JSON "result"
        val episodesDoc = Jsoup.parse(jsonResponse.result)
        
        // 4. Select thẻ a trong ul.ep-range và lấy 'data-ids'
        val episodes = episodesDoc.select("ul.ep-range li a").mapNotNull { element ->
            val epName = element.select("span.d-title").text() ?: "Episode ${element.attr("data-num")}"
            val epNum = element.attr("data-num").toFloatOrNull() ?: 1f
            
            val epIds = element.attr("data-ids")
            if (epIds.isBlank()) return@mapNotNull null

            // Link ảo chứa data-ids
            val epUrl = "$mainUrl/ajax/episode/servers?episodeId=$epIds"

            newEpisode(epUrl) {
                this.name = epName
                this.episode = epNum.toInt()
                val isSub = element.attr("data-sub") == "1"
                val isDub = element.attr("data-dub") == "1"
                this.scanlator = if (isSub && isDub) "Sub & Dub" else if (isDub) "Dub" else "Sub"
            }
        }

        return newTvSeriesLoadResponse(title, url, TvType.Anime, episodes) {
            this.posterUrl = poster
            this.plot = description
            this.rating = rating.toRatingInt()
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
        // data = "$mainUrl/ajax/episode/servers?episodeId=$epIds"
        
        // 1. Gọi API lấy danh sách server (trả về JSON { html: "..." })
        val json = app.get(data, headers = mapOf("X-Requested-With" to "XMLHttpRequest"))
                      .parsedSafe<ServerResponse>()
        
        val serverHtml = json?.html ?: return false
        val doc = Jsoup.parse(serverHtml)

        // 2. Loop qua các server
        doc.select(".server-item").forEach { server ->
            val serverId = server.attr("data-id")
            val serverName = server.text()

            // 3. Gọi API lấy Source (Embed Link)
            val sourceUrl = "$mainUrl/ajax/episode/sources?id=$serverId"
            val sourceJson = app.get(sourceUrl, headers = mapOf("X-Requested-With" to "XMLHttpRequest"))
                                .parsedSafe<SourceResponse>()

            val embedUrl = sourceJson?.link ?: return@forEach

            // 4. Load Extractor
            loadExtractor(embedUrl, serverName, subtitleCallback, callback)
        }

        return true
    }
}
