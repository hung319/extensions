package recloudstream

// Import các thư viện cần thiết
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import org.jsoup.nodes.Element
import kotlin.text.RegexOption

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
        val url = data
        val document = app.get(url).document
        var foundLinks = false

        val scriptContent = document.select("script").find { it.data().contains("kt_player") }?.data()

        if (scriptContent != null) {
            // Sửa lỗi: Thay đổi hoàn toàn cách lấy khối JSON để tránh lỗi cú pháp regex.
            // 1. Tìm toàn bộ chuỗi khớp với mẫu.
            val match = Regex("""var\s+\w+\s*=\s*\{.*};""", setOf(RegexOption.DOT_MATCHES_ALL)).find(scriptContent)
            if (match != null) {
                // 2. Cắt chuỗi con từ dấu `{` đầu tiên đến dấu `}` cuối cùng.
                val playerData = "{${match.value.substringAfter('{').substringBeforeLast('}')}}"
                
                // 3. Dùng regex đơn giản hơn trên khối JSON đã được làm sạch.
                val lqRegex = Regex("""video_url:\s*'(.*?)'""")
                val hdRegex = Regex("""video_alt_url:\s*'(.*?)'""")

                lqRegex.find(playerData)?.let {
                    val lqUrl = it.groupValues[1].replace("function/0/", "")
                    callback(
                        ExtractorLink(this.name, "${this.name} LQ", lqUrl, mainUrl, Qualities.P480.value, type = ExtractorLinkType.VIDEO)
                    )
                    foundLinks = true
                }

                hdRegex.find(playerData)?.let {
                    val hdUrl = it.groupValues[1].replace("function/0/", "")
                    callback(
                        ExtractorLink(this.name, "${this.name} HD", hdUrl, mainUrl, Qualities.P720.value, type = ExtractorLinkType.VIDEO)
                    )
                    foundLinks = true
                }
            }
        }
        
        return foundLinks
    }
}
