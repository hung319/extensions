package recloudstream

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.AppUtils
import com.lagradost.cloudstream3.utils.apmap
import org.jsoup.nodes.Element
import java.net.URI

class VeoHentaiProvider : MainAPI() {
    override var mainUrl = "https://veohentai.com"
    override var name = "VeoHentai"
    override val hasMainPage = true
    override var lang = "es"
    override val supportedTypes = setOf(TvType.NSFW)

    // *** Bỏ qua Cloudflare vì bạn xác nhận không phải vấn đề ***
    // Tuy nhiên, User-Agent vẫn cần thiết để tránh bị chặn cơ bản
    private val headers = mapOf(
        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/108.0.0.0 Safari/537.36"
    )

    // Parser cho Cấu trúc #2 (Layout mới: div#posts-home > a)
    private fun Element.toSearchResultFromNewLayout(): SearchResponse? {
        val href = this.attr("href")
        // Đảm bảo href là một URL hợp lệ
        if (!href.startsWith("http")) return null
        
        val title = this.selectFirst("h2")?.text()?.trim() ?: return null
        val posterUrl = this.selectFirst("figure img")?.attr("src")
        
        if (title.isBlank()) return null
        
        return newAnimeSearchResponse(title, href) {
            this.posterUrl = posterUrl
        }
    }

    // Parser cho Cấu trúc #1 (Layout cũ: div.page-content > article)
    private fun Element.toSearchResultFromOldLayout(): SearchResponse? {
        val titleElement = this.selectFirst("h2.entry-title a") ?: return null
        val href = titleElement.attr("href")
        val title = titleElement.text()
        val posterUrl = this.selectFirst("div.entry-media img")?.let {
            it.attr("data-src").ifBlank { it.attr("src") }
        }

        if (href.isBlank() || title.isBlank()) return null

        return newAnimeSearchResponse(title, href) {
            this.posterUrl = posterUrl
        }
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = if (page > 1) "$mainUrl/page/$page/" else mainUrl
        val document = app.get(url, headers = headers).document

        // Bước 1: Thử tìm item theo layout mới (div#posts-home a)
        var results = document.select("div#posts-home a").mapNotNull { it.toSearchResultFromNewLayout() }

        // Bước 2: Nếu không thấy, thử lại với layout cũ (article)
        if (results.isEmpty()) {
            results = document.select("div.page-content article").mapNotNull { it.toSearchResultFromOldLayout() }
        }

        if (results.isEmpty() && page == 1) {
            throw RuntimeException("Thất bại: Không tìm thấy item nào với cả hai cấu trúc HTML.")
        }

        return newHomePageResponse("Últimos Episodios", results)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val searchUrl = "$mainUrl/search/$query/"
        val document = app.get(searchUrl, headers = headers).document

        // Trang tìm kiếm luôn dùng layout cũ (article), nên ta chỉ cần 1 selector
        return document.select("div.page-content article").mapNotNull { it.toSearchResultFromOldLayout() }
    }


    override suspend fun load(url: String): LoadResponse {
        val document = app.get(url, headers = headers).document
        val title = document.selectFirst("h1.entry-title")?.text()?.trim()
            ?: throw ErrorLoadingException("Không thể tải tiêu đề")
        val posterUrl = document.selectFirst("div.single-featured-image img")?.attr("src")
        val description = document.select("div.entry-content > p").joinToString("\n") { it.text() }
        val tags = document.select("span.tag-links a").map { it.text() }
        val episode = newEpisode(url) { name = title }
        return newAnimeLoadResponse(title, url, TvType.NSFW) {
            this.posterUrl = posterUrl
            this.plot = description
            this.tags = tags
            addEpisodes(DubStatus.Subbed, listOf(episode))
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val document = app.get(data, headers = headers).document
        val serverItems = document.select("div#option-servers span.server-item")
        serverItems.apmap { server ->
            val embedUrl = server.attr("data-embed")
            if (embedUrl.isBlank()) return@apmap
            try {
                val embedDoc = app.get(embedUrl, referer = data, headers = headers).document
                val playerPath = embedDoc.selectFirst("li[data-id]")?.attr("data-id") ?: return@apmap
                val rootUrl = URI(embedUrl).let { "${it.scheme}://${it.host}" }
                val playerUrl = rootUrl + playerPath
                val playerContent = app.get(playerUrl, referer = embedUrl, headers = headers).text
                val sourcesJson = Regex("""var\s*F_sources\s*=\s*'([^']+)';""").find(playerContent)?.groupValues?.get(1)
                val tracksJson = Regex("""var\s*F_tracks\s*=\s*'([^']+)';""").find(playerContent)?.groupValues?.get(1)
                sourcesJson?.let { AppUtils.tryParseJson<List<Source>>(it)?.forEach { source ->
                    callback(ExtractorLink(name, "${server.text()} ${source.label}", source.file, playerUrl, Qualities.Unknown.value, type = ExtractorLinkType.VIDEO))
                }}
                tracksJson?.let { AppUtils.tryParseJson<List<Track>>(it)?.forEach { track ->
                    subtitleCallback(SubtitleFile(track.label, track.file))
                }}
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
        return true
    }

    private data class Source(val file: String, val label: String)
    private data class Track(val file: String, val label: String)
}
