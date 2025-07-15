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

    // Bước 1: Dùng `mainPageOf` để khai báo các mục trên trang chính
    override val mainPage = mainPageOf(
        "moi-cap-nhat/page/" to "Mới cập nhật",
        "top-xem-nhieu/page/" to "Top Xem Nhiều",
        "hoan-thanh/page/" to "Hoàn Thành",
    )

    // Bước 2: Dùng `getMainPage` để xử lý logic tải và phân trang cho từng mục
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

    private data class EpisodeInfo(val url: String, val serverLabel: String)

    override suspend fun load(url: String): LoadResponse? {
        val document = app.get(url).document

        val title = document.selectFirst("h1.entry-title")?.text()?.trim() ?: return null
        val poster = document.selectFirst(".movie-poster img")?.attr("src")
        val plot = document.selectFirst(".entry-content p")?.text()?.trim()
        val tags = document.select(".list_cate a").map { it.text() }

        val episodeMap = mutableMapOf<String, MutableList<EpisodeInfo>>()
        val serverBlocks = document.select(".halim-server")
        val numberRegex = Regex("""\d+""")

        serverBlocks.forEach { server ->
            val serverName = server.selectFirst(".halim-server-name")?.text()
            val serverLabel = when {
                serverName?.contains("Vietsub", true) == true -> "VS"
                serverName?.contains("Thuyết Minh", true) == true -> "TM"
                else -> "RAW"
            }

            server.select("ul.halim-list-eps li a").forEach { ep ->
                val epName = ep.selectFirst("span")?.text() ?: ep.text()
                val epKey = numberRegex.find(epName)?.value ?: epName 
                val epUrl = ep.attr("href")
                
                episodeMap.getOrPut(epKey) { mutableListOf() }.add(EpisodeInfo(epUrl, serverLabel))
            }
        }
        
        val episodes = episodeMap.keys
            .mapNotNull { it.toIntOrNull() }
            .sortedDescending()
            .mapNotNull { epKey ->
                val infoList = episodeMap[epKey.toString()] ?: return@mapNotNull null
                val representativeUrl = infoList.first().url
                val serverTags = infoList.joinToString(separator = "+") { it.serverLabel }.let { "($it)" }
                
                newEpisode(representativeUrl) {
                    this.name = "Tập $epKey $serverTags"
                }
            }

        val recommendations = document.select("section#halim-related-movies article.thumb").mapNotNull {
            it.toSearchResponse()
        }

        return newTvSeriesLoadResponse(title, url, TvType.Cartoon, episodes) {
            this.posterUrl = poster ?: ""
            this.plot = plot
            this.tags = tags
            this.recommendations = recommendations
        }
    }

    // Sửa lỗi #1: Cập nhật lại hoàn toàn hàm `loadLinks`
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        // Lấy thông tin chung từ trang xem phim, bất kể server là gì
        val watchPageDoc = app.get(data).document
        val postId = watchPageDoc.selectFirst("main.watch-page")?.attr("data-id") ?: return false
        val chapterSt = watchPageDoc.selectFirst(".halim-episode.active a")?.attr("data-ep") ?: return false
        
        var foundLinks = false

        // Lặp qua cả 2 loại server: 1 (Vietsub) và 2 (Thuyết Minh)
        listOf("1", "2").apmap { sv ->
            val langPrefix = if (sv == "2") "TM " else "VS "
            
            // Lấy danh sách các nút server (VIP 1, VIP 4K,...)
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
                    
                    val extractorPageSource = app.get(iframeSrc, referer = data).text
                    val fileRegex = Regex("""sources:\s*\[\{\s*type:\s*"hls",\s*file:\s*"(.*?)"""")
                    val relativeLink = fileRegex.find(extractorPageSource)?.groupValues?.get(1)

                    if (relativeLink != null) {
                        val domain = URI(iframeSrc).let { "${it.scheme}://${it.host}" }
                        val fullM3u8Url = if (relativeLink.startsWith("http")) relativeLink else domain + relativeLink

                        callback.invoke(
                            ExtractorLink(
                                source = this.name,
                                name = "$langPrefix$serverName", 
                                url = fullM3u8Url,
                                referer = iframeSrc,
                                quality = Qualities.Unknown.value,
                                type = ExtractorLinkType.M3U8
                            )
                        )
                        foundLinks = true
                    }
                } catch (e: Exception) {
                    // Bỏ qua nếu có lỗi
                }
            }
        }
        return foundLinks
    }
}
