package recloudstream

// Import các lớp cần thiết
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Element

class Ihentai : MainAPI() {
    // ---- METADATA ----
    override var name = "iHentai"
    override var mainUrl = "https://ihentai.ws"
    override var lang = "vi"
    override val hasMainPage = true
    override val supportedTypes = setOf(TvType.NSFW)

    // Set the User-Agent that worked with your curl command.
    private val headers = mapOf(
        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"
    )

    // --- UTILITY FUNCTIONS TO PARSE ITEMS ---
    // This function parses a single item from the homepage
    private fun parseHomepageCard(element: Element): SearchResponse? {
        val linkElement = element.selectFirst("a") ?: return null
        val href = fixUrl(linkElement.attr("href"))
        val title = element.selectFirst("div.v-card-text h2")?.text()?.trim() ?: return null
        val posterUrl = element.selectFirst("img")?.attr("src")

        return newAnimeSearchResponse(title, href, TvType.NSFW) {
            this.posterUrl = posterUrl
        }
    }

    // This function parses a single item from the search results page
    private fun parseSearchCard(element: Element): SearchResponse? {
        val linkElement = element.selectFirst("a") ?: return null
        val href = fixUrl(linkElement.attr("href"))
        // On the search page, the title is in an h3 tag
        val title = linkElement.selectFirst("h3")?.text()?.trim() ?: return null
        val posterUrl = linkElement.selectFirst("img")?.attr("src")

        return newAnimeSearchResponse(title, href, TvType.NSFW) {
            this.posterUrl = posterUrl
        }
    }

    // ---- CORE FUNCTIONS ----

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = "$mainUrl/?page=$page"
        val document = app.get(url, headers = headers).document

        val sections = document.select("div.tw-mb-16") // Selector này đã đúng
        val homePageList = mutableListOf<HomePageList>()

        for (section in sections) {
            val sectionTitle = section.selectFirst("h1")?.text()?.trim() ?: continue
            val items = section.select("div.tw-grid > div.v-card").mapNotNull {
                parseHomepageCard(it) // Dùng hàm parse cho trang chủ
            }
            if (items.isNotEmpty()) {
                homePageList.add(HomePageList(sectionTitle, items))
            }
        }
        
        return HomePageResponse(homePageList)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val url = "$mainUrl/tim-kiem?q=$query"
        val document = app.get(url, headers = headers).document

        // SỬA LẠI: Dùng selector đúng cho trang tìm kiếm từ file ihentai_search_output.html
        return document.select("div.film-list div.v-col").mapNotNull {
            parseSearchCard(it) // Dùng hàm parse cho trang tìm kiếm
        }
    }

    override suspend fun load(url: String): LoadResponse {
        val document = app.get(url, headers = headers).document

        // SỬA LẠI: Dùng các selector đúng cho trang load từ file ihentai_load_output.html
        val title = document.selectFirst("h1.tw-text-3xl")?.text()?.trim() ?: throw ErrorLoadingException("Không tìm thấy tiêu đề")
        val posterUrl = document.selectFirst("div.grid > div:first-child img")?.attr("src")
        val plot = document.select("h3:contains(Nội dung)").first()?.nextElementSibling()?.text()?.trim()
        val genres = document.select("div.film-tag-list a.v-chip")?.mapNotNull { it.text()?.trim() }

        // Lấy danh sách các tập từ div#episode-list
        val episodes = document.select("div#episode-list a").mapNotNull { element ->
            newEpisode(fixUrl(element.attr("href"))) {
                name = element.text().trim()
            }
        }.reversed()

        return newTvSeriesLoadResponse(title, url, TvType.NSFW, episodes) {
            this.posterUrl = posterUrl
            this.plot = plot
            this.tags = genres
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        // Tạm thời không làm gì cả theo yêu cầu
        return false
    }
}
