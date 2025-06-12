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
    
    // User-Agent để giả lập trình duyệt, rất quan trọng
    private val headers = mapOf(
        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"
    )

    // Hàm helper để phân tích một thẻ item trên trang chủ
    private fun parseHomepageCard(element: Element): SearchResponse? {
        val linkElement = element.selectFirst("a") ?: return null
        val href = fixUrl(linkElement.attr("href"))
        val title = element.selectFirst("div.v-card-text h2")?.text()?.trim() ?: return null
        val posterUrl = element.selectFirst("img")?.attr("src")

        return newAnimeSearchResponse(title, href, TvType.NSFW) {
            this.posterUrl = posterUrl
        }
    }

    // --- Hàm lấy trang chính (ĐÃ HOẠT ĐỘNG) ---
    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val document = app.get("$mainUrl/?page=$page", headers = headers).document
        val homePageList = mutableListOf<HomePageList>()

        document.select("div.container > div.tw-mb-16").forEach { section ->
            val sectionTitle = section.selectFirst("h1")?.text()?.trim() ?: return@forEach
            val items = section.select("div.v-card").mapNotNull {
                parseHomepageCard(it)
            }
            if (items.isNotEmpty()) {
                homePageList.add(HomePageList(sectionTitle, items))
            }
        }
        
        return HomePageResponse(homePageList)
    }

    // --- Hàm tìm kiếm (Sẽ sửa ở bước sau nếu cần) ---
    override suspend fun search(query: String): List<SearchResponse> {
        // Tạm thời giữ nguyên selector từ file search output
        val document = app.get("$mainUrl/tim-kiem?q=$query", headers = headers).document
        return document.select("div.film-list div.v-col").mapNotNull { element ->
            val linkElement = element.selectFirst("a") ?: return@mapNotNull null
            val href = fixUrl(linkElement.attr("href"))
            val title = linkElement.selectFirst("h3")?.text()?.trim() ?: return@mapNotNull null
            val posterUrl = linkElement.selectFirst("img")?.attr("src")

            newAnimeSearchResponse(title, href, TvType.NSFW) {
                this.posterUrl = posterUrl
            }
        }
    }

    // --- Hàm tải thông tin chi tiết (ĐÃ SỬA LẠI) ---
    override suspend fun load(url: String): LoadResponse {
        val document = app.get(url, headers = headers).document

        // Các selector đã được cập nhật dựa trên tệp ihentai_load_output.html
        val title = document.selectFirst("h1.tw-text-3xl")?.text()?.trim()
            ?: throw ErrorLoadingException("Không tìm thấy tiêu đề")
        
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

    // --- Hàm tải link xem phim (Tạm thời vô hiệu hóa) ---
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        return false
    }
}
