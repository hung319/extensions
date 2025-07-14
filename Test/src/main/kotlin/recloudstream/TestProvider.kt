package recloudstream

import com.lagradost.cloudstream3.plugins.Plugin
import android.content.Context
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import org.jsoup.nodes.Element
import java.net.URI

class HHKungfuProvider : MainAPI() {
    override var name = "HHKungfu"
    override var mainUrl = "https://hhkungfu.ee"
    override var lang = "vi"
    override val hasMainPage = true
    override val supportedTypes = setOf(
        TvType.Cartoon
    )

    // Đã loại bỏ "Trang chủ" khỏi danh sách này
    override val mainPage = mainPageOf(
        "$mainUrl/moi-cap-nhat/page/" to "Mới cập nhật",
        "$mainUrl/top-xem-nhieu/page/" to "Top Xem Nhiều",
        "$mainUrl/hoan-thanh/page/" to "Hoàn Thành",
    )

    override suspend fun mainPageLoad(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {
        val url = request.data + page
        val document = app.get(url).document

        val home = document.select("div.halim_box article.thumb").mapNotNull {
            it.toSearchResponse()
        }
        return newHomePageResponse(request.name, home)
    }

    private fun Element.toSearchResponse(): SearchResponse? {
        val a = this.selectFirst("a.halim-thumb") ?: return null
        val href = a.attr("href")
        val title = this.selectFirst("h2.entry-title")?.text() ?: return null
        val posterUrl = this.selectFirst("img.wp-post-image")?.attr("src")
        val episodeText = this.selectFirst("span.episode")?.text()

        return newTvSeriesSearchResponse(title, href, TvType.Cartoon) {
            this.posterUrl = posterUrl ?: ""

            if (episodeText != null) {
                val episodeRegex = Regex("""\d+""")
                episodeRegex.find(episodeText)?.value?.toIntOrNull()?.let {
                    this.episodes = it
                }
                
                this.quality = when {
                    episodeText.contains("4K", true) -> SearchQuality.FourK
                    episodeText.contains("FULL HD", true) -> SearchQuality.HD
                    episodeText.contains("HD", true) -> SearchQuality.HD
                    else -> null
                }
            }
        }
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

        val recommendations = document.select("section#halim-related-movies article.thumb").mapNotNull {
            it.toSearchResponse()
        }

        return newTvSeriesLoadResponse(title, url, TvType.Cartoon, episodes.reversed()) {
            this.posterUrl = poster
            this.plot = plot
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
                            type = ExtractorLinkType.M3U8
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
