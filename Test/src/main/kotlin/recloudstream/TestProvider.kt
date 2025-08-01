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
        
        val document = app.get(fullUrl).document
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
        val document = app.get(searchUrl).document

        return document.select("div.list-videos .item").mapNotNull {
            try {
                parseVideoCard(it)
            } catch (e: Exception) {
                null
            }
        }
    }

    override suspend fun load(url: String): LoadResponse {
        val document = app.get(url).document

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

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val document = app.get(data).document
        var foundLinks = false

        // Tìm thẻ script chứa thông tin player
        val scriptContent = document.select("script").find { it.data().contains("kt_player") }?.data()

        if (scriptContent != null) {
            // --- Sửa lỗi: Loại bỏ hoàn toàn Regex ---
            // 1. Tìm vị trí bắt đầu và kết thúc của khối object JavaScript
            val startIndex = scriptContent.indexOf("{")
            val endIndex = scriptContent.lastIndexOf("};")
            
            if (startIndex != -1 && endIndex > startIndex) {
                // 2. Cắt chuỗi để lấy nội dung bên trong object
                val objectContent = scriptContent.substring(startIndex + 1, endIndex)

                // 3. Tách các thuộc tính bằng dấu phẩy và xử lý từng phần
                objectContent.split(',').forEach { part ->
                    val trimmedPart = part.trim()
                    // Tìm và trích xuất link chất lượng thấp (LQ)
                    if (trimmedPart.startsWith("'video_url':") || trimmedPart.startsWith("video_url:")) {
                        val videoUrl = trimmedPart.substringAfter(":'").substringBeforeLast("'")
                        callback(
                            ExtractorLink(
                                this.name, 
                                "${this.name} LQ", 
                                videoUrl.replace("function/0/", ""), 
                                mainUrl, 
                                Qualities.P480.value, 
                                type = ExtractorLinkType.VIDEO
                            )
                        )
                        foundLinks = true
                    }
                    // Tìm và trích xuất link chất lượng cao (HD)
                    if (trimmedPart.startsWith("'video_alt_url':") || trimmedPart.startsWith("video_alt_url:")) {
                        val videoUrl = trimmedPart.substringAfter(":'").substringBeforeLast("'")
                        callback(
                            ExtractorLink(
                                this.name, 
                                "${this.name} HD", 
                                videoUrl.replace("function/0/", ""), 
                                mainUrl, 
                                Qualities.P720.value, 
                                type = ExtractorLinkType.VIDEO
                            )
                        )
                        foundLinks = true
                    }
                }
            }
        }
        
        return foundLinks
    }
}
