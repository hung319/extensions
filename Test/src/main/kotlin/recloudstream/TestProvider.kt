package recloudstream

import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.network.CloudflareKiller
import org.jsoup.nodes.Element

// --- Data Classes ---
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
    @JsonProperty("src_nc") val srcNc: String? = null,
    @JsonProperty("src_ss") val srcSs: String? = null
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

    // Tự động cập nhật domain mới nhất
    private suspend fun updateDomain() {
        try {
            val response = app.get(
                "https://hhdragon.io/",
                allowRedirects = false,
                interceptor = killer
            )
            // Nếu gặp redirect 301/302, lấy location mới
            if (response.code == 301 || response.code == 302) {
                val location = response.headers["location"] ?: response.headers["Location"]
                if (!location.isNullOrBlank()) {
                    mainUrl = location.trimEnd('/')
                }
            } else if (response.code == 200) {
                 // Trường hợp hiếm: domain cũ vẫn sống và trả về 200
                 mainUrl = response.url.trimEnd('/')
            }
        } catch (e: Exception) {
            // Fallback nếu không check được
            mainUrl = "https://hhdragon.run"
        }
    }

    override val mainPage = mainPageOf(
        "phim-moi-cap-nhap.html" to "Mới Cập Nhật",
        "the-loai/cna-3d.html" to "Hoạt Hình 3D",
        "the-loai/anime.html" to "Anime",
        "the-loai/sap-chieu.html" to "Sắp Chiếu"
    )

    private fun Element.toSearchResponse(): SearchResponse? {
        val linkTag = this.selectFirst("a") ?: return null
        val href = fixUrl(linkTag.attr("href"))
        val title = this.selectFirst(".name-movie")?.text()?.trim() ?: linkTag.attr("title")
        
        // Fix lỗi ảnh: Web dùng đường dẫn tương đối (/assets/...) -> cần fixUrl()
        val img = this.selectFirst("img")
        val posterUrl = img?.let { 
            val rawUrl = it.attr("data-src").ifBlank { 
                it.attr("data-original").ifBlank {
                    it.attr("src") 
                }
            }
            fixUrl(rawUrl) 
        }
        
        val episodeLatest = this.selectFirst(".episode-latest span")?.text()

        return newAnimeSearchResponse(title, href, TvType.Anime) {
            this.posterUrl = posterUrl
            this.otherName = episodeLatest
        }
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        if (mainUrl == "https://hhdragon.io") updateDomain()

        val url = "$mainUrl/${request.data}?p=$page"
        val document = app.get(url, interceptor = killer).document
        
        val home = document.select(".movies-list .movie-item").mapNotNull { it.toSearchResponse() }

        return newHomePageResponse(
            list = HomePageList(name = request.name, list = home),
            hasNext = home.isNotEmpty()
        )
    }

    override suspend fun search(query: String): List<SearchResponse> {
        if (mainUrl == "https://hhdragon.io") updateDomain()
        
        val url = "$mainUrl/tim-kiem/$query.html"
        val document = app.get(url, interceptor = killer).document
        
        return document.select(".movies-list .movie-item").mapNotNull { it.toSearchResponse() }
    }

    override suspend fun load(url: String): LoadResponse {
        if (mainUrl == "https://hhdragon.io") updateDomain()
        val document = app.get(url, interceptor = killer).document

        val title = document.selectFirst("h1.heading_movie")?.text()?.trim() ?: "No Title"
        
        val posterUrl = document.selectFirst(".info-movie .first img")?.let {
             val raw = it.attr("data-src").ifBlank { it.attr("src") }
             fixUrl(raw)
        }
        
        val description = document.select(".desc .list-item-episode p").text().trim()
        val tags = document.select(".list_cate a").map { it.text() }
        val type = if (tags.any { it.contains("3D") || it.contains("HH3D") }) TvType.Cartoon else TvType.Anime

        val episodes = document.select("#episode-list a.episode-item").map {
            val href = fixUrl(it.attr("href"))
            val name = it.text().trim()
            newEpisode(href) {
                this.name = name
                this.episode = name.toIntOrNull()
            }
        }.reversed()

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

        // --- CÁCH MỚI: Lấy ID từ Form báo lỗi (Chính xác 100%) ---
        // Trong HTML có: <input name="movie_id" value="3812"> và <input name="Episode_id" value="133451">
        var movieId = document.selectFirst("input[name='movie_id']")?.attr("value")
        var episodeId = document.selectFirst("input[name='Episode_id']")?.attr("value")

        // Fallback: Nếu không tìm thấy trong form, mới quét Regex trong script
        if (movieId == null || episodeId == null) {
            val scriptContent = document.select("script").joinToString("\n") { it.data() }
            
            // Tìm trong biến $info_data = { ... }
            if (movieId == null) {
                movieId = Regex("""movie_id\s*:\s*['"]?(\d+)['"]?""", RegexOption.IGNORE_CASE)
                    .find(scriptContent)?.groupValues?.get(1)
            }
            // Tìm id trong $info_data (thường đi sau movie_id)
            if (episodeId == null) {
                // Regex tìm id: 133451, tránh nhầm với các id string rỗng
                episodeId = Regex("""id\s*:\s*['"]?(\d+)['"]?,\s*no_ep""", RegexOption.IGNORE_CASE)
                    .find(scriptContent)?.groupValues?.get(1)
            }
        }

        if (movieId == null || episodeId == null) return false

        val apiUrl = "$mainUrl/server/ajax/player"
        val headers = mapOf(
            "X-CSRF-TOKEN" to csrfToken,
            "X-Requested-With" to "XMLHttpRequest",
            "Referer" to data,
            "Origin" to mainUrl
        )
        // Lưu ý: API nhận 'MovieID' và 'EpisodeID' (Viết hoa chữ cái đầu)
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
            } else {
                loadExtractor(link, data, subtitleCallback, callback)
            }
        }

        // Mapping dựa trên HTML file mới phân tích
        processSource(jsonResponse.srcVip, "Vip") // StreamBlue
        processSource(jsonResponse.srcOp, "Op")   // OpStream
        processSource(jsonResponse.srcKk, "Kk")   // Phim1280
        processSource(jsonResponse.srcArc, "Arc") // Archive
        processSource(jsonResponse.srcOk, "Ok")   // Ok.ru
        processSource(jsonResponse.srcDl, "Dl")   // Dood/StreamC
        processSource(jsonResponse.srcHx, "Hx")   // Hx
        processSource(jsonResponse.srcTok, "Tok")
        processSource(jsonResponse.srcSs, "Ss")
        
        return true
    }
}
