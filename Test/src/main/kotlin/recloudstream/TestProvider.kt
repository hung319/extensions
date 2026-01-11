package recloudstream

import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.network.CloudflareKiller
import org.jsoup.nodes.Element
import kotlin.random.Random

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
        "phim-moi-cap-nhap.html" to "Mới Cập Nhật",
        "the-loai/cna-3d.html" to "Hoạt Hình 3D",
        "the-loai/anime.html" to "Anime",
        "the-loai/sap-chieu.html" to "Sắp Chiếu"
    )

    private fun Element.toSearchResponse(): SearchResponse? {
        val linkTag = this.selectFirst("a") ?: return null
        val href = fixUrl(linkTag.attr("href"))
        val title = this.selectFirst(".name-movie")?.text()?.trim() ?: linkTag.attr("title")
        
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
        
        // Lấy Tags và URL của Tags để làm recommendation
        val tagsElements = document.select(".list_cate a")
        val tags = tagsElements.map { it.text() }
        val tagUrls = tagsElements.map { fixUrl(it.attr("href")) }
        
        val type = if (tags.any { it.contains("3D") || it.contains("HH3D") }) TvType.Cartoon else TvType.Anime

        // Xử lý tập phim
        val episodes = document.select("#episode-list a.episode-item").map {
            val href = fixUrl(it.attr("href"))
            val rawName = it.text().trim()
            // Format tên tập: "1" -> "Tập 1"
            val name = if (rawName.lowercase().startsWith("tập")) rawName else "Tập $rawName"
            
            newEpisode(href) {
                this.name = name
                this.episode = rawName.toIntOrNull()
            }
        }.reversed()

        // --- RECOMMENDATION LOGIC ---
        // 1. Thử tìm list phim liên quan trên page (nếu web có cấu trúc này)
        var recommendations = document.select(".related-movies .movie-item").mapNotNull { it.toSearchResponse() }

        // 2. Nếu không có, lấy random từ 1 genre bất kỳ của phim
        if (recommendations.isEmpty() && tagUrls.isNotEmpty()) {
            try {
                // Chọn ngẫu nhiên 1 link thể loại
                val randomTagUrl = tagUrls.random()
                val tagDoc = app.get(randomTagUrl, interceptor = killer).document
                recommendations = tagDoc.select(".movies-list .movie-item").mapNotNull { it.toSearchResponse() }
            } catch (e: Exception) {
                // Ignore error fetching recommendations
            }
        }

        return newAnimeLoadResponse(title, url, type) {
            this.posterUrl = posterUrl
            this.plot = description
            this.tags = tags
            this.recommendations = recommendations
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

        // Lấy ID từ Form báo lỗi (Chính xác nhất)
        var movieId = document.selectFirst("input[name='movie_id']")?.attr("value")
        var episodeId = document.selectFirst("input[name='Episode_id']")?.attr("value")

        // Fallback: Quét script nếu không thấy form
        if (movieId == null || episodeId == null) {
            val scriptContent = document.select("script").joinToString("\n") { it.data() }
            if (movieId == null) {
                movieId = Regex("""movie_id\s*:\s*['"]?(\d+)['"]?""", RegexOption.IGNORE_CASE).find(scriptContent)?.groupValues?.get(1)
            }
            if (episodeId == null) {
                episodeId = Regex("""id\s*:\s*['"]?(\d+)['"]?,\s*no_ep""", RegexOption.IGNORE_CASE).find(scriptContent)?.groupValues?.get(1)
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

        // Hàm xử lý link dùng newExtractorLink cho m3u8/mp4
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
            } else if (link.contains(".m3u8")) {
                // Dùng newExtractorLink cho M3U8 thay vì loadExtractor
                callback(
                    newExtractorLink(
                        source = name,
                        name = "$sourceName HLS",
                        url = link,
                        type = ExtractorLinkType.M3U8
                    ) {
                        this.referer = data
                    }
                )
            } else {
                // Các nguồn embed (StreamBlue, OkRu...) vẫn dùng loadExtractor để nó tự tách
                loadExtractor(link, data, subtitleCallback, callback)
            }
        }

        // Ưu tiên M3U8 Direct trước
        processSource(jsonResponse.srcOp, "Op")
        processSource(jsonResponse.srcKk, "Kk")
        processSource(jsonResponse.srcSs, "Ss")
        
        // Link Direct MP4
        processSource(jsonResponse.srcArc, "Arc")
        
        // Link Embed
        processSource(jsonResponse.srcVip, "Vip")
        processSource(jsonResponse.srcOk, "Ok")
        processSource(jsonResponse.srcDl, "Dl")
        processSource(jsonResponse.srcHx, "Hx")
        processSource(jsonResponse.srcTok, "Tok")
        
        return true
    }
}
