package recloudstream

// Import các thư viện cần thiết
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import org.jsoup.nodes.Element
import android.util.Base64
// SỬA LỖI: Thêm tất cả các import cần thiết
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.newExtractorLink

class HentaiHavenProvider : MainAPI() {
    override var name = "HentaiHaven"
    override var mainUrl = "https://hentaihaven.xxx"
    override var lang = "en"
    override var supportedTypes = setOf(TvType.NSFW)
    override val hasMainPage = true

    // Data class cho JSON đã được giải mã
    private data class Source(
        val file: String?,
        val label: String?
    )
    private data class DecryptedResponse(
        val sources: List<Source>?
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = if (page == 1) mainUrl else "$mainUrl/page/$page/"
        val document = app.get(url).document
        
        val homePageList = mutableListOf<HomePageList>()

        document.select("div.vraven_home_slider").forEach { slider ->
            var header = slider.selectFirst("div.home_slider_header h4")?.text() ?: "Unknown"

            if (header.contains("New Hentai")) {
                header = "New Hentai"
            }
            
            val items = slider.select("div.item.vraven_item").mapNotNull { el ->
                val titleEl = el.selectFirst(".post-title a")
                val title = titleEl?.text() ?: return@mapNotNull null
                val href = titleEl.attr("href")
                val image = el.selectFirst(".item-thumb img")?.let {
                    it.attr("data-src").ifBlank { it.attr("src") }
                }

                newTvSeriesSearchResponse(title, href, TvType.NSFW) {
                    this.posterUrl = image
                }
            }
            
            if (items.isNotEmpty()) {
                homePageList.add(HomePageList(header, items))
            }
        }
        
        if (homePageList.isEmpty()) throw ErrorLoadingException("Không tải được trang chính hoặc không tìm thấy nội dung.")
        
        return newHomePageResponse(homePageList)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val url = "$mainUrl/?s=$query&post_type=wp-manga"
        val document = app.get(url).document
        
        return document.select("div.c-tabs-item__content").mapNotNull {
            val titleElement = it.selectFirst("div.post-title h3 a")
            val title = titleElement?.text() ?: return@mapNotNull null
            val href = titleElement.attr("href")
            val image = it.selectFirst("div.tab-thumb img")?.attr("src")

            newTvSeriesSearchResponse(title, href, TvType.NSFW) {
                this.posterUrl = image
            }
        }
    }
    
    override suspend fun load(url: String): LoadResponse {
        val document = app.get(url).document

        val title = document.selectFirst("div.post-title h1")?.text()?.trim()
            ?: throw ErrorLoadingException("Không thể tải được tiêu đề")
        
        val poster = document.selectFirst("div.summary_image img")?.attr("src")
        val description = document.selectFirst("div.description-summary div.summary__content")?.text()?.trim()
        val tags = document.select("div.genres-content a").map { it.text() }

        val episodes = document.select("ul.main.version-chap li.wp-manga-chapter").mapNotNull {
            val link = it.selectFirst("a") ?: return@mapNotNull null
            val name = link.text().trim()
            val href = link.attr("href")
            newEpisode(href) {
                this.name = name
            }
        }.reversed()

        val recommendations = document.select("div.manga_related .related-reading-wrap").mapNotNull {
            val recTitleEl = it.selectFirst("h5.widget-title a")
            val recTitle = recTitleEl?.text() ?: return@mapNotNull null
            val recHref = recTitleEl.attr("href")
            val recPoster = it.selectFirst(".related-reading-img img")?.attr("src")

            newTvSeriesSearchResponse(recTitle, recHref, TvType.NSFW) {
                this.posterUrl = recPoster
            }
        }

        return newTvSeriesLoadResponse(
            name = title,
            url = url,
            type = TvType.NSFW,
            episodes = episodes,
        ) {
            this.posterUrl = poster
            this.plot = description
            this.tags = tags
            this.recommendations = recommendations
        }
    }

    private fun decryptSource(encoded: String): String {
        val key = "93422192433952489752342908585764"
        val decoded = Base64.decode(encoded, Base64.DEFAULT)
        val result = decoded.mapIndexed { index, byte ->
            (byte.toInt() xor key[index % key.length].code).toByte()
        }.toByteArray()
        return String(result)
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val episodePage = app.get(data).document
        
        // Thử phương pháp giải mã dữ liệu nhúng
        try {
            val scriptContent = episodePage.select("script#raven-js-extra").html()
            val encodedData = Regex("""(?:"downloads"|"fembed_down"|"mirror_down"): *"([^"]+)"""").find(scriptContent)?.groupValues?.get(1)

            if (encodedData != null && encodedData.isNotBlank() && encodedData != "2") {
                val decryptedJson = decryptSource(encodedData)
                parseJson<DecryptedResponse>(decryptedJson).sources?.forEach { source ->
                    val videoUrl = source.file ?: return@forEach
                    val quality = source.label ?: "Default"
                    
                    // SỬA LỖI: Sử dụng cấu trúc newExtractorLink với ExtractorLinkType và initializer block
                    callback(
                        newExtractorLink(
                            source = this.name,
                            name = "${this.name} $quality",
                            url = videoUrl,
                            type = if (videoUrl.contains(".m3u8")) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                        ) {
                            this.quality = quality.filter { it.isDigit() }.toIntOrNull() ?: 0
                            this.referer = mainUrl
                        }
                    )
                }
                return true // Nếu thành công thì kết thúc
            }
        } catch (_: Exception) { /* Bỏ qua và thử cách khác */ }

        // Nếu phương pháp trên thất bại, báo lỗi
        throw ErrorLoadingException("Không thể tìm thấy link video. Trang web có thể đã cập nhật hoặc video này dùng một phương pháp không được hỗ trợ.")
    }
}

open class ErrorLoadingException(message: String) : Exception(message)
