package com.example.nangcucprovider // Hoặc package của bạn

import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.TvType // Sẽ thay đổi cách sử dụng TvType
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.MainPageRequest
import com.lagradost.cloudstream3.HomePageList
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.HomePageResponse
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.newMovieSearchResponse // Đã đổi tên thành newSearchResponse trong các bản CS3 mới hơn, nhưng newMovieSearchResponse vẫn có thể dùng cho TvType.NSFW
import com.lagradost.cloudstream3.newHomePageResponse
import com.lagradost.cloudstream3.newMovieLoadResponse // Tương tự, newLoadResponse có thể là tên mới hơn
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.utils.JsUnpacker

import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.mapper
import com.lagradost.cloudstream3.utils.AppUtils.parseJson

import org.jsoup.nodes.Document
// org.jsoup.Jsoup không cần import trực tiếp nếu chỉ dùng app.get().document

// Data class để truyền thông tin cần thiết cho loadLinks
data class LoadLinksHelperDataNangCuc( // Đổi tên để tránh trùng lặp nếu có class tương tự ở nơi khác
    val apiUrl: String,
    val moviePageUrl: String
)

// Data classes cho API response
data class VideoApiResponseNangCuc( // Đổi tên
    @JsonProperty("status") val status: Int?,
    @JsonProperty("links") val links: List<VideoLinkNangCuc>?,
    @JsonProperty("cache_header") val cacheHeader: String?
)

data class VideoLinkNangCuc( // Đổi tên
    @JsonProperty("name") val name: String?,
    @JsonProperty("link") val link: String?,
    @JsonProperty("iframe") val iframe: Boolean? = null
)

class NangCucProvider : MainAPI() {
    override var mainUrl = "https://nangcuc.cc"
    override var name = "Nắng Cực TV"
    override val supportedTypes = setOf(TvType.NSFW) // ĐỔI TvType THÀNH NSFW
    override var lang = "vi"
    override val hasMainPage = true

    private fun extractSubtitlesFromHtmlAndCallback(document: Document, subtitleCallback: (SubtitleFile) -> Unit) {
        var subtitlesCalledCount = 0
        val allExtractedSubtitles = mutableListOf<SubtitleFile>()

        document.select("script").forEachIndexed { _, script ->
            var scriptData = script.data()
            if (scriptData.startsWith("eval(function(p,a,c,k,e,d)")) {
                try {
                    scriptData = JsUnpacker(scriptData).unpack() ?: scriptData
                } catch (e: Exception) { /* Bỏ qua lỗi unpack */ }
            }

            if (scriptData.contains("subtitles", ignoreCase = true)) {
                val arrayContentMatch = Regex("""["']?subtitles["']?\s*[:=]\s*(\[.*?\])""", RegexOption.DOT_MATCHES_ALL).find(scriptData)
                if (arrayContentMatch != null) {
                    val arrayContent = arrayContentMatch.groupValues[1]
                    val subObjectRegex = Regex("""\{\s*(?:["']?hl["']?\s*:\s*["']([^"']+)["']|["']?label["']?\s*:\s*["']([^"']+)["'])\s*,\s*(?:["']?url["']?\s*:\s*["']([^"']+\.(?:srt|vtt))["'])\s*.*?\}""", RegexOption.IGNORE_CASE)
                    val scriptSubtitles = subObjectRegex.findAll(arrayContent).mapNotNull { objMatchResult ->
                        val langFromHl = objMatchResult.groupValues.getOrNull(1)?.trim()
                        val langFromLabel = objMatchResult.groupValues.getOrNull(2)?.trim()
                        val extractedLang = langFromHl?.takeIf { it.isNotEmpty() } ?: langFromLabel?.takeIf { it.isNotEmpty() }
                        val subUrlValue = objMatchResult.groupValues.getOrNull(3)?.trim()

                        if (extractedLang != null && subUrlValue != null && subUrlValue.startsWith("http")) {
                            val finalLangCode = if (extractedLang.equals("VI", ignoreCase = true)) "vi" else extractedLang.lowercase()
                            SubtitleFile(lang = finalLangCode, url = subUrlValue) // Bỏ `name` nếu SubtitleFile không hỗ trợ
                        } else { null }
                    }.toList()
                    if (scriptSubtitles.isNotEmpty()) {
                        allExtractedSubtitles.addAll(scriptSubtitles)
                    }
                }
            }
        }
        allExtractedSubtitles.distinctBy { it.url }.forEach { subtitle ->
            subtitleCallback(subtitle)
            subtitlesCalledCount++
        }
        // Log cuối cùng có thể giữ lại nếu muốn theo dõi số lượng phụ đề được gọi
        if (subtitlesCalledCount > 0 || allExtractedSubtitles.isNotEmpty()) {
            println("NangCucProvider: Called subtitleCallback $subtitlesCalledCount times.")
        }
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse? {
        val url = if (page > 1) "$mainUrl/moi-nhat/$page" else "$mainUrl/moi-nhat/"
        try {
            val document = app.get(url).document
            val lists = mutableListOf<HomePageList>()
            val newestSection = document.selectFirst("section.block_area_home.section-id-02")
                ?: document.selectFirst(".block_area-content.block_area-list.film_list.film_list-grid")

            newestSection?.let { section ->
                val sectionTitle = section.selectFirst("h1.cat-heading")?.text() ?: "Phim Mới Cập Nhật"
                val movies = section.select("div.flw-item").mapNotNull { item ->
                    val filmPoster = item.selectFirst("div.film-poster")
                    val movieName = filmPoster?.selectFirst("img.film-poster-img")?.attr("title")
                    val movieHref = filmPoster?.selectFirst("a.film-poster-ahref")?.attr("href")
                    var moviePosterUrl = filmPoster?.selectFirst("img.film-poster-img")?.attr("src")
                    if (moviePosterUrl == "/images/1px.gif" || moviePosterUrl?.startsWith("data:image") == true) {
                        moviePosterUrl = filmPoster?.selectFirst("img.film-poster-img")?.attr("data-src")
                    }
                    if (movieName.isNullOrBlank() || movieHref.isNullOrBlank() || moviePosterUrl.isNullOrBlank()) return@mapNotNull null
                    
                    newMovieSearchResponse( // Sử dụng newMovieSearchResponse cho TvType.NSFW vẫn được
                        name = movieName,
                        url = if (movieHref.startsWith("http")) movieHref else mainUrl + movieHref
                    ) {
                        this.posterUrl = moviePosterUrl
                        this.type = TvType.NSFW // ĐỔI TvType THÀNH NSFW
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
            return document.select("div.block_area-content div.flw-item").mapNotNull { item ->
                val filmPoster = item.selectFirst("div.film-poster")
                val movieName = filmPoster?.selectFirst("img.film-poster-img")?.attr("title")
                val movieHref = filmPoster?.selectFirst("a.film-poster-ahref")?.attr("href")
                var moviePosterUrl = filmPoster?.selectFirst("img.film-poster-img")?.attr("src")
                if (moviePosterUrl == "/images/1px.gif" || moviePosterUrl?.startsWith("data:image") == true) {
                    moviePosterUrl = filmPoster?.selectFirst("img.film-poster-img")?.attr("data-src")
                }
                if (movieName.isNullOrBlank() || movieHref.isNullOrBlank() || moviePosterUrl.isNullOrBlank()) return@mapNotNull null

                newMovieSearchResponse(
                    name = movieName,
                    url = if (movieHref.startsWith("http")) movieHref else mainUrl + movieHref
                ) {
                    this.posterUrl = moviePosterUrl
                    this.type = TvType.NSFW // ĐỔI TvType THÀNH NSFW
                }
            }.ifEmpty { null }
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
            var apiUrlFromScript: String? = null

            document.select("script").find { script ->
                val scriptData = script.data()
                if (scriptData.contains("api.nangdata.xyz")) {
                    val fullUrlRegex = Regex("""["'](https?://api\.nangdata\.xyz/v2/[a-zA-Z0-9\-]+)["']""")
                    apiUrlFromScript = fullUrlRegex.find(scriptData)?.groupValues?.getOrNull(1)
                    if (apiUrlFromScript == null) {
                        val idRegexV2 = Regex("""api\.nangdata\.xyz/v2/([a-zA-Z0-9\-]+)""")
                        val idRegexShort = Regex("""api\.nangdata\.xyz/([a-zA-Z0-9\-]+)""")
                        val videoApiIdFromScript = idRegexV2.find(scriptData)?.groupValues?.getOrNull(1)
                            ?: idRegexShort.find(scriptData)?.groupValues?.getOrNull(1)
                        if (videoApiIdFromScript != null && videoApiIdFromScript != "v2") {
                            apiUrlFromScript = "https://api.nangdata.xyz/v2/$videoApiIdFromScript"
                        }
                    }
                }
                apiUrlFromScript != null // Điều kiện dừng cho find
            }

            val recommendationsList = document.select("section.block_area-related div.flw-item").mapNotNull { item ->
                val filmPosterRec = item.selectFirst("div.film-poster")
                val nameRec = filmPosterRec?.selectFirst("img.film-poster-img")?.attr("title")
                val hrefRec = filmPosterRec?.selectFirst("a.film-poster-ahref")?.attr("href")
                var posterUrlRec = filmPosterRec?.selectFirst("img.film-poster-img")?.attr("data-src")
                if (posterUrlRec == "/images/1px.gif" || posterUrlRec?.startsWith("data:image") == true) {
                    posterUrlRec = filmPosterRec?.selectFirst("img.film-poster-img")?.attr("src")
                }
                if (nameRec.isNullOrBlank() || hrefRec.isNullOrBlank() || posterUrlRec.isNullOrBlank()) return@mapNotNull null
                
                newMovieSearchResponse(
                    name = nameRec,
                    url = if (hrefRec.startsWith("http")) hrefRec else mainUrl + hrefRec
                ) {
                    this.posterUrl = posterUrlRec
                    this.type = TvType.NSFW // ĐỔI TvType THÀNH NSFW
                }
            }

            if (title.isNullOrBlank() || apiUrlFromScript.isNullOrBlank()) {
                return null
            }

            val loadLinksData = LoadLinksHelperDataNangCuc(
                apiUrl = apiUrlFromScript!!,
                moviePageUrl = url
            )
            val dataForLoadLinks = mapper.writeValueAsString(loadLinksData)

            return newMovieLoadResponse( // Sử dụng newMovieLoadResponse cho TvType.NSFW vẫn được
                name = title,
                url = url,
                type = TvType.NSFW, // ĐỔI TvType THÀNH NSFW
                dataUrl = dataForLoadLinks
            ) {
                this.posterUrl = poster
                this.plot = plot
                this.tags = genres
                this.recommendations = recommendationsList
            }
        } catch (e: Exception) {
            e.printStackTrace()
            return null
        }
    }

    override suspend fun loadLinks(
        data: String, // JSON string của LoadLinksHelperDataNangCuc
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        try {
            val helperData = parseJson<LoadLinksHelperDataNangCuc>(data)
            val apiUrl = helperData.apiUrl
            val moviePageUrl = helperData.moviePageUrl

            // Bước 1 (Phụ): Lấy phụ đề từ HTML của trang phim (moviePageUrl)
            try {
                val moviePageDocument = app.get(moviePageUrl).document
                extractSubtitlesFromHtmlAndCallback(moviePageDocument, subtitleCallback)
            } catch (e: Exception) {
                // Không làm crash nếu lỗi lấy phụ đề, video vẫn có thể xem được
                 e.printStackTrace() // Vẫn log lỗi để biết
            }

            // Bước 2: Lấy link video từ API
            val apiResponseJson = app.get(apiUrl).text
            val apiData = parseJson<VideoApiResponseNangCuc>(apiResponseJson)
            var foundVideoLinks = false

            if (apiData.status == 200) {
                apiData.links?.forEach { videoLink ->
                    val videoUrl = videoLink.link
                    val serverName = videoLink.name ?: this.name // Mặc định là tên provider nếu server không có tên
                    val isIframe = videoLink.iframe ?: false

                    if (!videoUrl.isNullOrBlank()) {
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
                        } else if (isIframe && videoUrl.contains(".m3u8", ignoreCase = true) ) { // iframe nhưng link là m3u8
                            ExtractorLinkType.M3U8
                        } else if (isIframe) { // iframe nhưng link không rõ ràng là media trực tiếp
                            ExtractorLinkType.VIDEO // Hoặc có thể cần một ExtractorType nếu đây là link trang web
                        }
                        else { ExtractorLinkType.VIDEO }
                        
                        callback.invoke(
                            ExtractorLink(
                                source = serverName, name = serverName, url = videoUrl,
                                referer = moviePageUrl, quality = quality, type = linkType,
                                headers = mapOf(), extractorData = null
                            )
                        )
                        foundVideoLinks = true
                    }
                }
            }
            return foundVideoLinks
        } catch (e: Exception) {
            e.printStackTrace()
            return false
        }
    }
}

// import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
// import com.lagradost.cloudstream3.plugins.Plugin
// import android.content.Context

// @CloudstreamPlugin
// class NangCucLoader : Plugin() {
//    override fun load(context: Context) {
//        registerMainAPI(NangCucProvider())
//    }
// }
