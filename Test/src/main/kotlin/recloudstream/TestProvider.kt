// Tên file: WatchHentaiProvider.kt
package recloudstream

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Element
import com.lagradost.cloudstream3.network.CloudflareKiller
import android.util.Log
import java.net.URLDecoder

class WatchHentaiProvider : MainAPI() {
    override var mainUrl = "https://watchhentai.net"
    override var name = "WatchHentai"
    override val hasMainPage = true
    override var lang = "en"
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(
        TvType.NSFW
    )

    // Tag log để bạn dễ debug trong Logcat (lệnh: adb logcat -s WatchHentaiDev)
    private val TAG = "WatchHentaiDev"
    
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
        
        // FIX: Xử lý lazy load ảnh (ưu tiên data-src)
        val imgElement = this.selectFirst("img")
        val posterUrl = imgElement?.let {
            it.attr("data-src").ifBlank { 
                it.attr("src").ifBlank { 
                    it.attr("srcset").substringBefore(" ") 
                } 
            }
        }

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
        return document.select("div.result-item article, article.item").mapNotNull {
            it.toSearchResult()
        }
    }

    override suspend fun load(url: String): LoadResponse? {
        val document = app.get(url, interceptor = cfInterceptor).document
        val isSeriesPage = url.contains("/series/")

        // Support layout cũ và mới
        val title = document.selectFirst("div.data h1, div.sheader h1")?.text()?.trim() ?: "Unknown"
        val posterUrl = document.selectFirst("div.poster img, div.sheader .poster img")?.let {
             it.attr("data-src").ifBlank { it.attr("src") }
        }
        val synopsis = document.selectFirst("div.wp-content p, div.synopsis p, .wp-content")?.text()?.trim()
        
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
        Log.d(TAG, "--> loadLinks STARTED: $data")
        val document = app.get(data, interceptor = cfInterceptor).document
        
        // Sử dụng Set để chứa các link thô tìm được (tự động loại bỏ trùng lặp)
        val rawLinks = mutableSetOf<String>()

        // 1. Quét Meta Tag (Thường là link sạch nhất)
        val metaContentUrl = document.selectFirst("meta[itemprop=contentUrl]")?.attr("content")
        if (!metaContentUrl.isNullOrBlank()) {
            Log.d(TAG, "Found Meta ContentUrl: $metaContentUrl")
            rawLinks.add(metaContentUrl)
        }

        // 2. Quét Iframe (Support lazyload data-src và ID specific)
        val iframes = document.select("iframe#search_iframe, iframe.metaframe, #playarea iframe, .entry-content iframe")
        for (iframe in iframes) {
            val dataSrc = iframe.attr("data-src")
            val src = iframe.attr("src")
            
            if (dataSrc.isNotBlank()) rawLinks.add(dataSrc)
            if (src.isNotBlank()) rawLinks.add(src)
        }

        Log.d(TAG, "Total unique raw links found: ${rawLinks.size}")

        if (rawLinks.isEmpty()) {
            Log.e(TAG, "No potential links found.")
            return false
        }

        // 3. Xử lý từng link trong Set
        var count = 0
        rawLinks.forEach { rawUrl ->
            // Bỏ qua link rác
            if (rawUrl.contains("about:blank") || (!rawUrl.startsWith("http") && !rawUrl.contains("source="))) {
                return@forEach // continue loop
            }

            Log.d(TAG, "Processing raw link: $rawUrl")

            // Decode: Nếu là link JWPlayer chứa param "source=", giải mã nó
            val sourceParam = Regex("""source=([^&]+)""").find(rawUrl)?.groupValues?.get(1)
            
            val finalUrl = if (sourceParam != null) {
                try {
                    URLDecoder.decode(sourceParam, "UTF-8")
                } catch (e: Exception) {
                    rawUrl // Fallback nếu decode lỗi
                }
            } else {
                rawUrl
            }

            Log.d(TAG, "-> Resolved URL: $finalUrl")

            // Phân loại link
            val linkType = when {
                finalUrl.endsWith(".mp4") || finalUrl.endsWith(".mkv") -> ExtractorLinkType.VIDEO
                finalUrl.contains(".m3u8") -> ExtractorLinkType.M3U8
                else -> null
            }

            if (linkType != null) {
                // Trường hợp 1: Link trực tiếp (Direct Link/VIP) -> Play luôn
                callback.invoke(
                    newExtractorLink(
                        source = this.name,
                        name = "${this.name} Source ${count + 1}", 
                        url = finalUrl,
                        type = linkType
                    ) {
                        this.referer = mainUrl
                        this.quality = Qualities.Unknown.value
                    }
                )
                count++
            } else {
                // Trường hợp 2: Link Embed (Dood, StreamSB...) -> Cần giải mã tiếp
                loadExtractor(finalUrl, mainUrl, subtitleCallback) { link ->
                    callback.invoke(link)
                    count++
                }
            }
        }

        Log.d(TAG, "<-- loadLinks FINISHED. Generated $count valid links.")
        return true
    }
}
