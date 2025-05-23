package com.example.nangcucprovider // Hoặc package của bạn

import android.content.Context
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.MainPageRequest
import com.lagradost.cloudstream3.HomePageList
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.HomePageResponse
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.newMovieSearchResponse
import com.lagradost.cloudstream3.newHomePageResponse
import com.lagradost.cloudstream3.newMovieLoadResponse
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.SubtitleFile
// import com.lagradost.cloudstream3.utils.M3u8Helper // Import nếu cần xử lý M3U8 phức tạp hơn

import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.mapper // Đối tượng mapper của Jackson
import com.lagradost.cloudstream3.utils.AppUtils.parseJson // Hoặc dùng tiện ích này

import org.jsoup.Jsoup

// --- Data Classes đã được cập nhật theo JSON bạn cung cấp ---
data class VideoApiResponse(
    @JsonProperty("status") val status: Int?,
    @JsonProperty("links") val links: List<VideoLink>?,
    @JsonProperty("cache_header") val cacheHeader: String?
)

data class VideoLink(
    @JsonProperty("name") val name: String?,
    @JsonProperty("link") val link: String?,
    @JsonProperty("iframe") val iframe: Boolean? = null
)

class NangCucProvider : MainAPI() {
    override var mainUrl = "https://nangcuc.cc"
    override var name = "Nắng Cực TV"
    override val supportedTypes = setOf(TvType.Movie)
    override var lang = "vi"
    override val hasMainPage = true

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse? {
        val url = if (page > 1) "$mainUrl/moi-nhat/$page" else "$mainUrl/moi-nhat/"
        try {
            val document = app.get(url).document
            val lists = mutableListOf<HomePageList>()
            val newestSection = document.selectFirst("section.block_area_home.section-id-02")
                ?: document.selectFirst(".block_area-content.block_area-list.film_list.film_list-grid")

            if (newestSection != null) {
                val sectionTitle = newestSection.selectFirst("h1.cat-heading")?.text() ?: "Phim Mới Cập Nhật"
                val movies = newestSection.select("div.flw-item").mapNotNull { item ->
                    val filmPoster = item.selectFirst("div.film-poster")
                    val movieName = filmPoster?.selectFirst("img.film-poster-img")?.attr("title")
                    val movieHref = filmPoster?.selectFirst("a.film-poster-ahref")?.attr("href")
                    var moviePosterUrl = filmPoster?.selectFirst("img.film-poster-img")?.attr("src")
                    if (moviePosterUrl == "/images/1px.gif" || moviePosterUrl?.startsWith("data:image") == true) {
                        moviePosterUrl = filmPoster?.selectFirst("img.film-poster-img")?.attr("data-src")
                    }
                    if (movieName != null && movieHref != null && moviePosterUrl != null) {
                        newMovieSearchResponse(
                            name = movieName,
                            url = if (movieHref.startsWith("http")) movieHref else mainUrl + movieHref
                        ) {
                            this.posterUrl = moviePosterUrl
                            this.type = TvType.Movie
                        }
                    } else {
                        null
                    }
                }
                if (movies.isNotEmpty()) {
                    lists.add(HomePageList(sectionTitle, movies))
                }
            }
            if (lists.isEmpty()) return null
            return newHomePageResponse(lists)
        } catch (e: Exception) {
            e.printStackTrace()
            return null
        }
    }

    override suspend fun search(query: String): List<SearchResponse>? {
        val searchUrl = "$mainUrl/tim-kiem/$query/"
        try {
            val document = app.get(searchUrl).document
            val searchResults = document.select("div.block_area-content div.flw-item").mapNotNull { item ->
                val filmPoster = item.selectFirst("div.film-poster")
                val movieName = filmPoster?.selectFirst("img.film-poster-img")?.attr("title")
                val movieHref = filmPoster?.selectFirst("a.film-poster-ahref")?.attr("href")
                var moviePosterUrl = filmPoster?.selectFirst("img.film-poster-img")?.attr("src")
                if (moviePosterUrl == "/images/1px.gif" || moviePosterUrl?.startsWith("data:image") == true) {
                    moviePosterUrl = filmPoster?.selectFirst("img.film-poster-img")?.attr("data-src")
                }
                if (movieName != null && movieHref != null && moviePosterUrl != null) {
                    newMovieSearchResponse(
                        name = movieName,
                        url = if (movieHref.startsWith("http")) movieHref else mainUrl + movieHref
                    ) {
                        this.posterUrl = moviePosterUrl
                        this.type = TvType.Movie
                    }
                } else {
                    null
                }
            }
            return searchResults.ifEmpty { null }
        } catch (e: Exception) {
            e.printStackTrace()
            return null
        }
    }

    override suspend fun load(url: String): LoadResponse? {
        try {
            val document = app.get(url).document
            val title = document.selectFirst("h1.video-title")?.text()
                ?: document.selectFirst("meta[property=\"og:title\"]")?.attr("content")
            val poster = document.selectFirst("meta[property=\"og:image\"]")?.attr("content")
            val plot = document.selectFirst("div.about span p")?.text()
                ?: document.selectFirst("meta[property=\"og:description\"]")?.attr("content")
            val genres = document.select("div.genres a")?.mapNotNull { it.text() }
            var videoApiId: String? = null
            var dataUrlForLoadLinks: String? = null
            var extractedSubtitles: List<SubtitleFile> = emptyList()

            document.select("script").forEach { script ->
                val scriptData = script.data()
                if (scriptData.contains("api.nangdata.xyz")) {
                    val fullUrlRegex = Regex("""["'](https?://api\.nangdata\.xyz/v2/[a-zA-Z0-9\-]+)["']""")
                    dataUrlForLoadLinks = fullUrlRegex.find(scriptData)?.groupValues?.getOrNull(1)
                    if (dataUrlForLoadLinks == null) {
                        val idRegexV2 = Regex("""api\.nangdata\.xyz/v2/([a-zA-Z0-9\-]+)""")
                        val idRegexShort = Regex("""api\.nangdata\.xyz/([a-zA-Z0-9\-]+)""")
                        videoApiId = idRegexV2.find(scriptData)?.groupValues?.getOrNull(1)
                            ?: idRegexShort.find(scriptData)?.groupValues?.getOrNull(1)
                        if (videoApiId != null && videoApiId != "v2") {
                            dataUrlForLoadLinks = "https://api.nangdata.xyz/v2/$videoApiId"
                        }
                    }
                }
                if (scriptData.contains("subtitles=")) {
                    val subRegex = Regex("""subtitles=\s*\[\s*\{.*?["']hl["']\s*:\s*["']([^"']+)["']\s*,\s*.*?["']url["']\s*:\s*["']([^"']+\.(?:srt|vtt))["'].*?\}\s*\]""")
                    extractedSubtitles = subRegex.findAll(scriptData).mapNotNull { matchResult ->
                        val lang = matchResult.groupValues.getOrNull(1)
                        val subUrlValue = matchResult.groupValues.getOrNull(2)
                        if (lang != null && subUrlValue != null) {
                            SubtitleFile(lang, subUrlValue)
                        } else {
                            null
                        }
                    }.toList()
                }
                // Thoát sớm nếu đã tìm thấy cả hai thông tin quan trọng
                if (dataUrlForLoadLinks != null && extractedSubtitles.isNotEmpty() && scriptData.contains("api.nangdata.xyz") && scriptData.contains("subtitles=")) {
                     //Điều kiện thoát này có thể cần điều chỉnh nếu vị trí của subtitles và api.nangdata.xyz không nhất quán
                     return@forEach
                }
                 // Hoặc nếu chỉ cần dataUrlForLoadLinks để tiếp tục
                 if (dataUrlForLoadLinks != null && !scriptData.contains("subtitles=")) {
                     // Nếu đã tìm thấy link API và script này không chứa subtitles, có thể thoát sớm để tối ưu
                     // return@forEach // Bỏ comment nếu muốn tối ưu mạnh hơn
                 }
            }

            val recommendationsList = document.select("section.block_area-related div.flw-item").mapNotNull { item ->
                val filmPosterRec = item.selectFirst("div.film-poster")
                val nameRec = filmPosterRec?.selectFirst("img.film-poster-img")?.attr("title")
                val hrefRec = filmPosterRec?.selectFirst("a.film-poster-ahref")?.attr("href")
                var posterUrlRec = filmPosterRec?.selectFirst("img.film-poster-img")?.attr("data-src")
                if (posterUrlRec == "/images/1px.gif" || posterUrlRec?.startsWith("data:image") == true) {
                    posterUrlRec = filmPosterRec?.selectFirst("img.film-poster-img")?.attr("src")
                }
                if (nameRec != null && hrefRec != null && posterUrlRec != null) {
                    newMovieSearchResponse(
                        name = nameRec,
                        url = if (hrefRec.startsWith("http")) hrefRec else mainUrl + hrefRec
                    ) {
                        this.posterUrl = posterUrlRec
                        this.type = TvType.Movie
                    }
                } else {
                    null
                }
            }

            if (title == null || dataUrlForLoadLinks == null) {
                println("Lỗi load(): Không tìm thấy title hoặc dataUrlForLoadLinks. Title: $title, DataUrl: $dataUrlForLoadLinks, Extracted API ID: $videoApiId, Page URL: $url")
                return null
            }

            val syncDataMap = mutableMapOf("movie_page_url" to url)
            if (extractedSubtitles.isNotEmpty()) {
                try {
                    syncDataMap["extracted_subtitles"] = mapper.writeValueAsString(extractedSubtitles)
                } catch (e: Exception) {
                    println("Lỗi khi chuyển extracted_subtitles thành JSON string: $e")
                    // Không làm crash app nếu lỗi serialize, chỉ log lỗi
                }
            }

            return newMovieLoadResponse(
                name = title,
                url = url,
                type = TvType.Movie,
                dataUrl = dataUrlForLoadLinks!!
            ) {
                this.posterUrl = poster
                this.plot = plot
                this.tags = genres
                this.recommendations = recommendationsList
                this.syncData = syncDataMap
            }
        } catch (e: Exception) {
            e.printStackTrace()
            return null
        }
    }

    override suspend fun loadLinks(
        data: String, // dataUrl từ MovieLoadResponse (API endpoint)
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        try {
            val currentLoadResponse = currentMovieData
            val syncData = currentLoadResponse?.syncData

            syncData?.get("extracted_subtitles")?.let { subtitlesJson ->
                try {
                    val subtitles = parseJson<List<SubtitleFile>>(subtitlesJson)
                    subtitles.forEach { subtitle -> subtitleCallback(subtitle) }
                } catch (e: Exception) {
                    println("Lỗi parse extracted_subtitles từ syncData: $e")
                }
            }

            val apiResponseJson = app.get(data).text
            val apiData = parseJson<VideoApiResponse>(apiResponseJson)

            var foundLinks = false
            if (apiData.status == 200) {
                apiData.links?.forEach { videoLink ->
                    val videoUrl = videoLink.link
                    val serverName = videoLink.name ?: this.name
                    val isIframe = videoLink.iframe ?: false

                    if (videoUrl != null) {
                        val quality = when {
                            serverName.contains("1080") -> Qualities.P1080.value
                            serverName.contains("720") -> Qualities.P720.value
                            serverName.contains("480") -> Qualities.P480.value
                            serverName.contains("360") -> Qualities.P360.value
                            else -> Qualities.Unknown.value
                        }

                        val linkType = if (videoUrl.contains(".m3u8", ignoreCase = true)) {
                            ExtractorLinkType.M3U8
                        } else if (videoUrl.contains(".mp4", ignoreCase = true)) {
                            ExtractorLinkType.VIDEO
                        } else if (isIframe) {
                            // Xử lý cẩn thận cho iframe. Nếu link là m3u8 thì vẫn là M3U8.
                            // Nếu không, có thể cần một extractor hoặc nó là một link video trực tiếp khác.
                            if (videoUrl.contains(".m3u8", ignoreCase = true)) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                            // Cân nhắc log warning ở đây nếu link iframe không phải là media trực tiếp
                            // println("Warning: Link iframe ${serverName} có thể cần xử lý đặc biệt: $videoUrl")
                        } else {
                            ExtractorLinkType.VIDEO
                        }
                        
                        val refererUrl = syncData?.get("movie_page_url") ?: mainUrl

                        callback.invoke(
                            ExtractorLink(
                                source = serverName,
                                name = serverName,
                                url = videoUrl,
                                referer = refererUrl,
                                quality = quality,
                                type = linkType,
                                headers = mapOf(),
                                extractorData = null
                            )
                        )
                        foundLinks = true
                    }
                }
            } else {
                println("API trả về status không thành công: ${apiData.status} cho URL: $data")
            }
            // Xử lý phụ đề từ API nếu có (ví dụ)
            // apiData.subtitles?.forEach { apiSub ->
            //     if (apiSub.url != null && apiSub.langCode != null) {
            //         subtitleCallback.invoke(SubtitleFile(apiSub.langCode, apiSub.url))
            //     }
            // }
            return foundLinks
        } catch (e: Exception) {
            e.printStackTrace()
            return false
        }
    }
}

// import com.lagradost.cloudstream3.plugins.CloudstreamPlugin // Bỏ comment nếu muốn đăng ký plugin
// import com.lagradost.cloudstream3.plugins.Plugin

// @CloudstreamPlugin
// class NangCucLoader : Plugin() {
//    override fun load(context: Context) {
//        registerMainAPI(NangCucProvider())
//    }
// }
