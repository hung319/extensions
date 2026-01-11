// Save this file as HHDRagonProvider.kt

package recloudstream

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.network.CloudflareKiller
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.utils.AppUtils.tryParseJson
import org.jsoup.nodes.Element
import org.jsoup.Jsoup

// === Data Classes khớp với JSON response mới ===
@JsonIgnoreProperties(ignoreUnknown = true)
data class HHAjaxResponse(
    val code: Int? = null,
    // Các nguồn phim từ JSON thực tế
    val src_vip: String? = null, // StreamBlue
    val src_op: String? = null,  // OpStream (m3u8)
    val src_kk: String? = null,  // Phim1280 (m3u8)
    val src_arc: String? = null, // Archive.org (mp4)
    val src_ok: String? = null,  // OkRu
    val src_dl: String? = null,  // StreamC
    val src_hx: String? = null,  // Short.icu
    val src_tok: String? = null  // Tok (nếu có)
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
    
    // Biến cache domain thật
    private var resolvedUrl: String? = null

    // === 1. Dynamic Domain Resolution ===
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

    // === 2. Parsing Helper ===
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
            this.rating = document.select(".score").text().substringBefore("||").trim().toRatingInt()
        }
    }

    // === 3. PLAYER LOGIC (UPDATED) ===
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val currentDomain = getRealUrl()
        val document = app.get(data, referer = currentDomain, interceptor = killer).document

        // Extract metadata for Request
        val csrfToken = document.select("meta[name=csrf-token]").attr("content")
        val scriptContent = document.select("script").joinToString("\n") { it.data() }
        val movieId = Regex("""MovieID\s*:\s*(\d+)""").find(scriptContent)?.groupValues?.get(1)
            ?: throw ErrorLoadingException("Không tìm thấy MovieID")
        val episodeId = Regex("""EpisodeID\s*:\s*(\d+)""").find(scriptContent)?.groupValues?.get(1)
            ?: throw ErrorLoadingException("Không tìm thấy EpisodeID")

        // Prepare AJAX Request
        val ajaxUrl = "$currentDomain/server/ajax/player"
        
        val headers = mapOf(
            "X-CSRF-TOKEN" to csrfToken,
            "X-Requested-With" to "XMLHttpRequest",
            "Referer" to data,
            "Origin" to currentDomain,
            "Accept" to "*/*"
        )

        // Gửi Form Data (application/x-www-form-urlencoded)
        val formBody = mapOf(
            "MovieID" to movieId,
            "EpisodeID" to episodeId
        )

        val ajaxResponse = app.post(
            ajaxUrl, 
            headers = headers, 
            data = formBody, // Cloudstream tự động encode map này thành form data
            interceptor = killer
        ).text

        val json = tryParseJson<HHAjaxResponse>(ajaxResponse) 
            ?: throw ErrorLoadingException("Lỗi parse JSON player: $ajaxResponse")

        // Map sources
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
                val cleanSrc = src.replace("\\/", "/") // Fix escaped slashes nếu có
                
                when {
                    // Trường hợp 1: File M3U8
                    cleanSrc.contains(".m3u8") -> {
                        generateM3u8(
                            name = serverName,
                            streamUrl = cleanSrc,
                            referer = currentDomain
                        ).forEach(callback)
                    }
                    // Trường hợp 2: File MP4 trực tiếp
                    cleanSrc.contains(".mp4") -> {
                        callback(
                            newExtractorLink(
                                name = serverName,
                                source = serverName,
                                url = cleanSrc,
                                referer = currentDomain,
                                quality = Qualities.Unknown.value,
                                type = ExtractorLinkType.VIDEO
                            )
                        )
                    }
                    // Trường hợp 3: Embed (StreamBlue, OkRu, StreamC, etc.)
                    else -> {
                        // Nếu là iframe, extract src
                        val linkUrl = if (cleanSrc.contains("<iframe")) {
                            Jsoup.parse(cleanSrc).select("iframe").attr("src")
                        } else {
                            cleanSrc
                        }
                        
                        if (linkUrl.startsWith("http")) {
                            loadExtractor(linkUrl, data, subtitleCallback) { link ->
                                callback(
                                    newExtractorLink(
                                        link.source,
                                        "$serverName - ${link.name}",
                                        link.url,
                                        link.referer,
                                        link.quality,
                                        link.type,
                                        link.headers,
                                        link.extractorData
                                    )
                                )
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
