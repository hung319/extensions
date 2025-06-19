package recloudstream

// Import các thư viện cần thiết
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import org.jsoup.nodes.Element

class HentaiHavenProvider : MainAPI() {
    override var name = "HentaiHaven"
    override var mainUrl = "https://hentaihaven.xxx"
    override var lang = "en"
    override var supportedTypes = setOf(TvType.NSFW)
    override val hasMainPage = true

    // Dùng để định nghĩa cấu trúc JSON trả về từ API
    private data class Source(
        val src: String?,
        val label: String?
    )
    private data class PlayerData(
        val sources: List<Source>?
    )
    private data class ApiResponse(
        val data: PlayerData?
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

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val episodePage = app.get(data).document
        val iframeUrl = episodePage.selectFirst("div.player_logic_item iframe")?.attr("src")
            ?: throw ErrorLoadingException("Không tìm thấy iframe của trình phát")
        
        val encodedData = Regex("""data=([^&"]+)""").find(iframeUrl)?.groupValues?.get(1)
            ?: throw ErrorLoadingException("Không tìm thấy dữ liệu mã hóa trong URL iframe")
        
        val apiUrl = "$mainUrl/wp-content/plugins/player-logic/api.php"

        // SỬA ĐỔI: Thêm header "X-Requested-With" vào yêu cầu POST
        val response = app.post(
            url = apiUrl,
            headers = mapOf("X-Requested-With" to "XMLHttpRequest"), // <-- Header quan trọng
            data = mapOf("id" to encodedData),
            referer = iframeUrl
        ).text
        
        if (response.isBlank()) throw ErrorLoadingException("API trả về nội dung rỗng, có thể do lớp bảo vệ.")

        parseJson<ApiResponse>(response).data?.sources?.forEach { source ->
            val videoUrl = source.src ?: return@forEach
            val quality = source.label ?: "Default"
            
            callback(
                ExtractorLink(
                    source = this.name,
                    name = "${this.name} $quality",
                    url = videoUrl,
                    referer = mainUrl,
                    quality = quality.filter { it.isDigit() }.toIntOrNull() ?: 0,
                    type = if (videoUrl.contains(".m3u8")) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                )
            )
        }
        
        return true
    }
}

open class ErrorLoadingException(message: String) : Exception(message)
