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

    // Hàm gọi link và xử lý chuyển hướng 302
    private suspend fun followRedirectAndCallback(url: String, referer: String, quality: Int, qualityName: String, callback: (ExtractorLink) -> Unit) {
        // Sửa lỗi: Chỉ xử lý nếu URL không rỗng để tránh lỗi "Expected URL scheme"
        if (url.isBlank()) return

        // Sửa lỗi 302 và Referer:
        // 1. Thêm referer vào request
        // 2. Không tự động theo dõi chuyển hướng (allowRedirects = false)
        val response = app.get(url, referer = referer, allowRedirects = false)
        
        // Kiểm tra nếu có chuyển hướng (3xx)
        val finalUrl = if (response.code in 300..399) {
            // Lấy link cuối cùng từ header 'Location'
            response.headers["Location"] ?: url
        } else {
            // Nếu không có chuyển hướng, dùng link gốc
            url
        }
        
        callback(
            ExtractorLink(
                this.name, 
                "${this.name} $qualityName", 
                finalUrl, 
                referer, // Sử dụng referer gốc cho yêu cầu cuối cùng
                quality, 
                type = ExtractorLinkType.VIDEO
            )
        )
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val document = app.get(data).document
        var foundLinks = false

        val scriptContent = document.select("script").find { it.data().contains("kt_player") }?.data()

        if (scriptContent != null) {
            // Sử dụng regex đơn giản và an toàn để tìm các URL
            val lqRegex = Regex("""video_url:\s*'(.*?)'""")
            val hdRegex = Regex("""video_alt_url:\s*'(.*?)'""")

            lqRegex.find(scriptContent)?.groupValues?.get(1)?.let {
                val videoUrl = it.replace("function/0/", "")
                followRedirectAndCallback(videoUrl, data, Qualities.P480.value, "LQ", callback)
                foundLinks = true
            }

            hdRegex.find(scriptContent)?.groupValues?.get(1)?.let {
                val videoUrl = it.replace("function/0/", "")
                followRedirectAndCallback(videoUrl, data, Qualities.P720.value, "HD", callback)
                foundLinks = true
            }
        }
        
        return foundLinks
    }
}
