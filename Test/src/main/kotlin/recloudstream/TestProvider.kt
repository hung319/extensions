package recloudstream

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.network.CloudflareKiller
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import org.jsoup.nodes.Element
import com.lagradost.cloudstream3.CommonActivity.showToast
import com.lagradost.cloudstream3.utils.loadExtractor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import android.widget.Toast

class AnimetmProvider : MainAPI() {
    override var name = "AnimeTM"
    override var mainUrl = "https://animetm.tv"
    override var lang = "vi"
    override val hasMainPage = true
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(
        TvType.Anime,
        TvType.AnimeMovie,
    )

    private val killer = CloudflareKiller()

    private fun Element.toSearchResult(): SearchResponse? {
        val link = this.selectFirst("a.film-poster-ahref") ?: return null
        val href = fixUrl(link.attr("href"))
        val title = link.attr("title")
        val posterUrl = this.selectFirst("img.film-poster-img")?.attr("data-src")
        
        val episodesText = this.selectFirst("div.tick-rate")?.text()
        val episodeCount = episodesText?.substringBefore("/")?.trim()?.toIntOrNull()

        return newAnimeSearchResponse(title, href, TvType.Anime) {
            this.posterUrl = posterUrl
            if (episodeCount != null) {
                this.addDubStatus(DubStatus.Subbed, episodeCount)
            }
        }
    }

    private suspend fun getPage(url: String): List<SearchResponse> {
        withContext(Dispatchers.Main) {
            CommonActivity.activity?.let { activity ->
                showToast(activity, "Free Repo From SIX [H4RS]\nTelegram/Discord: hung319", Toast.LENGTH_LONG)
            }
        }
        val document = app.get(url, interceptor = killer).document
        return document.select("div.flw-item").mapNotNull {
            it.toSearchResult()
        }
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val home = getPage("$mainUrl/moi-cap-nhat?page=$page")
        return newHomePageResponse("Phim Mới Cập Nhật", home)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val searchUrl = "$mainUrl/search?keysearch=$query"
        return getPage(searchUrl)
    }

    override suspend fun load(url: String): LoadResponse {
        val document = app.get(url, interceptor = killer).document
        
        // Selector đã được cập nhật cho tiêu đề và các thông tin khác
        val title = document.selectFirst("h2.film-name a")?.text()?.trim() ?: "Không rõ"
        val poster = document.selectFirst("div.anis-cover")?.attr("style")?.let {
            Regex("url\\((.*?)\\)").find(it)?.groupValues?.get(1)
        }
        val plot = document.selectFirst("div.film-description div.text")?.text()?.trim()
        
        // === FIX: Lấy danh sách tập phim trực tiếp từ trang xem phim ===
        // Không cần load trang episode riêng nữa
        val episodes = document.select("div.ep-range a.ssl-item").map {
            val episodeNumber = it.attr("title")
            newEpisode(it.attr("href")) {
                name = "Tập $episodeNumber"
            }
        }.reversed() // Đảo ngược để có thứ tự 1, 2, 3...

        return newAnimeLoadResponse(title, url, TvType.Anime) {
            this.posterUrl = poster
            this.plot = plot
            
            if (episodes.isNotEmpty()) {
                addEpisodes(DubStatus.Subbed, episodes)
            }

            recommendations = document.select("section.block_area_category div.flw-item").mapNotNull {
                it.toSearchResult()
            }
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        // === FIX: Logic trích xuất link mới ===
        val document = app.get(data, interceptor = killer).document
        val scriptContent = document.select("script").html()

        // Lấy tất cả các button server
        val servers = document.select("div.ps__-list a.btn3dsv")

        // Duyệt qua từng server để lấy link
        servers.forEach { serverElement ->
            try {
                // Lấy tên server (VD: "LINK1", "LINK10") và nhãn chất lượng (VD: "HD", "1080")
                val name = serverElement.attr("name")
                val qualityLabel = serverElement.text()
                
                // Trích xuất số ID từ tên server
                val serverId = name.removePrefix("LINK")

                // Dùng regex để tìm biến JavaScript tương ứng và lấy URL
                val linkRegex = Regex("""var\s*\${'$'}checkLink$serverId\s*=\s*"([^"]+)"""")
                val url = linkRegex.find(scriptContent)?.groupValues?.get(1)

                if (!url.isNullOrBlank()) {
                    // Ưu tiên link M3U8 vì có thể play trực tiếp
                    if (url.contains(".m3u8")) {
                        callback.invoke(
                            ExtractorLink(
                                source = this.name,
                                name = qualityLabel, // Dùng nhãn từ button
                                url = url,
                                referer = "$mainUrl/",
                                quality = Qualities.Unknown.value,
                                type = ExtractorLinkType.M3U8
                            )
                        )
                    } else {
                        // Với các link khác (iframe, shortener), để Cloudstream tự xử lý
                        loadExtractor(url, data, subtitleCallback, callback)
                    }
                }
            } catch (e: Exception) {
                // Bỏ qua nếu có lỗi ở một server cụ thể
            }
        }

        return true // Trả về true vì đã bắt đầu quá trình tìm link
    }
}
