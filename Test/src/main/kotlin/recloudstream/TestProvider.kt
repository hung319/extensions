package com.lagradost.cloudstream3.hentai.providers

// Import các lớp cần thiết
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import org.jsoup.nodes.Element
import java.net.URLEncoder

class IHentaiProvider : MainAPI() {
    // --- Thông tin cơ bản ---
    override var mainUrl = "https://ihentai.ws"
    override var name = "iHentai"
    override val hasMainPage = true
    override var lang = "vi"
    override val supportedTypes = setOf(TvType.NSFW)

    private val userAgent = "Mozilla/5.0 (Linux; Android 14; SM-S711B Build/UP1A.231005.007) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/135.0.7049.111 Mobile Safari/537.36"
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
        return newHomePageResponse(homePageList, hasNext = false)
    }

    // --- Hàm Helper: Phân tích thẻ Item ---
    private fun parseSearchCard(element: Element): AnimeSearchResponse? {
        val linkElement = element.selectFirst("a:has(img.tw-w-full)") ?: return null
        val href = fixUrlNull(linkElement.attr("href")) ?: return null
        if (href.isBlank() || href == "/") { return null }

        val imageElement = linkElement.selectFirst("img.tw-w-full")
        var posterUrl = fixUrlNull(imageElement?.attr("src"))
        if (posterUrl.isNullOrBlank()) {
            posterUrl = fixUrlNull(imageElement?.attr("data-src"))
        }

        var title = imageElement?.attr("alt")?.trim()
        if (title.isNullOrBlank()) { title = imageElement?.attr("title")?.trim() }
        if (title.isNullOrBlank()) { title = element.selectFirst("a > div.v-card-text h2.text-subtitle-1")?.text()?.trim() }
        if (title.isNullOrBlank()) { title = linkElement.attr("title").trim() }
        if (title.isNullOrBlank()) return null

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

    // --- Tải thông tin chi tiết (*** THÊM LOGIC LẤY GỢI Ý ***) ---
    override suspend fun load(url: String): LoadResponse {
        val document = app.get(url, headers = headers).document
        val title = document.selectFirst("div.tw-mb-3 > h1.tw-text-lg")?.text()?.trim()
            ?: document.selectFirst("h1")?.text()?.trim()
            ?: throw RuntimeException("Không tìm thấy tiêu đề cho URL: $url")
        val posterUrl = fixUrlNull(document.selectFirst("div.tw-grid div.tw-col-span-5 img")?.attr("src"))
        val description = document.selectFirst("div.v-sheet.tw-p-5 > p.tw-text-sm")?.text()?.trim()
        val genres = document.select("div.v-sheet.tw-p-5 a.v-chip")?.mapNotNull { it.text()?.trim() }

        // --- BẮT ĐẦU LOGIC LẤY PHIM GỢI Ý ---
        // Tìm khu vực "Phim gợi ý"
        val recommendationsSection = document.select("div.tw-mb-5:has(h2:contains(Phim gợi ý))").firstOrNull()
        val recommendations = recommendationsSection?.select("div.tw-relative.tw-grid")?.mapNotNull { item ->
            val recTitle = item.selectFirst("h3 a")?.text()?.trim()
            val recUrl = fixUrlNull(item.selectFirst("h3 a")?.attr("href"))
            val recPoster = fixUrlNull(item.selectFirst("img")?.attr("src"))

            if (recTitle != null && recUrl != null) {
                newAnimeSearchResponse(recTitle, recUrl, TvType.NSFW) {
                    this.posterUrl = recPoster
                }
            } else {
                null
            }
        } ?: emptyList()
        // --- KẾT THÚC LOGIC LẤY PHIM GỢI Ý ---
        
        return newMovieLoadResponse(
            name = title,
            url = url,
            type = TvType.NSFW,
            dataUrl = url
        ) {
            this.posterUrl = posterUrl
            this.plot = description
            this.tags = genres
            // Thêm danh sách gợi ý vào response
            this.recommendations = recommendations
        }
    }

    // --- Tải Link Xem Phim/Video ---
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

            val masterM3u8Url = "https://s2.mimix.cc/$videoId/master.m3u8"
            
            val m3u8Headers = mapOf(
                "Referer" to iframeSrc,
                "User-Agent" to userAgent
            )
            
            callback(
                ExtractorLink(
                    source = name,
                    name = "$name (M3U8)",
                    url = masterM3u8Url,
                    referer = iframeSrc,
                    quality = Qualities.Unknown.value,
                    type = ExtractorLinkType.M3U8,
                    headers = m3u8Headers
                )
            )
            return true
        } catch (e: Exception) {
            e.printStackTrace()
            return false
        }
    }
}
