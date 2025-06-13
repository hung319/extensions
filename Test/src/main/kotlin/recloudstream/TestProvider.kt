// File: IHentai.kt (DEBUG TOOL v5 - FINAL)
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
        // Trả về một item debug duy nhất cho trang chủ
        val debugUrl = "debug-homepage-key"
        return HomePageResponse(listOf(
            HomePageList("DEBUG HOMEPAGE", listOf(
                newMovieSearchResponse("CLICK HERE to get HOMEPAGE DEBUG DATA", debugUrl)
            ))
        ))
    }

    override suspend fun loadLinks(
        data: String, isCasting: Boolean, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit
    ): Boolean { return false }

    override suspend fun search(query: String): List<SearchResponse> {
        val debugUrl = "debug-search-key:$query"
        return listOf(
            newMovieSearchResponse("CLICK HERE to get SEARCH DEBUG DATA for '$query'", debugUrl)
        )
    }

    override suspend fun load(url: String): LoadResponse? {
        val isHomePage = url == "debug-homepage-key"
        val isSearch = url.startsWith("debug-search-key:")
        
        if (!isHomePage && !isSearch) return null

        val targetUrl = if (isHomePage) {
            mainUrl
        } else {
            val query = url.removePrefix("debug-search-key:")
            val encodedQuery = URLEncoder.encode(query, "UTF-8")
            "$mainUrl/tim-kiem?keyword=$encodedQuery"
        }

        try {
            val document = app.get(targetUrl, headers = headers).text
            val nuxtData = getNuxtData(document) ?: return newMovieLoadResponse("DEBUG", url, TvType.Movie, url) {
                this.plot = "ERROR: Could not find __NUXT_DATA__ script tag."
            }
            
            val jsonObject: Any = mapper.readValue(nuxtData)
            val prettyJson = mapper.writerWithDefaultPrettyPrinter().writeValueAsString(jsonObject)

            return newMovieLoadResponse("DEBUG DATA", url, TvType.Movie, url) {
                this.plot = "VUI LÒNG SAO CHÉP TOÀN BỘ NỘI DUNG BÊN DƯỚI VÀ GỬI LẠI CHO TÔI:\n\n--BEGIN JSON for ${if(isHomePage) "Homepage" else "Search"}--\n\n$prettyJson\n\n--END JSON--"
            }

        } catch (e: Exception) {
            return newMovieLoadResponse("DEBUG ERROR", url, TvType.Movie, url) {
                this.plot = "AN ERROR OCCURRED:\n\n${e.stackTraceToString()}"
            }
        }
    }
}
