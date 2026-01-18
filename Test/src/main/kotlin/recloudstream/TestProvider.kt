// Tên file: WatchHentaiProvider.kt
package recloudstream

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Element
import com.lagradost.cloudstream3.network.CloudflareKiller
import android.util.Log // Import thư viện Log của Android

class WatchHentaiProvider : MainAPI() {
    override var mainUrl = "https://watchhentai.net"
    override var name = "WatchHentai"
    override val hasMainPage = true
    override var lang = "en"
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(TvType.NSFW)

    // Tag dùng để filter trong Logcat
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
        
        // 1. Lấy TẤT CẢ iframe trong khu vực player thay vì chỉ cái đầu tiên
        // Thêm selector "div#playarea iframe" và "div.entry-content iframe" để bao quát hơn
        val potentialIframes = document.select("iframe.metaframe, #playarea iframe, .player_container iframe, .entry-content iframe")
        
        var targetSrc: String? = null
        
        Log.d(TAG, "Found ${potentialIframes.size} potential iframes")

        // 2. Lặp qua để tìm iframe "xịn"
        for (iframe in potentialIframes) {
            var src = iframe.attr("src")
            
            // Check thêm data-src phòng trường hợp lazyload
            if (src.isBlank() || src.contains("about:blank")) {
                src = iframe.attr("data-src")
            }

            Log.d(TAG, "Checking iframe: $src")

            // Điều kiện chọn: Không rỗng, không phải about:blank, và phải chứa http hoặc source=
            if (src.isNotBlank() && !src.contains("about:blank") && (src.startsWith("http") || src.contains("source="))) {
                targetSrc = src
                Log.d(TAG, ">> SELECTED Valid Iframe: $targetSrc")
                break // Tìm thấy rồi thì dừng luôn
            }
        }

        if (targetSrc == null) {
            Log.e(TAG, "Error: No valid video iframe found (All were blank or missing)")
            return false
        }

        // 3. Xử lý logic decode như cũ với link đã tìm được
        val sourceParam = Regex("""source=([^&]+)""").find(targetSrc)?.groupValues?.get(1)
        
        val extractedUrl = if (sourceParam != null) {
            val decoded = java.net.URLDecoder.decode(sourceParam, "UTF-8")
            Log.d(TAG, "Decoded source param: $decoded")
            decoded
        } else {
            targetSrc
        }

        // 4. Phân loại và trả về link
        val linkType = when {
            extractedUrl.endsWith(".mp4") || extractedUrl.endsWith(".mkv") -> ExtractorLinkType.VIDEO
            extractedUrl.contains(".m3u8") -> ExtractorLinkType.M3U8
            else -> null
        }

        if (linkType != null) {
            val link = newExtractorLink(
                source = this.name,
                name = "${this.name} VIP",
                url = extractedUrl,
                type = linkType
            ) {
                this.referer = mainUrl
                this.quality = Qualities.Unknown.value
            }
            callback.invoke(link)
        } else {
            loadExtractor(extractedUrl, mainUrl, subtitleCallback) { link ->
                callback.invoke(link)
            }
        }

        Log.d(TAG, "<-- loadLinks FINISHED")
        return true
    }
}
