package recloudstream

import android.content.Context
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Element
import com.lagradost.cloudstream3.utils.AppUtils.toJson
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import com.lagradost.cloudstream3.AcraApplication
import com.lagradost.cloudstream3.utils.DataStore
import okhttp3.Interceptor
import okhttp3.Response
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MultipartBody
import java.security.MessageDigest
import java.net.URI
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope

// Sửa đổi: LinkData chứa cả 2 server
data class LinkData(
    val server1Url: String,
    val server2Url: String?, // Có thể null nếu chỉ có 1 server
    val title: String,
    val year: Int?,
    val season: Int? = null,
    val episode: Int? = null
)

// Xác định lớp provider chính
class BluPhimProvider : MainAPI() {
    override var mainUrl = "https://bluphim.uk.com"
    override var name = "BluPhim"
    override val hasMainPage = true
    override var lang = "vi"
    override val hasDownloadSupport = false
    
    // =========================================================================
    // THAY ĐỔI 1: Thêm TvType.Anime để hỗ trợ phim hoạt hình
    // =========================================================================
    override val supportedTypes = setOf(
        TvType.Movie,
        TvType.TvSeries,
        TvType.Anime 
    )

    override fun getVideoInterceptor(extractorLink: ExtractorLink): Interceptor? {
        return object : Interceptor {
            override fun intercept(chain: Interceptor.Chain): Response {
                val request = chain.request()
                val response = chain.proceed(request)
                if (response.code == 200 && response.body != null) {
                    val url = request.url.toString()
                    if (url.contains(".m3u8")) {
                        val body = response.body!!.string()
                        val newBody = body.replace("https://bluphim.uk.com", mainUrl)
                        return response.newBuilder().body(
                            okhttp3.ResponseBody.create(
                                response.body!!.contentType(),
                                newBody
                            )
                        ).build()
                    }
                }
                return response
            }
        }
    }

    override val mainPage = mainPageOf(
        "phim-hot" to "Phim Hot",
        "/the-loai/phim-moi-" to "Phim Mới",
        "/the-loai/phim-cap-nhat-" to "Phim Cập Nhật",
        "/the-loai/phim-bo-" to "Phim Bộ",
        "/the-loai/phim-le-" to "Phim Lẻ",
        "/the-loai/phim-chieu-rap-" to "Phim Chiếu Rạp"
    )

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {
        if (request.data == "phim-hot") {
            if (page > 1) return newHomePageResponse(request.name, emptyList(), false)
            val document = app.get(mainUrl).document
            val movies = document.select("div.list-films.film-hot ul#film_hot li.item").mapNotNull {
                it.toSearchResult()
            }
            return newHomePageResponse(request.name, movies, false)
        }

        val url = mainUrl + request.data + page
        val document = app.get(url).document
        val movies = document.select("div.list-films.film-new div.item").mapNotNull {
            it.toSearchResult()
        }
        
        return newHomePageResponse(request.name, movies, movies.isNotEmpty())
    }

    private fun Element.toSearchResult(): MovieSearchResponse? {
        val href = this.selectFirst("a")?.attr("href") ?: return null
        val title = this.selectFirst("div.text span.title a")?.text()?.trim()
            ?: this.selectFirst("div.name")?.text()?.trim()
            ?: this.attr("title").trim().takeIf { it.isNotEmpty() }
            ?: return null
        val posterUrl = this.selectFirst("img")?.attr("src")
        
        val fullHref = if (href.startsWith("http")) href else "$mainUrl$href"
        val fullPosterUrl = posterUrl?.let { if (it.startsWith("http")) it else "$mainUrl$it" }

        return newMovieSearchResponse(title, fullHref) {
            this.posterUrl = fullPosterUrl
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val url = "$mainUrl/search?k=$query"
        val document = app.get(url).document
        return document.select("div.list-films.film-new li.item").mapNotNull {
            it.toSearchResult()
        }
    }

    override suspend fun load(url: String): LoadResponse? {
        val document = app.get(url).document
        val title = document.selectFirst("div.text h1 span.title")?.text()?.trim() ?: return null
        val poster = document.selectFirst("div.poster img")?.let {
            val src = it.attr("src")
            if (src.startsWith("http")) src else "$mainUrl$src"
        }
        val year = document.select("div.dinfo dl.col dt:contains(Năm sản xuất) + dd")
            .text().toIntOrNull()
        val description = document.selectFirst("div.detail div.tab")?.text()?.trim()
        val ratingString = document.select("div.dinfo dl.col dt:contains(Điểm IMDb) + dd a")
            .text().trim()
        val score = runCatching { Score.from(ratingString, 10) }.getOrNull()
        val genres = document.select("dd.theloaidd a").map { it.text() }
        val recommendations = document.select("div.list-films.film-hot ul#film_related li.item").mapNotNull {
            it.toSearchResult()
        }

        // =========================================================================
        // THAY ĐỔI 2: Cập nhật logic để xác định loại phim (Anime/TVSeries/Movie)
        // và cấu trúc (phim lẻ hay phim bộ) một cách chính xác.
        // =========================================================================

        // Xác định xem có phải là phim bộ hay không
        val isSeries = document.select("dd.theloaidd a:contains(TV Series - Phim bộ)").isNotEmpty()

        // Xác định loại nội dung: Ưu tiên Anime nếu có thể loại "Hoạt hình"
        val tvType = if (genres.any { it.equals("Hoạt hình", ignoreCase = true) }) {
            TvType.Anime
        } else if (isSeries) {
            TvType.TvSeries
        } else {
            TvType.Movie
        }
        
        val watchUrl = document.selectFirst("a.btn-see.btn-stream-link")?.attr("href")?.let {
            if (it.startsWith("http")) it else "$mainUrl$it"
        } ?: url

        // Dùng `isSeries` để quyết định tạo response cho phim bộ hay phim lẻ
        return if (isSeries) {
            val episodes = getEpisodes(watchUrl, title, year)
            newTvSeriesLoadResponse(title, url, tvType, episodes) {
                this.posterUrl = poster
                this.year = year
                this.plot = description
                this.score = score
                this.tags = genres
                this.recommendations = recommendations
            }
        } else {
            val server2Url = app.get(watchUrl).document.selectFirst("a[href*=?sv2=true]")?.attr("href")?.let {
                if (it.startsWith("http")) it else mainUrl + it
            }
            val linkData = LinkData(server1Url = watchUrl, server2Url = server2Url, title = title, year = year).toJson()
            newMovieLoadResponse(title, url, tvType, linkData) {
                this.posterUrl = poster
                this.year = year
                this.plot = description
                this.score = score
                this.tags = genres
                this.recommendations = recommendations
            }
        }
    }

    private suspend fun getEpisodes(watchUrl: String, title: String, year: Int?): List<Episode> {
        val document = app.get(watchUrl).document
        val episodeList = ArrayList<Episode>()
        document.select("div.list-episode a:not([href*=?sv2=true])").forEach { element ->
            val href = element.attr("href")
            val name = element.text().trim()
            val epNum = name.filter { it.isDigit() }.toIntOrNull()

            if (href.isNotEmpty() && !name.contains("Server", ignoreCase = true)) {
                val server1Url = if (href.startsWith("http")) href else "$mainUrl$href"
                val server2Url = element.nextElementSibling()?.let { next ->
                    if (next.attr("href").contains("?sv2=true")) {
                        if (next.attr("href").startsWith("http")) next.attr("href") else mainUrl + next.attr("href")
                    } else null
                } ?: "$server1Url?sv2=true" // Dự phòng

                val linkData = LinkData(server1Url, server2Url, title, null, epNum).toJson()
                episodeList.add(newEpisode(linkData) {
                    this.name = name
                    this.episode = epNum
                })
            }
        }
        return episodeList
    }

    private fun String.md5(): String {
        val bytes = MessageDigest.getInstance("MD5").digest(this.toByteArray())
        return bytes.joinToString("") { "%02x".format(it) }
    }
    
    private fun fixUrl(url: String, baseUrl: String): String {
        if (url.startsWith("http")) return url
        if (url.startsWith("//")) return "https:$url"
        try {
            return URI(baseUrl).resolve(url).toString()
        } catch (e: Exception) {
            return "$mainUrl$url"
        }
    }

    override suspend fun loadLinks(
    data: String,
    isCasting: Boolean,
    subtitleCallback: (SubtitleFile) -> Unit,
    callback: (ExtractorLink) -> Unit
): Boolean = coroutineScope {
    val linkData = parseJson<LinkData>(data)

    // Chạy song song cả hai server
    async {
        try {
            // SERVER GỐC
            // Bước 1: Lấy thông tin từ iframe đầu tiên để tạo link video
            val document = app.get(linkData.server1Url).document
            val iframeStreamSrc = fixUrl(document.selectFirst("iframe#iframeStream")?.attr("src") ?: return@async, linkData.server1Url)
            val iframeStreamDoc = app.get(iframeStreamSrc, referer = linkData.server1Url).document
            val script = iframeStreamDoc.select("script").find { it.data().contains("var videoId =") }?.data()

            if (script != null) {
                val videoId = script.substringAfter("var videoId = '").substringBefore("'")
                val cdn = script.substringAfter("var cdn = '").substringBefore("'")
                val domain = script.substringAfter("var domain = '").substringBefore("'")

                val context = AcraApplication.context!!
                val prefs = context.getSharedPreferences("bluphim_prefs", Context.MODE_PRIVATE)
                var token = prefs.getString("bluphim_token", null)
                if (token == null) {
                    token = "r" + System.currentTimeMillis()
                    prefs.edit().putString("bluphim_token", token).apply()
                }
                
                val requestBody = MultipartBody.Builder()
                    .setType(MultipartBody.FORM)
                    .addFormDataPart("renderer", "ANGLE (ARM, Mali-G78, OpenGL ES 3.2)")
                    .addFormDataPart("id", token.md5())
                    .addFormDataPart("videoId", videoId)
                    .addFormDataPart("domain", domain)
                    .build()

                // Lấy tất cả các token từ server
                val tokenString = app.post(url = "${getBaseUrl(iframeStreamSrc)}/geturl", requestBody = requestBody, referer = iframeStreamSrc, headers = mapOf("X-Requested-With" to "XMLHttpRequest")).text
                val tokens = tokenString.split("&").associate { val (key, value) = it.split("="); key to value }
                
                // Tạo và gửi link video (M3U8)
                val finalCdn = cdn.replace("cdn3.", "cdn.")
                val finalUrl = "$finalCdn/segment/$videoId/?token1=${tokens["token1"]}&token3=${tokens["token3"]}"
                callback.invoke(ExtractorLink(name, "Server Gốc", finalUrl, "$cdn/", Qualities.P1080.value, type = ExtractorLinkType.M3U8))

                // =========================================================================
                // PHẦN SỬA LỖI: Lấy phụ đề từ iframe thứ hai với Regex chính xác
                // =========================================================================
                try {
                    // Xây dựng URL cho iframe thứ 2 (trang chứa JWPlayer và tracks)
                    val token2 = tokens["token2"] ?: "" // Lấy token2 nếu có
                    val iframe2Url = "$cdn/streaming?id=$videoId&web=$mainUrl&token1=${tokens["token1"]}&token2=$token2&token3=${tokens["token3"]}&cdn=$cdn&lang=vi"
                    
                    // Tải nội dung iframe thứ hai
                    val iframe2Doc = app.get(iframe2Url, referer = iframeStreamSrc).document
                    // Chọn thẻ script chứa trình phát video
                    val setupScript = iframe2Doc.selectFirst("script:containsData(myvideo.setup)")?.data()

                    if (setupScript != null) {
                        // Trích xuất chuỗi JSON của mảng 'tracks' một cách an toàn
                        val tracksJson = setupScript.substringAfter("tracks: ", "null")
                                                    .substringBefore("],")
                                                    .trim()
                        
                        if (tracksJson.startsWith("[")) {
                            val fullTracksJson = "$tracksJson]" // Gắn lại dấu ngoặc vuông bị mất

                            // Dùng Regex đã sửa lỗi để tìm tất cả các file phụ đề
                            // Regex này tìm chính xác các cặp key:value "file" và "label"
                            val subsRegex = Regex("""\{"file":"([^"]+)",[^}]+"label":"([^"]+)"[^}]+}""")
                            val matches = subsRegex.findAll(fullTracksJson)
                            
                            matches.forEach { match ->
                                // Dùng `destructured` để lấy kết quả từ group của Regex một cách an toàn
                                val (subUrl, subLabel) = match.destructured
                                subtitleCallback.invoke(SubtitleFile(subLabel, subUrl.replace("\\/", "/")))
                            }
                        }
                    }
                } catch (e: Exception) {
                    // Lỗi ở bước lấy sub sẽ không làm ảnh hưởng đến việc phát video
                    e.printStackTrace()
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    async {
        // SERVER BÊN THỨ 3 (giữ nguyên)
        if (linkData.server2Url != null) {
            try {
                val document = app.get(linkData.server2Url).document
                val iframeStreamSrc = fixUrl(document.selectFirst("iframe#iframeStream")?.attr("src") ?: return@async, linkData.server2Url)
                val iframeStreamDoc = app.get(iframeStreamSrc, referer = linkData.server2Url).document
                val iframeEmbedSrc = fixUrl(iframeStreamDoc.selectFirst("iframe#embedIframe")?.attr("src") ?: return@async, iframeStreamSrc)
                val oPhimScript = app.get(iframeEmbedSrc, referer = iframeStreamSrc).document.select("script").find { it.data().contains("var url = '") }?.data()
                
                if (oPhimScript != null) {
                    val url = oPhimScript.substringAfter("var url = '").substringBefore("'")
                    val sourceName = if (url.contains("opstream")) "Ổ Phim" else "KKPhim"
                    callback.invoke(ExtractorLink(name, "Server bên thứ 3 - $sourceName", url, iframeEmbedSrc, Qualities.Unknown.value, type = ExtractorLinkType.M3U8))
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }
    
    return@coroutineScope true
}


    private fun getBaseUrl(url: String): String {
        return try {
            URI(url).let { "${it.scheme}://${it.host}" }
        } catch (e: Exception) {
            url.substringBefore("/video/")
        }
    }

    data class VideoResponse(
        val status: String,
        val url: String
    )
}
