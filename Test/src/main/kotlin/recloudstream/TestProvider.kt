package recloudstream

import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.network.CloudflareKiller
import com.lagradost.cloudstream3.mvvm.suspendSafeApiCall
import android.util.Log
import org.jsoup.nodes.Element

// --- Data Classes khớp với JSON response ---
data class PlayerAjaxResponse(
    @JsonProperty("code") val code: Int? = null,
    @JsonProperty("src_vip") val srcVip: String? = null, // StreamBlue
    @JsonProperty("src_op") val srcOp: String? = null,   // OpStream
    @JsonProperty("src_kk") val srcKk: String? = null,   // Phim1280
    @JsonProperty("src_arc") val srcArc: String? = null, // Archive.org
    @JsonProperty("src_ok") val srcOk: String? = null,   // Ok.ru
    @JsonProperty("src_dl") val srcDl: String? = null,   // StreamC
    @JsonProperty("src_hx") val srcHx: String? = null,   // Short link
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
        "phim-moi-cap-nhap.html" to "Mới Cập Nhật",
        "the-loai/cna-3d.html" to "Hoạt Hình 3D",
        "the-loai/anime.html" to "Anime",
        "the-loai/sap-chieu.html" to "Sắp Chiếu"
    )

    private fun Element.toSearchResponse(): SearchResponse? {
        val linkTag = this.selectFirst("a") ?: return null
        val href = fixUrl(linkTag.attr("href"))
        val title = this.selectFirst(".name-movie")?.text()?.trim() ?: linkTag.attr("title")
        val posterUrl = this.selectFirst("img")?.let { img ->
            img.attr("data-src").ifBlank { img.attr("src") }
        }
        val episodeLatest = this.selectFirst(".episode-latest span")?.text()
        return newAnimeSearchResponse(title, href, TvType.Anime) {
            this.posterUrl = posterUrl
            addLatest(episodeLatest)
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
            it.attr("data-src").ifBlank { it.attr("src") }
        }
        val description = document.select(".desc .list-item-episode p").text().trim()
        val tags = document.select(".list_cate a").map { it.text() }
        val type = if (tags.any { it.contains("3D") || it.contains("HH3D") }) TvType.Cartoon else TvType.Anime

        val episodes = document.select("#episode-list a.episode-item").map {
            val href = fixUrl(it.attr("href"))
            val name = it.text().trim()
            Episode(data = href, name = name)
        }.reversed()

        return newAnimeLoadResponse(title, url, type) {
            this.posterUrl = posterUrl
            this.plot = description
            this.tags = tags
            addEpisodes(TvType.Anime, episodes)
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
        val movieId = Regex("""movie_id\s*:\s*(\d+)""").find(scriptContent)?.groupValues?.get(1)
        val episodeId = Regex("""id\s*:\s*(\d+)""").find(scriptContent)?.groupValues?.get(1)

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

        suspend fun processSource(link: String?, sourceName: String) {
            if (link.isNullOrBlank()) return

            if (link.endsWith(".mp4")) {
                // Sử dụng suspend newExtractorLink như yêu cầu
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

        // Ưu tiên các nguồn ngon trước
        processSource(jsonResponse.srcVip, "Vip")
        processSource(jsonResponse.srcOp, "Op")
        processSource(jsonResponse.srcKk, "Kk")
        processSource(jsonResponse.srcArc, "Archive") // Thường là mp4 direct
        processSource(jsonResponse.srcOk, "OkRu")
        processSource(jsonResponse.srcDl, "StreamC")
        processSource(jsonResponse.srcHx, "Hx")
        processSource(jsonResponse.srcTok, "Tok")
        
        return true
    }
}
