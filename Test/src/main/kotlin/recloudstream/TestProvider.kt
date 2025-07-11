package recloudstream

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Element

open class XpornTo : MainAPI() {
    override var mainUrl = "https://xporn.to"
    override var name = "Xporn.to"
    override val hasMainPage = true
    override var lang = "en"
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(
        TvType.NSFW
    )

    override val mainPage = mainPageOf(
        "/" to "Latest Videos",
    )

    override suspend fun load(url: String): LoadResponse? {
        val document = app.get(url).document
        val items = document.select("div.videos__inner > div.post, main#main > div.articles > article").mapNotNull {
            it.toSearchResult()
        }

        return newVideoSearchLoadResponse(name, url, TvType.NSFW, items)
    }
    
    override suspend fun search(query: String): List<SearchResponse> {
        val searchUrl = "$mainUrl/?s=$query&post_type=video"
        val document = app.get(searchUrl).document
        val results = document.select("main#main > div.articles > article").mapNotNull {
            it.toSearchResult()
        }
        return results
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val document = app.get(data).document

        // Tìm thẻ iframe và lấy src
        val iframeSrc = document.selectFirst("div.single-video__head iframe")?.attr("src") ?: return false

        // Dùng loadExtractor để xử lý URL từ iframe, nó sẽ tự động tìm trình trích xuất phù hợp
        return loadExtractor(iframeSrc, data, subtitleCallback, callback)
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val linkElement = this.selectFirst("a.masvideos-LoopVideo-link") ?: return null
        val href = linkElement.attr("href")
        if (href.isBlank()) return null
        
        val title = this.selectFirst("h3.video__title, h2.article__title a")?.text() ?: return null
        val posterUrl = this.selectFirst("img.video__poster--image, img.article__attachment--thumbnail img")?.attr("src")

        return newMovieSearchResponse(title, href, TvType.NSFW) {
            this.posterUrl = posterUrl
        }
    }
}
