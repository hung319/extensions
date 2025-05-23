package com.example.nangcucprovider

// ... (Các import khác giữ nguyên như file đầy đủ trước đó) ...
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
import com.lagradost.cloudstream3.utils.JsUnpacker // Nếu cần unpack script JS

import org.jsoup.Jsoup
import org.jsoup.nodes.Document


// Data class để truyền thông tin cần thiết cho loadLinks
data class LoadLinksHelperData(
    val apiUrl: String,
    val moviePageUrl: String
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

// Data class tạm thời để parse các object phụ đề từ script (gần giống JSON)
data class RawSubtitleObject(
    @JsonProperty("hl") val hl: String? = null,
    @JsonProperty("url") val url: String? = null
    // Thêm các trường khác nếu có, ví dụ: label, kind
)

class NangCucProvider : MainAPI() {
    override var mainUrl = "https://nangcuc.cc"
    override var name = "Nắng Cực TV"
    override val supportedTypes = setOf(TvType.Movie)
    override var lang = "vi"
    override val hasMainPage = true

    // ... (getMainPage, search giữ nguyên) ...

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

    override suspend fun load(url: String): LoadResponse? {
        try {
            val document = app.get(url).document
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
                if (apiUrlFromScript != null) return@forEach
            }

            val recommendationsList = document.select("section.block_area-related div.flw-item").mapNotNull { item ->
                // ... (logic lấy recommendations giữ nguyên) ...
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

            val loadLinksData = LoadLinksHelperData(
                apiUrl = apiUrlFromScript!!,
                moviePageUrl = url
            )
            val dataForLoadLinks = mapper.writeValueAsString(loadLinksData)
            println("NangCucProvider LOG: dataForLoadLinks (JSON cho loadLinks): $dataForLoadLinks")

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
            }
        } catch (e: Exception) {
            e.printStackTrace()
            return null
        }
    }

    // Hàm trích xuất và parse phụ đề, có thể tái sử dụng
    private fun extractSubtitlesFromHtml(document: Document, subtitleCallback: (SubtitleFile) -> Unit) {
        var subtitlesCalledCount = 0
        var scriptContainingSubtitlesFound = false
        val allExtractedSubtitles = mutableListOf<SubtitleFile>()

        document.select("script").forEachIndexed { index, script ->
            var scriptData = script.data()

            // Một số trang có thể dùng P,A,C,K,E,D (Dean Edwards Packer)
            if (scriptData.startsWith("eval(function(p,a,c,k,e,d)")) {
                try {
                    scriptData = JsUnpacker(scriptData).unpack() ?: scriptData
                    println("NangCucProvider LOG: Script #${index} đã được unpack.")
                } catch (e: Exception) {
                    println("NangCucProvider LOG: Lỗi khi unpack script #${index}: $e")
                }
            }

            if (scriptData.contains("subtitles", ignoreCase = true)) { // Tìm không phân biệt hoa thường
                scriptContainingSubtitlesFound = true
                println("NangCucProvider LOG: Script #${index} có chứa 'subtitles'. Dữ liệu (một phần): ${scriptData.substring(0, minOf(scriptData.length, 300))}...")

                // Regex để trích xuất toàn bộ mảng [...] sau "subtitles=" hoặc "subtitles =" hoặc "subtitles:"
                // \s* cho phép khoảng trắng, (?:...) là non-capturing group
                // (\[\s*\{.*?\}\s*(?:,\s*\{.*?\}\s*)*\s*\]) : Bắt mảng các object JSON
                val arrayContentMatch = Regex("""["']?subtitles["']?\s*[:=]\s*(\[.*?\])""", RegexOption.DOT_MATCHES_ALL).find(scriptData)

                if (arrayContentMatch != null) {
                    val arrayContent = arrayContentMatch.groupValues[1]
                    println("NangCucProvider LOG: Nội dung array phụ đề trích xuất được: $arrayContent")

                    // Regex để trích xuất từng object {hl:"...", url:"..."} bên trong arrayContent
                    // Cho phép key có hoặc không có dấu ngoặc kép
                    val subObjectRegex = Regex("""\{\s*(?:["']?hl["']?\s*:\s*["']([^"']+)["']|["']?label["']?\s*:\s*["']([^"']+)["'])\s*,\s*(?:["']?url["']?\s*:\s*["']([^"']+\.(?:srt|vtt))["'])\s*.*?\}""", RegexOption.IGNORE_CASE)
                    
                    val scriptSubtitles = subObjectRegex.findAll(arrayContent).mapNotNull { objMatchResult ->
                        // hl hoặc label cho ngôn ngữ, url cho link
                        val langFromHl = objMatchResult.groupValues.getOrNull(1)?.trim()
                        val langFromLabel = objMatchResult.groupValues.getOrNull(2)?.trim()
                        val lang = langFromHl?.takeIf { it.isNotEmpty() } ?: langFromLabel?.takeIf { it.isNotEmpty() }

                        val subUrlValue = objMatchResult.groupValues.getOrNull(3)?.trim()

                        println("NangCucProvider LOG: Regex object phụ đề: lang='${lang}', url='${subUrlValue}'")
                        if (lang != null && subUrlValue != null && subUrlValue.startsWith("http")) {
                            SubtitleFile(lang, subUrlValue)
                        } else {
                            if (subUrlValue != null && !subUrlValue.startsWith("http")) {
                                println("NangCucProvider LOG: URL phụ đề không hợp lệ (không phải http/https): $subUrlValue")
                            }
                            null
                        }
                    }.toList()
                    
                    if (scriptSubtitles.isNotEmpty()) {
                        println("NangCucProvider LOG: Phụ đề trích xuất từ script #${index}: $scriptSubtitles")
                        allExtractedSubtitles.addAll(scriptSubtitles)
                    }
                } else {
                    println("NangCucProvider LOG: Không tìm thấy nội dung array ([...]) sau 'subtitles=' trong script #${index}.")
                }
            }
        }

        if (!scriptContainingSubtitlesFound) {
            println("NangCucProvider LOG: Không tìm thấy script nào chứa 'subtitles='.")
        }

        // Loại bỏ phụ đề trùng lặp và gọi callback
        allExtractedSubtitles.distinctBy { it.url }.forEach { subtitle ->
            println("NangCucProvider LOG: Gọi subtitleCallback cho: ${subtitle.lang} - ${subtitle.url}")
            subtitleCallback(subtitle)
            subtitlesCalledCount++
        }
        println("NangCucProvider LOG: Kết thúc quét script trang phim. Đã gọi subtitleCallback $subtitlesCalledCount lần (sau khi loại bỏ trùng lặp).")
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
            val moviePageUrl = helperData.moviePageUrl

            println("NangCucProvider LOG: loadLinks - apiUrl: $apiUrl")
            println("NangCucProvider LOG: loadLinks - moviePageUrl (cho phụ đề & referer): $moviePageUrl")

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
                                referer = moviePageUrl, quality = quality, type = linkType,
                                headers = mapOf(), extractorData = null
                            )
                        )
                        foundVideoLinks = true
                    }
                }
            } else {
                println("NangCucProvider LOG: API video trả về status không thành công: ${apiData.status} cho URL: $apiUrl")
            }

            // Bước 2: Lấy phụ đề từ HTML của trang phim (moviePageUrl)
            println("NangCucProvider LOG: --- Bắt đầu quá trình lấy phụ đề từ trang phim (trong loadLinks) ---")
            try {
                val moviePageDocument = app.get(moviePageUrl).document
                println("NangCucProvider LOG: Đã fetch xong HTML trang phim cho phụ đề. Độ dài HTML: ${moviePageDocument.html().length}")
                extractSubtitlesFromHtml(moviePageDocument, subtitleCallback) // Gọi hàm riêng để xử lý
            } catch (e: Exception) {
                println("NangCucProvider LOG: Lỗi khi fetch/parse lại trang phim để lấy phụ đề: ${e.message}")
                e.printStackTrace()
            }
            println("NangCucProvider LOG: --- Kết thúc quá trình lấy phụ đề từ trang phim (trong loadLinks) ---")

            return foundVideoLinks
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
