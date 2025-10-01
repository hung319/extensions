// Tên file: HoatHinhKungfuProvider.kt
package recloudstream

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Element

/**
 * Senior Software Engineer's Note:
 * This is an updated provider for hhkungfu.click.
 * Version 8 Changelog:
 * - Optimized `player.cloudbeta.win` extractor to build m3u8 link directly, removing an HTTP request for faster link loading.
 * - Added custom extractor for `ssplay.net` to handle dynamic source URLs.
 * - Switched to using the `newEpisode` helper function.
 * - Implemented pagination for the main page.
 * - Added a custom internal extractor for `viupload.net`.
 */
class HoatHinhKungfuProvider : MainAPI() {
    override var mainUrl = "https://hhkungfu.click"
    override var name = "Hoạt Hình Kungfu"
    override val hasMainPage = true
    override var lang = "vi"
    override val hasDownloadSupport = true

    override val supportedTypes = setOf(
        TvType.Anime,
        TvType.TvSeries,
        TvType.Cartoon
    )

    private fun Element.toSearchResponse(): SearchResponse? {
        val title = this.selectFirst("h2.entry-title")?.text() ?: return null
        val href = this.selectFirst("a.halim-thumb")?.attr("href") ?: return null
        val posterUrl = this.selectFirst("figure > img")?.let {
            it.attr("data-src").ifBlank { it.attr("src") }
        }

        return newTvSeriesSearchResponse(title, href, TvType.TvSeries) {
            this.posterUrl = posterUrl
            this.latestEpisode = this@toSearchResponse.selectFirst("span.episode")?.text()
        }
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val document = app.get(mainUrl).document
        val homePageList = mutableListOf<HomePageList>()

        val latestUpdatesItems = document.select("section#halim-advanced-widget-3 article.grid-item").mapNotNull {
            it.toSearchResponse()
        }
        
        homePageList.add(HomePageList("Mới Cập Nhật", latestUpdatesItems, url = "$mainUrl/latest-movie/page/"))
        
        return HomePageResponse(homePageList)
    }

    override suspend fun loadPage(url: String): LoadPageResponse {
        val document = app.get(url).document

        val items = document.select("main#main-contents div.halim_box article").mapNotNull {
            it.toSearchResponse()
        }

        val nextUrl = document.selectFirst("a.next.page-numbers")?.attr("href")

        return newTvSeriesLoadPageResponse(url, items) {
            this.nextUrl = nextUrl
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val searchUrl = "$mainUrl/?s=$query"
        val document = app.get(searchUrl).document

        return document.select("div.halim_box article.thumb").mapNotNull {
            it.toSearchResponse()
        }
    }

    override suspend fun load(url: String): LoadResponse {
        val document = app.get(url).document

        val title = document.selectFirst("h1.entry-title")?.text()?.trim()
            ?: throw RuntimeException("Không tìm thấy tiêu đề")

        val posterUrl = document.selectFirst("img.movie-thumb")?.attr("src")
        val plot = document.select("div.video-item.halim-entry-box article p").joinToString("\n\n") { it.text() }
        val tags = document.select("p.category a").map { it.text() }

        val episodes = document.select("ul.halim-list-eps li a").mapNotNull {
            val href = it.attr("href")
            val name = it.text().trim()
            if (href.isNotBlank() && name.isNotBlank()) {
                newEpisode(href) {
                    this.name = "Tập $name"
                }
            } else {
                null
            }
        }.reversed()

        return newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
            this.posterUrl = posterUrl
            this.plot = plot
            this.tags = tags
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val document = app.get(data).document
        val sources = mutableListOf<String>()

        // 1. Nguồn chính
        document.selectFirst("div#ajax-player iframe")?.let {
            it.attr("data-src").ifBlank { it.attr("src") }
        }?.let { sources.add(it) }

        // 2. Các nguồn dự phòng
        document.select("div#halim-ajax-list-serverX span.get-eps").forEach {
            it.attr("data-link")?.let { link ->
                if (link.isNotBlank()) sources.add(link)
            }
        }

        // Xử lý song song các nguồn link
        sources.apmap { sourceUrl ->
            // Custom extractor cho viupload.net
            if ("viupload.net" in sourceUrl) {
                try {
                    val embedContent = app.get(sourceUrl, referer = mainUrl).text
                    val m3u8Regex = Regex("""sources:\s*\[\{file:"([^"]+)""")
                    m3u8Regex.find(embedContent)?.groupValues?.get(1)?.let { m3u8Link ->
                        callback.invoke(
                            ExtractorLink("ViUpload", "ViUpload", m3u8Link, sourceUrl, Qualities.Unknown.value, type = ExtractorLinkType.M3U8)
                        )
                    }
                } catch (e: Exception) { /* Bỏ qua lỗi */ }
            } 
            // Custom extractor cho ssplay.net
            else if ("ssplay.net" in sourceUrl) {
                try {
                    val embedDocument = app.get(sourceUrl, referer = mainUrl).document
                    val scriptContent = embedDocument.select("script").firstOrNull { 
                        it.data().contains("sources:") 
                    }?.data()

                    if (scriptContent != null) {
                        val fileRegex = Regex("""file:\s*"(.*?)"""")
                        var m3u8Url = fileRegex.find(scriptContent)?.groupValues?.get(1)

                        if (m3u8Url != null) {
                            if (m3u8Url.startsWith("/")) {
                                m3u8Url = "https://ssplay.net$m3u8Url"
                            }
                            callback.invoke(
                                ExtractorLink("SSPlay", "SSPlay", m3u8Url, sourceUrl, Qualities.Unknown.value, type = ExtractorLinkType.M3U8)
                            )
                        }
                    }
                } catch (e: Exception) { /* Bỏ qua lỗi */ }
            }
            // Tối ưu: Custom extractor cho cloudbeta.win
            else if ("player.cloudbeta.win" in sourceUrl) {
                try {
                    // Lấy UUID từ cuối URL embed
                    val uuid = sourceUrl.substringAfterLast('/')
                    // Xây dựng trực tiếp link .m3u8 mà không cần request thêm
                    val m3u8Link = "https://play.cloudbeta.win/file/play/$uuid.m3u8"
                    
                    callback.invoke(
                        ExtractorLink("CloudBeta", "CloudBeta", m3u8Link, sourceUrl, Qualities.Unknown.value, type = ExtractorLinkType.M3U8)
                    )
                } catch (e: Exception) { /* Bỏ qua lỗi */ }
            }
            else {
                // Sử dụng extractor mặc định cho các host khác
                loadExtractor(sourceUrl, mainUrl, subtitleCallback, callback)
            }
        }

        return sources.isNotEmpty()
    }
}
