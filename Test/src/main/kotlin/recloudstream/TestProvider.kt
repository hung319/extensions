package recloudstream

import com.google.gson.reflect.TypeToken
import com.lagradost.cloudstream3.plugins.Plugin
import android.content.Context
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import com.lagradost.cloudstream3.utils.AppUtils.toJson
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

    override val mainPage = mainPageOf(
        "moi-cap-nhat/page/" to "Mới cập nhật",
        "top-xem-nhieu/page/" to "Top Xem Nhiều",
        "hoan-thanh/page/" to "Hoàn Thành",
    )

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {
        val url = "$mainUrl/${request.data}$page"
        val document = app.get(url).document

        val home = document.select("div.halim_box article.thumb").mapNotNull {
            it.toSearchResponse()
        }
        
        val hasNext = document.selectFirst("a.next.page-numbers") != null

        return newHomePageResponse(
            list = HomePageList(
                name = request.name,
                list = home
            ),
            hasNext = hasNext
        )
    }

    private fun Element.toSearchResponse(): SearchResponse? {
        val a = this.selectFirst("a.halim-thumb, a.thumbnail-link") ?: return null
        val href = a.attr("href")
        val title = this.selectFirst("h2.entry-title, h3.title")?.text() ?: return null
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

    private data class EpisodeInfo(val url: String, val serverLabel: String)

    override suspend fun load(url: String): LoadResponse? {
        val document = app.get(url).document

        val title = document.selectFirst("h1.entry-title")?.text()?.trim() ?: return null
        val poster = document.selectFirst(".movie-poster img")?.attr("src")
        val plot = document.selectFirst(".entry-content p")?.text()?.trim()
        val tags = document.select(".list_cate a").map { it.text() }

        val episodeMap = mutableMapOf<String, MutableList<EpisodeInfo>>()
        val serverBlocks = document.select(".halim-server")
        
        serverBlocks.forEach { server ->
            val serverName = server.selectFirst(".halim-server-name")?.text()
            val serverLabel = when {
                serverName?.contains("Vietsub", true) == true -> "VS"
                serverName?.contains("Thuyết Minh", true) == true -> "TM"
                else -> "RAW"
            }

            server.select("ul.halim-list-eps li a").forEach { ep ->
                // Lấy tên tập phim gốc từ web, ví dụ: "Tập 1-6"
                val epName = ep.selectFirst("span")?.text()?.trim() ?: ep.text().trim()
                val epUrl = ep.attr("href")
                
                // Dùng tên gốc này làm key để gộp
                episodeMap.getOrPut(epName) { mutableListOf() }.add(EpisodeInfo(epUrl, serverLabel))
            }
        }
        
        val numberRegex = Regex("""\d+""")
        val episodes = episodeMap.entries
            .map { (epName, infoList) ->
                // Lấy số đầu tiên trong tên tập để sắp xếp
                val sortKey = numberRegex.find(epName)?.value?.toIntOrNull() ?: 0
                Triple(sortKey, epName, infoList)
            }
            .sortedBy { it.first } // Sắp xếp theo thứ tự tăng dần
            .map { (_, epName, infoList) ->
                val data = infoList.toJson()
                val serverTags = infoList.joinToString(separator = "+") { it.serverLabel }.let { "($it)" }
                
                newEpisode(data) {
                    this.name = "$epName $serverTags"
                }
            }

        val recommendations = document.select("aside#sidebar .popular-post .item").mapNotNull {
            it.toSearchResponse()
        }

        return newTvSeriesLoadResponse(title, url, TvType.Cartoon, episodes) {
            this.posterUrl = poster ?: ""
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
        val listType = object : TypeToken<List<EpisodeInfo>>() {}.type
        val infoList = parseJson<List<EpisodeInfo>>(data)
        
        coroutineScope {
            infoList.forEach { info ->
                async {
                    try {
                        val watchPageDoc = app.get(info.url).document
                        val activeEpisode = watchPageDoc.selectFirst(".halim-episode.active a") ?: return@async
                        val postId = watchPageDoc.selectFirst("main.watch-page")?.attr("data-id") ?: return@async
                        val chapterSt = activeEpisode.attr("data-ep") ?: return@async
                        val sv = activeEpisode.attr("data-sv") ?: return@async
                        
                        val langPrefix = "${info.serverLabel} "

                        val serverButtons = watchPageDoc.select("#halim-ajax-list-server .get-eps")
                        serverButtons.forEach { button ->
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

                                val iframeSrc = ajaxResponse.selectFirst("iframe")?.attr("src") ?: return@forEach
                                val extractorPageSource = app.get(iframeSrc, referer = info.url).text
                                val fileRegex = Regex("""sources:\s*\[\{\s*type:\s*"hls",\s*file:\s*"(.*?)"""")
                                val relativeLink = fileRegex.find(extractorPageSource)?.groupValues?.get(1)

                                if (relativeLink != null) {
                                    val domain = URI(iframeSrc).let { "${it.scheme}://${it.host}" }
                                    val fullM3u8Url = if (relativeLink.startsWith("http")) relativeLink else domain + relativeLink

                                    callback.invoke(
                                        ExtractorLink(
                                            source = this@HHKungfuProvider.name,
                                            name = "$langPrefix$serverName",
                                            url = fullM3u8Url,
                                            referer = iframeSrc,
                                            quality = Qualities.Unknown.value,
                                            type = ExtractorLinkType.M3U8
                                        )
                                    )
                                }
                            } catch (e: Exception) {
                                // Bỏ qua lỗi server con
                            }
                        }
                    } catch (e: Exception) {
                        // Bỏ qua lỗi server chính
                    }
                }
            }
        }
        return true
    }
}
