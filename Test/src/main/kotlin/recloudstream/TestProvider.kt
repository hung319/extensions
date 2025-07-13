package recloudstream

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.network.CloudflareKiller
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import org.jsoup.nodes.Element

class AnimetmProvider : MainAPI() {
    override var name = "AnimeTM"
    override var mainUrl = "https://animetm.tv"
    override var lang = "vi"
    override val hasMainPage = true
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(
        TvType.Anime,
        TvType.AnimeMovie,
    )

    private val killer = CloudflareKiller()

    private fun Element.toSearchResult(): SearchResponse? {
        val link = this.selectFirst("a.film-poster-ahref") ?: return null
        val href = fixUrl(link.attr("href"))
        val title = link.attr("title")
        val posterUrl = this.selectFirst("img.film-poster-img")?.attr("data-src")

        return newAnimeSearchResponse(title, href, TvType.Anime) {
            this.posterUrl = posterUrl
        }
    }

    private suspend fun getPage(url: String): List<SearchResponse> {
        val document = app.get(url, interceptor = killer).document
        return document.select("div.flw-item").mapNotNull {
            it.toSearchResult()
        }
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val home = getPage("$mainUrl/moi-cap-nhat?page=$page")
        return newHomePageResponse("Phim Mới Cập Nhật", home)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val searchUrl = "$mainUrl/search?keysearch=$query"
        return getPage(searchUrl)
    }

    override suspend fun load(url: String): LoadResponse {
        val document = app.get(url, interceptor = killer).document
        val title = document.selectFirst("h2.film-name")?.text()?.trim() ?: "Không rõ"
        val poster = document.selectFirst("div.anisc-poster img")?.attr("src")
        val plot = document.selectFirst("div.film-description > div.text")?.text()?.trim()

        val episodePageUrl = document.selectFirst("a.btn-play")?.attr("href")

        return newAnimeLoadResponse(title, url, TvType.Anime) {
            this.posterUrl = poster
            this.plot = plot

            if (episodePageUrl != null) {
                val episodeListDocument = app.get(episodePageUrl, interceptor = killer).document
                
                val episodes = episodeListDocument.select("div.ep-range a.ssl-item").map {
                    val episodeNumber = it.attr("title")
                    newEpisode(it.attr("href")) {
                        name = "Tập $episodeNumber"
                    }
                }.reversed()
                
                addEpisodes(DubStatus.Subbed, episodes)
            }

            // *** ĐÃ THÊM DANH SÁCH PHIM ĐỀ XUẤT ***
            recommendations = document.select("section.block_area_category div.flw-item").mapNotNull {
                it.toSearchResult()
            }
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val episodePage = app.get(data, interceptor = killer).document
        val scriptContent = episodePage.select("script").html()
        
        val iframeSrc = Regex("""checkLink\d*\s*=\s*"([^"]+)"""")
            .findAll(scriptContent)
            .map { it.groupValues[1] }
            .firstOrNull { it.contains("abyss-cdn") }

        if (iframeSrc != null && iframeSrc.isNotBlank()) {
            val m3u8Url = "$iframeSrc/master.m3u8"
            callback.invoke(
                ExtractorLink(
                    source = this.name,
                    name = "Server Abyss",
                    url = m3u8Url,
                    referer = "$mainUrl/",
                    quality = Qualities.P1080.value,
                    type = ExtractorLinkType.M3U8
                )
            )
            return true
        }
        return false
    }
}
