// Tên file: HoatHinhKungfuProvider.kt
package recloudstream

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Element

/**
 * Senior Software Engineer's Note:
 * This is an updated provider for hhkungfu.click.
 * Version 10 Changelog:
 * - FIXED: Build errors by strictly adhering to the provided data class definitions.
 * - REMOVED: Assignment to `latestEpisodeName` as it does not exist in the user's TvSeriesSearchResponse class.
 * - IMPLEMENTED: `hasNext` logic in `getMainPage` to correctly use the `HomePageResponse(items, hasNext)` constructor.
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

    // ĐÃ SỬA: Loại bỏ `latestEpisodeName`
    private fun Element.toSearchResponse(): SearchResponse? {
        val title = this.selectFirst("h2.entry-title")?.text() ?: return null
        val href = this.selectFirst("a.halim-thumb")?.attr("href") ?: return null
        val posterUrl = this.selectFirst("figure > img")?.let {
            it.attr("data-src").ifBlank { it.attr("src") }
        }

        return newTvSeriesSearchResponse(title, href, TvType.TvSeries) {
            this.posterUrl = posterUrl
            // Không gán `latestEpisodeName` nữa vì nó không tồn tại
        }
    }

    // ĐÃ SỬA: Sử dụng đúng constructor HomePageResponse(items, hasNext)
    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = if (page <= 1) mainUrl else "$mainUrl/latest-movie/page/$page/"
        val document = app.get(url).document

        val articles = if (page <= 1) {
            document.select("section#halim-advanced-widget-3 article.grid-item")
        } else {
            document.select("main#main-contents div.halim_box article")
        }
        
        val homePageList = articles.mapNotNull {
            it.toSearchResponse()
        }

        // Kiểm tra xem có trang tiếp theo không
        val hasNext = document.selectFirst("a.next.page-numbers") != null

        val items = listOf(HomePageList("Mới Cập Nhật", homePageList))
        
        // Trả về theo đúng cấu trúc data class
        return HomePageResponse(items, hasNext)
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

        document.selectFirst("div#ajax-player iframe")?.let {
            it.attr("data-src").ifBlank { it.attr("src") }
        }?.let { sources.add(it) }

        document.select("div#halim-ajax-list-serverX span.get-eps").forEach {
            it.attr("data-link")?.let { link ->
                if (link.isNotBlank()) sources.add(link)
            }
        }

        sources.apmap { sourceUrl ->
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
            else if ("player.cloudbeta.win" in sourceUrl) {
                try {
                    val uuid = sourceUrl.substringAfterLast('/')
                    val m3u8Link = "https://play.cloudbeta.win/file/play/$uuid.m3u8"
                    callback.invoke(
                        ExtractorLink("CloudBeta", "CloudBeta", m3u8Link, sourceUrl, Qualities.Unknown.value, type = ExtractorLinkType.M3U8)
                    )
                } catch (e: Exception) { /* Bỏ qua lỗi */ }
            }
            else {
                loadExtractor(sourceUrl, mainUrl, subtitleCallback, callback)
            }
        }

        return sources.isNotEmpty()
    }
}
