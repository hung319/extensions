package recloudstream

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.AppUtils.app
import com.lagradost.cloudstream3.utils.OkhttpConfig
import android.util.Log
import com.google.gson.Gson
import okhttp3.Interceptor
import okhttp3.Response
import java.net.URL
import java.net.URLEncoder
import java.security.MessageDigest
import java.util.Base64
import java.util.zip.Inflater
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlinx.coroutines.runBlocking

class AnimeVietsubProvider : MainAPI() {

    private val gson = Gson()
    override var mainUrl = "https://bit.ly/animevietsubtv"
    override var name = "AnimeVietsub"
    override val supportedTypes = setOf(
        TvType.Anime,
        TvType.Cartoon,
        TvType.Movie
    )
    override var lang = "vi"
    override val hasMainPage = true
    private val USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/100.0.4896.127 Safari/537.36"

    private val bitlyResolverUrl = "https://bit.ly/animevietsubtv"
    private val ultimateFallbackDomain = "https://animevietsub.lol"
    private var currentActiveUrl = bitlyResolverUrl
    private var domainResolutionAttempted = false

    // Máy chủ proxy ảo cho các segment
    private val segmentProxyHost = "cf-proxy.local"
    // Dùng để lưu trữ tạm thời domain của các segment
    private var segmentHostUrl: String? = null
    
    // Đăng ký Interceptor để xử lý các request đến segment proxy
    override val overrideConfig: OkhttpConfig
        get() = OkhttpConfig(
            interceptors = listOf(SegmentProxyInterceptor())
        )

    // Interceptor sẽ bắt các request đến host ảo, và dùng app.get để tải link gốc
    private inner class SegmentProxyInterceptor : Interceptor {
        override fun intercept(chain: Interceptor.Chain): Response {
            val request = chain.request()
            val url = request.url

            // Bỏ qua nếu không phải là host proxy của chúng ta
            if (url.host != segmentProxyHost) {
                return chain.proceed(request)
            }
            
            // Lấy lại domain của segment đã lưu
            val host = segmentHostUrl ?: throw Exception("Segment Host not found")
            // Ghép lại URL gốc của segment
            val originalSegmentUrl = "$host${url.encodedPath}"
            
            Log.d(name, "Intercepting segment: $originalSegmentUrl")

            // Dùng app.get để tải segment, app.get sẽ tự động dùng Cloudflare Killer
            // Phải dùng runBlocking vì interceptor không phải là suspend function
            return runBlocking {
                app.get(originalSegmentUrl, headers = request.headers.toMap(), referer = mainUrl).res
            }
        }
    }


    // =================== LOGIC GIẢI MÃ ===================
    private val KEY_STRING_B64 = "ZG1fdGhhbmdfc3VjX3ZhdF9nZXRfbGlua19hbl9kYnQ="

    private val aesKey by lazy {
        try {
            val decodedKeyBytes = Base64.getDecoder().decode(KEY_STRING_B64)
            val sha256Hasher = MessageDigest.getInstance("SHA-256")
            val hashedKey = sha256Hasher.digest(decodedKeyBytes)
            SecretKeySpec(hashedKey, "AES")
        } catch (e: Exception) {
            Log.e(name, "Failed to calculate AES key", e)
            null
        }
    }

    private fun decryptM3u8Content(encryptedDataB64: String): String? {
        //... (Giữ nguyên hàm giải mã)
        val key = aesKey ?: return null
        try {
            val cleanedEncDataB64 = encryptedDataB64.replace(Regex("[^A-Za-z0-9+/=]"), "")
            val encryptedBytes = Base64.getDecoder().decode(cleanedEncDataB64)
            if (encryptedBytes.size < 16) throw Exception("Encrypted data is too short to contain IV.")
            val ivBytes = encryptedBytes.copyOfRange(0, 16)
            val ciphertextBytes = encryptedBytes.copyOfRange(16, encryptedBytes.size)
            val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
            cipher.init(Cipher.DECRYPT_MODE, key, IvParameterSpec(ivBytes))
            val decryptedBytesPadded = cipher.doFinal(ciphertextBytes)
            val inflater = Inflater(true)
            inflater.setInput(decryptedBytesPadded)
            val result = ByteArray(1024 * 1024)
            val decompressedSize = inflater.inflate(result)
            inflater.end()
            val decompressedBytes = result.copyOfRange(0, decompressedSize)
            return decompressedBytes.toString(Charsets.UTF_8)
                .trim().removeSurrounding("\"")
                .replace("\\n", "\n")
        } catch (error: Exception) {
            Log.e(name, "Decryption/Decompression failed: ${error.message}", error)
            return null
        }
    }

    // Ghi đè loadLinks để thực hiện việc viết lại M3U8
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val episodeData = gson.fromJson(data, EpisodeData::class.java)
        val episodeId = episodeData.dataId ?: throw ErrorLoadingException("Missing episode ID")
        val episodePageUrl = episodeData.url
        val baseUrl = getBaseUrl()

        val ajaxResponse = app.post(
            url = "$baseUrl/ajax/player?v=2019a",
            data = mapOf("id" to episodeId, "play" to "api").toMutableMap().apply {
                episodeData.duHash?.let { put("link", it) }
            },
            headers = mapOf(
                "Content-Type" to "application/x-www-form-urlencoded; charset=UTF-8",
                "Accept" to "application/json, text/javascript, */*; q=0.01",
                "X-Requested-With" to "XMLHttpRequest",
                "User-Agent" to USER_AGENT,
                "Referer" to episodePageUrl
            )
        ).text

        val playerResponse = gson.fromJson(ajaxResponse, AjaxPlayerResponse::class.java)

        if (playerResponse?.success != 1 || playerResponse.link.isNullOrEmpty()) {
            throw ErrorLoadingException("Failed to get links from AJAX response: $ajaxResponse")
        }

        playerResponse.link.forEach { linkSource ->
            val encryptedUrl = linkSource.file ?: return@forEach
            val decryptedM3u8 = decryptM3u8Content(encryptedUrl) ?: return@forEach

            // Tìm một URL segment mẫu để lấy host
            val firstSegmentLine = decryptedM3u8.lines().firstOrNull { it.trim().startsWith("https") }
            if (firstSegmentLine != null) {
                val url = URL(firstSegmentLine)
                // Lưu lại host để Interceptor sử dụng
                segmentHostUrl = "${url.protocol}://${url.host}"
            } else {
                return@forEach // Bỏ qua nếu không tìm thấy link segment nào
            }
            
            // Viết lại M3U8 để trỏ đến proxy nội bộ
            val rewrittenM3u8 = decryptedM3u8.lines().joinToString("\n") { line ->
                if (line.trim().startsWith("https")) {
                    val path = URL(line).path
                    "https://$segmentProxyHost$path" // Đổi domain thành host ảo
                } else {
                    line
                }
            }

            // Dùng Data URI để nhúng M3U8 đã viết lại vào URL
            val m3u8DataUri = "data:application/vnd.apple.mpegurl;charset=utf-8,${URLEncoder.encode(rewrittenM3u8, "UTF-8")}"

            newExtractorLink(
                source = name,
                name = name,
                url = m3u8DataUri,
                type = ExtractorLinkType.M3U8,
                referer = episodePageUrl
            ) {
                this.quality = Qualities.Unknown.value
            }.let { callback(it) }
        }

        return true
    }

    // Các hàm còn lại (getMainPage, search, load, v.v...) giữ nguyên như cũ
    // ...
    // (Bạn có thể sao chép phần còn lại của các hàm này từ phiên bản trước)
    // ...
     override val mainPage = mainPageOf(
        "/anime-moi/" to "Mới Cập Nhật",
        "/anime-sap-chieu/" to "Sắp Chiếu",
        "/bang-xep-hang/day.html" to "Xem Nhiều Trong Ngày"
    )
    
    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val baseUrl = getBaseUrl()
        val url = if (page == 1) {
            "$baseUrl${request.data}"
        } else {
            if (request.data.contains("bang-xep-hang")) {
                "$baseUrl${request.data}"
            } else {
                val slug = request.data.removeSuffix("/")
                "$baseUrl$slug/trang-$page.html"
            }
        }
        val document = app.get(url).document
        val home = when {
            request.data.contains("bang-xep-hang") -> {
                document.select("ul.bxh-movie-phimletv li.group").mapNotNull { element ->
                    try {
                        val titleElement = element.selectFirst("h3.title-item a") ?: return@mapNotNull null
                        val title = titleElement.text().trim()
                        val href = fixUrl(titleElement.attr("href"), baseUrl) ?: return@mapNotNull null
                        val posterUrl = fixUrl(element.selectFirst("a.thumb img")?.attr("src"), baseUrl)
                        newMovieSearchResponse(title, href, TvType.Anime) {
                            this.posterUrl = posterUrl
                        }
                    } catch (e: Exception) {
                        Log.e(name, "Error parsing ranking item", e)
                        null
                    }
                }
            }
            else -> {
                document.select("ul.MovieList.Rows li.TPostMv").mapNotNull {
                    it.toSearchResponse(this, baseUrl)
                }
            }
        }
        val hasNext = if (request.data.contains("bang-xep-hang")) {
            false
        } else {
            document.selectFirst("div.wp-pagenavi span.current + a.page, div.wp-pagenavi a.larger:contains(Trang Cuối)") != null
        }
        return newHomePageResponse(
            list = HomePageList(
                name = request.name,
                list = home
            ),
            hasNext = hasNext
        )
    }

    override suspend fun search(query: String): List<SearchResponse> {
        return try {
            val baseUrl = getBaseUrl()
            val requestUrl = "$baseUrl/tim-kiem/${query.encodeUri()}/"
            val document = app.get(requestUrl).document
            document.select("ul.MovieList.Rows li.TPostMv")
                .mapNotNull { it.toSearchResponse(this, baseUrl) }
        } catch (e: Exception) {
            Log.e(name, "Error in search with query '$query'", e)
            emptyList()
        }
    }

    private suspend fun getBaseUrl(): String {
        if (domainResolutionAttempted && !currentActiveUrl.contains("bit.ly")) {
            return currentActiveUrl
        }
        var resolvedDomain: String? = null
        val urlToAttemptResolution = if (currentActiveUrl.contains("bit.ly") || !domainResolutionAttempted) {
            bitlyResolverUrl
        } else {
            currentActiveUrl
        }
        try {
            val response = app.get(urlToAttemptResolution, allowRedirects = true, timeout = 15_000)
            val finalUrlString = response.url
            if (finalUrlString.startsWith("http") && !finalUrlString.contains("bit.ly")) {
                val urlObject = URL(finalUrlString)
                resolvedDomain = "${urlObject.protocol}://${urlObject.host}"
            } else {
                Log.w(name, "Bitly resolution did not lead to a valid different domain. Final URL: $finalUrlString")
            }
        } catch (e: Exception) {
            Log.e(name, "Error resolving domain link '$urlToAttemptResolution': ${e.message}", e)
        }
        domainResolutionAttempted = true
        if (resolvedDomain != null) {
            if (currentActiveUrl != resolvedDomain) {
                Log.i(name, "Domain updated: $currentActiveUrl -> $resolvedDomain")
            }
            currentActiveUrl = resolvedDomain
        } else {
            if (currentActiveUrl.contains("bit.ly") || (urlToAttemptResolution != ultimateFallbackDomain && currentActiveUrl != ultimateFallbackDomain)) {
                Log.w(name, "Domain resolution failed for '$urlToAttemptResolution'. Using fallback: $ultimateFallbackDomain")
                currentActiveUrl = ultimateFallbackDomain
            } else {
                Log.e(name, "All domain resolution attempts failed. Sticking with last known: $currentActiveUrl")
            }
        }
        return currentActiveUrl
    }

    override suspend fun load(url: String): LoadResponse? {
        val baseUrl = getBaseUrl()
        try {
            val infoDocument = app.get(url, headers = mapOf("Referer" to baseUrl)).document
            val genres = infoDocument.getGenres()
            val watchPageDoc = if (!genres.any { it.equals("Anime sắp chiếu", ignoreCase = true) }) {
                try {
                    val watchPageUrl = if (url.endsWith("/")) "${url}xem-phim.html" else "$url/xem-phim.html"
                    app.get(watchPageUrl, referer = url).document
                } catch (e: Exception) {
                    Log.w(name, "Failed to load watch page. Error: ${e.message}")
                    null
                }
            } else {
                null
            }
            return infoDocument.toLoadResponse(this, url, baseUrl, watchPageDoc)
        } catch (e: Exception) {
            Log.e(name, "FATAL Error loading main info page ($url): ${e.message}", e)
            return null
        }
    }
    
    data class EpisodeData(val url: String, val dataId: String?, val duHash: String?)
    data class AjaxPlayerResponse(val success: Int?, val link: List<LinkSource>?)
    data class LinkSource(val file: String?, val type: String?, val label: String?)
}
