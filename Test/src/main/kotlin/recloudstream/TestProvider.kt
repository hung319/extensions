package recloudstream

// Import các thư viện cần thiết cho CloudStream
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import org.jsoup.nodes.Element

/**
 * Lớp chính của Provider, kế thừa từ MainAPI
 */
class HentaiHavenProvider : MainAPI() {
    // Thông tin cơ bản của plugin
    override var name = "HentaiHaven"
    override var mainUrl = "https://hentaihaven.xxx"
    override var lang = "en"
    override var supportedTypes = setOf(
        TvType.NSFW
    )

    // Cho ứng dụng biết rằng plugin này có trang chính
    override val hasMainPage = true

    /**
     * Hàm này được gọi khi người dùng mở trang chính của plugin.
     * Nó tải dữ liệu từ trang chủ của HentaiHaven và phân loại thành các danh sách.
     * Đã sửa lại chữ ký hàm để khớp với yêu cầu của API.
     */
    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse? {
        // Tạo URL cho trang hiện tại. Trang 1 không cần /page/1
        val url = if (page == 1) mainUrl else "$mainUrl/page/$page/"
        val document = app.get(url).document
        
        // Tạo một danh sách (mutable) để chứa các HomePageList
        val homePageList = mutableListOf<HomePageList>()

        // Tìm tất cả các khu vực slider trên trang chủ
        document.select("div.vraven_home_slider")?.forEach { slider ->
            // Lấy tiêu đề của khu vực slider
            val header = slider.selectFirst("div.home_slider_header h4")?.text() ?: "Unknown"
            
            // Tìm tất cả các item trong slider
            val items = slider.select("div.item.vraven_item").mapNotNull { el ->
                val titleEl = el.selectFirst(".post-title a")
                val title = titleEl?.text() ?: return@mapNotNull null
                val href = titleEl.attr("href")
                // Lấy ảnh từ data-src hoặc src để đảm bảo luôn có ảnh
                val image = el.selectFirst(".item-thumb img")?.let {
                    it.attr("data-src").ifBlank { it.attr("src") }
                }

                // Tạo đối tượng TvSeriesSearchResponse cho mỗi item
                newTvSeriesSearchResponse(title, href) {
                    this.posterUrl = image
                }
            }
            
            // Nếu danh sách item không rỗng thì thêm vào homePageList
            if (items.isNotEmpty()) {
                homePageList.add(HomePageList(header, items))
            }
        }
        
        if (homePageList.isEmpty()) return null // Trả về null nếu không có gì
        return HomePageResponse(homePageList)
    }

    /**
     * Hàm này được gọi khi người dùng tìm kiếm.
     * @param query Từ khóa tìm kiếm.
     */
    override suspend fun search(query: String): List<SearchResponse> {
        val url = "$mainUrl/?s=$query&post_type=wp-manga"
        val document = app.get(url).document
        
        // Tìm tất cả các kết quả trong trang tìm kiếm
        return document.select("div.c-tabs-item__content").mapNotNull {
            val titleElement = it.selectFirst("div.post-title h3 a")
            val title = titleElement?.text() ?: return@mapNotNull null
            val href = titleElement.attr("href")
            val image = it.selectFirst("div.tab-thumb img")?.attr("src")

            // Trả về đối tượng TvSeriesSearchResponse
            newTvSeriesSearchResponse(title, href) {
                this.posterUrl = image
            }
        }
    }
    
    /**
     * Hàm này được gọi khi người dùng chọn một bộ phim để xem thông tin chi tiết và danh sách tập.
     * @param url Đường dẫn đến trang của bộ phim.
     */
    override suspend fun load(url: String): LoadResponse {
        val document = app.get(url).document

        // Lấy các thông tin chi tiết của phim
        val title = document.selectFirst("div.post-title h1")?.text()?.trim()
            ?: throw ErrorLoadingException("Không thể tải được tiêu đề")
        
        val poster = document.selectFirst("div.summary_image img")?.attr("src")
        val description = document.selectFirst("div.description-summary div.summary__content")?.text()?.trim()
        val tags = document.select("div.genres-content a").map { it.text() }

        // Lấy danh sách các tập phim
        val episodes = document.select("ul.main.version-chap li.wp-manga-chapter").mapNotNull {
            val link = it.selectFirst("a") ?: return@mapNotNull null
            val name = link.text().trim()
            val href = link.attr("href")
            // Tạo đối tượng Episode cho mỗi tập
            Episode(href, name)
        }.reversed() // Đảo ngược danh sách để các tập sắp xếp đúng thứ tự

        // Trả về đối tượng TvSeriesLoadResponse
        return newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
            this.posterUrl = poster
            this.plot = description
            this.tags = tags
        }
    }
}

// Custom exception để báo lỗi rõ ràng hơn
open class ErrorLoadingException(message: String) : Exception(message)
