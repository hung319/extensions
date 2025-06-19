package recloudstream

// Import các thư viện cần thiết cho CloudStream
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import org.jsoup.nodes.Element

/**
 * Lớp chính của Provider, kế thừa từ MainAPI
 */
class HentaiHavenProvider : MainAPI() {
    // Thông tin cơ bản của plugin
    override var name = "HentaiHaven"
    override var mainUrl = "https://hentaihaven.xxx"
    override var lang = "en"
    
    override var supportedTypes = setOf(
        TvType.NSFW 
    )

    override val hasMainPage = true

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse? {
        val url = if (page == 1) mainUrl else "$mainUrl/page/$page/"
        val document = app.get(url).document
        
        val homePageList = mutableListOf<HomePageList>()

        document.select("div.vraven_home_slider")?.forEach { slider ->
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

                TvSeriesSearchResponse(
                    title,
                    href,
                    this.name,
                    TvType.NSFW,
                    posterUrl = image,
                    null,
                    null
                )
            }
            
            if (items.isNotEmpty()) {
                homePageList.add(HomePageList(header, items))
            }
        }
        
        if (homePageList.isEmpty()) return null
        return HomePageResponse(homePageList)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val url = "$mainUrl/?s=$query&post_type=wp-manga"
        val document = app.get(url).document
        
        return document.select("div.c-tabs-item__content").mapNotNull {
            val titleElement = it.selectFirst("div.post-title h3 a")
            val title = titleElement?.text() ?: return@mapNotNull null
            val href = titleElement.attr("href")
            val image = it.selectFirst("div.tab-thumb img")?.attr("src")

            TvSeriesSearchResponse(
                title,
                href,
                this.name,
                TvType.NSFW,
                posterUrl = image,
                null,
                null
            )
        }
    }
    
    /**
     * Hàm này được gọi khi người dùng chọn một bộ phim để xem thông tin chi tiết.
     */
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
            Episode(href, name)
        }.reversed()

        val recommendations = document.select("div.manga_related .related-reading-wrap").mapNotNull {
            val recTitleEl = it.selectFirst("h5.widget-title a")
            val recTitle = recTitleEl?.text()
            val recHref = recTitleEl?.attr("href")
            val recPoster = it.selectFirst(".related-reading-img img")?.attr("src")

            if (recTitle != null && recHref != null) {
                TvSeriesSearchResponse(
                    recTitle,
                    recHref,
                    this.name,
                    TvType.NSFW,
                    posterUrl = recPoster
                )
            } else {
                null
            }
        }

        // SỬA LỖI: Sắp xếp lại các tham số cho đúng thứ tự
        return TvSeriesLoadResponse(
            name = title,
            url = url,
            apiName = this.name,
            type = TvType.NSFW,
            data = episodes,
            posterUrl = poster,
            year = null,
            plot = description,
            tags = tags,
            showType = null,
            duration = null, // <-- Đặt là null
            rating = null,
            recommendations = recommendations
        )
    }
}

// Custom exception để báo lỗi rõ ràng hơn
open class ErrorLoadingException(message: String) : Exception(message)
