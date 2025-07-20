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

    // Data class để phân tích JSON chứa thông tin poster
    private data class PlayerData(val image: String?)
    private data class PlayerApiResponse(val data: PlayerData?)

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        // Bước 1 & 2: Vào trang tập phim và lấy link iframe
        val document = app.get(data).document
        val iframeSrc = document.selectFirst("div.player_logic_item iframe")?.attr("src")
            ?: throw ErrorLoadingException("Không tìm thấy iframe của trình phát.")

        // Bước 3: Dùng lại logic POST request để lấy dữ liệu JSON đáng tin cậy
        // vì đây là cách trang web hoạt động, thay vì phân tích HTML của iframe
        val encodedData = iframeSrc.substringAfter("?data=", "")
        if (encodedData.isBlank()) throw ErrorLoadingException("Không có tham số data trong iframe.")

        val decodedString = String(java.util.Base64.getDecoder().decode(encodedData))

        val paramA: String
        val paramB: String
        val regex = "(.+?):[|\\]]::\\|:(.+)".toRegex()
        val match = regex.find(decodedString)

        if (match != null && match.groupValues.size >= 3) {
            paramA = match.groupValues[1]
            paramB = match.groupValues[2]
        } else {
            val parts = decodedString.split("::")
            if (parts.size < 3) throw ErrorLoadingException("Định dạng dữ liệu không xác định: '$decodedString'")
            paramA = parts[0]
            paramB = parts[2]
        }
        
        val apiUrl = "$mainUrl/wp-content/plugins/player-logic/api.php"
        val postData = mapOf("action" to "zarat_get_data_player_ajax", "a" to paramA, "b" to paramB)
        val headers = mapOf(
            "Origin" to mainUrl,
            "Referer" to iframeSrc,
            "User-Agent" to "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/125.0.0.0 Mobile Safari/537.36"
        )

        val apiResponseText = app.post(apiUrl, data = postData, headers = headers).text
        if (apiResponseText.isBlank()) {
            throw ErrorLoadingException("API trả về phản hồi rỗng, có thể do Cloudflare chặn.")
        }
        
        val apiResponse = parseJson<PlayerApiResponse>(apiResponseText)

        // Bước 4 & 5: Lấy URL poster và trích xuất slug
        val posterUrl = apiResponse.data?.image
            ?: throw ErrorLoadingException("Không tìm thấy URL poster trong phản hồi API.")

        // posterUrl có dạng: https://himg.nl/images/hh/{video-slug}/poster.jpg
        val videoSlug = posterUrl.split("/").getOrNull(5)
            ?: throw ErrorLoadingException("Không thể trích xuất video slug từ URL poster: $posterUrl")

        // Bước 6: Tạo link M3U8 cuối cùng
        val m3u8Url = "https://master-lengs.org/api/v3/hh/$videoSlug/master.m3u8"

        callback(
            newExtractorLink(
                source = this.name,
                name = this.name,
                url = m3u8Url,
                type = ExtractorLinkType.M3U8,
            ) {
                this.quality = getQualityFromName("Auto")
            }
        )
        return true
    }
}

open class ErrorLoadingException(message: String) : Exception(message)
