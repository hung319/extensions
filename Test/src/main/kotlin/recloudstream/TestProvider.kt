// Tên file: WatchHentaiProvider.kt
package recloudstream // <--- ĐÃ THÊM PACKAGE NAME

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Element
import com.lagradost.cloudstream3.network.CloudflareKiller
import com.lagradost.cloudstream3.extractors.helper.AesHelper

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
            it.attr("data-src").ifEmpty { it.attr("src") }
        }

        return newMovieSearchResponse(title, href, TvType.NSFW) {
            this.posterUrl = posterUrl
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

        // Kiểm tra xem đây là trang series hay trang tập phim
        val isSeriesPage = document.selectFirst("div#seasons") != null

        return if (isSeriesPage) {
            val title = document.selectFirst("div.data h1")?.text()?.trim() ?: "Unknown Series"
            val posterUrl = document.selectFirst("div.poster img")?.attr("data-src")
            val synopsis = document.selectFirst("div.wp-content p")?.text()?.trim()
            val episodes = document.select("ul.episodios li").mapNotNull { el ->
                val epTitle = el.selectFirst("div.episodiotitle a")?.text()?.trim() ?: "Episode"
                val epHref = el.selectFirst("div.episodiotitle a")?.attr("href") ?: return@mapNotNull null
                val epPoster = el.selectFirst("div.imagen img")?.attr("data-src")
                val epNum = epTitle.substringAfter("Episode ").toIntOrNull()

                newEpisode(epHref) {
                    name = epTitle
                    posterUrl = epPoster
                    episode = epNum
                }
            }.reversed() // Đảo ngược để tập mới nhất ở cuối

            newTvSeriesLoadResponse(title, url, TvType.NSFW, episodes) {
                this.posterUrl = posterUrl
                this.plot = synopsis
            }
        } else {
            // Đây là trang video/tập phim, nhưng loadLinks sẽ xử lý nó
            // Ta vẫn cần load thông tin cơ bản cho UI
            val title = document.selectFirst("div.data h1")?.text()?.trim() ?: "Unknown Episode"
            val posterUrl = document.selectFirst("meta[property=og:image]")?.attr("content")
            val synopsis = document.selectFirst("div.synopsis p")?.text()?.trim()
            
            // newVideoLoadResponse hoạt động tốt nhất cho các mục đơn lẻ
            newVideoLoadResponse(title, url, TvType.NSFW, url) {
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
        // 'data' bây giờ là URL của trang tập phim
        val episodePageDoc = app.get(data, interceptor = cfInterceptor).document
        
        // Tìm iframe chứa player
        val iframeSrc = episodePageDoc.selectFirst("iframe.metaframe")?.attr("src") ?: return false
        
        // Tải nội dung của iframe
        val iframeDoc = app.get(iframeSrc, interceptor = cfInterceptor, referer = data).document

        // Tìm script chứa thông tin player
        val scriptContent = iframeDoc.select("script").firstOrNull { 
            it.data().contains("jwplayer('player').setup") 
        }?.data() ?: return false
        
        // Regex để trích xuất các nguồn video
        val sourcesRegex = """sources:\s*\[([^\]]+)\]""".toRegex()
        val sourcesBlock = sourcesRegex.find(scriptContent)?.groupValues?.get(1) ?: return false

        val fileRegex = """file:\s*"([^"]+)"""".toRegex()
        val labelRegex = """label:\s*"([^"]+)"""".toRegex()
        
        val sources = sourcesBlock.split("},").filter { it.isNotBlank() }

        for (source in sources) {
            val fileMatch = fileRegex.find(source)
            val labelMatch = labelRegex.find(source)
            
            if (fileMatch != null && labelMatch != null) {
                val url = fileMatch.groupValues[1]
                val quality = labelMatch.groupValues[1]
                
                callback(
                    ExtractorLink(
                        this.name,
                        "${this.name} $quality",
                        url,
                        mainUrl,
                        quality.toIntOrNull() ?: Qualities.Unknown.value,
                        isM3u8 = url.contains(".m3u8")
                    )
                )
            }
        }

        return true
    }
}
