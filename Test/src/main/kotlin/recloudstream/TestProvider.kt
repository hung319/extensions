package com.lagradost.cloudstream3.hentai.providers

// Import các lớp cần thiết
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLinkType
// M3u8Helper không còn cần nữa
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Element
import java.net.URLEncoder
// import com.lagradost.cloudstream3.network.CloudflareKiller

// --- Lớp Provider ---

class IHentaiProvider : MainAPI() {
    // --- Thông tin cơ bản ---
    override var mainUrl = "https://ihentai.ws"
    override var name = "iHentai"
    override val hasMainPage = true
    override var lang = "vi"
    override val supportedTypes = setOf(TvType.NSFW)

    // *** Thêm User-Agent chung cho tất cả request ***
    private val userAgent = "Mozilla/5.5 (Linux; Android 14; SM-S711B Build/UP1A.231005.007) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/135.0.7049.111 Mobile Safari/537.36"
    private val headers = mapOf("User-Agent" to userAgent)

    // --- Trang chính (Dùng parse HTML) ---
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

    // --- Hàm Helper: Phân tích thẻ Item ---
    private fun parseSearchCard(element: Element): AnimeSearchResponse? {
        val linkElement = element.selectFirst("a:has(img.tw-w-full)") ?: return null
        val href = fixUrlNull(linkElement.attr("href")) ?: return null
        if (href.isBlank() || href == "/") { return null }

        val imageElement = linkElement.selectFirst("img.tw-w-full")
        var posterUrl = fixUrlNull(imageElement?.attr("src"))
        if (posterUrl.isNullOrBlank()) { // Thêm fallback cho posterUrl nếu src không có
            posterUrl = fixUrlNull(imageElement?.attr("data-src"))
        }

        var title = imageElement?.attr("alt")?.trim()
        if (title.isNullOrBlank()) { title = imageElement?.attr("title")?.trim() }
        if (title.isNullOrBlank()) { title = element.selectFirst("a > div.v-card-text h2.text-subtitle-1")?.text()?.trim() }
        if (title.isNullOrBlank()) { title = linkElement.attr("title").trim() }
        if (title.isNullOrBlank()) return null

        // URL của SearchResponse trỏ thẳng đến trang tập phim
        return newAnimeSearchResponse(title, fixUrl(href), TvType.NSFW) {
            this.posterUrl = posterUrl
        }
    }

    // --- Tìm kiếm (Dùng parse HTML) ---
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

    // --- Tải thông tin chi tiết (Dùng parse HTML, chỉ 1 tập) ---
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

    // --- Tải Link Xem Phim/Video (Sử dụng ExtractorLink trực tiếp) ---
    override suspend fun loadLinks(
        data: String, // data là URL của trang tập phim
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

            // Tạo URL master playlist mới
            val masterM3u8Url = "https://s2.mimix.cc/$videoId/master.m3u8"
            println("IHentaiProvider: Generated master M3U8 URL: $masterM3u8Url")

            // Headers để tải M3U8 (Referer là trang iframe)
            val m3u8Headers = mapOf(
                "Referer" to iframeSrc,
                "User-Agent" to userAgent
            )

            // Trực tiếp tạo ExtractorLink với URL M3U8 đã biết
            callback(
                ExtractorLink(
                    source = name,
                    name = "$name (M3U8)", // Tên hiển thị cho link
                    url = masterM3u8Url,
                    referer = iframeSrc, // Referer vẫn là iframe
                    quality = Qualities.Unknown.value, // Chất lượng có thể được điều chỉnh nếu bạn có thông tin
                    type = ExtractorLinkType.M3U8,
                    headers = m3u8Headers // Truyền headers cho trình phát
                )
            )
            return true
        } catch (e: Exception) {
            e.printStackTrace()
            return false
        }
    }
}
