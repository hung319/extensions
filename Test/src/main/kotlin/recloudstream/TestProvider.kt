// File: IHentai.kt (DEBUG TOOL v4 - FINAL)
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
    
    // Các hàm load/loadLinks không cần thiết
    override suspend fun load(url: String): LoadResponse? { return null }
    override suspend fun loadLinks(
        data: String, isCasting: Boolean, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit
    ): Boolean { return false }

    /**
     * This function now fetches the JSON and splits it into chunks,
     * displaying each chunk as the title of a search result.
     */
    override suspend fun search(query: String): List<SearchResponse> {
        val encodedQuery = URLEncoder.encode(query, "UTF-8")
        val searchUrl = "$mainUrl/tim-kiem?keyword=$encodedQuery"

        try {
            val document = app.get(searchUrl, headers = headers).text
            val nuxtData = getNuxtData(document) ?: return listOf(
                newMovieSearchResponse("ERROR: Could not find __NUXT_DATA__", "error1")
            )

            // Định dạng và chia nhỏ JSON
            val jsonObject: Any = mapper.readValue(nuxtData)
            val prettyJson = mapper.writerWithDefaultPrettyPrinter().writeValueAsString(jsonObject)
            val chunks = prettyJson.chunked(250) // Chia thành các phần nhỏ 250 ký tự

            // Trả về mỗi phần như một kết quả tìm kiếm
            return chunks.mapIndexed { index, chunk ->
                newMovieSearchResponse("Part ${index + 1}/${chunks.size}: $chunk", "debug-part-$index")
            }

        } catch (e: Exception) {
            // Nếu có lỗi, hiển thị lỗi
            val errorChunks = e.stackTraceToString().chunked(250)
            return errorChunks.mapIndexed { index, chunk ->
                 newMovieSearchResponse("ERROR ${index+1}: $chunk", "error-part-$index")
            }
        }
    }
}
