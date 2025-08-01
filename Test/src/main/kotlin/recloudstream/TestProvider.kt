package recloudstream

// Import các thư viện cần thiết
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import org.jsoup.nodes.Element

// Provider class
class NikPornProvider : MainAPI() {
    // Thông tin cơ bản về provider
    override var mainUrl = "https://nikporn.com"
    override var name = "NikPorn"
    override val hasMainPage = true
    override var lang = "en"
    override val supportedTypes = setOf(TvType.NSFW)
    
    override val hasDownloadSupport = true
    override val hasChromecastSupport = true

    override val mainPage = mainPageOf(
        "$mainUrl/" to "Hot Videos",
        "$mainUrl/new/" to "New Videos",
        "$mainUrl/top-rated/" to "Top Rated",
        "$mainUrl/most-popular/" to "Most Popular"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = request.data
        val fullUrl = if (page > 1) url + page else url
        
        val document = app.get(fullUrl, referer = mainUrl).document
        val items = document.select("div.list-videos .item").mapNotNull {
            try {
                parseVideoCard(it)
            } catch (e: Exception) {
                null
            }
        }
        
        val hasNext = document.selectFirst("li.next > a") != null
        return newHomePageResponse(request.name, items, hasNext = hasNext)
    }

    private fun parseVideoCard(element: Element): SearchResponse {
        val link = element.selectFirst("a")!!
        val href = fixUrl(link.attr("href"))
        val title = link.selectFirst("strong.title")?.text()?.trim() ?: "N/A"
        val image = link.selectFirst("img.thumb")?.attr("data-original")

        return newMovieSearchResponse(title, href, TvType.NSFW) {
            this.posterUrl = image
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val searchUrl = "$mainUrl/search/$query/"
        val document = app.get(searchUrl, referer = mainUrl).document

        return document.select("div.list-videos .item").mapNotNull {
            try {
                parseVideoCard(it)
            } catch (e: Exception) {
                null
            }
        }
    }

    override suspend fun load(url: String): LoadResponse {
        val document = app.get(url, referer = mainUrl).document

        val title = document.selectFirst("h1")?.text()?.trim()
            ?: document.selectFirst("meta[property=og:title]")?.attr("content") ?: "N/A"
        
        val poster = document.selectFirst("meta[property=og:image]")?.attr("content")
        val description = document.selectFirst("meta[property=og:description]")?.attr("content")
        
        val recommendations = document.select("div.related-videos .item").mapNotNull {
            try {
                parseVideoCard(it)
            } catch (e: Exception) {
                null
            }
        }

        return newMovieLoadResponse(title, url, TvType.NSFW, data = url) {
            this.posterUrl = poster
            this.plot = description
            this.recommendations = recommendations
        }
    }

    // --- Thuật toán giải mã URL được tái tạo từ JavaScript ---
    private fun decodeUrl(encodedUrl: String): String {
        try {
            val parts = encodedUrl.split('/')
            if (parts.size < 7) return encodedUrl // Không đủ thành phần để giải mã

            val keyString = "16" // Giá trị này được suy ra từ phân tích JS
            var hashPart = parts[6]
            var h = hashPart.substring(0, 2 * keyString.toInt())

            // Logic hoán đổi ký tự
            for (k in h.length - 1 downTo 0) {
                var l = k
                for (m in k until keyString.length) {
                    l += keyString[m].toString().toInt()
                }
                while (l >= h.length) {
                    l -= h.length
                }

                val charArray = h.toCharArray()
                val temp = charArray[k]
                charArray[k] = charArray[l]
                charArray[l] = temp
                h = String(charArray)
            }
            
            val originalHashSubstring = parts[6].substring(0, h.length)
            val decodedHashPart = hashPart.replace(originalHashSubstring, h)
            
            // Nối lại URL đã giải mã
            val finalParts = parts.toMutableList()
            finalParts[6] = decodedHashPart
            finalParts.removeAt(0) // Xóa số 0
            return finalParts.joinToString("/")
        } catch (e: Exception) {
            // Nếu giải mã thất bại, trả về URL gốc
            return encodedUrl.replace("function/0/", "")
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val document = app.get(data, referer = mainUrl).document
        var foundLinks = false

        val scriptContent = document.select("script").find { it.data().contains("kt_player") }?.data()

        if (scriptContent != null) {
            val startIndex = scriptContent.indexOf("{")
            val endIndex = scriptContent.lastIndexOf("};")
            
            if (startIndex != -1 && endIndex > startIndex) {
                val objectContent = scriptContent.substring(startIndex + 1, endIndex)

                objectContent.split(',').forEach { part ->
                    val trimmedPart = part.trim()
                    
                    if (trimmedPart.startsWith("'video_url':") || trimmedPart.startsWith("video_url:")) {
                        val encodedUrl = trimmedPart.substringAfter(":'").substringBeforeLast("'")
                        if (encodedUrl.isNotBlank()) {
                            val decodedUrl = decodeUrl(encodedUrl.substringAfter("function/"))
                            callback(ExtractorLink(this.name, "${this.name} LQ", decodedUrl, mainUrl, Qualities.P480.value, type = ExtractorLinkType.VIDEO))
                            foundLinks = true
                        }
                    }
                    
                    if (trimmedPart.startsWith("'video_alt_url':") || trimmedPart.startsWith("video_alt_url:")) {
                        val encodedUrl = trimmedPart.substringAfter(":'").substringBeforeLast("'")
                        if (encodedUrl.isNotBlank()) {
                            val decodedUrl = decodeUrl(encodedUrl.substringAfter("function/"))
                            callback(ExtractorLink(this.name, "${this.name} HD", decodedUrl, mainUrl, Qualities.P720.value, type = ExtractorLinkType.VIDEO))
                            foundLinks = true
                        }
                    }
                }
            }
        }
        
        return foundLinks
    }
}
