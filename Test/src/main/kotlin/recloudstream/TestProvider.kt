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
import com.lagradost.cloudstream3.utils.JsUnpacker

import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.mapper
import com.lagradost.cloudstream3.utils.AppUtils.parseJson

import org.jsoup.Jsoup
import org.jsoup.nodes.Document

// Data class để truyền thông tin cần thiết cho loadLinks (chỉ còn apiUrl và moviePageUrl)
data class LoadLinksHelperData(
    val apiUrl: String,
    val moviePageUrl: String // Vẫn cần cho referer trong loadLinks
)

// Data classes cho API response (giữ nguyên)
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

    // Hàm trích xuất phụ đề, giờ sẽ được gọi từ load()
    private fun extractAndCallbackSubtitles(document: Document, subtitleCallback: (SubtitleFile) -> Unit) {
        var subtitlesCalledCount = 0
        var scriptContainingSubtitlesFound = false
        val allExtractedSubtitles = mutableListOf<SubtitleFile>()

        document.select("script").forEachIndexed { index, script ->
            var scriptData = script.data()
            if (scriptData.startsWith("eval(function(p,a,c,k,e,d)")) {
                try {
                    scriptData = JsUnpacker(scriptData).unpack() ?: scriptData
                    println("NangCucProvider LOG (extractSubs): Script #${index} đã được unpack.")
                } catch (e: Exception) {
                    println("NangCucProvider LOG (extractSubs): Lỗi khi unpack script #${index}: $e")
                }
            }

            if (scriptData.contains("subtitles", ignoreCase = true)) {
                scriptContainingSubtitlesFound = true
                println("NangCucProvider LOG (extractSubs): Script #${index} có chứa 'subtitles'.")

                val arrayContentMatch = Regex("""["']?subtitles["']?\s*[:=]\s*(\[.*?\])""", RegexOption.DOT_MATCHES_ALL).find(scriptData)
                if (arrayContentMatch != null) {
                    val arrayContent = arrayContentMatch.groupValues[1]
                    println("NangCucProvider LOG (extractSubs): Nội dung array phụ đề: $arrayContent")
                    val subObjectRegex = Regex("""\{\s*(?:["']?hl["']?\s*:\s*["']([^"']+)["']|["']?label["']?\s*:\s*["']([^"']+)["'])\s*,\s*(?:["']?url["']?\s*:\s*["']([^"']+\.(?:srt|vtt))["'])\s*.*?\}""", RegexOption.IGNORE_CASE)
                    
                    val scriptSubtitles = subObjectRegex.findAll(arrayContent).mapNotNull { objMatchResult ->
                        val langFromHl = objMatchResult.groupValues.getOrNull(1)?.trim()
                        val langFromLabel = objMatchResult.groupValues.getOrNull(2)?.trim()
                        val lang = langFromHl?.takeIf { it.isNotEmpty() } ?: langFromLabel?.takeIf { it.isNotEmpty() }
                        val subUrlValue = objMatchResult.groupValues.getOrNull(3)?.trim()

                        if (lang != null && subUrlValue != null && subUrlValue.startsWith("http")) {
                            SubtitleFile(lang, subUrlValue)
                        } else { null }
                    }.toList()
                    
                    if (scriptSubtitles.isNotEmpty()) {
                        allExtractedSubtitles.addAll(scriptSubtitles)
                    }
                } else {
                    println("NangCucProvider LOG (extractSubs): Không tìm thấy nội dung array ([...]) sau 'subtitles=' trong script #${index}.")
                }
            }
        }

        if (!scriptContainingSubtitlesFound) {
            println("NangCucProvider LOG (extractSubs): Không tìm thấy script nào chứa 'subtitles='.")
        }

        allExtractedSubtitles.distinctBy { it.url }.forEach { subtitle ->
            println("NangCucProvider LOG (extractSubs): Gọi subtitleCallback cho: ${subtitle.lang} - ${subtitle.url}")
            subtitleCallback(subtitle)
            subtitlesCalledCount++
        }
        println("NangCucProvider LOG (extractSubs): Đã gọi subtitleCallback $subtitlesCalledCount lần.")
    }

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse? {
        // ... (giữ nguyên không đổi) ...
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
                    } else { null }
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
        // ... (giữ nguyên không đổi) ...
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
                } else { null }
            }
            return searchResults.ifEmpty { null }
        } catch (e: Exception) {
            e.printStackTrace()
            return null
        }
    }

    // Hàm load giờ sẽ gọi subtitleCallback
    override suspend fun load(url: String): LoadResponse? { // url ở đây là moviePageUrl
        try {
            println("NangCucProvider LOG (load): Fetching page: $url")
            val document = app.get(url).document // Tải HTML trang phim
            val title = document.selectFirst("h1.video-title")?.text()
                ?: document.selectFirst("meta[property=\"og:title\"]")?.attr("content")
            val poster = document.selectFirst("meta[property=\"og:image\"]")?.attr("content")
            val plot = document.selectFirst("div.about span p")?.text()
                ?: document.selectFirst("meta[property=\"og:description\"]")?.attr("content")
            val genres = document.select("div.genres a")?.mapNotNull { it.text() }
            var videoApiIdFromScript: String? = null
            var apiUrlFromScript: String? = null

            document.select("script").forEach { script ->
                val scriptData = script.data()
                if (scriptData.contains("api.nangdata.xyz")) {
                    val fullUrlRegex = Regex("""["'](https?://api\.nangdata\.xyz/v2/[a-zA-Z0-9\-]+)["']""")
                    apiUrlFromScript = fullUrlRegex.find(scriptData)?.groupValues?.getOrNull(1)
                    if (apiUrlFromScript == null) {
                        val idRegexV2 = Regex("""api\.nangdata\.xyz/v2/([a-zA-Z0-9\-]+)""")
                        val idRegexShort = Regex("""api\.nangdata\.xyz/([a-zA-Z0-9\-]+)""")
                        videoApiIdFromScript = idRegexV2.find(scriptData)?.groupValues?.getOrNull(1)
                            ?: idRegexShort.find(scriptData)?.groupValues?.getOrNull(1)
                        if (videoApiIdFromScript != null && videoApiIdFromScript != "v2") {
                            apiUrlFromScript = "https://api.nangdata.xyz/v2/$videoApiIdFromScript"
                        }
                    }
                }
                if (apiUrlFromScript != null) return@forEach // Thoát sớm nếu đã tìm thấy API URL
            }
            println("NangCucProvider LOG (load): apiUrlFromScript: $apiUrlFromScript")

            val recommendationsList = document.select("section.block_area-related div.flw-item").mapNotNull {
                // ... (logic lấy recommendations giữ nguyên) ...
                val filmPosterRec = it.selectFirst("div.film-poster")
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
                } else { null }
            }

            if (title == null || apiUrlFromScript == null) {
                println("NangCucProvider LOG: Lỗi load(): Không tìm thấy title hoặc apiUrlFromScript. Title: $title, ApiUrl: $apiUrlFromScript, Page URL: $url")
                return null
            }

            // Gọi hàm trích xuất phụ đề và subtitleCallback ngay tại đây
            // Lưu ý: subtitleCallback được truyền ngầm vào load thông qua MainAPI
            println("NangCucProvider LOG (load): Bắt đầu trích xuất phụ đề từ HTML đã tải.")
            extractAndCallbackSubtitles(document, મુખ્યSubtitleCallback) // ಮುಖ್ಯSubtitleCallback là callback của MainAPI

            val loadLinksData = LoadLinksHelperData(
                apiUrl = apiUrlFromScript!!,
                moviePageUrl = url // url gốc của trang phim, để loadLinks dùng làm referer
            )
            val dataForLoadLinks = mapper.writeValueAsString(loadLinksData)
            println("NangCucProvider LOG (load): dataForLoadLinks: $dataForLoadLinks")

            return newMovieLoadResponse(
                name = title,
                url = url,
                type = TvType.Movie,
                dataUrl = dataForLoadLinks
            ) {
                this.posterUrl = poster
                this.plot = plot
                this.tags = genres
                this.recommendations = recommendationsList
                // Không cần syncData cho phụ đề nữa
            }
        } catch (e: Exception) {
            e.printStackTrace()
            return null
        }
    }

    override suspend fun loadLinks(
        data: String, // Đây là JSON string của LoadLinksHelperData (chỉ chứa apiUrl và moviePageUrl)
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit, // subtitleCallback này sẽ không được dùng ở đây nữa
                                                // vì `load` đã gọi callback của MainAPI
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        try {
            println("NangCucProvider LOG (loadLinks): Nhận được data string: $data")
            val helperData = parseJson<LoadLinksHelperData>(data)
            val apiUrl = helperData.apiUrl
            val moviePageUrl = helperData.moviePageUrl // Dùng cho referer

            println("NangCucProvider LOG (loadLinks): apiUrl: $apiUrl")
            println("NangCucProvider LOG (loadLinks): moviePageUrl (cho referer): $moviePageUrl")

            // Bước 1: Lấy link video từ API
            val apiResponseJson = app.get(apiUrl).text
            val apiData = parseJson<VideoApiResponse>(apiResponseJson)
            var foundVideoLinks = false

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
                            if (videoUrl.contains(".m3u8", ignoreCase = true)) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                        } else { ExtractorLinkType.VIDEO }
                        
                        callback.invoke(
                            ExtractorLink(
                                source = serverName, name = serverName, url = videoUrl,
                                referer = moviePageUrl, // Sử dụng moviePageUrl đã lấy từ helperData
                                quality = quality, type = linkType,
                                headers = mapOf(), extractorData = null
                            )
                        )
                        foundVideoLinks = true
                    }
                }
            } else {
                println("NangCucProvider LOG (loadLinks): API video trả về status không thành công: ${apiData.status} cho URL: $apiUrl")
            }

            // Bước 2: Lấy phụ đề ĐÃ ĐƯỢC CHUYỂN SANG HÀM LOAD()
            // Không cần fetch lại trang phim ở đây nữa.
            println("NangCucProvider LOG (loadLinks): Phụ đề đã được xử lý trong hàm load().")

            return foundVideoLinks
        } catch (e: Exception) {
            println("NangCucProvider LOG (loadLinks): Lỗi nghiêm trọng: ${e.message}")
            e.printStackTrace()
            return false
        }
    }
}

// import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
// import com.lagradost.cloudstream3.plugins.Plugin

// @CloudstreamPlugin
// class NangCucLoader : Plugin() {
//    override fun load(context: Context) {
//        registerMainAPI(NangCucProvider())
//    }
// }
