// Desription: провайдер для сайта VeoHentai
// Date: 2025-08-04
// Version: 1.5
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

    // ============================ HOMEPAGE ============================
    override val mainPage = mainPageOf(
        "/" to "Episodios Recientes",
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val document = app.get("$mainUrl${request.data}/page/$page/").document
        // SỬA LỖI: Selector chính xác cho trang chủ
        val home = document.select("div#posts-home a").mapNotNull {
            it.toSearchResult()
        }
        return newHomePageResponse(request.name, home)
    }

    // ============================ SEARCH ============================
    override suspend fun search(query: String): List<SearchResponse> {
        val searchResponse = app.get("$mainUrl/?s=$query").document
        // SỬA LỖI: Selector chính xác cho trang tìm kiếm
        return searchResponse.select("div.grid a").mapNotNull {
            it.toSearchResult()
        }
    }
    
    // SỬA LỖI: Hàm helper được cập nhật để xử lý thẻ <a>
    private fun Element.toSearchResult(): AnimeSearchResponse? {
        // `this` bây giờ là thẻ <a>
        val href = this.attr("href")
        if (href.isEmpty()) return null

        val title = this.selectFirst("h2")?.text() ?: return null
        val posterUrl = this.selectFirst("figure img")?.attr("src")

        return newAnimeSearchResponse(title, href, TvType.NSFW) {
            this.posterUrl = posterUrl
            this.quality = SearchQuality.HD
        }
    }

    // ============================ LOAD EPISODE/MOVIE INFO ============================
    override suspend fun load(url: String): LoadResponse {
        val document = app.get(url).document

        val title = document.selectFirst("h1.entry-title")?.text()?.trim()
            ?: throw Error("Could not find title")
        val poster = document.selectFirst("meta[property=og:image]")?.attr("content")
        val tags = document.select("span.single-tags a").map { it.text() }
        val description = document.selectFirst("div.entry-content p")?.text()

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
