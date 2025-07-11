package recloudstream // Giữ nguyên package gốc của file AnimeHay

// === Imports ===
import android.util.Log
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addDuration
import com.lagradost.cloudstream3.mvvm.normalSafeApiCall
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.newExtractorLink
import okhttp3.Interceptor // Import cần thiết
import okhttp3.ResponseBody.Companion.toResponseBody // Import cần
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.net.MalformedURLException
import java.net.URL
import java.net.URLEncoder
import kotlin.math.roundToInt

// Data classes và các phần khác giữ nguyên...
data class KitsuMain(val data: List<KitsuData>?)
data class KitsuData(val attributes: KitsuAttributes?)
data class KitsuAttributes(
    val canonicalTitle: String?,
    val posterImage: KitsuPoster?
)
data class KitsuPoster(
    val original: String?,
    val large: String?,
    val medium: String?,
    val small: String?,
    val tiny: String?
)

class AnimeHayProvider : MainAPI() {

    override var mainUrl = "https://ahay.in"
    override var name = "AnimeHay"
    override val supportedTypes = setOf(TvType.Anime, TvType.AnimeMovie, TvType.OVA, TvType.Cartoon)
    override var lang = "vi"
    override val hasMainPage = true
    private var currentActiveUrl = "https://animehay.bid"
    private var domainCheckPerformed = false
    private val domainCheckUrl = mainUrl

    // =================================================================================================
    // *** PHẦN KIỂM TRA DEBUG ***
    // =================================================================================================
    override fun getVideoInterceptor(extractorLink: ExtractorLink): Interceptor? {
        return Interceptor { chain ->
            val request = chain.request()
            val url = request.url.toString()

            val isIbyte = url.contains("ibyteimg.com", ignoreCase = true)
            val isTiktok = url.contains(".tiktokcdn.", ignoreCase = true)
            val isSegment = url.contains("segment.cloudbeta.win/file/segment/", ignoreCase = true)
            val isHtmlToken = url.contains(".html?token=", ignoreCase = true)
            
            val needsFix = isIbyte || isTiktok || (isSegment && !isHtmlToken)

            if (needsFix) {
                // =================================================================
                // === MÃ KIỂM TRA: CỐ TÌNH GÂY LỖI ĐỂ XEM INTERCEPTOR CÓ CHẠY KHÔNG ===
                // Nếu video bị crash và bạn thấy thông báo này, có nghĩa là interceptor ĐÃ HOẠT ĐỘNG.
                // Nếu video chỉ không phát được như cũ, có nghĩa là interceptor đã bị bỏ qua.
                // =================================================================
                throw Exception("ANIMEHAY_DEBUG_INTERCEPTOR_IS_WORKING")
            }
            
            // Nếu không cần sửa, tiến hành như bình thường
            chain.proceed(request)
        }
    }
    // =================================================================================================
    // *** KẾT THÚC PHẦN KIỂM TRA DEBUG ***
    // =================================================================================================

    // Các hàm còn lại (getMainPage, load, search, loadLinks...) giữ nguyên như phiên bản trước.
    // Tôi sẽ rút gọn chúng ở đây để bạn tập trung vào phần thay đổi.
    // BẠN VẪN CẦN COPY TOÀN BỘ FILE NHÉ.

    private suspend fun getBaseUrl(): String {
        if (domainCheckPerformed) { return currentActiveUrl }
        var finalNewDomain: String? = null
        try {
            val response = app.get(domainCheckUrl, allowRedirects = true)
            val landedUrl = response.url
            val document = response.document
            var hrefFromContent: String? = null
            val linkSelectors = listOf("a.bt-link", "a.bt-link-1")
            for (selector in linkSelectors) {
                hrefFromContent = document.selectFirst(selector)?.attr("href")
                if (!hrefFromContent.isNullOrBlank()) break
            }
            if (hrefFromContent.isNullOrBlank()) {
                val scriptElements = document.select("script")
                val newDomainRegex = Regex("""var\s+new_domain\s*=\s*["'](https?://[^"']+)["']""")
                for (scriptElement in scriptElements) {
                    if (!scriptElement.hasAttr("src")) {
                        val scriptContent = scriptElement.html()
                        val match = newDomainRegex.find(scriptContent)
                        if (match != null && match.groups.size > 1) {
                            hrefFromContent = match.groups[1]?.value
                            break
                        }
                    }
                }
            }
            if (!hrefFromContent.isNullOrBlank()) {
                try {
                    val urlObject = URL(hrefFromContent)
                    val extractedBase = "${urlObject.protocol}://${urlObject.host}"
                    if (extractedBase.startsWith("http")) finalNewDomain = extractedBase
                } catch (_: MalformedURLException) {}
            }
            if (finalNewDomain.isNullOrBlank()) {
                val landedUrlBase = try {
                    val landedObj = URL(landedUrl)
                    "${landedObj.protocol}://${landedObj.host}"
                } catch (_: Exception) { null }
                val initialCheckUrlHost = try { URL(domainCheckUrl).host } catch (_: Exception) { null }
                val landedUrlHost = try { URL(landedUrl).host } catch (_: Exception) { null }
                if (landedUrlBase != null && landedUrlBase.startsWith("http") && landedUrlHost != initialCheckUrlHost) {
                    finalNewDomain = landedUrlBase
                }
            }
            if (!finalNewDomain.isNullOrBlank() && finalNewDomain != currentActiveUrl) {
                if (!finalNewDomain!!.contains("archive.org", ignoreCase = true)) {
                    currentActiveUrl = finalNewDomain!!
                }
            }
        } catch (_: Exception) {} 
        finally { domainCheckPerformed = true }
        return currentActiveUrl
    }

    override suspend fun loadLinks(data: String, isCasting: Boolean, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit): Boolean {
        var foundLinks = false
        val document: Document? = try { app.get(data, headers = mapOf("Referer" to getBaseUrl())).document } catch (_: Exception) { null }
        if (document == null) return false
        try {
            val combinedScript = document.select("script:not([src])").joinToString("\n") { it.html() }
            val pageHtml = document.html()
            val baseUrl = getBaseUrl()
            val m3u8Link = Regex("""tik:\s*['"]([^'"]+)['"]""").find(combinedScript)?.groupValues?.get(1)?.trim()
            if (!m3u8Link.isNullOrEmpty()) {
                callback(
                    newExtractorLink(source = m3u8Link, name = "Server TOK", url = m3u8Link, type = ExtractorLinkType.M3U8) {
                        this.quality = Qualities.Unknown.value
                        this.referer = baseUrl 
                    }
                )
                foundLinks = true
            }
        } catch (e: Exception) {
            Log.e("AnimeHayProvider", "loadLinks error", e)
        }
        return foundLinks
    }
    
    // ... các hàm khác không thay đổi ...
    private fun decryptXOR(data: ByteArray): ByteArray {
        val key = byteArrayOf(55, 33, 2, 1, 5, 4, 9, 8, 99, 3, 2, 5, 7, 8, 9, 10)
        val limit = 1024 
        val length = if (data.size < limit) data.size else limit
        for (i in 0 until length) {
            data[i] = (data[i].toInt() xor key[i % key.size].toInt()).toByte()
        }
        return data
    }
    private suspend fun getKitsuPoster(title: String): String? { return null }
    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse { return newHomePageResponse(emptyList(), false) }
    override suspend fun search(query: String): List<SearchResponse> { return emptyList() }
    override suspend fun load(url: String): LoadResponse? { return null }
}
