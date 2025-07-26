package recloudstream

// Info: Plugin for phevkl.gg
// Author: Coder
// Date: 2025-07-26
// Version: 2.7 (Added Recommendations & Reordered Main Page)

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.Jsoup
import org.jsoup.nodes.Element

class Phevkl : MainAPI() {
    override var mainUrl = "https://phevkl.gg"
    override var name = "Phevkl"
    override val hasMainPage = true
    override var lang = "vi"
    override val hasDownloadSupport = true
    
    override val supportedTypes = setOf(
        TvType.Movie 
    )

    override val mainPage = mainPageOf(
        "$mainUrl/page/%d/" to "Mới cập nhật",
        "$mainUrl/phim-sex-hay/" to "Phim sex hay",
        "$mainUrl/phim-sex-viet-nam/" to "Phim sex Việt Nam",
        "$mainUrl/phim-sex-trung-quoc/" to "Phim sex Trung Quốc",
        "$mainUrl/phim-sex-onlyfans/" to "Sex Onlyfans",
        "$mainUrl/phim-sex-tiktok/" to "Sex Tiktok"
    )

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {
        val url = if (request.data.contains("%d")) {
            request.data.format(page)
        } else {
            if (page == 1) request.data else "${request.data}page/$page/"
        }
        
        val document = app.get(url).document
        val home = document.select("div#video-list div.video-item").mapNotNull {
            it.toSearchResult()
        }
        return newHomePageResponse(request.name, home)
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val link = this.selectFirst("a") ?: return null
        val title = link.attr("title").trim()
        val href = link.attr("href")
        val posterUrl = this.selectFirst("img.video-image")?.let { it.attr("data-src").ifEmpty { it.attr("src") } }

        return if (href.isNotBlank() && title.isNotBlank()) {
            newMovieSearchResponse(title, href, TvType.Movie) {
                this.posterUrl = posterUrl
            }
        } else {
            null
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val searchUrl = "$mainUrl/?s=$query"
        val document = app.get(searchUrl).document
        return document.select("div#video-list div.video-item").mapNotNull {
            it.toSearchResult()
        }
    }

    override suspend fun load(url: String): LoadResponse? {
        val document = app.get(url).document
        val title = document.selectFirst("h1#page-title")?.text()?.trim() ?: return null
        val poster = document.selectFirst("meta[property=og:image]")?.attr("content")
        val description = document.selectFirst("div.video-description")?.text()?.trim()
        val tags = document.select("div.actress-tag a").map { it.text() }
        
        // Extract recommendations from the "Phim sex liên quan" section
        val recommendations = document.select("div#video-list div.video-item").mapNotNull {
            it.toSearchResult()
        }
        
        return newMovieLoadResponse(title, url, TvType.Movie, url) {
            this.posterUrl = poster
            this.plot = description
            this.tags = tags
            this.recommendations = recommendations
        }
    }
    
    private data class AjaxResponse(val type: String?, val player: String?)

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val doc = app.get(data).document
        val postId = doc.selectFirst("#haun-player")?.attr("data-id") ?: return false
        val ajaxUrl = "$mainUrl/wp-admin/admin-ajax.php"
        var foundLinks = false

        for (server in 1..2) {
            try {
                val response = app.post(
                    ajaxUrl,
                    headers = mapOf("Referer" to data, "X-Requested-With" to "XMLHttpRequest"),
                    data = mapOf(
                        "action" to "load_server",
                        "id" to postId,
                        "server" to server.toString()
                    )
                ).parsedSafe<AjaxResponse>()

                val iframeSrc = response?.player?.let {
                    Jsoup.parse(it).selectFirst("iframe")?.attr("src")
                } ?: continue

                if (iframeSrc.contains("cnd-videosvn.online")) {
                    val videoId = iframeSrc.substringAfterLast('/')
                    val m3u8Url = "https://oss1.dlhls.xyz/videos/$videoId/main.m3u8"
                    callback.invoke(
                        ExtractorLink(
                            this.name,
                            "Server 1 - CDN",
                            m3u8Url,
                            mainUrl,
                            Qualities.Unknown.value,
                            type = ExtractorLinkType.M3U8
                        )
                    )
                    foundLinks = true
                } else if (iframeSrc.contains("helvid.net")) {
                    val iframeContent = app.get(iframeSrc, referer = data).document
                    val playerScript = iframeContent.select("script").find { it.data().contains("jwplayer.key") }?.data()
                    val m3u8Path = Regex("""file:\s*"(.*?)",""").find(playerScript ?: "")?.groupValues?.get(1)

                    if (m3u8Path != null) {
                        val m3u8Url = "https://helvid.net$m3u8Path"
                         callback.invoke(
                            ExtractorLink(
                                this.name,
                                "Server 2 - Helvid",
                                m3u8Url,
                                iframeSrc,
                                Qualities.Unknown.value,
                                type = ExtractorLinkType.M3U8
                            )
                        )
                        foundLinks = true
                    }
                }

            } catch (e: Exception) {
                // Ignore exceptions and try the next server
            }
        }
        return foundLinks
    }
}
