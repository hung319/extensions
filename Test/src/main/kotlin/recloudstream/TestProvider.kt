package recloudstream

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Element
import java.net.URLEncoder
import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.spec.*

class IhentaiProvider : MainAPI() {
    // --- Cấu hình Extension ---
    override var mainUrl = "https://ihentai.to"
    override var name = "iHentai"
    override val hasMainPage = true
    override var lang = "vi"
    override val supportedTypes = setOf(TvType.NSFW)

    companion object {
        // User-Agent giả lập Android để tránh bị chặn
        private const val USER_AGENT = "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/137.0.0.0 Mobile Safari/537.36"
        private val COMMON_HEADERS = mapOf("User-Agent" to USER_AGENT)
    }

    // --- 1. Trang chủ ---
    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val document = app.get(mainUrl, headers = COMMON_HEADERS).document
        val homePageList = mutableListOf<HomePageList>()

        // Lấy các section chính
        document.select("main > div.v-container > div.tw-mb-16").forEach { section ->
            val title = section.selectFirst("h1.tw-text-3xl")?.text()?.trim() ?: return@forEach
            val items = section.select("div.tw-grid > div.v-card").mapNotNull { parseSearchCard(it) }

            if (items.isNotEmpty()) {
                homePageList.add(HomePageList(title, items))
            }
        }

        // Fallback: Nếu không parse được section nào, thử lấy list mặc định
        if (homePageList.isEmpty()) {
            val latestItems = document.select("main.v-main div.v-container div.tw-grid > div.v-card")
                .mapNotNull { parseSearchCard(it) }

            if (latestItems.isNotEmpty()) {
                homePageList.add(HomePageList("Truyện Mới", latestItems))
            }
        }

        return newHomePageResponse(homePageList, hasNext = false)
    }

    // --- Helper: Parse Item Card ---
    private fun parseSearchCard(element: Element): AnimeSearchResponse? {
        val linkElement = element.selectFirst("a:has(img.tw-w-full)") ?: return null
        val href = fixUrlNull(linkElement.attr("href"))?.takeIf { it.isNotBlank() && it != "/" } ?: return null

        val imageElement = linkElement.selectFirst("img.tw-w-full")
        val posterUrl = fixUrlNull(imageElement?.attr("src"))
            ?: fixUrlNull(imageElement?.attr("data-src"))

        // Logic fallback title
        val title = imageElement?.attr("alt")?.trim()
            ?: imageElement?.attr("title")?.trim()
            ?: element.selectFirst("a > div.v-card-text h2.text-subtitle-1")?.text()?.trim()
            ?: linkElement.attr("title").trim()

        if (title.isNullOrBlank()) return null

        return newAnimeSearchResponse(title, fixUrl(href), TvType.NSFW) {
            this.posterUrl = posterUrl
        }
    }

    // --- 2. Tìm kiếm ---
    override suspend fun search(query: String): List<SearchResponse> {
        return try {
            val encodedQuery = URLEncoder.encode(query, "UTF-8")
            val searchUrl = "$mainUrl/search?s=$encodedQuery"
            val document = app.get(searchUrl, headers = COMMON_HEADERS).document

            document.select("main.v-main div.v-container div.tw-grid > div.v-card")
                .mapNotNull { parseSearchCard(it) }
        } catch (e: Exception) {
            e.printStackTrace()
            emptyList()
        }
    }

    // --- 3. Chi tiết phim ---
    override suspend fun load(url: String): LoadResponse {
        val document = app.get(url, headers = COMMON_HEADERS).document

        val title = document.selectFirst("div.tw-mb-3 > h1.tw-text-lg")?.text()?.trim()
            ?: document.selectFirst("h1")?.text()?.trim()
            ?: throw ErrorLoadingException("Không tìm thấy tiêu đề")

        val posterUrl = fixUrlNull(document.selectFirst("div.tw-grid div.tw-col-span-5 img")?.attr("src"))
        val description = document.selectFirst("div.v-sheet.tw-p-5 > p.tw-text-sm")?.text()?.trim()
        val genres = document.select("div.v-sheet.tw-p-5 a.v-chip").mapNotNull { it.text()?.trim() }

        // Lấy Recommendations
        val recommendations = document.select("div.tw-col-span-3.lg\\:tw-col-span-1 > div.tw-mb-5:has(h2)")
            .flatMap { section ->
                section.select("div.tw-relative.tw-grid").mapNotNull { item ->
                    val recTitle = item.selectFirst("h3 a")?.text()?.trim() ?: return@mapNotNull null
                    val recUrl = fixUrlNull(item.selectFirst("h3 a")?.attr("href")) ?: return@mapNotNull null
                    val recPoster = fixUrlNull(item.selectFirst("img")?.attr("src"))

                    newAnimeSearchResponse(recTitle, recUrl, TvType.NSFW) {
                        this.posterUrl = recPoster
                    }
                }
            }.distinctBy { it.url }

        return newMovieLoadResponse(title, url, TvType.NSFW, url) {
            this.posterUrl = posterUrl
            this.plot = description
            this.tags = genres
            this.recommendations = recommendations
        }
    }

    // --- 4. Load Links (Direct Decryption + newExtractorLink) ---
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        try {
            // Bước 1: Lấy HTML để tìm Video ID
            val document = app.get(data, headers = COMMON_HEADERS).document
            val iframeSrc = document.selectFirst("iframe.tw-w-full")?.attr("src") ?: return false

            // src format: https://play.sonar-cdn.com/watch?v=UUID
            val videoId = iframeSrc.substringAfter("?v=").substringBefore("&")
            if (videoId.isBlank()) return false

            // Bước 2: Gọi API lấy dữ liệu mã hóa (IV:Cipher)
            // Headers phải chuẩn để bypass CORS/Protection của Mimix
            val cryptoHeaders = mapOf(
                "Authority" to "x.mimix.cc",
                "Accept" to "*/*",
                "Origin" to "https://play.sonar-cdn.com",
                "Referer" to "https://play.sonar-cdn.com/",
                "User-Agent" to USER_AGENT
            )
            val apiUrl = "https://x.mimix.cc/watch/$videoId"

            val response = app.get(apiUrl, headers = cryptoHeaders).text
            
            // Format mong đợi: HEX_IV:HEX_DATA (VD: 460fe0...:a583...)
            if (!response.contains(":")) return false

            // Bước 3: Giải mã AES-CTR
            val decryptedUrl = decryptMimix(response, videoId)

            if (decryptedUrl.startsWith("http")) {
                callback(
                    newExtractorLink(
                        source = name,
                        name = "iHentai (VIP)",
                        url = decryptedUrl,
                        type = ExtractorLinkType.M3U8
                    ) {
                        this.referer = "https://play.sonar-cdn.com/"
                        this.quality = Qualities.Unknown.value
                    }
                )
                return true
            }

        } catch (e: Exception) {
            e.printStackTrace()
        }
        return false
    }

    // --- Crypto Helper: AES-CTR + SHA256 ---
    private fun decryptMimix(encryptedString: String, keySeed: String): String {
        return try {
            val parts = encryptedString.split(":")
            if (parts.size != 2) return ""

            val ivHex = parts[0]
            val cipherHex = parts[1]

            // 1. Tạo Key từ VideoID (SHA-256)
            val md = MessageDigest.getInstance("SHA-256")
            val keyBytes = md.digest(keySeed.toByteArray(Charsets.UTF_8))
            val secretKey = SecretKeySpec(keyBytes, "AES")

            // 2. Chuyển Hex sang Byte
            val ivBytes = hexToBytes(ivHex)
            val cipherBytes = hexToBytes(cipherHex)
            val ivSpec = IvParameterSpec(ivBytes)

            // 3. Giải mã AES/CTR/NoPadding
            val cipher = Cipher.getInstance("AES/CTR/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, secretKey, ivSpec)

            val decryptedBytes = cipher.doFinal(cipherBytes)
            String(decryptedBytes, Charsets.UTF_8)

        } catch (e: Exception) {
            e.printStackTrace()
            ""
        }
    }

    // Utility: Chuyển Hex String sang ByteArray thủ công
    private fun hexToBytes(hex: String): ByteArray {
        val len = hex.length
        val data = ByteArray(len / 2)
        var i = 0
        while (i < len) {
            data[i / 2] = ((Character.digit(hex[i], 16) shl 4) +
                    Character.digit(hex[i + 1], 16)).toByte()
            i += 2
        }
        return data
    }
}
