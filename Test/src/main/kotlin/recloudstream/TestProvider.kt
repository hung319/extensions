package recloudstream

// Import các thư viện cần thiết
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.getQualityFromName
import com.lagradost.cloudstream3.utils.newExtractorLink
import org.jsoup.nodes.Element
import java.util.Base64

class HentaiHavenProvider : MainAPI() {
    override var name = "HentaiHaven"
    override var mainUrl = "https://hentaihaven.xxx"
    override var lang = "en"
    override var supportedTypes = setOf(TvType.NSFW)
    override val hasMainPage = true

    // Data class cho JSON trả về từ API
    private data class Source(val src: String?, val label: String?)
    private data class VideoData(val sources: List<Source>?)
    private data class ApiResponse(val status: Boolean?, val data: VideoData?)

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
        // Khởi tạo một danh sách để lưu lại toàn bộ quá trình
        val debugLog = mutableListOf<String>()
        debugLog.add("--- HentaiHaven Debug Log ---")

        try {
            debugLog.add("1. Bắt đầu loadLinks với data: $data")

            val document = app.get(data).document
            debugLog.add("2. Lấy HTML của trang tập phim thành công.")

            val iframeSrc = document.selectFirst("div.player_logic_item iframe")?.attr("src")
                ?: throw ErrorLoadingException("Không tìm thấy iframe của trình phát.")
            debugLog.add("3. Tìm thấy iframe src: $iframeSrc")

            val encodedData = iframeSrc.substringAfter("?data=", "")
            if (encodedData.isBlank()) {
                throw ErrorLoadingException("Không tìm thấy tham số 'data' trong URL của iframe.")
            }
            debugLog.add("4. Trích xuất encodedData: ${encodedData.take(60)}...")

            val decodedString = String(Base64.getDecoder().decode(encodedData))
            debugLog.add("5. Chuỗi sau khi giải mã Base64: '$decodedString'")

            val parts = decodedString.split("::")
            debugLog.add("6. Tách chuỗi thành ${parts.size} phần.")

            if (parts.size < 3) {
                throw ErrorLoadingException("Dữ liệu sau khi giải mã không hợp lệ (không đủ 3 phần).")
            }

            val paramA = parts[0]
            val paramB = parts[2]
            debugLog.add("7. Lấy paramA: ${paramA.take(60)}...")
            debugLog.add("8. Lấy paramB: $paramB")

            val apiUrl = "$mainUrl/wp-content/plugins/player-logic/api.php"
            val postData = mapOf("action" to "zarat_get_data_player_ajax", "a" to paramA, "b" to paramB)
            val headers = mapOf(
                "Origin" to mainUrl,
                "Referer" to iframeSrc,
                "Accept" to "*/*",
                "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/108.0.0.0 Safari/537.36"
            )

            debugLog.add("9. Gửi POST request đến API: $apiUrl")
            val apiResponseText = app.post(apiUrl, data = postData, headers = headers).text
            debugLog.add("10. Phản hồi từ API: $apiResponseText")

            val apiResponse = parseJson<ApiResponse>(apiResponseText)

            if (apiResponse.status == true) {
                val sourceCount = apiResponse.data?.sources?.size ?: 0
                debugLog.add("11. API trả về status true. Tìm thấy $sourceCount link.")
                apiResponse.data?.sources?.forEach { source ->
                    val videoUrl = source.src ?: return@forEach
                    val quality = source.label ?: "Default"
                    debugLog.add("  -> Trích xuất link: $quality - $videoUrl")

                    callback(
                        newExtractorLink(
                            source = this.name,
                            name = quality,
                            url = videoUrl,
                            type = ExtractorLinkType.M3U8
                        ) {
                            this.quality = getQualityFromName(quality)
                        }
                    )
                }
                debugLog.add("\n[SUCCESS] Hoàn tất quá trình lấy link.")
            } else {
                throw ErrorLoadingException("API không trả về link hoặc có lỗi xảy ra.")
            }

            // Ném ra Exception chứa log ngay cả khi thành công
            throw Exception(debugLog.joinToString("\n"))
            
        } catch (e: Exception) {
            // Nếu có lỗi, thêm thông tin lỗi vào log và ném ra
            if (!e.message.orEmpty().contains("HentaiHaven Debug Log")) { // Tránh ghi lặp lại tiêu đề
                debugLog.add("\n[ERROR] Đã xảy ra lỗi: ${e.message}")
                debugLog.add("Stack Trace:\n${e.stackTraceToString().take(500)}...")
            }
            throw Exception(debugLog.joinToString("\n"))
        }
    }
}

open class ErrorLoadingException(message: String) : Exception(message)
