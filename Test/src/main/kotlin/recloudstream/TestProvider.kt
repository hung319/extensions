package recloudstream

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.AppUtils
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.mvvm.logError
import java.net.URLEncoder // Fix lỗi encodeUri

class Kuudere : MainAPI() { // Tên class giữ nguyên theo file của bạn
    override var mainUrl = "https://kuudere.to"
    override var name = "Kuudere"
    override var hasMainPage = true
    override var hasChromecastSupport = true
    override var hasDownloadSupport = true
    override var supportedTypes = setOf(TvType.Anime, TvType.AnimeMovie, TvType.OVA)
    override var lang = "vi"

    private val commonHeaders = mapOf(
        "User-Agent" to "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/137.0.0.0 Mobile Safari/537.36",
        "Referer" to "$mainUrl/",
        "Sec-Ch-Ua" to "\"Chromium\";v=\"137\", \"Not/A)Brand\";v=\"24\"",
        "Sec-Ch-Ua-Mobile" to "?1",
        "Sec-Ch-Ua-Platform" to "\"Android\"",
        "Accept" to "application/json, text/plain, */*"
    )

    // ================= DATA CLASSES =================
    data class HomeResult(val success: Boolean, val data: List<HomeItem>?)
    data class HomeItem(val title: String, val image: String?, val url: String, val stats: Map<String, Int>?)

    data class SearchResult(val success: Boolean, val results: List<SearchItem>?)
    data class SearchItem(val id: String, val title: String, val details: String?, val coverImage: String?)

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

    // FIX 1: Cập nhật chữ ký hàm (thêm page: Int, return nullable)
    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse? {
        // API này không dùng page pagination (nó dùng limit), nhưng ta vẫn phải tuân thủ chữ ký hàm
        val json = app.get(request.data, headers = commonHeaders).parsedSafe<HomeResult>()
        
        val home = json?.data?.map { item ->
            newAnimeSearchResponse(item.title, "$mainUrl${item.url}", TvType.Anime) {
                this.posterUrl = item.image
                val sub = item.stats?.get("subbed") ?: 0
                val dub = item.stats?.get("dubbed") ?: 0
                addQuality("Sub: $sub | Dub: $dub")
            }
        } ?: listOf()

        return newHomePageResponse(request.name, home)
    }

    // ================= SEARCH =================
    override suspend fun search(query: String): List<SearchResponse> {
        // FIX 2: Dùng URLEncoder chuẩn của Java để tránh lỗi Unresolved reference
        val encodedQuery = URLEncoder.encode(query, "UTF-8")
        val url = "$mainUrl/api/search?q=$encodedQuery"
        
        val json = app.get(url, headers = commonHeaders).parsedSafe<SearchResult>()
        
        return json?.results?.map { item ->
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

    // ================= LOAD DETAILS (UPDATED) =================
    override suspend fun load(url: String): LoadResponse {
        // Bước 1: Gọi API JSON để lấy metadata chính xác (Title, Desc, Banner...)
        // url input: https://kuudere.to/anime/{id}
        val response = app.get(url, headers = commonHeaders)
        val json = response.parsedSafe<DetailsResult>()
        val data = json?.data ?: throw ErrorLoadingException("Không thể tải thông tin phim")

        val title = data.english ?: data.romaji ?: data.native ?: "Unknown Title"
        val showStatus = when (data.status) {
            "RELEASING" -> ShowStatus.Ongoing
            "FINISHED" -> ShowStatus.Completed
            else -> null
        }

        // Bước 2: Gọi HTML trang Watch để lấy (1) List tập chi tiết và (2) Recommendations
        // Lý do: API details không trả về tên tập hay list recommendations đầy đủ hình ảnh.
        // Ta thử gọi vào tập 1. Nếu phim chưa có tập 1, fallback về trang details HTML.
        val watchUrl = "$mainUrl/watch/${data.id}/1"
        val doc = app.get(watchUrl, headers = commonHeaders).document

        // --- Xử lý Episodes ---
        // Tìm list tập trong HTML (thường nằm trong .episodes-list hoặc sidebar)
        var htmlEpisodes = doc.select(".episodes-list a, .list-episodes a, #episodes-page-1 a")
        
        // Nếu không tìm thấy ở trang watch (do phim chưa ra tập 1), thử parse ở trang details gốc
        if (htmlEpisodes.isEmpty()) {
             val detailDoc = app.get("$mainUrl/anime/${data.id}", headers = commonHeaders).document
             htmlEpisodes = detailDoc.select(".episodes-list a, .list-episodes a")
        }

        val episodesList = htmlEpisodes.mapNotNull { element ->
            val href = fixUrl(element.attr("href"))
            // Lấy số tập
            val epNum = element.attr("data-num").toIntOrNull() 
                ?: element.select(".num").text().toIntOrNull()
                ?: href.substringAfterLast("/").toIntOrNull()
                ?: return@mapNotNull null
            
            // Lấy tên gốc (Title)
            // Ưu tiên lấy trong title attribute, hoặc text bên cạnh số tập
            val rawName = element.attr("title").ifEmpty { 
                element.select(".name").text() 
            }
            val finalName = if (rawName.isNotBlank() && rawName != "Episode $epNum") {
                rawName // Tên gốc xịn (VD: "The Beginning")
            } else {
                "Episode $epNum" // Tên mặc định
            }

            // Xử lý TAG [SUB] / [DUB]
            // Logic: Check class hoặc URL params. Nếu không rõ, dựa vào stats từ JSON API.
            val isDub = element.hasClass("dub") || href.contains("lang=dub") || element.text().contains("Dub", true)
            // Nếu JSON báo có dubbedCount > 0 và episode này ko xác định, mặc định hiển thị cả 2 hoặc SUB.
            // Ở đây tôi sẽ gắn tag cứng dựa trên check sơ bộ.
            val tag = if (isDub) "[DUB]" else "[SUB]"

            newEpisode(href) {
                this.episode = epNum
                this.name = "$finalName $tag" // VD: "Episode 1 - Start [SUB]"
            }
        }.reversed()

        // Fallback: Nếu parse HTML thất bại (không tìm thấy CSS selector), dùng lại cách lazy cũ
        val finalEpisodes = if (episodesList.isNotEmpty()) episodesList else {
            val count = data.epCount ?: 0
            (1..count).map { epNum ->
                newEpisode("$mainUrl/watch/${data.id}/$epNum") {
                    this.episode = epNum
                    this.name = "Episode $epNum [SUB]"
                }
            }.reversed()
        }

        // --- Xử lý Recommendations ---
        // Tìm trong vùng "Related", "Recommended" hoặc "Trending" bên sidebar trang watch
        val recommendations = doc.select(".related-anime .item, .recommendations .item, .sidebar-content .item").mapNotNull { item ->
            val recTitle = item.select(".title, .name").text().trim()
            val recHref = item.select("a").attr("href")
            val recImg = item.select("img").attr("src").ifEmpty { item.select("img").attr("data-src") }
            
            if (recTitle.isBlank() || recHref.isBlank()) return@mapNotNull null

            newAnimeSearchResponse(recTitle, fixUrl(recHref), TvType.Anime) {
                this.posterUrl = recImg
            }
        }

        return newAnimeLoadResponse(title, url, TvType.Anime) {
            this.posterUrl = data.cover
            this.backgroundPosterUrl = data.banner
            this.plot = data.description
            this.tags = data.genres
            this.year = data.year
            this.showStatus = showStatus
            
            // Map episodes vào Subbed (Cloudstream sẽ tự gộp nếu sau này tách dub riêng)
            this.episodes = mutableMapOf(
                DubStatus.Subbed to finalEpisodes
            )
            
            // Thêm list đề xuất
            this.recommendations = recommendations.ifEmpty { null }
        }
    }

    // ================= LOAD LINKS =================
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val doc = app.get(data, headers = commonHeaders).document
        
        doc.select("iframe").forEach { iframe ->
            val src = iframe.attr("src")
            if (src.isNotBlank()) {
                loadExtractor(fixUrl(src), data, subtitleCallback, callback)
            }
        }

        doc.select(".servers .server, li.link-server").forEach { node ->
            val link = node.attr("data-src").ifEmpty { node.attr("data-link") }
            if (link.isNotBlank() && link.startsWith("http")) {
                loadExtractor(fixUrl(link), data, subtitleCallback, callback)
            }
        }

        return true
    }
}
