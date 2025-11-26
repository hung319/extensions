package recloudstream

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.Jsoup
import org.jsoup.nodes.Element

class AnikotoProvider : MainAPI() { // Đã đổi tên class khớp với Plugin
    override var mainUrl = "https://anikoto.tv"
    override var name = "Anikoto"
    override val hasMainPage = true
    override var lang = "en"
    override val supportedTypes = setOf(TvType.Anime, TvType.Movie, TvType.OVA)

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

        // Lấy thông tin sub/dub ra ngoài
        val subText = this.selectFirst(".ep-status.sub span")?.text()
        val dubText = this.selectFirst(".ep-status.dub span")?.text()

        return newAnimeSearchResponse(title, fixUrl(href)) {
            this.posterUrl = posterUrl
            // FIX LỖI TYPE MISMATCH: Chỉ addQuality nếu text không null
            if (!subText.isNullOrEmpty()) {
                addQuality("Sub $subText")
            }
            if (!dubText.isNullOrEmpty()) {
                addQuality("Dub $dubText")
            }
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
        
        // FIX LỖI DEPRECATED RATING
        // Rating được xử lý an toàn, nếu null thì bỏ qua
        val ratingText = doc.selectFirst(".meta .rating")?.text()

        val dataId = doc.selectFirst("#watch-main")?.attr("data-id")
            ?: throw ErrorLoadingException("Could not find Anime ID")

        val ajaxUrl = "$mainUrl/ajax/episode/list/$dataId"
        
        val jsonResponse = app.get(ajaxUrl, headers = mapOf("X-Requested-With" to "XMLHttpRequest"))
                              .parsedSafe<AjaxResponse>() 
                              ?: throw ErrorLoadingException("Failed to fetch episodes JSON")

        val episodesDoc = Jsoup.parse(jsonResponse.result)
        
        val episodes = episodesDoc.select("ul.ep-range li a").mapNotNull { element ->
            val epName = element.select("span.d-title").text() ?: "Episode ${element.attr("data-num")}"
            val epNum = element.attr("data-num").toFloatOrNull() ?: 1f
            
            val epIds = element.attr("data-ids")
            if (epIds.isBlank()) return@mapNotNull null

            val epUrl = "$mainUrl/ajax/episode/servers?episodeId=$epIds"

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
            // Sử dụng toRatingInt nhưng chấp nhận nó deprecated vì nó tương thích ngược tốt nhất
            // Hoặc đơn giản là ép kiểu an toàn
            this.rating = ratingText.toRatingInt() 
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
        val json = app.get(data, headers = mapOf("X-Requested-With" to "XMLHttpRequest"))
                      .parsedSafe<ServerResponse>()
        
        val serverHtml = json?.html ?: return false
        val doc = Jsoup.parse(serverHtml)

        doc.select(".server-item").forEach { server ->
            val serverId = server.attr("data-id")
            val serverName = server.text()

            val sourceUrl = "$mainUrl/ajax/episode/sources?id=$serverId"
            val sourceJson = app.get(sourceUrl, headers = mapOf("X-Requested-With" to "XMLHttpRequest"))
                                .parsedSafe<SourceResponse>()

            val embedUrl = sourceJson?.link ?: return@forEach

            loadExtractor(embedUrl, serverName, subtitleCallback, callback)
        }

        return true
    }
}
