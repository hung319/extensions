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

    // ================= LOAD DETAILS =================
    override suspend fun load(url: String): LoadResponse {
        val response = app.get(url, headers = commonHeaders)
        val json = response.parsedSafe<DetailsResult>()
        val data = json?.data ?: throw ErrorLoadingException("Không thể tải thông tin phim")

        val title = data.english ?: data.romaji ?: data.native ?: "Unknown Title"
        val showStatus = when (data.status) {
            "RELEASING" -> ShowStatus.Ongoing
            "FINISHED" -> ShowStatus.Completed
            else -> null
        }

        val epCount = data.epCount ?: 0
        val episodesList = (1..epCount).map { epNum ->
            val watchUrl = "$mainUrl/watch/${data.id}/$epNum"
            newEpisode(watchUrl) {
                this.episode = epNum
                this.name = "Episode $epNum"
            }
        }.reversed()

        return newAnimeLoadResponse(title, url, TvType.Anime) {
            this.posterUrl = data.cover
            this.backgroundPosterUrl = data.banner
            this.plot = data.description
            this.tags = data.genres
            this.year = data.year
            this.showStatus = showStatus
            
            // FIX 3: Gán episodes vào MutableMap<DubStatus, List<Episode>>
            // Mặc định gán vào Subbed, nếu site có dub riêng thì cần logic tách sau.
            this.episodes = mutableMapOf(
                DubStatus.Subbed to episodesList
            )
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
