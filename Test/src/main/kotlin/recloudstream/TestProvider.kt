package recloudstream // Dòng package đã được thêm vào

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Element

class HentaiTvProvider : MainAPI() {
    override var mainUrl = "https://hentai.tv"
    override var name = "Hentai.tv"
    override val hasMainPage = true
    override var lang = "en"
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(
        TvType.NSFW
    )

    override val mainPage = mainPageOf(
        "$mainUrl/page/%d/" to "Recent Uploads",
        "$mainUrl/trending/page/%d/" to "Trending"
    )

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {
        val document = app.get(request.data.format(page)).document
        val home = document.select("div.crsl-slde").mapNotNull {
            it.toSearchResult()
        }
        return newHomePageResponse(request.name, home)
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val title = this.selectFirst("a")?.text()?.trim() ?: return null
        val href = this.selectFirst("a")?.attr("href") ?: return null
        val posterUrl = this.selectFirst("img")?.attr("src")

        return newMovieSearchResponse(title, href, TvType.NSFW) {
            this.posterUrl = posterUrl
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val searchResponse = app.get("$mainUrl/?s=$query").document
        return searchResponse.select("div.crsl-slde").mapNotNull {
            it.toSearchResult()
        }
    }

    override suspend fun load(url: String): LoadResponse? {
        val document = app.get(url).document

        val title = document.selectFirst("h1")?.text()?.trim() ?: return null
        val poster = document.selectFirst("aside.flex-1 img")?.attr("src")
        val tags = document.select("div.flex.flex-wrap.pb-3 a").map { it.text() }
        val description = document.selectFirst("div.prose")?.text()?.trim()
        
        // The main video is in an iframe
        val iframeSrc = document.selectFirst("div.aspect-video iframe")?.attr("src") ?: return null

        return newMovieLoadResponse(title, url, TvType.NSFW, iframeSrc) {
            this.posterUrl = poster
            this.plot = description
            this.tags = tags
        }
    }
    
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        // The 'data' is the iframe URL from load()
        // We let the built-in extractors handle the link
        loadExtractor(data, subtitleCallback, callback)
        return true
    }
}
