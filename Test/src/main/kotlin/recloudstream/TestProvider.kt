package recloudstream

// Import các lớp cần thiết
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.SubtitleFile
import org.jsoup.nodes.Element
import java.net.URLEncoder

class IhentaiProvider : MainAPI() { // Đổi tên class để tránh trùng lặp
    // --- Thông tin cơ bản ---
    override var mainUrl = "https://ihentai.ws" // Sử dụng tên miền .ws ổn định hơn
    override var name = "iHentai"
    override val hasMainPage = true
    override var lang = "vi"
    override val supportedTypes = setOf(TvType.NSFW)
    
    // User-Agent để giả lập trình duyệt, rất quan trọng
    private val headers = mapOf(
        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"
    )

    // Hàm helper để phân tích một thẻ item thành đối tượng SearchResponse
    private fun parseSearchCard(element: Element): SearchResponse? {
        // Tìm thẻ a chứa link và ảnh
        val linkElement = element.selectFirst("a") ?: return null
        val href = fixUrl(linkElement.attr("href"))
        
        // Lấy tiêu đề từ thẻ h2 hoặc h3
        val title = element.selectFirst("h2, h3")?.text()?.trim() ?: return null
        
        // Lấy ảnh bìa
        val posterUrl = element.selectFirst("img")?.attr("src")

        // Trả về đối tượng mà CloudStream có thể hiểu
        return newAnimeSearchResponse(title, href, TvType.NSFW) {
            this.posterUrl = posterUrl
        }
    }

    // --- Hàm lấy trang chính ---
    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val document = app.get("$mainUrl/?page=$page", headers = headers).document
        val homePageList = mutableListOf<HomePageList>()

        // Lấy từng khu vực ("Mới cập nhật", "Hentai 3D",...) trên trang chủ
        document.select("div.container > div.tw-mb-16").forEach { section ->
            val sectionTitle = section.selectFirst("h1")?.text()?.trim() ?: return@forEach
            // Lấy danh sách các item trong khu vực đó
            val items = section.select("div.v-card").mapNotNull {
                parseSearchCard(it)
            }
            if (items.isNotEmpty()) {
                homePageList.add(HomePageList(sectionTitle, items))
            }
        }
        
        return HomePageResponse(homePageList)
    }

    // --- Hàm tìm kiếm ---
    override suspend fun search(query: String): List<SearchResponse> {
        val encodedQuery = URLEncoder.encode(query, "UTF-8")
        val document = app.get("$mainUrl/tim-kiem?q=$encodedQuery", headers = headers).document
        // Selector cho trang tìm kiếm là "div.film-list div.v-col"
        return document.select("div.film-list div.v-col").mapNotNull {
            parseSearchCard(it)
        }
    }

    // --- Hàm tải thông tin chi tiết của phim/truyện ---
    override suspend fun load(url: String): LoadResponse {
        val document = app.get(url, headers = headers).document

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
        // Tạm thời không làm gì cả theo yêu cầu
        return false
    }
}
