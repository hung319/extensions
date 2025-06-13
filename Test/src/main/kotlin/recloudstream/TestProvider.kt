package com.lagradost.cloudstream3.hentai.providers

// Import các lớp cần thiết cho cả Provider và Extractor
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.api.ExtractorApi
import com.lagradost.cloudstream3.api.ExtractorLink
import com.lagradost.cloudstream3.api.extractors.Extractor
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.M3u8Helper
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Element
import java.net.URLEncoder
import java.util.Base64

// =================================================================================
// --- PHẦN 1: PROVIDER CHÍNH (TÌM KIẾM, HIỂN THỊ THÔNG TIN) ---
// =================================================================================

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
        return HomePageResponse(homePageList)
    }

    // --- Hàm Helper: Phân tích thẻ Item ---
    private fun parseSearchCard(element: Element): AnimeSearchResponse? {
        val linkElement = element.selectFirst("a:has(img.tw-w-full)") ?: return null
        val href = fixUrlNull(linkElement.attr("href")) ?: return null
        if (href.isBlank() || href == "/") { return null }

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

    // --- Tải Link (Tạo link ảo .local) ---
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

            // 1. Tạo URL master playlist thật
            val masterM3u8Url = "https://s2.mimix.cc/$videoId/master.m3u8"

            // 2. Mã hóa link thật và referer bằng Base64
            val masterUrlEncoded = Base64.getEncoder().encodeToString(masterM3u8Url.toByteArray(Charsets.UTF_8))
            val refererEncoded = Base64.getEncoder().encodeToString(iframeSrc.toByteArray(Charsets.UTF_8))

            // 3. Tạo link ảo .local
            val virtualUrl = "https://ihentai.local/proxy.m3u8?url=$masterUrlEncoded&referer=$refererEncoded"
            println("IHentaiProvider: Generated virtual URL: $virtualUrl")

            // 4. Gọi callback với link ảo này. Extractor IHentaiExtractor sẽ xử lý nó.
            callback(
                ExtractorLink(
                    source = this.name,
                    name = "iHentai Server", // Chỉ cần 1 server
                    url = virtualUrl, // URL là link ảo
                    referer = data, // Referer là trang chứa iframe
                    quality = Qualities.Unknown.value, // Chất lượng sẽ được Extractor xác định
                    isM3u8 = true // Báo cho trình phát biết đây là M3U8
                )
            )
            return true

        } catch (e: Exception) {
            e.printStackTrace()
            return false
        }
    }
}


// =================================================================================
// --- PHẦN 2: EXTRACTOR XỬ LÝ LINK ẢO .local ---
// =================================================================================

// Lớp Extractor để xử lý domain ảo "ihentai.local"
open class IHentaiExtractor : Extractor() {
    override val name = "IHentaiExtractor"
    override val mainUrl = "https://ihentai.local" // Domain ảo mà extractor này sẽ xử lý
    override val requiresReferer = true

    // Hàm getUrl sẽ được gọi khi trình phát cố gắng mở link .local
    @Suppress("DEPRECATION")
    override suspend fun getUrl(
        url: String, // Đây là link ảo, ví dụ: https://ihentai.local/proxy.m3u8?url=...&referer=...
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
        headers: Map<String, String>?
    ) {
        try {
            // Phân tích link ảo để lấy dữ liệu đã mã hóa
            val masterUrlEncoded = url.substringAfter("url=").substringBefore("&")
            val refererEncoded = url.substringAfter("referer=")

            // Giải mã Base64 để lấy lại link thật
            val masterUrl = String(Base64.getDecoder().decode(masterUrlEncoded))
            val iframeReferer = String(Base64.getDecoder().decode(refererEncoded))

            println("IHentaiExtractor: Decoded masterM3u8Url -> $masterUrl")
            println("IHentaiExtractor: Decoded referer -> $iframeReferer")

            // Tạo headers để tải master M3U8
            val m3u8Headers = mapOf(
                "Referer" to iframeReferer,
                "User-Agent" to "Mozilla/5.0 (Linux; Android 14; SM-S711B Build/UP1A.231005.007) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/135.0.7049.111 Mobile Safari/537.36"
            )

            // Dùng M3u8Helper để phân giải master playlist và lấy các link chất lượng
            M3u8Helper.m3u8Generation(
                M3u8Helper.M3u8Stream(
                    streamUrl = masterUrl,
                    headers = m3u8Headers
                )
            ).forEach { stream ->
                // Gọi callback với các link video cuối cùng
                callback.invoke(
                    ExtractorLink(
                        this.name,
                        "${this.name} - ${stream.quality}p",
                        stream.streamUrl,
                        iframeReferer,
                        stream.quality ?: Qualities.Unknown.value,
                        type = ExtractorLinkType.M3U8,
                        headers = stream.headers // Pass along headers M3u8Helper có thể đã thêm/thay đổi
                    )
                )
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
}
