// Save this file as XpornTo.kt
package recloudstream

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import org.jsoup.nodes.Element

class XpornTo : MainAPI() {
    override var mainUrl = "https://xporn.to"
    override var name = "Xporn.to"
    override val hasMainPage = true
    override var lang = "en"
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(
        TvType.NSFW
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = if (page == 1) mainUrl else "$mainUrl/page/$page/"
        val document = app.get(url).document

        val mainVideos = document.select("div.videos__inner > div.video").mapNotNull {
            it.toSearchResult()
        }
        
        // Determine if there is a next page. If mainVideos is not empty, it's likely there is.
        val hasNext = mainVideos.isNotEmpty()

        // Create a HomePageList and pass it to newHomePageResponse
        val homePageList = HomePageList("Latest Videos", mainVideos)

        // Pass the hasNext value to the response
        return newHomePageResponse(listOf(homePageList), hasNext)
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val title = this.selectFirst("h3.video__title")?.text()?.trim() ?: return null
        val href = this.selectFirst("a.video__link")?.attr("href") ?: return null
        val posterUrl = this.selectFirst("img.video__poster--image")?.attr("src")

        return newMovieSearchResponse(title, href, TvType.NSFW) {
            this.posterUrl = posterUrl
        }
    }
    
    private fun Element.toSearchArticleResult(): SearchResponse? {
        val title = this.selectFirst("h2.article__title a")?.text()?.trim() ?: return null
        val href = this.selectFirst("a")?.attr("href") ?: return null
        val posterUrl = this.selectFirst("img")?.attr("src")

        return newMovieSearchResponse(title, href, TvType.NSFW) {
            this.posterUrl = posterUrl
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val searchUrl = "$mainUrl/?s=$query&post_type=video"
        val document = app.get(searchUrl).document

        return document.select("article.video").mapNotNull {
            it.toSearchArticleResult()
        }
    }

    override suspend fun load(url: String): LoadResponse? {
        val document = app.get(url).document

        val title = document.selectFirst("h1.single-video__title")?.text()?.trim() ?: "No title"
        val poster = document.selectFirst("meta[property=og:image]")?.attr("content")
        val description = document.selectFirst("div.single-video__description > div > p")?.text()
        val tags = document.select("div.st-post-tags a").map { it.text() }

        val iframeSrc = document.selectFirst("p > iframe")?.attr("src") ?: return null
        val dataParam = iframeSrc.substringAfter("data=", "")

        if (dataParam.isBlank()) return null
        
        val recommendations = document.select("section.single-video__related div.video").mapNotNull {
            it.toSearchResult()
        }

        return newMovieLoadResponse(title, url, TvType.NSFW, dataParam) {
            this.posterUrl = poster
            this.plot = description
            this.tags = tags
            this.recommendations = recommendations
        }
    }

    override suspend fun loadLinks(
        data: String, 
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val videoUrl = "https://xporn.xtremestream.xyz/player/xs1.php?data=$data"
        val referer = "https://xporn.xtremestream.xyz/"

        callback.invoke(
            ExtractorLink(
                source = this.name,
                name = "${this.name} - Auto",
                url = videoUrl,
                referer = referer,
                quality = Qualities.Unknown.value,
                type = ExtractorLinkType.M3U8
            )
        )
        return true
    }
}
