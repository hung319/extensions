package com.lagradost.cloudstream3.hentai.providers

// Import các lớp cần thiết
import android.content.Context
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.M3u8Helper
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.AppUtils.fixUrlNull
import com.lagradost.cloudstream3.utils.AppUtils.fixUrl
import org.jsoup.nodes.Element
import java.net.URLEncoder

@CloudstreamPlugin
class IHentaiPlugin: Plugin() {
    override fun load(context: Context) {
        registerMainAPI(IHentaiProvider())
    }
}

class IHentaiProvider : MainAPI() {
    override var mainUrl = "https://ihentai.ws"
    override var name = "iHentai"
    override val hasMainPage = true
    override var lang = "vi"
    override val supportedTypes = setOf(TvType.NSFW)

    private val userAgent = "Mozilla/5.0 (Linux; Android 14; SM-S711B Build/UP1A.231005.007) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/135.0.7049.111 Mobile Safari/537.36"
    private val headers = mapOf("User-Agent" to userAgent)

    override suspend fun getMainPage(page: Int, request : MainPageRequest): HomePageResponse {
        val document = app.get(mainUrl, headers = headers).document
        val homePageList = mutableListOf<HomePageList>()

        document.select("main > div.v-container > div.tw-mb-16").forEach { section ->
            try {
                val title = section.selectFirst("h1.tw-text-3xl")?.text()?.trim() ?: "Không rõ tiêu đề"
                val items = section.select("div.tw-grid > div.v-card").mapNotNull { element ->
                    parseSearchCard(element)
                }
                if (items.isNotEmpty()) {
                    homePageList.add(HomePageList(title, items))
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
        if (homePageList.isEmpty()) {
             try {
                 val latestItems = document.select("main.v-main div.v-container div.tw-grid > div.v-card").mapNotNull { element ->
                     parseSearchCard(element)
                 }
                 if (latestItems.isNotEmpty()) {
                    homePageList.add(HomePageList("Truyện Mới", latestItems))
                 }
             } catch(e: Exception) {
                  e.printStackTrace()
             }
        }
        return HomePageResponse(homePageList)
    }

    private fun parseSearchCard(element: Element): AnimeSearchResponse? {
        val linkElement = element.selectFirst("a:has(img.tw-w-full)") ?: return null
        val href = fixUrlNull(linkElement.attr("href")) ?: return null
        if (href.isBlank() || href == "/") return null

        val imageElement = linkElement.selectFirst("img.tw-w-full")
        val posterUrl = fixUrlNull(imageElement?.attr("src"))
        var title = imageElement?.attr("alt")?.trim()
        if (title.isNullOrBlank()) { title = imageElement?.attr("title")?.trim() }
        if (title.isNullOrBlank()) { title = element.selectFirst("a > div.v-card-text h2.text-subtitle-1")?.text()?.trim() }
        if (title.isNullOrBlank()) { title = linkElement.attr("title").trim() }
        if (title.isNullOrBlank()) return null

        return newAnimeSearchResponse(title, fixUrl(href), TvType.NSFW) {
            this.posterUrl = posterUrl
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val encodedQuery = URLEncoder.encode(query, "UTF-8")
        val searchUrl = "$mainUrl/search?s=$encodedQuery"
        return try {
            val document = app.get(searchUrl, headers = headers).document
            val resultsGrid = document.selectFirst("main.v-main div.v-container div.tw-grid")
            resultsGrid?.select("div.v-card")?.mapNotNull { element ->
                parseSearchCard(element)
            } ?: emptyList()
        } catch (e: Exception) {
            e.printStackTrace()
            emptyList()
        }
    }

    override suspend fun load(url: String): LoadResponse {
        val document = app.get(url, headers = headers).document
        val title = document.selectFirst("div.tw-mb-3 > h1.tw-text-lg")?.text()?.trim()
            ?: document.selectFirst("h1")?.text()?.trim()
            ?: throw RuntimeException("Không tìm thấy tiêu đề cho URL: $url")
        val posterUrl = fixUrlNull(document.selectFirst("div.tw-grid div.tw-col-span-5 img")?.attr("src"))
        val description = document.selectFirst("div.v-sheet.tw-p-5 > p.tw-text-sm")?.text()?.trim()
        val genres = document.select("div.v-sheet.tw-p-5 a.v-chip")?.mapNotNull { it.text()?.trim() }
        val currentEpisode = Episode(data = url, name = title)
        val episodes = listOf(currentEpisode)
        return newAnimeLoadResponse(title, url, TvType.NSFW) {
            this.posterUrl = posterUrl
            this.plot = description
            this.tags = genres
            addEpisodes(DubStatus.Subbed, episodes)
        }
    }

    // --- Tải Link Xem Phim/Video (Sử dụng M3u8Helper) ---
    @Suppress("DEPRECATION")
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        try {
            val document = app.get(data, headers = headers).document
            val iframeSrc = document.selectFirst("iframe.tw-w-full")?.attr("src")
                ?: throw RuntimeException("Không tìm thấy iframe video trên trang: $data")

            val videoId = iframeSrc.substringAfterLast("?v=", "").substringBefore("&")
            if (videoId.isBlank()) {
                throw RuntimeException("Không thể trích xuất videoId từ iframe src: $iframeSrc")
            }

            // Tạo URL master playlist theo cấu trúc bạn yêu cầu
            val masterM3u8Url = "https://s2.mimix.cc/$videoId/master.m3u8"
            println("IHentaiProvider: Generated master M3U8 URL: $masterM3u8Url")
            println("IHentaiProvider: This URL previously failed with 404. Retrying as requested.")

            // Headers để tải M3U8 (Referer là trang iframe)
            val m3u8Headers = mapOf(
                "Referer" to iframeSrc,
                "User-Agent" to userAgent
            )

            // Dùng M3u8Helper để lấy các luồng video từ master playlist
            M3u8Helper.generateM3u8(
                this.name, // Nguồn
                masterM3u8Url, // URL master
                iframeSrc, // Referer
                headers = m3u8Headers // Headers
            ).forEach { stream ->
                // stream ở đây đã là một ExtractorLink được M3u8Helper tạo sẵn
                println("IHentaiProvider: Found stream -> Quality: ${stream.quality}p, URL: ${stream.url}")
                callback(stream)
            }
            return true
        } catch (e: Exception) {
            e.printStackTrace()
            println("IHentaiProvider: Failed to process M3U8 link. Error: ${e.message}")
            return false
        }
    }
}
