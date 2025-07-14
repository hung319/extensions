package recloudstream

import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import android.content.Context
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import org.jsoup.nodes.Element
import java.net.URI

@CloudstreamPlugin
class HHKungfuProvider : Plugin() {
    override var name = "HHKungfu"
    override var mainUrl = "https://hhkungfu.ee"
    override var lang = "vi"
    override val hasMainPage = true
    override val supportedTypes = setOf(
        TvType.Cartoon
    )

    override fun getLoadProfile(name: String, url: String, data: String, isCasting: Boolean): Boolean {
        return false
    }

    private fun Element.toSearchResponse(): SearchResponse? {
        val a = this.selectFirst("a.halim-thumb") ?: return null
        val href = a.attr("href")
        val title = this.selectFirst("h2.entry-title")?.text() ?: return null
        val posterUrl = this.selectFirst("img.wp-post-image")?.attr("src")
        val episode = this.selectFirst("span.episode")?.text()

        return newTvSeriesSearchResponse(title, href, TvType.Cartoon) {
            this.posterUrl = posterUrl
            addQuality(episode)
        }
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val document = app.get("$mainUrl/page/$page").document
        val homePageList = ArrayList<HomePageList>()

        val sections = document.select("section.hot-movies")
        sections.forEach { section ->
            val title = section.selectFirst("h3.section-title span")?.text() ?: "Không có tiêu đề"
            val movies = section.select("article.thumb").mapNotNull {
                it.toSearchResponse()
            }
            if (movies.isNotEmpty()) {
                homePageList.add(HomePageList(title, movies))
            }
        }

        return HomePageResponse(homePageList)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val searchUrl = "$mainUrl/?s=$query"
        val document = app.get(searchUrl).document
        return document.select("#loop-content article.thumb").mapNotNull {
            it.toSearchResponse()
        }
    }

    override suspend fun load(url: String): LoadResponse? {
        val document = app.get(url).document

        val title = document.selectFirst("h1.entry-title")?.text()?.trim() ?: return null
        val poster = document.selectFirst(".movie-poster img")?.attr("src")
        val plot = document.selectFirst(".entry-content p")?.text()?.trim()
        val tags = document.select(".list_cate a").map { it.text() }

        val episodes = ArrayList<Episode>()
        val serverBlocks = document.select(".halim-server")

        serverBlocks.forEach { server ->
            val serverName = server.selectFirst(".halim-server-name")?.text()?.replace("#", "")?.trim() ?: "Server"
            val episodeElements = server.select("ul.halim-list-eps li a")

            episodeElements.forEach { ep ->
                val epUrl = ep.attr("href")
                val epName = ep.selectFirst("span")?.text() ?: ep.text()
                episodes.add(
                    newEpisode(epUrl) {
                        name = if (serverBlocks.size > 1) "$epName - $serverName" else epName
                        this.data = epUrl
                    }
                )
            }
        }

        return newTvSeriesLoadResponse(title, url, TvType.Cartoon, episodes.reversed()) {
            this.posterUrl = poster
            this.plot = plot
            this.tags = tags
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val watchPageDoc = app.get(data).document

        val activeEpisode = watchPageDoc.selectFirst(".halim-episode.active a") ?: return false
        val postId = activeEpisode.attr("data-post-id")
        val chapterSt = activeEpisode.attr("data-ep")
        val sv = activeEpisode.attr("data-sv")
        val serverButtons = watchPageDoc.select("#halim-ajax-list-server .get-eps")

        var foundLinks = false
        serverButtons.apmap { button ->
            try {
                val type = button.attr("data-type")
                val serverName = button.text()
                val playerAjaxUrl = "$mainUrl/player/player.php"
                val ajaxResponse = app.get(
                    playerAjaxUrl,
                    params = mapOf(
                        "action" to "dox_ajax_player",
                        "post_id" to postId,
                        "chapter_st" to chapterSt,
                        "type" to type,
                        "sv" to sv
                    ),
                    headers = mapOf("X-Requested-With" to "XMLHttpRequest")
                ).document

                val iframeSrc = ajaxResponse.selectFirst("iframe")?.attr("src") ?: return@apmap
                
                val extractorPageSource = app.get(iframeSrc, referer = data).text
                
                val fileRegex = Regex("""sources:\s*\[\{\s*type:\s*"hls",\s*file:\s*"(.*?)"""")
                val relativeLink = fileRegex.find(extractorPageSource)?.groupValues?.get(1)

                if (relativeLink != null) {
                    val domain = URI(iframeSrc).let { "${it.scheme}://${it.host}" }
                    val fullM3u8Url = if (relativeLink.startsWith("http")) relativeLink else domain + relativeLink

                    callback.invoke(
                        ExtractorLink(
                            source = this.name,
                            name = serverName,
                            url = fullM3u8Url,
                            referer = iframeSrc,
                            quality = Qualities.Unknown.value,
                            type = ExtractorLinkType.M3U8 // Sửa lại thành M3U8
                        )
                    )
                    foundLinks = true
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }

        return foundLinks
    }
}
