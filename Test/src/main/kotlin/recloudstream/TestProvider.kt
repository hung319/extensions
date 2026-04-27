package recloudstream

import android.content.Context
import android.content.ClipboardManager
import android.content.ClipData
import android.widget.Toast
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Element
import com.lagradost.cloudstream3.utils.AppUtils.toJson
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import com.lagradost.cloudstream3.AcraApplication
import okhttp3.Interceptor
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okhttp3.MultipartBody
import java.security.MessageDigest
import java.net.URI
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class LinkData(
    val server1Url: String,
    val server2Url: String?,
    val title: String,
    val year: Int?,
    val season: Int? = null,
    val episode: Int? = null
)

class BluPhimProvider : MainAPI() {
    override var mainUrl = "https://bluphim5.com"
    override var name = "BluPhim"
    override val hasMainPage = true
    override var lang = "vi"
    override val hasDownloadSupport = true
    
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

                if (response.code == 200) {
                    val url = request.url.toString()
                    if (url.contains(".m3u8")) {
                        response.body?.let { body ->
                            val bodyString = body.string()
                            val newBody = bodyString.replace("https://bluphim.uk.com", mainUrl)
                            
                            return response.newBuilder().body(
                                newBody.toResponseBody(body.contentType())
                            ).build()
                        }
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
        withContext(Dispatchers.Main) {
            CommonActivity.activity?.let { activity ->
                CommonActivity.showToast(activity, "Free Repo From H4RS", Toast.LENGTH_LONG)
            }
        }
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

        val isSeries = document.select("dd.theloaidd a:contains(TV Series - Phim bộ)").isNotEmpty()

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
                } ?: "$server1Url?sv2=true"

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
        return try {
            URI(baseUrl).resolve(url).toString()
        } catch (e: Exception) {
            "$mainUrl$url"
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean = coroutineScope {
        val linkData = parseJson<LinkData>(data)

        // HÀM BẮT LỖI TỰ ĐỘNG VÀ LƯU VÀO CLIPBOARD
        suspend fun logErrorAndCopy(message: String) {
            withContext(Dispatchers.Main) {
                val appCtx = AcraApplication.context
                if (appCtx != null) {
                    val clipboard = appCtx.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                    val clip = android.content.ClipData.newPlainText("BluPhim Debug", message)
                    clipboard.setPrimaryClip(clip)
                    Toast.makeText(appCtx, "Đã bắt được lỗi! Hãy dán (Paste) log cho CodeSAS.", Toast.LENGTH_LONG).show()
                }
            }
        }

        // SERVER GỐC
        async {
            try {
                // 1. Đồng bộ 1 User-Agent duy nhất cho mọi request để vượt mặt hệ thống Anti-bot
                val commonUserAgent = "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/137.0.0.0 Mobile Safari/537.36"
                val baseHeaders = mapOf("User-Agent" to commonUserAgent)

                val document = app.get(linkData.server1Url, headers = baseHeaders).document
                val iframeSrc = document.selectFirst("iframe#iframeStream")?.attr("src")
                if (iframeSrc == null) {
                    logErrorAndCopy("LỖI 1: Không tìm thấy iframe#iframeStream tại URL: ${linkData.server1Url}")
                    return@async
                }
                
                val iframeStreamSrc = fixUrl(iframeSrc, linkData.server1Url)
                val iframeStreamDoc = app.get(iframeStreamSrc, referer = linkData.server1Url, headers = baseHeaders).document
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

                    val postUrl = "${getBaseUrl(iframeStreamSrc)}/geturl"
                    
                    // 2. Chèn Header (đặc biệt là User-Agent) vào lúc POST lấy Token
                    val tokenResponse = app.post(
                        url = postUrl, 
                        requestBody = requestBody, 
                        headers = mapOf(
                            "X-Requested-With" to "XMLHttpRequest",
                            "User-Agent" to commonUserAgent,
                            "Referer" to iframeStreamSrc
                        )
                    )
                    
                    val tokenString = tokenResponse.text
                    
                    // 3. Kiểm tra xem Server có chặn hoặc yêu cầu Captcha Cloudflare không
                    if (!tokenString.contains("token1=")) {
                        logErrorAndCopy("LỖI 2: Lấy Token thất bại.\nHTTP Code: ${tokenResponse.code}\nNội dung trả về: ${tokenString.take(500)}")
                        return@async
                    }

                    // 4. Parse token an toàn (Không bị văng Exception nếu chuỗi thiếu dấu '=')
                    val tokens = try {
                        tokenString.split("&").associate { 
                            val parts = it.split("=")
                            parts[0] to parts.getOrElse(1) { "" }
                        }
                    } catch (e: Exception) {
                        logErrorAndCopy("LỖI 3: Không thể bóc tách Token.\nTokenString: $tokenString")
                        return@async
                    }
                    
                    val finalCdn = cdn.replace(Regex("""cdn\d+\."""), "cdn.")
                    val finalUrl = "$finalCdn/segment/$videoId/?token1=${tokens["token1"]}&token3=${tokens["token3"]}"
                    
                    val headersMap = mapOf(
                        "Origin" to cdn,
                        "Accept" to "*/*",
                        "Accept-Language" to "vi-VN,vi;q=0.9",
                        "User-Agent" to commonUserAgent,
                        "Sec-Fetch-Mode" to "cors",
                        "Sec-Fetch-Site" to "same-site"
                    )

                    // 5. Test trực tiếp link (Mô phỏng ExoPlayer)
                    val testResponse = app.get(finalUrl, headers = headersMap)
                    if (testResponse.code != 200 || !testResponse.text.contains("#EXTM3U")) {
                        logErrorAndCopy("LỖI 4: Server từ chối M3U8 (Giống lỗi 2001).\nM3U8 URL: $finalUrl\nHTTP Code: ${testResponse.code}\nBody: ${testResponse.text.take(500)}")
                    }

                    // Trả link về cho Player
                    val extractorLink = newExtractorLink(
                        source = name,
                        name = "Server Gốc",
                        url = finalUrl,
                        type = ExtractorLinkType.M3U8
                    ) {
                        this.referer = "$cdn/"
                        this.quality = Qualities.P1080.value
                        this.headers = headersMap
                    }
                    callback.invoke(extractorLink)
                    
                    // LẤY PHỤ ĐỀ
                    try {
                        val token2 = tokens["token2"] ?: ""
                        val iframe2Url = "$cdn/streaming?id=$videoId&web=$mainUrl&token1=${tokens["token1"]}&token2=$token2&token3=${tokens["token3"]}&cdn=$cdn&lang=vi"
                        val iframe2Doc = app.get(iframe2Url, referer = iframeStreamSrc, headers = baseHeaders).document
                        val setupScript = iframe2Doc.selectFirst("script:containsData(myvideo.setup)")?.data()

                        if (setupScript != null) {
                            val tracksStartIndex = setupScript.indexOf("tracks:")
                            if (tracksStartIndex != -1) {
                                val arrayStartIndex = setupScript.indexOf('[', tracksStartIndex)
                                if (arrayStartIndex != -1) {
                                    var balance = 0
                                    var arrayEndIndex = -1
                                    for (i in arrayStartIndex until setupScript.length) {
                                        when (setupScript[i]) {
                                            '[' -> balance++
                                            ']' -> balance--
                                        }
                                        if (balance == 0) {
                                            arrayEndIndex = i
                                            break
                                        }
                                    }
                                    if (arrayEndIndex != -1) {
                                        val tracksJson = setupScript.substring(arrayStartIndex, arrayEndIndex + 1)
                                        try {
                                            val tracks = parseJson<List<Map<String, Any>>>(tracksJson)
                                            tracks.forEach { track ->
                                                val file = track["file"] as? String
                                                val label = track["label"] as? String
                                                if (file != null && label != null) {
                                                    subtitleCallback.invoke(newSubtitleFile(label, file))
                                                }
                                            }
                                        } catch (e: Exception) {}
                                    }
                                }
                            }
                        }
                    } catch (e: Exception) {}

                } else {
                    logErrorAndCopy("LỖI 5: Không tìm thấy VideoID trong Iframe.")
                }
            } catch (e: Exception) {
                // Bắt toàn bộ lỗi Crash Code
                logErrorAndCopy("CRASH MÃ NGUỒN:\n${e.stackTraceToString().take(800)}")
            }
        }

        // SERVER BÊN THỨ 3 (KKPhim/OPhim)
        async {
            if (linkData.server2Url != null) {
                try {
                    val document = app.get(linkData.server2Url).document
                    val iframeStreamSrc = fixUrl(document.selectFirst("iframe#iframeStream")?.attr("src") ?: return@async, linkData.server2Url)
                    val iframeStreamDoc = app.get(iframeStreamSrc, referer = linkData.server2Url).document
                    val iframeEmbedSrc = fixUrl(iframeStreamDoc.selectFirst("iframe#embedIframe")?.attr("src") ?: return@async, iframeStreamSrc)
                    
                    val oPhimScript = app.get(iframeEmbedSrc, referer = iframeStreamSrc).document.select("script").find { it.data().contains("var url = '") }?.data()
                    
                    if (oPhimScript != null) {
                        val masterM3u8Url = oPhimScript.substringAfter("var url = '").substringBefore("'")
                        val sourceName = if (masterM3u8Url.contains("opstream")) "Ổ Phim" else "KKPhim"
                        
                        val extractorLink = newExtractorLink(
                            source = name,
                            name = "Server bên thứ 3 - $sourceName",
                            url = masterM3u8Url,
                            type = ExtractorLinkType.M3U8
                        ) {
                            this.referer = iframeEmbedSrc
                            this.quality = Qualities.Unknown.value
                        }
                        callback.invoke(extractorLink)
                    }
                } catch (e: Exception) {}
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
}
