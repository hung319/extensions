// Desription: провайдер для сайта VeoHentai
// Date: 2025-08-04
// Version: 1.2
// Author: Coder

package recloudstream

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.* // ExtractorLinkType is already included here
import org.jsoup.nodes.Element

class VeoHentaiProvider : MainAPI() {
    override var mainUrl = "https://veohentai.com"
    override var name = "VeoHentai"
    override val hasMainPage = true
    override var lang = "es"
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(
        TvType.NSFW
    )

    // ============================ HOMEPAGE ============================
    override val mainPage = mainPageOf(
        "/" to "Episodios Recientes",
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val document = app.get("$mainUrl${request.data}/page/$page/").document
        val home = document.select("div.row-cols-xxl-6 article").mapNotNull {
            it.toSearchResult()
        }
        return newHomePageResponse(request.name, home)
    }

    private fun Element.toSearchResult(): AnimeSearchResponse? {
        val title = this.selectFirst("h2.text-nowrap")?.text() ?: return null
        val href = this.selectFirst("a.text-white")?.attr("href") ?: return null
        val posterUrl = this.selectFirst("img.img-fluid")?.attr("src")

        return newAnimeSearchResponse(title, href, TvType.NSFW) {
            this.posterUrl = posterUrl
            this.quality = SearchQuality.HD
        }
    }

    // ============================ SEARCH ============================
    override suspend fun search(query: String): List<SearchResponse> {
        val searchResponse = app.get("$mainUrl/?s=$query").document
        return searchResponse.select("main.container article").mapNotNull {
            it.toSearchResult()
        }
    }

    // ============================ LOAD EPISODE/MOVIE INFO ============================
    override suspend fun load(url: String): LoadResponse {
        val document = app.get(url).document

        val title = document.selectFirst("h1.entry-title")?.text()?.trim()
            ?: throw Error("Could not find title")
        val poster = document.selectFirst("meta[property=og:image]")?.attr("content")
        val tags = document.select("span.single-tags > a").map { it.text() }
        val description = document.selectFirst("div.entry-content.text-white > p")?.text()

        return newMovieLoadResponse(title, url, TvType.Movie, url) {
            this.posterUrl = poster
            this.plot = description
            this.tags = tags
        }
    }

    // ============================ LOAD VIDEO LINKS ============================
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val watchPageDoc = app.get(data).document
        val embedUrl = watchPageDoc.selectFirst("div.ratio.ratio-16x9 > iframe")?.attr("src")
            ?: throw ErrorLoadingException("Could not find embed iframe")

        val embedPageDoc = app.get(embedUrl).document
        val playerUrl = embedPageDoc.selectFirst("div.video-content > iframe")?.attr("src")
             ?: throw ErrorLoadingException("Could not find player iframe")

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
