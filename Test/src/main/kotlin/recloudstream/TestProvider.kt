// Save this file as XpornTo.kt
package recloudstream

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType // Import for the enum
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
        val document = app.get("$mainUrl/page/$page/").document
        val homePageList = ArrayList<HomePageList>()

        val mainVideos = document.select("div.videos__inner > div.video").mapNotNull {
            it.toSearchResult()
        }
        if (mainVideos.isNotEmpty()) {
            homePageList.add(HomePageList("Latest Videos", mainVideos))
        }

        return HomePageResponse(homePageList)
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val title = this.selectFirst("h3.video__title")?.text()?.trim() ?: return null
        val href = this.selectFirst("a.video__link")?.attr("href") ?: return null
        val posterUrl = this.selectFirst("img.video__poster--image")?.attr("src")

        return newTvSeriesSearchResponse(title, href, TvType.NSFW) {
            this.posterUrl = posterUrl
        }
    }
    
    private fun Element.toSearchArticleResult(): SearchResponse? {
        val title = this.selectFirst("h2.article__title a")?.text()?.trim() ?: return null
        val href = this.selectFirst("a")?.attr("href") ?: return null
        val posterUrl = this.selectFirst("img")?.attr("src")

        return newTvSeriesSearchResponse(title, href, TvType.NSFW) {
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

        // Extract the `data` parameter from the iframe src
        val iframeSrc = document.selectFirst("p > iframe")?.attr("src") ?: return null
        val dataParam = iframeSrc.substringAfter("data=", "")

        if (dataParam.isBlank()) return null

        val episodes = listOf(
            newEpisode(dataParam) { // Pass only the data parameter to loadLinks
                this.name = title
                this.description = description
            }
        )

        return newTvSeriesLoadResponse(title, url, TvType.NSFW, episodes) {
            this.posterUrl = poster
            this.plot = description
            this.tags = tags
        }
    }

    override suspend fun loadLinks(
        data: String, // This is the `data` parameter, e.g., "707597804a4106d6c217fa74667d75bf"
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        // Directly construct the final M3U8 URL
        val videoUrl = "https://xporn.xtremestream.xyz/player/xs1.php?data=$data"
        val referer = "https://xporn.xtremestream.xyz/"

        callback.invoke(
            ExtractorLink(
                source = this.name,
                name = "${this.name} - Auto",
                url = videoUrl,
                referer = referer,
                quality = Qualities.Unknown.value,
                type = ExtractorLinkType.M3U8 // <<<< CHANGED HERE
            )
        )
        return true
    }
}
