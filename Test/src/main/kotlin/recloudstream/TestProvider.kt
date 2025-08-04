// Desription: провайдер для сайта VeoHentai
// Date: 2025-08-05
// Version: 1.8
// Author: Coder

package recloudstream

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Element
import java.net.URI

class VeoHentaiProvider : MainAPI() {
    override var mainUrl = "https://veohentai.com"
    override var name = "VeoHentai"
    override val hasMainPage = true
    override var lang = "es"
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(
        TvType.NSFW
    )

    // ============================ HOMEPAGE & SEARCH ============================
    override val mainPage = mainPageOf(
        "/" to "Episodios Recientes",
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val document = app.get("$mainUrl${request.data}/page/$page/").document
        val home = document.select("div#posts-home a").mapNotNull {
            it.toSearchResult()
        }
        return newHomePageResponse(request.name, home)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val searchResponse = app.get("$mainUrl/?s=$query").document
        return searchResponse.select("div.grid a").mapNotNull {
            it.toSearchResult()
        }
    }

    private fun Element.toSearchResult(): AnimeSearchResponse? {
        val href = this.attr("href")
        if (href.isEmpty()) return null

        val title = this.selectFirst("h2")?.text() ?: return null
        val posterUrl = this.selectFirst("figure img")?.attr("src")

        return newAnimeSearchResponse(title, href, TvType.NSFW) {
            this.posterUrl = posterUrl
            this.quality = SearchQuality.HD
        }
    }

    // ======================= LOAD EPISODE/MOVIE INFO (UPDATED LOGIC) =======================
    override suspend fun load(url: String): LoadResponse {
        val document = app.get(url).document

        val title = document.selectFirst("meta[property=og:title]")?.attr("content")?.replace("Ver ","")?.replace(" - Ver Hentai en Español","")?.trim()
            ?: throw Error("Could not find title")
        
        val poster = document.selectFirst("meta[property=og:image]")?.attr("content")
        val tags = document.select("meta[property=article:tag]").map { it.attr("content") }
        val description = document.selectFirst("meta[property=og:description]")?.attr("content")

        // SỬA LỖI: Quay lại dùng newMovieLoadResponse và chỉ định đúng TvType.NSFW
        return newMovieLoadResponse(title, url, TvType.NSFW, url) {
            this.posterUrl = poster
            this.plot = description
            this.tags = tags
        }
    }

    // ============================ LOAD VIDEO LINKS (UPDATED SELECTOR) ============================
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val watchPageDoc = app.get(data).document
        
        val embedUrl = watchPageDoc.selectFirst("div[class*=ratio] iframe, main iframe[src]")?.attr("src")
            ?: throw ErrorLoadingException("Could not find embed iframe")

        val embedPageDoc = app.get(embedUrl).document
        val playerPath = embedPageDoc.selectFirst("div.servers li")?.attr("data-id")
            ?: throw ErrorLoadingException("Could not find player path in data-id")

        val embedUri = URI(embedUrl)
        val playerUrl = "${embedUri.scheme}://${embedUri.host}$playerPath"

        val playerPageContent = app.get(playerUrl).text
        val m3u8Url = Regex("""sources:\s*\[\s*\{\s*file:\s*"(.*?)"""").find(playerPageContent)?.groupValues?.get(1)
            ?: throw ErrorLoadingException("Could not extract m3u8 link")

        callback.invoke(
            ExtractorLink(
                source = this.name,
                name = this.name,
                url = m3u8Url,
                referer = mainUrl,
                quality = Qualities.Unknown.value,
                type = ExtractorLinkType.VIDEO
            )
        )

        return true
    }
}
