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

import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.mapper
import com.lagradost.cloudstream3.utils.AppUtils.parseJson

import org.jsoup.Jsoup
import org.jsoup.nodes.Document // Import Jsoup Document

// Data class để truyền thông tin cần thiết cho loadLinks
data class LoadLinksHelperData(
    val apiUrl: String,
    val moviePageUrl: String // URL của trang phim để fetch lại lấy phụ đề
)

// Data classes cho API response (giữ nguyên từ JSON bạn cung cấp)
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

    override suspend fun load(url: String): LoadResponse? { // url ở đây là moviePageUrl
        try {
            val document = app.get(url).document // Tải HTML trang phim một lần ở đây
            val title = document.selectFirst("h1.video-title")?.text()
                ?: document.selectFirst("meta[property=\"og:title\"]")?.attr("content")
            val poster = document.selectFirst("meta[property=\"og:image\"]")?.attr("content")
            val plot = document.selectFirst("div.about span p")?.text()
                ?: document.selectFirst("meta[property=\"og:description\"]")?.attr("content")
            val genres = document.select("div.genres a")?.mapNotNull { it.text() }
            var videoApiIdFromScript: String? = null
            var apiUrlFromScript: String? = null

            // Chỉ lấy apiUrlFromScript từ đây, không lấy phụ đề nữa
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
                } else { null }
            }

            if (title == null || apiUrlFromScript == null) {
                println("NangCucProvider LOG: Lỗi load(): Không tìm thấy title hoặc apiUrlFromScript. Title: $title, ApiUrl: $apiUrlFromScript, Page URL: $url")
                return null
            }

            // Tạo payload chỉ chứa apiUrl và moviePageUrl
            val loadLinksData = LoadLinksHelperData(
                apiUrl = apiUrlFromScript!!,
                moviePageUrl = url // url gốc của trang phim
            )
            val dataForLoadLinks = mapper.writeValueAsString(loadLinksData)
            println("NangCucProvider LOG: dataForLoadLinks (JSON cho loadLinks): $dataForLoadLinks")


            return newMovieLoadResponse(
                name = title,
                url = url, // URL gốc của trang phim
                type = TvType.Movie,
                dataUrl = dataForLoadLinks // Truyền JSON string của LoadLinksHelperData (chỉ chứa apiUrl và moviePageUrl)
            ) {
                this.posterUrl = poster
                this.plot = plot
                this.tags = genres
                this.recommendations = recommendationsList
                // syncData không cần thiết để truyền phụ đề nữa
            }
        } catch (e: Exception) {
            e.printStackTrace()
            return null
        }
    }

    override suspend fun loadLinks(
        data: String, // Đây là JSON string của LoadLinksHelperData
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        try {
            println("NangCucProvider LOG: loadLinks nhận được data string: $data")
            val helperData = parseJson<LoadLinksHelperData>(data)
            val apiUrl = helperData.apiUrl
            val moviePageUrl = helperData.moviePageUrl // Sẽ dùng để fetch HTML lấy phụ đề

            println("NangCucProvider LOG: apiUrl từ helperData: $apiUrl")
            println("NangCucProvider LOG: moviePageUrl từ helperData: $moviePageUrl (dùng cho phụ đề và referer)")

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
                                source = serverName,
                                name = serverName,
                                url = videoUrl,
                                referer = moviePageUrl, // Dùng moviePageUrl làm referer
                                quality = quality,
                                type = linkType,
                                headers = mapOf(),
                                extractorData = null
                            )
                        )
                        foundVideoLinks = true
                    }
                }
            } else {
                println("NangCucProvider LOG: API video trả về status không thành công: ${apiData.status} cho URL: $apiUrl")
            }

            // Bước 2: Lấy phụ đề từ HTML của trang phim (moviePageUrl)
            try {
                println("NangCucProvider LOG: Bắt đầu fetch lại trang phim để lấy phụ đề từ: $moviePageUrl")
                val moviePageDocument = app.get(moviePageUrl).document // Fetch lại HTML trang phim
                var extractedSubtitles: List<SubtitleFile> = emptyList()
                var subtitlesCalledCount = 0

                moviePageDocument.select("script").forEach { script ->
                    val scriptData = script.data()
                    if (scriptData.contains("subtitles=")) {
                        println("NangCucProvider LOG: Tìm thấy 'subtitles=' trong script (lần 2). Bắt đầu regex.")
                        val subRegex = Regex("""subtitles=\s*\[\s*\{.*?["']hl["']\s*:\s*["']([^"']+)["']\s*,\s*.*?["']url["']\s*:\s*["']([^"']+\.(?:srt|vtt))["'].*?\}\s*\]""")
                        val allMatches = subRegex.findAll(scriptData).toList()
                        println("NangCucProvider LOG: Regex phụ đề (lần 2) tìm thấy ${allMatches.size} khớp.")

                        extractedSubtitles = allMatches.mapNotNull { matchResult ->
                            val lang: String? = matchResult.groupValues.getOrNull(1)
                            val subUrlValue: String? = matchResult.groupValues.getOrNull(2)
                            println("NangCucProvider LOG: Regex phụ đề (lần 2) group: lang='${lang}', url='${subUrlValue}'")
                            if (lang != null && subUrlValue != null) {
                                SubtitleFile(lang, subUrlValue)
                            } else { null }
                        }.toList()
                        
                        if (extractedSubtitles.isNotEmpty()) {
                             println("NangCucProvider LOG: Phụ đề trích xuất (lần 2): $extractedSubtitles")
                            // Gọi subtitleCallback ngay khi tìm thấy
                            extractedSubtitles.forEach { subtitle ->
                                println("NangCucProvider LOG: Gọi subtitleCallback cho (từ fetch lại): ${subtitle.lang} - ${subtitle.url}")
                                subtitleCallback(subtitle)
                                subtitlesCalledCount++
                            }
                            // Nếu tìm thấy phụ đề trong một script, có thể thoát sớm
                            // (trừ khi có nhiều khối subtitles= khác nhau trên trang)
                            // return@forEach 
                        }
                    }
                }
                 println("NangCucProvider LOG: Kết thúc quét script (lần 2). Đã gọi subtitleCallback $subtitlesCalledCount lần.")
            } catch (e: Exception) {
                println("NangCucProvider LOG: Lỗi khi fetch/parse lại trang phim để lấy phụ đề: ${e.message}")
                // Không làm crash nếu lỗi lấy phụ đề, video vẫn có thể xem được
            }

            return foundVideoLinks // Chỉ trả về true nếu tìm thấy link video
        } catch (e: Exception) {
            println("NangCucProvider LOG: Lỗi nghiêm trọng trong loadLinks: ${e.message}")
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
