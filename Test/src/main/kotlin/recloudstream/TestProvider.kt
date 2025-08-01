// Tên file: WatchHentaiProvider.kt
package recloudstream

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Element
import com.lagradost.cloudstream3.network.CloudflareKiller
import com.lagradost.cloudstream3.extractors.helper.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities

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
        // Trang web sử dụng /page/2/, /page/3/, ... cho các trang tiếp theo
        val url = if (page == 1) request.data else "${request.data}page/$page/"
        val document = app.get(url, interceptor = cfInterceptor).document
        
        // Selector 'article.item' bao quát tất cả các mục trên trang chủ
        val home = document.select("article.item").mapNotNull {
            it.toSearchResult()
        }
        return newHomePageResponse(request.name, home)
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val href = this.selectFirst("a")?.attr("href") ?: return null
        val posterUrl = this.selectFirst("img")?.let {
            // Ưu tiên 'data-src' cho lazy-loading images
            it.attr("data-src").ifBlank { it.attr("src") }
        }

        // Cập nhật logic lấy tiêu đề để xử lý các layout khác nhau
        val seriesTitle = this.selectFirst("span.serie")?.text()?.trim()
        val episodeTitle = this.selectFirst("h3")?.text()?.trim()
        val defaultTitle = this.selectFirst("h3 a, .title")?.text()?.trim()

        val title = if (seriesTitle != null && episodeTitle != null) {
            "$seriesTitle - $episodeTitle" // Dành cho layout "Recent Episodes"
        } else {
            episodeTitle ?: defaultTitle ?: return null // Dành cho layout series và slider
        }

        // Phân biệt giữa series và tập lẻ (được coi như phim)
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
        // Selector cho kết quả tìm kiếm
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
            val synopsis = document.selectFirst("div.wp-content p, div.synopsis p")?.text()?.trim()
            val episodes = document.select("ul.episodios li").mapNotNull { el ->
                val epHref = el.selectFirst("div.episodiotitle a")?.attr("href") ?: return@mapNotNull null
                val epTitle = el.selectFirst("div.episodiotitle a")?.text()?.trim() ?: "Episode"
                val epPoster = el.selectFirst("div.imagen img")?.attr("data-src")
                val epNum = epTitle.substringAfter("Episode ").toIntOrNull()

                newEpisode(url = epHref) {
                    this.name = epTitle
                    this.posterUrl = epPoster
                    this.episode = epNum
                }
            }.reversed() // Đảo ngược để tập mới nhất lên đầu

            // Lấy danh sách phim đề xuất
            val recommendations = document.select("div#single_relacionados article, div#dt-episodes article").mapNotNull {
                it.toSearchResult()
            }

            newTvSeriesLoadResponse(title, url, TvType.NSFW, episodes) {
                this.posterUrl = posterUrl
                this.plot = synopsis
                this.recommendations = recommendations
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
        data: String, // URL của trang xem phim
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val document = app.get(data, interceptor = cfInterceptor).document
        // Video được nhúng trong một iframe
        val iframeSrc = document.selectFirst("iframe.metaframe")?.attr("src") ?: return false

        // Logic mới: URL video được truyền trực tiếp qua tham số 'source' trong src của iframe.
        // Điều này hiệu quả và nhanh hơn nhiều so với việc parse Javascript.
        val sourceUrl =
            Regex("""source=([^&]+)""").find(iframeSrc)?.groupValues?.get(1)?.let {
                // Giải mã URL-encoded string
                java.net.URLDecoder.decode(it, "UTF-8")
            } ?: return false

        // HTML không cung cấp nhãn chất lượng rõ ràng.
        // File "-raw.mp4" cho thấy đây là file gốc. Ta sẽ để chất lượng là không xác định.
        callback(
            ExtractorLink(
                source = this.name,
                name = this.name, // Tên của provider
                url = sourceUrl,
                referer = mainUrl, // Trang web có thể yêu cầu referer
                quality = Qualities.Unknown.value,
                type = if (sourceUrl.contains(".m3u8")) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
            )
        )

        return true
    }
}
