// File: IHentai.kt (DEBUG TOOL - POSTER VERSION)
package recloudstream

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import org.jsoup.Jsoup
import java.net.URLEncoder

class IHentai : MainAPI() {
    override var name = "iHentai (POST-DEBUG)"
    override var mainUrl = "https://ihentai.ws"
    override var lang = "vi"
    override val hasMainPage = true
    override val supportedTypes = setOf(
        TvType.NSFW
    )

    private val headers = mapOf(
        "User-Agent" to "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/116.0.0.0 Mobile Safari/537.36",
        "Referer" to "$mainUrl/"
    )

    private fun getNuxtData(html: String): String? {
        return Jsoup.parse(html).selectFirst("script[id=__NUXT_DATA__]")?.data()
    }

    // Hàm tiện ích để POST log
    private suspend fun postLog(content: String, source: String) {
        val logUrl = "https://text.h4rs.pp.ua/upload/text/log.txt"
        try {
            // Định dạng lại JSON cho dễ đọc trước khi gửi
            val mapper = jacksonObjectMapper()
            val jsonObject = mapper.readValue(content, Any::class.java)
            val prettyJson = mapper.writerWithDefaultPrettyPrinter().writeValueAsString(jsonObject)

            val logContent = "--- LOG FROM IHENTAI PROVIDER (Source: $source) ---\n\n$prettyJson"
            
            // Gửi dữ liệu dưới dạng text
            app.post(logUrl, data = logContent, headers = mapOf("Content-Type" to "text/plain"))
        } catch (e: Exception) {
            // Nếu có lỗi, gửi thông báo lỗi
            app.post(logUrl, data = "--- ERROR PARSING OR POSTING (Source: $source) ---\n\n${e.stackTraceToString()}")
        }
    }
    
    // Các hàm load/loadLinks không cần thiết
    override suspend fun load(url: String): LoadResponse? { return null }
    override suspend fun loadLinks(
        data: String, isCasting: Boolean, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit
    ): Boolean { return false }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        return try {
            val document = app.get(mainUrl, headers = headers).text
            val nuxtData = getNuxtData(document) ?: return HomePageResponse(listOf(HomePageList("Homepage Error", listOf(newMovieSearchResponse("Could not get Nuxt Data", "error")))))

            postLog(nuxtData, "Homepage")

            HomePageResponse(listOf(HomePageList("Homepage Debug", listOf(
                newMovieSearchResponse("SUCCESS", "Homepage data has been POSTed to your URL.")
            ))))
        } catch (e: Exception) {
            postLog(e.stackTraceToString(), "Homepage GET Error")
            HomePageResponse(listOf(HomePageList("Homepage Error", listOf(
                newMovieSearchResponse("Failed to fetch page", "error")
            ))))
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val encodedQuery = URLEncoder.encode(query, "UTF-8")
        val searchUrl = "$mainUrl/search?s=$encodedQuery"

        return try {
            val document = app.get(searchUrl, headers = headers).text
            val nuxtData = getNuxtData(document) ?: return listOf(
                newMovieSearchResponse("Search Error", "Could not get Nuxt Data")
            )

            postLog(nuxtData, "Search for '$query'")

            listOf(
                newMovieSearchResponse("SUCCESS", "Search data for '$query' has been POSTed to your URL.")
            )
        } catch (e: Exception) {
            postLog(e.stackTraceToString(), "Search GET Error for '$query'")
            listOf(
                newMovieSearchResponse("Search Error", "Failed to fetch page. See log.")
            )
        }
    }
}
