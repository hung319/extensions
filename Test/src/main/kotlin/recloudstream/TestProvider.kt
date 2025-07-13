package recloudstream

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.Qualities
// Thêm import cụ thể cho ExtractorLinkType
import com.lagradost.cloudstream3.utils.ExtractorLinkType
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

    private fun Element.toSearchResult(): SearchResponse {
        val link = this.selectFirst("a.film-item-link")
        val href = fixUrl(link?.attr("href").toString())
        val title = link?.attr("title").toString()
        val posterUrl = this.selectFirst("img.film-item-img")?.attr("src")
        
        return newAnimeSearchResponse(title, href, TvType.Anime) {
            this.posterUrl = posterUrl
        }
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val document = app.get("$mainUrl/danh-sach?page=$page").document
        val home = document.select("div.film-item").map {
            it.toSearchResult()
        }
        return newHomePageResponse("Phim Mới Cập Nhật", home)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val searchUrl = "$mainUrl/tim-kiem?keyword=$query"
        val document = app.get(searchUrl).document
        return document.select("div.film-item").map {
            it.toSearchResult()
        }
    }

    override suspend fun load(url: String): LoadResponse {
        val document = app.get(url).document
        val title = document.selectFirst("h1.film-title")?.text()?.trim() ?: "Không rõ"

        return newAnimeLoadResponse(title, url, TvType.Anime) {
            posterUrl = document.selectFirst("img.film-thumbnail-img")?.attr("src")
            plot = document.selectFirst("div.film-description")?.text()?.trim()

            val episodes = document.select("div.episode-item a").map {
                newEpisode(it.attr("href")) {
                    name = it.attr("title")
                }
            }.reversed()

            addEpisodes(DubStatus.Subbed, episodes)
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val episodePage = app.get(data).document
        val iframeSrc = episodePage.selectFirst("div#player-wrapper iframe")?.attr("src") 
            ?: return false

        val m3u8Url = "$iframeSrc/master.m3u8"

        callback.invoke(
            ExtractorLink(
                source = this.name,
                name = "Server Abyss",
                url = m3u8Url,
                referer = "$mainUrl/",
                quality = Qualities.P1080.value,
                // Sử dụng ExtractorLinkType sau khi đã import
                type = ExtractorLinkType.M3U8 
            )
        )
        return true
    }
}
