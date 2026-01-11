package recloudstream

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.network.CloudflareKiller
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.utils.AppUtils.tryParseJson
import org.jsoup.nodes.Element
import org.jsoup.Jsoup

// Import cần thiết cho M3U8
import com.lagradost.cloudstream3.utils.M3u8Helper

// === Data Classes ===
@JsonIgnoreProperties(ignoreUnknown = true)
data class HHAjaxResponse(
    val code: Int? = null,
    val src_vip: String? = null,
    val src_op: String? = null,
    val src_kk: String? = null,
    val src_arc: String? = null,
    val src_ok: String? = null,
    val src_dl: String? = null,
    val src_hx: String? = null,
    val src_tok: String? = null
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
    private var resolvedUrl: String? = null

    // === Helper: Resolve Redirect ===
    private suspend fun getRealUrl(): String {
        resolvedUrl?.let { return it }
        return try {
            val response = app.get(mainUrl, allowRedirects = false)
            if (response.code == 301 || response.code == 302) {
                response.headers["location"]?.trimEnd('/')?.also { resolvedUrl = it } ?: mainUrl
            } else {
                mainUrl.trimEnd('/').also { resolvedUrl = it }
            }
        } catch (e: Exception) {
            mainUrl
        }
    }

    // === Helper: Parse Search Item ===
    private fun Element.toSearchResponse(domain: String): SearchResponse? {
        val aTag = this.selectFirst("a") ?: return null
        val title = aTag.selectFirst(".name-movie")?.text()?.trim() ?: return null
        var href = aTag.attr("href")
        if (!href.startsWith("http")) href = "$domain$href"
        
        val imgTag = aTag.selectFirst("img")
        val posterUrl = imgTag?.attr("data-src") ?: imgTag?.attr("src")
        val episodeLabel = aTag.selectFirst(".episode-latest span")?.text()

        return newAnimeSearchResponse(title, href, TvType.Anime) {
            this.posterUrl = posterUrl
            this.otherName = episodeLabel
        }
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val currentDomain = getRealUrl()
        val url = "$currentDomain${request.data}" + if (page > 1) "?p=$page" else ""
        val document = app.get(url, interceptor = killer).document
        
        val home = document.select("div.movies-list div.movie-item").mapNotNull {
            it.toSearchResponse(currentDomain)
        }
        val hasNext = document.select("div.pagination a.page-link").any { 
            it.attr("href").contains("p=${page + 1}") 
        }

        return newHomePageResponse(
            list = HomePageList(name = request.name, list = home),
            hasNext = hasNext
        )
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val currentDomain = getRealUrl()
        val url = "$currentDomain/tim-kiem/$query.html"
        val document = app.get(url, interceptor = killer).document
        return document.select("div.movies-list div.movie-item").mapNotNull {
            it.toSearchResponse(currentDomain)
        }
    }

    override suspend fun load(url: String): LoadResponse {
        val document = app.get(url, interceptor = killer).document
        val title = document.selectFirst("h1.heading_movie")?.text()?.trim() ?: "No Title"
        val posterUrl = document.selectFirst("div.head img")?.let { it.attr("data-src").ifEmpty { it.attr("src") } }
        val desc = document.select("div.desc .list-item-episode p").text().trim()
        val tags = document.select("div.list_cate a").map { it.text() }
        val type = if (tags.any { it.contains("3D", true) }) TvType.Cartoon else TvType.Anime

        val episodes = document.select("#episode-list a.episode-item").mapNotNull {
            val epHref = it.attr("href")
            val epName = it.select("span").text().trim()
            if (epHref.isEmpty()) return@mapNotNull null
            newEpisode(epHref) {
                this.name = "Tập $epName"
                this.episode = epName.toIntOrNull()
            }
        }.reversed()

        return newTvSeriesLoadResponse(title, url, type, episodes) {
            this.posterUrl = posterUrl
            this.plot = desc
            this.tags = tags
            // Đã xóa phần rating/score theo yêu cầu
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val currentDomain = getRealUrl()
        val document = app.get(data, referer = currentDomain, interceptor = killer).document

        val csrfToken = document.select("meta[name=csrf-token]").attr("content")
        val scriptContent = document.select("script").joinToString("\n") { it.data() }
        
        val movieId = Regex("""MovieID\s*:\s*(\d+)""").find(scriptContent)?.groupValues?.get(1)
            ?: throw ErrorLoadingException("Không tìm thấy MovieID")
        val episodeId = Regex("""EpisodeID\s*:\s*(\d+)""").find(scriptContent)?.groupValues?.get(1)
            ?: throw ErrorLoadingException("Không tìm thấy EpisodeID")

        val ajaxUrl = "$currentDomain/server/ajax/player"
        val headers = mapOf(
            "X-CSRF-TOKEN" to csrfToken,
            "X-Requested-With" to "XMLHttpRequest",
            "Referer" to data,
            "Origin" to currentDomain,
            "Accept" to "*/*"
        )
        val formBody = mapOf("MovieID" to movieId, "EpisodeID" to episodeId)

        val ajaxResponse = app.post(ajaxUrl, headers = headers, data = formBody, interceptor = killer).text
        val json = tryParseJson<HHAjaxResponse>(ajaxResponse) 
            ?: throw ErrorLoadingException("Lỗi parse JSON player")

        val sourceMap = mapOf(
            "Vip (StreamBlue)" to json.src_vip,
            "OpStream" to json.src_op,
            "Kk (Phim1280)" to json.src_kk,
            "Archive" to json.src_arc,
            "OkRu" to json.src_ok,
            "StreamC" to json.src_dl,
            "Short" to json.src_hx,
            "Tok" to json.src_tok
        )

        sourceMap.forEach { (serverName, src) ->
            if (!src.isNullOrEmpty()) {
                val cleanSrc = src.replace("\\/", "/")
                
                when {
                    cleanSrc.contains(".m3u8") -> {
                        // M3u8Helper đã trả về list ExtractorLink chuẩn, có thể dùng trực tiếp
                        M3u8Helper.generateM3u8(
                            serverName,
                            cleanSrc,
                            currentDomain
                        ).forEach(callback)
                    }
                    cleanSrc.contains(".mp4") -> {
                        // SỬ DỤNG newExtractorLink (MP4)
                        val link = newExtractorLink(
                            source = serverName,
                            name = serverName,
                            url = cleanSrc,
                            type = ExtractorLinkType.VIDEO
                        ) {
                            this.referer = currentDomain
                            this.quality = Qualities.Unknown.value
                        }
                        callback(link)
                    }
                    else -> {
                        val linkUrl = if (cleanSrc.contains("<iframe")) {
                            Jsoup.parse(cleanSrc).select("iframe").attr("src")
                        } else {
                            cleanSrc
                        }
                        
                        if (linkUrl.startsWith("http")) {
                            loadExtractor(linkUrl, data, subtitleCallback) { extractedLink ->
                                // SỬ DỤNG newExtractorLink (Embed)
                                val finalLink = newExtractorLink(
                                    source = extractedLink.source,
                                    name = "$serverName - ${extractedLink.name}",
                                    url = extractedLink.url,
                                    type = extractedLink.type
                                ) {
                                    this.referer = extractedLink.referer
                                    this.quality = extractedLink.quality
                                    this.headers = extractedLink.headers
                                    this.extractorData = extractedLink.extractorData
                                }
                                callback(finalLink)
                            }
                        }
                    }
                }
            }
        }
        return true
    }
    
    override val mainPage = mainPageOf(
        "/phim-moi-cap-nhap.html" to "Mới Cập Nhật",
        "/the-loai/cna-3d.html" to "Hoạt Hình 3D",
        "/the-loai/anime.html" to "Anime",
        "/the-loai/huyen-ao.html" to "Huyền Ảo",
        "/the-loai/tu-tien.html" to "Tu Tiên"
    )
}
