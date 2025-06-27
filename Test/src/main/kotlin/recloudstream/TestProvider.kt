package com.lagradost.cloudstream3

import com.lagradost.cloudstream3.LoadResponse.Companion.addActors
import com.lagradost.cloudstream3.LoadResponse.Companion.addDuration
import com.lagradost.cloudstream3.utils.*
import android.util.Log
import com.google.gson.Gson
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.net.URLEncoder
import java.net.URL
import kotlin.math.roundToInt
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch

// CÁC IMPORT MỚI
import java.util.Base64
import java.util.zip.Inflater
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

class AnimeVietsubProvider : MainAPI() {

    companion object {
        val dataStore = ConcurrentHashMap<String, String>()
    }

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
    private val USER_AGENT = "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/137.0.0.0 Mobile Safari/537.36"

    private val bitlyResolverUrl = "https://bit.ly/animevietsubtv"
    private val ultimateFallbackDomain = "https://animevietsub.lol"
    private var currentActiveUrl = bitlyResolverUrl
    private var domainResolutionAttempted = false

    override val mainPage = mainPageOf(
        "/anime-moi/" to "Mới Cập Nhật",
        "/anime-sap-chieu/" to "Sắp Chiếu",
        "/bang-xep-hang/day.html" to "Xem Nhiều Trong Ngày"
    )

    // ... Toàn bộ các hàm khác từ getMainPage đến trước loadLinks giữ nguyên ...
    // ...

    private val aesKey: SecretKeySpec by lazy {
        val keyStringB64 = "ZG1fdGhhbmdfc3VjX3ZhdF9nZXRfbGlua19hbl9kYnQ="
        val decodedKeyBytes = Base64.getDecoder().decode(keyStringB64)
        val sha256Hasher = MessageDigest.getInstance("SHA-256")
        val hashedKeyBytes = sha256Hasher.digest(decodedKeyBytes)
        SecretKeySpec(hashedKeyBytes, "AES")
    }

    private fun decryptM3u8Content(encryptedDataString: String): String? {
        return try {
            val encryptedBytes = Base64.getDecoder().decode(encryptedDataString)
            if (encryptedBytes.size < 16) return null
            val ivBytes = encryptedBytes.copyOfRange(0, 16)
            val ciphertextBytes = encryptedBytes.copyOfRange(16, encryptedBytes.size)
            val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
            cipher.init(Cipher.DECRYPT_MODE, aesKey, IvParameterSpec(ivBytes))
            val decryptedBytesPadded = cipher.doFinal(ciphertextBytes)
            val inflater = Inflater(true)
            inflater.setInput(decryptedBytesPadded, 0, decryptedBytesPadded.size)
            val result = ByteArray(1024 * 1024)
            val decompressedLength = inflater.inflate(result)
            inflater.end()
            val decompressedBytes = result.copyOfRange(0, decompressedLength)
            var m3u8Content = decompressedBytes.toString(Charsets.UTF_8)
            m3u8Content = m3u8Content.trim().removeSurrounding("\"")
            m3u8Content = m3u8Content.replace("\\n", "\n")
            m3u8Content
        } catch (e: Exception) {
            Log.e(name, "Lỗi giải mã M3U8 nội bộ", e)
            null
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        try {
            val episodeData = gson.fromJson(data, EpisodeData::class.java)
            val episodePageUrl = episodeData.url
            val baseUrl = getBaseUrl()

            val postData = mutableMapOf("id" to (episodeData.dataId ?: throw ErrorLoadingException("Missing episode ID")), "play" to "api").apply {
                episodeData.duHash?.let { put("link", it) }
            }
            val headers = mapOf(
                "Content-Type" to "application/x-www-form-urlencoded; charset=UTF-8",
                "Accept" to "application/json, text/javascript, */*; q=0.01",
                "X-Requested-With" to "XMLHttpRequest",
                "User-Agent" to USER_AGENT,
                "Referer" to episodePageUrl
            )
            val ajaxUrl = "$baseUrl/ajax/player?v=2019a"
            val playerResponse = app.post(ajaxUrl, data = postData, headers = headers, referer = episodePageUrl).parsed<AjaxPlayerResponse>()

            if (playerResponse.success != 1 || playerResponse.link.isNullOrEmpty()) {
                throw ErrorLoadingException("Failed to get links from AJAX response: $playerResponse")
            }
            
            coroutineScope {
                playerResponse.link.forEach { linkSource ->
                    launch {
                        val encryptedUrl = linkSource.file ?: return@launch
                        val m3u8Content = decryptM3u8Content(encryptedUrl) ?: return@launch

                        // Sửa đổi nội dung M3U8 để trỏ segment về proxy
                        val modifiedM3u8 = m3u8Content.lines().joinToString("\n") { line ->
                            if (line.isNotBlank() && !line.startsWith("#")) {
                                val encodedSegmentUrl = URLEncoder.encode(line, "UTF-8")
                                "http://rat.local/segment-proxy?url=$encodedSegmentUrl&referer=${URLEncoder.encode(episodePageUrl, "UTF-8")}"
                            } else {
                                line
                            }
                        }
                        
                        val key = UUID.randomUUID().toString()
                        dataStore[key] = modifiedM3u8
                        
                        val localM3u8Url = "http://rat.local/$key"

                        newExtractorLink(
                            source = name,
                            name = "$name HLS",
                            url = localM3u8Url,
                            type = ExtractorLinkType.M3U8
                        ) {
                            this.referer = episodePageUrl
                            this.quality = Qualities.Unknown.value
                        }.let { callback(it) }
                    }
                }
            }

        } catch (e: Exception) {
            Log.e(name, "Error in loadLinks for data: $data", e)
            return false
        }
        return true
    }
    
    // HÀM MỚI: Xử lý các yêu cầu đến server ảo .local
    override suspend fun loadExtractor(url: String, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit) {
        // http://rat.local/segment-proxy?url=...
        // http://rat.local/key
        val host = URL(url).host
        val path = URL(url).path.removePrefix("/")

        if (host == "rat.local") {
            // Trường hợp 1: Yêu cầu file M3U8 đã được sửa đổi
            if (!path.contains("segment-proxy")) {
                val m3u8Data = dataStore[path]
                if (m3u8Data != null) {
                    callback(
                        ExtractorLink(
                            this.name,
                            this.name,
                            // Dùng data URI để trả về nội dung M3U8 trực tiếp
                            "data:application/vnd.apple.mpegurl;base64," + Base64.getEncoder().encodeToString(m3u8Data.toByteArray()),
                            "",
                            Qualities.Unknown.value,
                            type = ExtractorLinkType.M3U8
                        )
                    )
                }
            } 
            // Trường hợp 2: Yêu cầu proxy cho một segment
            else {
                val query = URL(url).query
                val originalUrl = getUrlParameter(query, "url")
                val referer = getUrlParameter(query, "referer")

                // Thực hiện yêu cầu đến segment với header chuẩn
                val segmentResponse = app.get(originalUrl, referer = referer, headers = mapOf("User-Agent" to USER_AGENT))
                
                // Trả về link cuối cùng (sau khi redirect) cho player
                callback(
                    ExtractorLink(
                        this.name,
                        "Segment",
                        segmentResponse.url,
                        referer,
                        Qualities.Unknown.value,
                        type = ExtractorLinkType.VIDEO,
                        headers = segmentResponse.headers.toMap()
                    )
                )
            }
        }
    }
    
    // ... Toàn bộ các hàm tiện ích còn lại giữ nguyên ...
    private fun getUrlParameter(query: String, param: String): String {
        return query.split('&').find { it.startsWith("$param=") }?.substringAfter('=') ?: ""
    }
}
