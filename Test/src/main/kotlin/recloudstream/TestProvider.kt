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
    
    // Bật chức năng loadLinks
    override val hasDownloadSupport = true
    override val hasChromecastSupport = true

    private val mainPageSections = listOf(
        "Hot Videos" to "$mainUrl/",
        "New Videos" to "$mainUrl/new/",
        "Top Rated" to "$mainUrl/top-rated/",
        "Most Popular" to "$mainUrl/most-popular/"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        // Phân trang cho một mục cụ thể
        if (page > 1 && request.data.isNotBlank()) {
            val document = app.get(request.data + page).document
            val items = document.select("div.list-videos .item").mapNotNull {
                try {
                    parseVideoCard(it)
                } catch (e: Exception) {
                    null
                }
            }
            // --- Cập nhật: Kiểm tra xem có trang tiếp theo không ---
            val hasNext = document.selectFirst("li.next > a") != null
            return newHomePageResponse(request.name, items, hasNextPage = hasNext)
        }

        // Tải lần đầu cho tất cả các mục
        val allSections = mainPageSections.apmap { (name, url) ->
            val document = app.get(url).document
            val items = document.select("div.list-videos .item").mapNotNull {
                try {
                    parseVideoCard(it)
                } catch (e: Exception) {
                    null
                }
            }
            // --- Cập nhật: Kiểm tra và gán hasNextPage cho từng mục ---
            val hasNext = document.selectFirst("li.next > a") != null
            newHomePageList(name, items, data = url, hasNextPage = hasNext)
        }

        return HomePageResponse(allSections.filter { it.list.isNotEmpty() })
    }

    private fun parseVideoCard(element: Element): SearchResponse {
        val link = element.selectFirst("a")!!
        val href = fixUrl(link.attr("href"))
        val title = link.selectFirst("strong.title")?.text()?.trim() ?: "N/A"
        val image = link.selectFirst("img.thumb")?.attr("data-original")

        return newMovieSearchResponse(title, href, TvType.Movie) {
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

        return newMovieLoadResponse(title, url, TvType.Movie, url) {
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
            val regex = Regex("""var\s+\w+\s*=\s*(\{[\s\S]*?});""")
            val match = regex.find(scriptContent)
            
            if (match != null) {
                val playerData = match.groupValues[1]
                val lqRegex = Regex("""video_url:\s*'(.*?)'""")
                val hdRegex = Regex("""video_alt_url:\s*'(.*?)'""")

                lqRegex.find(playerData)?.let {
                    val lqUrl = it.groupValues[1].replace("function/0/", "")
                    callback(
                        ExtractorLink(this.name, "${this.name} LQ", lqUrl, mainUrl, Qualities.SD.value, type = ExtractorLinkType.VIDEO)
                    )
                    foundLinks = true
                }

                hdRegex.find(playerData)?.let {
                    val hdUrl = it.groupValues[1].replace("function/0/", "")
                    callback(
                        ExtractorLink(this.name, "${this.name} HD", hdUrl, mainUrl, Qualities.HD.value, type = ExtractorLinkType.VIDEO)
                    )
                    foundLinks = true
                }
            }
        }
        
        return foundLinks
    }
}
