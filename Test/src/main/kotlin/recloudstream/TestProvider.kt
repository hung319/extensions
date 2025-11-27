package recloudstream

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.AppUtils
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.mvvm.logError

class Kuudere : MainAPI() {
    override var mainUrl = "https://kuudere.to"
    override var name = "Kuudere"
    override var hasMainPage = true
    override var hasChromecastSupport = true
    override var hasDownloadSupport = true
    override var supportedTypes = setOf(TvType.Anime, TvType.AnimeMovie, TvType.OVA)
    override var lang = "vi" // Để tiếng Việt để ưu tiên hiển thị cho user VN

    // Headers giả lập browser và bypass Cloudflare cơ bản
    private val commonHeaders = mapOf(
        "User-Agent" to "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/137.0.0.0 Mobile Safari/537.36",
        "Referer" to "$mainUrl/",
        "Sec-Ch-Ua" to "\"Chromium\";v=\"137\", \"Not/A)Brand\";v=\"24\"",
        "Sec-Ch-Ua-Mobile" to "?1",
        "Sec-Ch-Ua-Platform" to "\"Android\"",
        "Accept" to "application/json, text/plain, */*" // Quan trọng để server trả về JSON
    )

    // ================= DATA CLASSES =================
    // Home API
    data class HomeResult(val success: Boolean, val data: List<HomeItem>?)
    data class HomeItem(val title: String, val image: String?, val url: String, val stats: Map<String, Int>?)

    // Search API
    data class SearchResult(val success: Boolean, val results: List<SearchItem>?)
    data class SearchItem(val id: String, val title: String, val details: String?, val coverImage: String?)

    // Details API
    data class DetailsResult(val success: Boolean, val data: AnimeInfo?)
    data class AnimeInfo(
        val id: String,
        val english: String?,
        val romaji: String?,
        val native: String?,
        val description: String?,
        val cover: String?,
        val banner: String?,
        val status: String?,
        val genres: List<String>?,
        val year: Int?,
        val epCount: Int?
    )

    // ================= MAIN PAGE =================
    override val mainPage = mainPageOf(
        "$mainUrl/api/top/anime?tab=today&limit=20" to "Top Anime (Today)",
        "$mainUrl/api/top/anime?tab=week&limit=20" to "Top Anime (Week)",
        "$mainUrl/api/top/anime?tab=month&limit=20" to "Top Anime (Month)"
    )

    override suspend fun getMainPage(page: MainPageRequest): HomePageResponse {
        val json = app.get(page.data, headers = commonHeaders).parsedSafe<HomeResult>()
        
        val home = json?.data?.map { item ->
            newAnimeSearchResponse(item.title, "$mainUrl${item.url}", TvType.Anime) {
                this.posterUrl = item.image
                // Hiển thị số lượng sub/dub ở góc poster (nếu cần)
                val sub = item.stats?.get("subbed") ?: 0
                val dub = item.stats?.get("dubbed") ?: 0
                addQuality("Sub: $sub | Dub: $dub")
            }
        } ?: listOf()

        return newHomePageResponse(page.name, home)
    }

    // ================= SEARCH =================
    override suspend fun search(query: String): List<SearchResponse> {
        val url = "$mainUrl/api/search?q=${query.encodeUri()}"
        val json = app.get(url, headers = commonHeaders).parsedSafe<SearchResult>()
        
        return json?.results?.map { item ->
            // Format URL details: https://kuudere.to/anime/{id}
            val href = "$mainUrl/anime/${item.id}"
            val year = item.details?.substringBefore("•")?.trim()?.toIntOrNull()
            
            newAnimeSearchResponse(item.title, href, TvType.Anime) {
                this.posterUrl = item.coverImage
                this.year = year
                if (item.details?.contains("Movie") == true) {
                    addQuality("Movie")
                }
            }
        } ?: emptyList()
    }

    // ================= LOAD DETAILS =================
    override suspend fun load(url: String): LoadResponse {
        // url input: https://kuudere.to/anime/{id}
        // Gọi thẳng vào URL này với header Accept JSON để lấy data sạch
        val response = app.get(url, headers = commonHeaders)
        
        // Thử parse JSON
        val json = response.parsedSafe<DetailsResult>()
        val data = json?.data

        if (data == null) {
            throw ErrorLoadingException("Không thể tải thông tin phim (JSON null)")
        }

        val title = data.english ?: data.romaji ?: data.native ?: "Unknown Title"
        val showStatus = when (data.status) {
            "RELEASING" -> ShowStatus.Ongoing
            "FINISHED" -> ShowStatus.Completed
            else -> null
        }

        // Tạo danh sách tập phim dựa trên tổng số tập (Lazy Generation)
        // URL Watch chuẩn: https://kuudere.to/watch/{id}/{ep}
        val epCount = data.epCount ?: 0
        val episodes = (1..epCount).map { epNum ->
            val watchUrl = "$mainUrl/watch/${data.id}/$epNum"
            newEpisode(watchUrl) {
                this.episode = epNum
                this.name = "Episode $epNum"
            }
        }

        return newAnimeLoadResponse(title, url, TvType.Anime) {
            this.posterUrl = data.cover
            this.backgroundPosterUrl = data.banner
            this.plot = data.description
            this.tags = data.genres
            this.year = data.year
            this.showStatus = showStatus
            this.episodes = episodes.reversed() // Mới nhất lên đầu
            this.recommendations = null // Có thể thêm nếu muốn gọi thêm API
        }
    }

    // ================= LOAD LINKS =================
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        // data: https://kuudere.to/watch/{id}/{ep}
        // Parse HTML trang watch để lấy iframe/servers
        
        val doc = app.get(data, headers = commonHeaders).document
        
        // 1. Tìm iframe chính
        doc.select("iframe").forEach { iframe ->
            val src = iframe.attr("src")
            if (src.isNotBlank()) {
                loadExtractor(fixUrl(src), data, subtitleCallback, callback)
            }
        }

        // 2. Tìm các server phụ (nếu có)
        // Check các class phổ biến như .server, .link-server, li[data-link]
        doc.select(".servers .server, li.link-server").forEach { node ->
            val link = node.attr("data-src").ifEmpty { node.attr("data-link") }
            if (link.isNotBlank()) {
                // Link có thể là URL trực tiếp hoặc cần decode. 
                // Với site kiểu này thường là direct URL embed.
                if (link.startsWith("http")) {
                    loadExtractor(fixUrl(link), data, subtitleCallback, callback)
                }
            }
        }

        return true
    }
}
