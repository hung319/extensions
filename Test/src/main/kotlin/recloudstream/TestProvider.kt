package recloudstream

// Thêm các import cần thiết
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Element
import java.net.URI

// Lớp Provider chính
class VeoHentaiProvider : MainAPI() {
    // Thông tin cơ bản của Provider
    override var mainUrl = "https://veohentai.com"
    override var name = "VeoHentai"
    override val hasMainPage = true
    override var lang = "es"
    override val supportedTypes = setOf(TvType.NSFW)

    // Dùng để parse JSON từ script
    private data class Source(val file: String, val label: String)
    private data class Track(val file: String, val label: String)

    // Hàm lấy danh sách item từ trang chủ và các trang con
    private fun Element.toSearchResult(): SearchResponse? {
        val titleElement = this.selectFirst("h2.entry-title a") ?: return null
        val title = titleElement.text()
        val href = titleElement.attr("href")
        val posterUrl = this.selectFirst("div.entry-media img")?.let {
            it.attr("data-src").ifBlank { it.attr("src") }
        }

        if (href.isBlank() || title.isBlank()) {
            return null
        }

        return newAnimeSearchResponse(title, href) {
            this.posterUrl = posterUrl
        }
    }

    // Tải trang chính
    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = if (page > 1) "$mainUrl/page/$page/" else mainUrl
        val document = app.get(url).document
        
        val home = document.select("div.page-content article").mapNotNull {
            it.toSearchResult()
        }

        return newHomePageResponse("Últimos Episodios", home)
    }

    // Chức năng tìm kiếm
    override suspend fun search(query: String): List<SearchResponse> {
        val searchUrl = "$mainUrl/search/$query/"
        val document = app.get(searchUrl).document

        return document.select("div.page-content article").mapNotNull {
            it.toSearchResult()
        }
    }

    // Tải thông tin chi tiết
    override suspend fun load(url: String): LoadResponse {
        val document = app.get(url).document

        val title = document.selectFirst("h1.entry-title")?.text()?.trim()
            ?: throw ErrorLoadingException("Không thể tải tiêu đề")

        val posterUrl = document.selectFirst("div.single-featured-image img")?.attr("src")
        val description = document.select("div.entry-content > p").joinToString("\n") { it.text() }
        val tags = document.select("span.tag-links a").map { it.text() }

        val episode = newEpisode(url) {
            name = title
        }

        return newAnimeLoadResponse(title, url, TvType.NSFW) {
            this.posterUrl = posterUrl
            this.plot = description
            this.tags = tags
            addEpisodes(DubStatus.Subbed, listOf(episode))
        }
    }

    // Tải link video
    override suspend fun loadLinks(
        data: String, // URL trang xem phim
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val document = app.get(data).document
        val serverItems = document.select("div#option-servers span.server-item")

        serverItems.apmap { server ->
            val embedUrl = server.attr("data-embed")
            if (embedUrl.isBlank()) return@apmap

            try {
                val embedDoc = app.get(embedUrl, referer = data).document
                val playerPath = embedDoc.selectFirst("li[data-id]")?.attr("data-id") ?: return@apmap
                
                val rootUrl = URI(embedUrl).let { "${it.scheme}://${it.host}" }
                val playerUrl = rootUrl + playerPath

                val playerContent = app.get(playerUrl, referer = embedUrl).text
                
                val sourcesJson = Regex("""var\s*F_sources\s*=\s*'([^']+)';""").find(playerContent)?.groupValues?.get(1)
                val tracksJson = Regex("""var\s*F_tracks\s*=\s*'([^']+)';""").find(playerContent)?.groupValues?.get(1)

                sourcesJson?.let { json ->
                    AppUtils.tryParseJson<List<Source>>(json)?.forEach { source ->
                        val quality = source.label.filter { it.isDigit() }.toIntOrNull()
                        callback.invoke(
                            ExtractorLink(
                                name = this.name,
                                source = "${server.text()} ${source.label}",
                                url = source.file,
                                referer = playerUrl,
                                quality = quality ?: Qualities.Unknown.value,
                                type = ExtractorLinkType.VIDEO // Đã thay đổi ở đây
                            )
                        )
                    }
                }

                tracksJson?.let { json ->
                    AppUtils.tryParseJson<List<Track>>(json)?.forEach { track ->
                        subtitleCallback.invoke(
                            SubtitleFile(
                                track.label,
                                track.file
                            )
                        )
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
        return true
    }
}
