package recloudstream // Gói này phải khớp với cấu trúc thư mục của bạn

import android.util.Base64 // Từ code gốc của bạn
import com.lagradost.cloudstream3.*
// Quan trọng: Đảm bảo module "Test" của bạn có thể thấy các utils này từ Cloudstream
import com.lagradost.cloudstream3.utils.AppUtils // Cho parseJson và hy vọng là toJson
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.loadExtractor
import org.jsoup.nodes.Element // Từ code gốc của bạn
import org.jsoup.Jsoup // Từ code gốc của bạn
import java.net.URLEncoder

// Nếu AppUtils.toJson không có và bạn phải dùng Klaxon trực tiếp,
// bạn cần thêm Klaxon vào build.gradle của module "Test" và uncomment dòng dưới:
// import com.beust.klaxon.Klaxon

// class TestProvider : MainAPI() { // Sử dụng tên class từ log lỗi
class Anime47Provider : MainAPI() { // Hoặc tên class đúng của bạn
    // ... (các thuộc tính và hàm khác như mainUrl, name, sendLog, v.v. giữ nguyên như code gốc của bạn)
    override var mainUrl = "https://anime47.lat"
    override var name = "Anime47"
    override val hasMainPage = true
    override var lang = "vi"
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(TvType.Anime, TvType.AnimeMovie, TvType.OVA)

    private data class DecryptionResponse(val decryptedResult: String?) // Đảm bảo class này được định nghĩa

    private suspend fun sendLog(logMessage: String) {
        val encodedLog = try {
            Base64.encodeToString(logMessage.toByteArray(), Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
        } catch (e: Exception) { "log_encoding_error::${e.message}".take(50) }
        val logUrl = "https://text.013666.xyz/upload/text/logs.txt/$encodedLog"
        try { app.get(logUrl, timeout = 5) } catch (e: Exception) { println("Failed to send log: ${e.message}") }
    }

    // ... (getBackgroundImageUrl, getMainPage, search, load giữ nguyên như code gốc của bạn) ...
    // Phần code getMainPage, search, load đã có trong các tin nhắn trước, bạn giữ nguyên phần đó.
    // Mình sẽ chỉ tập trung vào loadLinks dưới đây.


    // Hàm tải link video và phụ đề
    override suspend fun loadLinks(
        data: String, // Watch page URL
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        var sourceLoaded = false // Khai báo ở scope ngoài cùng của loadLinks
        val episodeId = data.substringAfterLast('/').substringBefore('.').trim()
        val serverIds = listOf("4", "2", "7") // Ưu tiên Fe, Fa, Hy
        val commonUA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/110.0.0.0 Safari/537.36"
        val thanhhoaRegex = Regex("""var\s+thanhhoa\s*=\s*atob\(['"](.*?)['"]\)""")
        val externalDecryptApiBase = "https://m3u8.013666.xyz/anime47/link/"
        val m3u8ProxyUrlBase = "https://proxy.h4rs.io.vn/proxy"

        sendLog("loadLinks started for: $data - Servers: ${serverIds.joinToString()}. Using M3U8 proxy if applicable.")

        suspend fun tryLoadFromServerExternalApi(serverId: String): Boolean {
            var successInner = false // Biến local cho hàm này
            val serverName = when(serverId) { "2" -> "Fa"; "4" -> "Fe"; "7" -> "Hy"; else -> "SV$serverId" }
            try {
                sendLog("Attempting server $serverName (ID: $serverId)")
                val apiPlayerPageResponse = app.post( // Đổi tên biến để rõ ràng hơn
                    "$mainUrl/player/player.php",
                    data = mapOf("ID" to episodeId, "SV" to serverId),
                    referer = data,
                    headers = mapOf( "X-Requested-With" to "XMLHttpRequest", "User-Agent" to commonUA, "Origin" to mainUrl, "Referer" to data )
                )
                val apiScript = apiPlayerPageResponse.document.select("script:containsData(jwplayer(\"player\").setup)").html()

                // Lấy phụ đề (giữ nguyên)
                val tracksRegex = Regex("""tracks:\s*(\[.*?\])""", RegexOption.DOT_MATCHES_ALL)
                tracksRegex.find(apiScript)?.groupValues?.getOrNull(1)?.let { tracksJson ->
                    try {
                        val subtitleRegex = Regex("""file:\s*["'](.*?)["'].*?label:\s*["'](.*?)["']""")
                        subtitleRegex.findAll(tracksJson).forEach { match ->
                            subtitleCallback(SubtitleFile(match.groupValues[2], fixUrl(match.groupValues[1])))
                        }
                    } catch (e: Exception) { sendLog("Error parsing subtitles from API server $serverId: $e") }
                }

                val apiThanhhoaBase64 = thanhhoaRegex.find(apiScript)?.groupValues?.getOrNull(1)

                if (apiThanhhoaBase64 != null) {
                    sendLog("Server $serverName: Found thanhhoa Base64: ${apiThanhhoaBase64.take(50)}...")
                    val decryptUrl = "$externalDecryptApiBase$apiThanhhoaBase64"
                    try {
                        val decryptApiResponseText = app.get(decryptUrl).text
                        sendLog("Server $serverName: Decrypt API raw response: ${decryptApiResponseText.take(200)}...")

                        // Quan trọng: Sử dụng AppUtils.parseJson. Nếu không được, bạn phải sửa module dependency.
                        val decryptionResultObject: DecryptionResponse? = try {
                            AppUtils.parseJson<DecryptionResponse>(decryptApiResponseText)
                        } catch (e: Exception) {
                            sendLog("Server $serverName: JSON parsing error for DecryptionResponse: $e")
                            null
                        }

                        val videoUrl: String? = decryptionResultObject?.decryptedResult

                        if (videoUrl != null && videoUrl.startsWith("http")) { // videoUrl giờ là String non-null trong scope này
                            sendLog("Server $serverName: Success! Extracted URL: $videoUrl")

                            if (videoUrl.contains(".m3u8", ignoreCase = true) || videoUrl.contains("/hls", ignoreCase = true)) {
                                sendLog("Server $serverName: M3U8 link detected. Applying proxy.")
                                val originalM3U8Headers = mapOf(
                                    "Referer" to data,
                                    "User-Agent" to commonUA,
                                    "Origin" to mainUrl
                                )

                                // Ưu tiên dùng AppUtils.toJson. Nếu không có, bạn phải xử lý Klaxon dependency.
                                val headersJson: String = try {
                                    AppUtils.toJson(originalM3U8Headers)
                                } catch (e: Throwable) { // Bắt lỗi nếu AppUtils.toJson không có hoặc lỗi
                                    sendLog("AppUtils.toJson failed ($e), falling back. Ensure Klaxon dependency if using Klaxon directly.")
                                    // Nếu dùng Klaxon trực tiếp:
                                    // import com.beust.klaxon.Klaxon (cần uncomment ở đầu file)
                                    // Klaxon().toJsonString(originalM3U8Headers)
                                    // Fallback rất cơ bản (chỉ cho cấu trúc đơn giản này):
                                    """{"Referer":"${originalM3U8Headers["Referer"]}","User-Agent":"${originalM3U8Headers["User-Agent"]}","Origin":"${originalM3U8Headers["Origin"]}"}"""
                                }

                                val encodedVideoUrl = URLEncoder.encode(videoUrl, "UTF-8")
                                val encodedHeadersJson = URLEncoder.encode(headersJson, "UTF-8")
                                val proxiedUrl = "$m3u8ProxyUrlBase?url=$encodedVideoUrl&headers=$encodedHeadersJson"
                                sendLog("Server $serverName: Proxied M3U8 URL: $proxiedUrl")

                                callback(
                                    ExtractorLink(
                                        source = "$name $serverName (Proxy)",
                                        name = "$name $serverName HLS (Proxied)",
                                        url = proxiedUrl,
                                        referer = data,
                                        quality = Qualities.Unknown.value,
                                        type = ExtractorLinkType.M3U8,
                                        headers = emptyMap()
                                    )
                                )
                            } else {
                                sendLog("Server $serverName: Non-M3U8 link. Using direct link: $videoUrl")
                                val linkHeaders = mapOf( "Referer" to data, "User-Agent" to commonUA, "Origin" to mainUrl )
                                callback(
                                    ExtractorLink(
                                        source = "$name $serverName (Ext)",
                                        name = "$name $serverName",
                                        url = videoUrl,
                                        referer = data,
                                        quality = Qualities.Unknown.value,
                                        // Sửa 'MP4' thành 'STREAM' hoặc type phù hợp
                                        type = if (videoUrl.contains(".mp4", ignoreCase = true)) ExtractorLinkType.STREAM else ExtractorLinkType.M3U8,
                                        headers = linkHeaders
                                    )
                                )
                            }
                            successInner = true
                        } else {
                            sendLog("Server $serverName: Failed to parse video URL or URL invalid (is null or not http). videoUrl: $videoUrl")
                        }
                    } catch (apiError: Exception) {
                        sendLog("Server $serverName: Error calling/processing external decrypt API ($decryptUrl): ${apiError.message}")
                        apiError.printStackTrace()
                    }
                } else {
                    sendLog("API Response (Server $serverName) does not contain 'thanhhoa'. Checking for iframes...")
                    // apiPlayerPageResponse.document cần được sử dụng ở đây nếu apiResponse từ trên
                    apiPlayerPageResponse.document.select("iframe[src]").forEach { iframe ->
                        if (successInner) return@forEach
                        val iframeSrc = iframe.attr("src") ?: return@forEach
                        sendLog("Server $serverName: Found iframe fallback: $iframeSrc")
                        successInner = loadExtractor(iframeSrc, data, subtitleCallback, callback) || successInner
                    }
                }
            } catch (e: Exception) {
                sendLog("Error loading or processing server $serverId: ${e.message}")
                e.printStackTrace()
            }
            return successInner
        }

        serverIds.forEach { serverId ->
            if (sourceLoaded) return@forEach
            sourceLoaded = tryLoadFromServerExternalApi(serverId)
        }

        if (!sourceLoaded) {
            sendLog("All API attempts failed. Trying iframe fallback on original page...")
            try {
                val document = app.get(data, referer = data).document
                document.select("iframe[src]").forEach { iframe ->
                    if (sourceLoaded) return@forEach
                    val iframeSrc = iframe.attr("src") ?: return@forEach
                    if (!iframeSrc.contains("facebook.com")) {
                        sendLog("Found iframe fallback on original page: $iframeSrc")
                        // Sửa lỗi 'success' thành 'sourceLoaded'
                        sourceLoaded = loadExtractor(iframeSrc, data, subtitleCallback, callback) || sourceLoaded
                    }
                }
            } catch (e: Exception) {
                sendLog("Error searching for iframe fallback: $e")
            }
        }

        if (!sourceLoaded) {
            sendLog("Failed to load video link from any source for: $data")
            println("Không thể load được link video từ bất kỳ nguồn nào.")
        }
        return sourceLoaded
    }

    private fun getQualityFromString(str: String?): Int {
        return Regex("""\b(\d{3,4})p?\b""").find(str ?: "")?.groupValues?.getOrNull(1)?.toIntOrNull()
            ?: Qualities.Unknown.value
    }

    // Bạn cần các hàm fixUrl, fixUrlNull, newAnimeSearchResponse, newTvSeriesLoadResponse
    // đã được định nghĩa trong MainAPI hoặc bạn phải tự định nghĩa chúng nếu TestProvider không kế thừa từ MainAPI
    // hoặc nếu chúng là extension functions không có trong scope.
    // Ví dụ, nếu chúng là extension functions, bạn có thể cần import chúng.
}
