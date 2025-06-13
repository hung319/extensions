// File: IHentai.kt (DEBUG TOOL - FILE LOGGER)
package recloudstream

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import org.jsoup.Jsoup
import java.net.URLEncoder
import java.io.File
import android.os.Environment

class IHentai : MainAPI() {
    override var name = "iHentai (File Logger)"
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
        val debugUrl = "log-homepage-data"
        return HomePageResponse(listOf(
            HomePageList("DEBUG TOOL", listOf(
                newMovieSearchResponse("CLICK HERE to LOG HOMEPAGE DATA", debugUrl)
            ))
        ))
    }
    
    override suspend fun search(query: String): List<SearchResponse> {
        val debugUrl = "log-search-data:$query"
        return listOf(
            newMovieSearchResponse("CLICK HERE to LOG SEARCH DATA for '$query'", debugUrl)
        )
    }

    override suspend fun loadLinks(
        data: String, isCasting: Boolean, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit
    ): Boolean { return false }

    override suspend fun load(url: String): LoadResponse? {
        val isHomePage = url == "log-homepage-data"
        val isSearch = url.startsWith("log-search-data:")
        
        if (!isHomePage && !isSearch) return null

        val (fetchUrl, logSource) = if (isHomePage) {
            mainUrl to "Homepage"
        } else {
            val query = url.removePrefix("log-search-data:")
            val encodedQuery = URLEncoder.encode(query, "UTF-8")
            "$mainUrl/search?s=$encodedQuery" to "Search for '$query'"
        }

        var plot: String
        try {
            val document = app.get(fetchUrl, headers = headers).text
            val nuxtData = getNuxtData(document) ?: "ERROR: Could not find __NUXT_DATA__ script tag."
            
            val logFile = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "cloudstream_ihentai_log.txt")
            
            // Định dạng và ghi file
            try {
                val jsonObject: Any = mapper.readValue(nuxtData)
                val prettyJson = mapper.writerWithDefaultPrettyPrinter().writeValueAsString(jsonObject)
                val logContent = "--- LOG FOR: $logSource ---\n\n$prettyJson\n\n"
                logFile.appendText(logContent, Charsets.UTF_8)
                plot = "SUCCESS: Data for '$logSource' has been written to 'Download/cloudstream_ihentai_log.txt'"
            } catch (e: Exception) {
                val errorContent = "--- FAILED TO PARSE JSON FOR: $logSource ---\n\n${e.stackTraceToString()}\n\n--- RAW DATA ---\n\n$nuxtData\n\n"
                logFile.appendText(errorContent, Charsets.UTF_8)
                plot = "ERROR: Failed to parse JSON, but raw data has been written to 'Download/cloudstream_ihentai_log.txt'"
            }
        } catch (e: Exception) {
            val logFile = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "cloudstream_ihentai_log.txt")
            val errorContent = "--- FAILED TO FETCH URL FOR: $logSource ---\n\n${e.stackTraceToString()}\n\n"
            logFile.appendText(errorContent, Charsets.UTF_8)
            plot = "FATAL ERROR: Could not fetch URL. Error details written to 'Download/cloudstream_ihentai_log.txt'"
        }

        return newMovieLoadResponse("DEBUGGER", url, TvType.Movie, url) {
            this.plot = plot
        }
    }
}
