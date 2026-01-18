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
        val href = this.selectFirst("a")?.attr("href") ?: return null
        
        // FIX: Xử lý lazy load ảnh (Litespeed cache thường dùng data-src)
        val imgElement = this.selectFirst("img")
        val posterUrl = imgElement?.let {
            it.attr("data-src").ifBlank { 
                it.attr("src").ifBlank { 
                    it.attr("srcset").substringBefore(" ") 
                } 
            }
        }

        // FIX: Logic lấy title an toàn hơn, support nhiều layout
        val seriesTitle = this.selectFirst("span.serie")?.text()?.trim()
        val episodeTitle = this.selectFirst("h3")?.text()?.trim()
        val searchTitle = this.selectFirst(".title a")?.text()?.trim()

        val title = when {
            seriesTitle != null && !episodeTitle.isNullOrBlank() -> "$seriesTitle - $episodeTitle"
            !episodeTitle.isNullOrBlank() -> episodeTitle
            !searchTitle.isNullOrBlank() -> searchTitle
            else -> "Unknown Title"
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
        // Support cả layout grid search mới và cũ
        return document.select("div.result-item article, article.item").mapNotNull {
            it.toSearchResult()
        }
    }

    override suspend fun load(url: String): LoadResponse? {
        val document = app.get(url, interceptor = cfInterceptor).document
        val isSeriesPage = url.contains("/series/")

        // Common Extractors
        // FIX: Layout mới có thể dùng .sheader hoặc .data
        val title = document.selectFirst("div.data h1, div.sheader h1")?.text()?.trim() ?: "Unknown"
        val posterUrl = document.selectFirst("div.poster img, div.sheader .poster img")?.let {
             it.attr("data-src").ifBlank { it.attr("src") }
        }
        val synopsis = document.selectFirst("div.wp-content p, div.synopsis p, .wp-content")?.text()?.trim()
        
        // Recommendations
        val recommendations = document.select("div#single_relacionados article, div#dt-episodes article, .related-items article").mapNotNull {
            it.toSearchResult()
        }

        return if (isSeriesPage) {
            val episodes = document.select("ul.episodios li").mapNotNull { el ->
                val epHref = el.selectFirst("div.episodiotitle a")?.attr("href") ?: return@mapNotNull null
                val epTitle = el.selectFirst("div.episodiotitle a")?.text()?.trim() ?: "Episode"
                val epPoster = el.selectFirst("div.imagen img")?.let { 
                    it.attr("data-src").ifBlank { it.attr("src") } 
                }
                
                // Parse episode number
                val epNum = Regex("""Episode\s+(\d+)""").find(epTitle)?.groupValues?.get(1)?.toIntOrNull()

                newEpisode(epHref) {
                    this.name = epTitle
                    this.posterUrl = epPoster
                    this.episode = epNum
                }
            }.reversed()

            newTvSeriesLoadResponse(title, url, TvType.NSFW, episodes) {
                this.posterUrl = posterUrl
                this.plot = synopsis
                this.recommendations = recommendations
            }
        } else {
            // Single Movie / Episode page
            newMovieLoadResponse(title, url, TvType.NSFW, dataUrl = url) {
                this.posterUrl = posterUrl
                this.plot = synopsis
                this.recommendations = recommendations
            }
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val document = app.get(data, interceptor = cfInterceptor).document
        
        // FIX: Tìm iframe ở nhiều vị trí hơn (metaframe, playarea, player_container)
        val iframeElement = document.selectFirst("iframe.metaframe, #playarea iframe, .player_container iframe") 
            ?: return false
            
        val iframeSrc = iframeElement.attr("src")
        if (iframeSrc.isBlank()) return false

        // FIX: Logic xử lý link source từ parameter (dành cho JWPlayer wrapper)
        val sourceParam = Regex("""source=([^&]+)""").find(iframeSrc)?.groupValues?.get(1)
        
        val extractedUrl = if (sourceParam != null) {
            java.net.URLDecoder.decode(sourceParam, "UTF-8")
        } else {
            iframeSrc // Fallback: Link embed trực tiếp
        }

        // Phân loại link để xử lý tối ưu
        if (extractedUrl.endsWith(".mp4") || extractedUrl.endsWith(".mkv")) {
            // Đây là Direct Link (như hstorage.xyz), trả về luôn không cần Extractor
            callback.invoke(
                ExtractorLink(
                    source = "WatchHentai", 
                    name = "WatchHentai VIP",
                    url = extractedUrl,
                    referer = mainUrl, 
                    quality = Qualities.Unknown.value,
                    type = ExtractorLinkType.VIDEO
                )
            )
        } else if (extractedUrl.contains(".m3u8")) {
            // Direct HLS Link
             callback.invoke(
                ExtractorLink(
                    source = "WatchHentai",
                    name = "WatchHentai HLS",
                    url = extractedUrl,
                    referer = mainUrl,
                    quality = Qualities.Unknown.value,
                    type = ExtractorLinkType.M3U8
                )
            )
        } else {
            // Link Embed server (Dood, StreamSB, etc.) -> Dùng loadExtractor
            loadExtractor(extractedUrl, mainUrl, subtitleCallback) { link ->
                callback.invoke(link)
            }
        }

        return true
    }
}
