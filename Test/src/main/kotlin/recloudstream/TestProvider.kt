// Tên file: WatchHentaiProvider.kt
package recloudstream

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Element
import com.lagradost.cloudstream3.network.CloudflareKiller

class WatchHentaiProvider : MainAPI() {
    override var mainUrl = "https://watchhentai.net"
    override var name = "WatchHentai"
    override val hasMainPage = true
    override var lang = "en"
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(
        TvType.NSFW
    )

    // Interceptor để xử lý Cloudflare
    private val cfInterceptor = CloudflareKiller()
    
    override val mainPage = mainPageOf(
        "$mainUrl/videos/" to "Recent Episodes",
        "$mainUrl/series/" to "Hentai Series",
        "$mainUrl/genre/uncensored/" to "Uncensored",
        "$mainUrl/genre/harem/" to "Harem",
        "$mainUrl/genre/school-girls/" to "School Girls",
    )

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {
        val url = if (page == 1) request.data else "${request.data}page/$page/"
        val document = app.get(url, interceptor = cfInterceptor).document
        val home = document.select("article.item").mapNotNull {
            it.toSearchResult()
        }
        return newHomePageResponse(request.name, home)
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val title = this.selectFirst("div.data h3 a, div.title a")?.text()?.trim() ?: return null
        val href = this.selectFirst("a")?.attr("href") ?: return null
        val posterUrl = this.selectFirst("img")?.let {
            it.attr("data-src").ifBlank { it.attr("src") }
        }

        return if (href.contains("/series/")) {
            newTvSeriesSearchResponse(title, href, TvType.NSFW) {
                this.posterUrl = posterUrl
            }
        } else {
            newMovieSearchResponse(title, href, TvType.NSFW) {
                this.posterUrl = posterUrl
            }
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val searchUrl = "$mainUrl/?s=$query"
        val document = app.get(searchUrl, interceptor = cfInterceptor).document
        return document.select("div.result-item article").mapNotNull {
            it.toSearchResult()
        }
    }

    override suspend fun load(url: String): LoadResponse? {
        val document = app.get(url, interceptor = cfInterceptor).document
        val isSeriesPage = url.contains("/series/")

        return if (isSeriesPage) {
            val title = document.selectFirst("div.data h1")?.text()?.trim() ?: "Unknown Series"
            val posterUrl = document.selectFirst("div.poster img")?.attr("data-src")
            val synopsis = document.selectFirst("div.wp-content p")?.text()?.trim()
            val episodes = document.select("ul.episodios li").mapNotNull { el ->
                val epHref = el.selectFirst("div.episodiotitle a")?.attr("href") ?: return@mapNotNull null
                val epTitle = el.selectFirst("div.episodiotitle a")?.text()?.trim() ?: "Episode"
                val epPoster = el.selectFirst("div.imagen img")?.attr("data-src")
                val epNum = epTitle.substringAfter("Episode ").toIntOrNull()

                // SỬA LỖI: Sử dụng tham số 'url' theo yêu cầu của trình biên dịch
                // và đặt các thuộc tính khác trong khối lambda.
                newEpisode(url = epHref) {
                    this.name = epTitle
                    this.posterUrl = epPoster
                    this.episode = epNum
                }
            }.reversed()

            newTvSeriesLoadResponse(title, url, TvType.NSFW, episodes) {
                this.posterUrl = posterUrl
                this.plot = synopsis
            }
        } else {
            val title = document.selectFirst("div.data h1")?.text()?.trim() ?: "Unknown Episode"
            val posterUrl = document.selectFirst("meta[property=og:image]")?.attr("content")
            val synopsis = document.selectFirst("div.synopsis p")?.text()?.trim()
            
            newMovieLoadResponse(title, url, TvType.NSFW, dataUrl = url) {
                 this.posterUrl = posterUrl
                 this.plot = synopsis
            }
        }
    }
    
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val episodePageDoc = app.get(data, interceptor = cfInterceptor).document
        val iframeSrc = episodePageDoc.selectFirst("iframe.metaframe")?.attr("src") ?: return false
        val iframeDoc = app.get(iframeSrc, interceptor = cfInterceptor, referer = data).document
        val scriptContent = iframeDoc.select("script").firstOrNull { 
            it.data().contains("jwplayer('player').setup") 
        }?.data() ?: return false
        
        val sourcesRegex = """sources:\s*\[([^\]]+)\]""".toRegex()
        val sourcesBlock = sourcesRegex.find(scriptContent)?.groupValues?.get(1) ?: return false

        val fileRegex = """file:\s*"([^"]+)"""".toRegex()
        val labelRegex = """label:\s*"([^"]+)"""".toRegex()
        
        val sources = sourcesBlock.split("},").filter { it.isNotBlank() }

        sources.forEach { source ->
            val url = fileRegex.find(source)?.groupValues?.get(1)
            val quality = labelRegex.find(source)?.groupValues?.get(1)
            
            if (url != null && quality != null) {
                callback(
                    ExtractorLink(
                        source = this.name,
                        name = "${this.name} $quality",
                        url = url,
                        referer = mainUrl,
                        quality = quality.replace("p", "").toIntOrNull() ?: Qualities.Unknown.value,
                        type = if (url.contains(".m3u8")) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                    )
                )
            }
        }
        return true
    }
}
