// File: IHentai.kt (DEBUG TOOL v3 - FINAL)
package recloudstream

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import org.jsoup.Jsoup
import java.net.URLEncoder

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

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        return HomePageResponse(emptyList())
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        return false
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val debugUrl = "debug-data-key:$query"
        return listOf(
            newMovieSearchResponse(
                "CLICK HERE to get DEBUG DATA for '$query'",
                debugUrl,
            )
        )
    }

    override suspend fun load(url: String): LoadResponse? {
        if (!url.startsWith("debug-data-key:")) return null

        val query = url.removePrefix("debug-data-key:")
        // [FIX] Mã hóa ký tự tiếng Việt trong query để tạo URL hợp lệ
        val encodedQuery = URLEncoder.encode(query, "UTF-8")
        val searchUrl = "$mainUrl/tim-kiem?keyword=$encodedQuery"

        try {
            val document = app.get(searchUrl, headers = headers).text
            val nuxtData = getNuxtData(document) ?: return newMovieLoadResponse("DEBUG RESULT", url, TvType.Movie, url) {
                this.plot = "ERROR: Could not find __NUXT_DATA__ script tag on the page."
            }
            
            val jsonObject: Any = mapper.readValue(nuxtData)
            val prettyJson = mapper.writerWithDefaultPrettyPrinter().writeValueAsString(jsonObject)

            return newMovieLoadResponse("DEBUG DATA", url, TvType.Movie, url) {
                this.plot = "VUI LÒNG SAO CHÉP TOÀN BỘ NỘI DUNG BÊN DƯỚI VÀ GỬI LẠI CHO TÔI:\n\n--BEGIN JSON--\n\n$prettyJson\n\n--END JSON--"
            }

        } catch (e: Exception) {
            return newMovieLoadResponse("DEBUG RESULT", url, TvType.Movie, url) {
                this.plot = "AN ERROR OCCURRED:\n\n${e.stackTraceToString()}"
            }
        }
    }
}
