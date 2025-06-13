// File: IHentai.kt (DEBUG VERSION)
package recloudstream

import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import com.lagradost.cloudstream3.*
import org.jsoup.Jsoup

class IHentai : MainAPI() {
    override var name = "iHentai (DEBUG TOOL)"
    override var mainUrl = "https://ihentai.ws"
    override var lang = "vi"
    override val hasMainPage = true
    override val supportedTypes = setOf(
        TvType.NSFW
    )

    private val mapper = jacksonObjectMapper()

    private val headers = mapOf(
        "User-Agent" to "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/116.0.0.0 Mobile Safari/537.36",
        "Referer" to "$mainUrl/"
    )

    private fun getNuxtData(html: String): String? {
        return Jsoup.parse(html).selectFirst("script[id=__NUXT_DATA__]")?.data()
    }

    // Trang chủ tạm thời trống
    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        return HomePageResponse(emptyList())
    }

    // Hàm load và loadLinks không cần thiết cho việc debug
    override suspend fun load(url: String): LoadResponse? {
        return null
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        return false
    }

    /**
     * This function is now a debug tool. It fetches the raw NUXT JSON
     * and displays it in a search result for you to copy.
     */
    override suspend fun search(query: String): List<SearchResponse> {
        val url = "$mainUrl/tim-kiem?keyword=$query"
        try {
            val document = app.get(url, headers = headers).text
            val nuxtData = getNuxtData(document) ?: return listOf(
                newMovieSearchResponse("DEBUG RESULT", url) {
                    plot = "ERROR: Could not find __NUXT_DATA__ script tag on the page."
                }
            )

            // Định dạng lại JSON cho dễ đọc
            val jsonObject: Any = mapper.readValue(nuxtData)
            val prettyJson = mapper.writerWithDefaultPrettyPrinter().writeValueAsString(jsonObject)

            // Trả về một kết quả duy nhất chứa toàn bộ JSON
            return listOf(
                newMovieSearchResponse("DEBUG: Raw JSON for query: '$query'", url) {
                    this.plot = "VUI LÒNG SAO CHÉP TOÀN BỘ NỘI DUNG BÊN DƯỚI VÀ GỬI LẠI CHO TÔI:\n\n--BEGIN JSON--\n\n$prettyJson\n\n--END JSON--"
                }
            )

        } catch (e: Exception) {
            // Nếu có lỗi, hiển thị lỗi
            return listOf(
                newMovieSearchResponse("DEBUG RESULT", url) {
                    plot = "AN ERROR OCCURRED:\n\n${e.stackTraceToString()}"
                }
            )
        }
    }
}
