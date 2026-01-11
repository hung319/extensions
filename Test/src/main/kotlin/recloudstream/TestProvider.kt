package recloudstream

import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.network.CloudflareKiller
import org.jsoup.nodes.Element

// --- Data Classes khớp với JSON response ---
data class PlayerAjaxResponse(
    @JsonProperty("code") val code: Int? = null,
    @JsonProperty("src_vip") val srcVip: String? = null,
    @JsonProperty("src_op") val srcOp: String? = null,
    @JsonProperty("src_kk") val srcKk: String? = null,
    @JsonProperty("src_arc") val srcArc: String? = null,
    @JsonProperty("src_ok") val srcOk: String? = null,
    @JsonProperty("src_dl") val srcDl: String? = null,
    @JsonProperty("src_hx") val srcHx: String? = null,
    @JsonProperty("src_tok") val srcTok: String? = null,
    @JsonProperty("src_tm") val srcTm: String? = null,
    @JsonProperty("src_nc") val srcNc: String? = null
)

class HHDRagonProvider : MainAPI() {
    override var mainUrl = "https://hhdragon.io"
    override var name = "HHDragon"
    override var lang = "vi"
    override val hasMainPage = true
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(
        TvType.Anime,
        TvType.AnimeMovie,
        TvType.Cartoon,
    )

    private val killer = CloudflareKiller()

    private suspend fun updateDomain() {
        try {
            val response = app.get(
                "https://hhdragon.io/",
                allowRedirects = false,
                interceptor = killer
            )
            if (response.code == 301 || response.code == 302) {
                val location = response.headers["location"] ?: response.headers["Location"]
                if (!location.isNullOrBlank()) {
                    mainUrl = location.trimEnd('/')
                }
            } else if (response.code == 200) {
                 mainUrl = response.url.trimEnd('/')
            }
        } catch (e: Exception) {
            mainUrl = "https://hhdragon.run"
        }
    }

    override val mainPage = mainPageOf(
        "phim-moi-cap-nhap" to "Mới Cập Nhật", // URL chuẩn của Halim theme thường không có .html ở list
        "the-loai/hoat-hinh-trung-quoc" to "Hoạt Hình Trung Quốc",
        "the-loai/anime" to "Anime",
        "the-loai/sap-chieu" to "Sắp Chiếu"
    )

    private fun Element.toSearchResponse(): SearchResponse? {
        // Selector chuẩn cho Halim Theme
        val linkTag = this.selectFirst("a.halim-thumb") ?: return null
        val href = fixUrl(linkTag.attr("href"))
        
        // Lấy title
        val title = linkTag.attr("title").trim()
        
        // Lấy poster: Fix lỗi mất ảnh
        val imgTag = this.selectFirst("figure img")
        val posterUrl = imgTag?.let { 
            it.attr("data-src").ifBlank { it.attr("src") } 
        }
        
        // Lấy tập mới nhất
        val episodeLatest = this.selectFirst(".status")?.text()
        
        return newAnimeSearchResponse(title, href, TvType.Anime) {
            this.posterUrl = posterUrl
            this.otherName = episodeLatest 
        }
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        if (mainUrl == "https://hhdragon.io") updateDomain()
        
        // Fix URL page: Halim theme thường là /page/2
        val url = if(page > 1) {
            "$mainUrl/${request.data}/page/$page"
        } else {
            "$mainUrl/${request.data}"
        }
        
        val document = app.get(url, interceptor = killer).document
        
        // Selector chuẩn Halim Theme: .halim_box .halim-item
        val home = document.select(".halim_box .halim-item").mapNotNull { it.toSearchResponse() }
        
        return newHomePageResponse(
            list = HomePageList(name = request.name, list = home),
            hasNext = home.isNotEmpty()
        )
    }

    override suspend fun search(query: String): List<SearchResponse> {
        if (mainUrl == "https://hhdragon.io") updateDomain()
        val url = "$mainUrl/tim-kiem/$query" // Halim theme search url
        val document = app.get(url, interceptor = killer).document
        
        return document.select(".halim_box .halim-item").mapNotNull { it.toSearchResponse() }
    }

    override suspend fun load(url: String): LoadResponse {
        if (mainUrl == "https://hhdragon.io") updateDomain()
        val document = app.get(url, interceptor = killer).document

        // Selector chi tiết phim chuẩn Halim Theme
        val title = document.selectFirst(".movie-title, .entry-title")?.text()?.trim() ?: "No Title"
        
        val posterUrl = document.selectFirst(".movie-poster img")?.let {
            it.attr("data-src").ifBlank { it.attr("src") }
        } ?: document.selectFirst(".info-movie .first img")?.attr("src")
        
        val description = document.select("#chi-tiet .entry-content, .movie-detail .entry-content").text().trim()
        
        val tags = document.select(".tags-link a, .category a").map { it.text() }
        val type = if (tags.any { it.contains("3D") || it.contains("HH3D") }) TvType.Cartoon else TvType.Anime

        // Selector tập phim: Thường nằm trong #halim-list-server hoặc .list-episode
        val episodes = document.select(".halim-list-eps a, .list-episode a").map {
            val href = fixUrl(it.attr("href"))
            val name = it.text().trim()
            newEpisode(href) {
                this.name = name
                this.episode = name.replace(Regex("[^0-9]"), "").toIntOrNull()
            }
        }
        // Không cần reverse vì Halim thường list từ 1->end, hoặc nếu end->1 thì reverse tùy site
        // Site này có vẻ list mới nhất lên đầu? Nếu thế thì episodes.reversed()

        return newAnimeLoadResponse(title, url, type) {
            this.posterUrl = posterUrl
            this.plot = description
            this.tags = tags
            addEpisodes(DubStatus.Subbed, episodes)
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        if (mainUrl == "https://hhdragon.io") updateDomain()

        val document = app.get(data, interceptor = killer).document

        val csrfToken = document.selectFirst("meta[name='csrf-token']")?.attr("content")
            ?: return false

        val scriptContent = document.select("script").joinToString("\n") { it.data() }
        
        // --- FIX REGEX: Chấp nhận cả "3812" và 3812, chấp nhận khoảng trắng lỏng lẻo ---
        // Tìm pattern: "movie_id": "3812" hoặc movie_id: 3812
        val movieIdRegex = Regex("""["']?movie_id["']?\s*:\s*["']?(\d+)["']?""")
        val episodeIdRegex = Regex("""["']?id["']?\s*:\s*["']?(\d+)["']?""") // id trong $info_data thường là episode ID

        val movieId = movieIdRegex.find(scriptContent)?.groupValues?.get(1)
        val episodeId = episodeIdRegex.find(scriptContent)?.groupValues?.get(1)

        if (movieId == null || episodeId == null) {
            // Fallback: Tìm trong thẻ HTML nếu Regex script thất bại (phòng hờ)
            // Ví dụ: <div id="player-wrapper" data-movie-id="...">
            // Có thể thêm logic fallback ở đây nếu cần thiết
            return false
        }

        val apiUrl = "$mainUrl/server/ajax/player"
        val headers = mapOf(
            "X-CSRF-TOKEN" to csrfToken,
            "X-Requested-With" to "XMLHttpRequest",
            "Referer" to data,
            "Origin" to mainUrl
        )
        val formData = mapOf(
            "MovieID" to movieId,
            "EpisodeID" to episodeId
        )

        val jsonResponse = app.post(
            apiUrl, 
            headers = headers, 
            data = formData,
            interceptor = killer
        ).parsedSafe<PlayerAjaxResponse>() ?: return false

        if (jsonResponse.code != 200) return false

        suspend fun processSource(link: String?, sourceName: String) {
            if (link.isNullOrBlank()) return

            if (link.endsWith(".mp4")) {
                callback(
                    newExtractorLink(
                        source = name,
                        name = "$sourceName Direct",
                        url = link,
                        type = ExtractorLinkType.VIDEO
                    ) {
                        this.referer = data
                    }
                )
            } else if (link.endsWith(".m3u8")) {
                loadExtractor(link, data, subtitleCallback, callback)
            } else {
                loadExtractor(link, data, subtitleCallback, callback)
            }
        }

        processSource(jsonResponse.srcVip, "Vip")
        processSource(jsonResponse.srcOp, "Op")
        processSource(jsonResponse.srcKk, "Kk")
        processSource(jsonResponse.srcArc, "Archive")
        processSource(jsonResponse.srcOk, "OkRu")
        processSource(jsonResponse.srcDl, "StreamC")
        processSource(jsonResponse.srcHx, "Hx")
        processSource(jsonResponse.srcTok, "Tok")
        
        return true
    }
}
