package recloudstream

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.getQualityFromName
import com.lagradost.cloudstream3.utils.newExtractorLink
import org.jsoup.nodes.Element
import java.net.URI

class HentaiHavenProvider : MainAPI() {
    override var name = "HentaiHaven"
    override var mainUrl = "https://hentaihaven.xxx"
    override var lang = "en"
    override var supportedTypes = setOf(TvType.NSFW)
    override val hasMainPage = true

    // --- Các hàm getMainPage, search, load giữ nguyên ---
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
    // --- Kết thúc các hàm giữ nguyên ---

    /**
     * Hàm loadLinks đã được đơn giản hóa hoàn toàn
     * để tạo link M3U8 trực tiếp từ URL của tập phim.
     */
    override suspend fun loadLinks(
        data: String, // data là URL của trang tập phim
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        // Lấy đường dẫn từ URL, ví dụ: "/watch/oni-chichi-harem/episode-4"
        val path = URI(data).path

        // Biến đổi đường dẫn thành slug video
        // 1. Bỏ "/watch/" -> "oni-chichi-harem/episode-4"
        // 2. Thay "/episode-" bằng "-" -> "oni-chichi-harem-4"
        // 3. Thêm "-eng" -> "oni-chichi-harem-4-eng"
        val videoSlug = path.removePrefix("/watch/")
            .replace("/episode-", "-")
            .plus("-eng")

        // Tạo URL M3U8 cuối cùng
        val m3u8Url = "https://master-lengs.org/api/v3/hh/$videoSlug/master.m3u8"

        callback(
            newExtractorLink(
                source = this.name,
                name = this.name, // Đặt tên mặc định
                url = m3u8Url,
                type = ExtractorLinkType.M3U8
            ) {
                this.quality = getQualityFromName("Auto")
            }
        )

        return true
    }
}

open class ErrorLoadingException(message: String) : Exception(message)
