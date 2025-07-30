package recloudstream

import android.util.Log
import com.google.gson.annotations.SerializedName
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.network.CloudflareKiller
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import okhttp3.Interceptor
import okhttp3.Response
import org.jsoup.nodes.Element
import java.net.URI
import java.security.MessageDigest
import java.security.SecureRandom
import java.time.Instant
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlin.text.Charsets
import kotlin.text.Regex

//region: Data classes (ĐÃ SỬA LẠI ĐỂ KHỚP VỚI FILE JAVA)
private data class Payload(
    @SerializedName("idfile") val idfile: String,
    @SerializedName("iduser") val iduser: String,
    @SerializedName("domain_play") val domain_play: String,
    @SerializedName("platform") val platform: String = "noplf",
    @SerializedName("ip_clien") val ip_clien: String,
    @SerializedName("time_request") val time_request: String,
    @SerializedName("hlsSupport") val hlsSupport: Boolean = true,
    @SerializedName("jwplayer") val jwplayer: JWPlayer
)

private data class JWPlayer(
    // FIXED: Uppercase field names to match Java source
    @SerializedName("Browser") val Browser: JWPlayerBrowser,
    @SerializedName("OS") val OS: JWPlayerOS,
    @SerializedName("Features") val Features: JWPlayerFeatures
)

private data class JWPlayerBrowser(
    @SerializedName("androidNative") val androidNative: Boolean,
    @SerializedName("chrome") val chrome: Boolean,
    @SerializedName("edge") val edge: Boolean,
    @SerializedName("facebook") val facebook: Boolean,
    @SerializedName("firefox") val firefox: Boolean,
    @SerializedName("ie") val ie: Boolean,
    @SerializedName("msie") val msie: Boolean,
    @SerializedName("safari") val safari: Boolean,
    @SerializedName("version") val version: JWPlayerBrowserVersion
)

private data class JWPlayerBrowserVersion(
    @SerializedName("version") val version: String,
    @SerializedName("major") val major: Int,
    @SerializedName("minor") val minor: Int
)

private data class JWPlayerOS(
    @SerializedName("android") val android: Boolean,
    // FIXED: Case-sensitive field name
    @SerializedName("iOS") val iOS: Boolean,
    @SerializedName("mobile") val mobile: Boolean,
    @SerializedName("mac") val mac: Boolean,
    @SerializedName("iPad") val iPad: Boolean,
    @SerializedName("iPhone") val iPhone: Boolean,
    @SerializedName("windows") val windows: Boolean,
    @SerializedName("tizen") val tizen: Boolean,
    @SerializedName("tizenApp") val tizenApp: Boolean,
    @SerializedName("version") val version: JWPlayerOSVersion
)

private data class JWPlayerOSVersion(
    @SerializedName("version") val version: String,
    @SerializedName("major") val major: Int,
    @SerializedName("minor") val minor: Int
)

private data class JWPlayerFeatures(
    @SerializedName("iframe") val iframe: Boolean,
    @SerializedName("passiveEvents") val passiveEvents: Boolean,
    @SerializedName("backgroundLoading") val backgroundLoading: Boolean
)

private data class ApiResponse(
    @SerializedName("status") val status: Int,
    @SerializedName("type") val type: String?,
    @SerializedName("data") val data: String?
)
//endregion

//region: Logic mã hoá
private object Crypto {
    private const val AES = "AES"
    private const val HASH_CIPHER = "AES/CBC/PKCS5Padding"
    private const val KDF_DIGEST = "MD5"
    private const val KEY_SIZE_BITS = 256
    private const val IV_SIZE_BITS = 128
    private const val SALT_SIZE_BYTES = 8
    private const val APPEND_TEXT = "Salted__"

    fun encrypt(plainText: String, password: String): String {
        val salt = generateSalt(SALT_SIZE_BYTES)
        val key = ByteArray(KEY_SIZE_BITS / 8)
        val iv = ByteArray(IV_SIZE_BITS / 8)
        evpkdf(password.toByteArray(Charsets.UTF_8), salt, key, iv)
        val secretKeySpec = SecretKeySpec(key, AES)
        val cipher = Cipher.getInstance(HASH_CIPHER)
        cipher.init(Cipher.ENCRYPT_MODE, secretKeySpec, IvParameterSpec(iv))
        val encryptedBytes = cipher.doFinal(plainText.toByteArray(Charsets.UTF_8))
        val saltedPrefixBytes = APPEND_TEXT.toByteArray(Charsets.UTF_8)
        val result = ByteArray(saltedPrefixBytes.size + salt.size + encryptedBytes.size)
        System.arraycopy(saltedPrefixBytes, 0, result, 0, saltedPrefixBytes.size)
        System.arraycopy(salt, 0, result, saltedPrefixBytes.size, salt.size)
        System.arraycopy(encryptedBytes, 0, result, saltedPrefixBytes.size + salt.size, encryptedBytes.size)
        return Base64.getEncoder().encodeToString(result)
    }

    fun decrypt(cipherTextB64: String, password: String): String {
        val decodedBytes = Base64.getDecoder().decode(cipherTextB64)
        val salt = decodedBytes.copyOfRange(8, 16)
        val cipherBytes = decodedBytes.copyOfRange(16, decodedBytes.size)
        val key = ByteArray(KEY_SIZE_BITS / 8)
        val iv = ByteArray(IV_SIZE_BITS / 8)
        evpkdf(password.toByteArray(Charsets.UTF_8), salt, key, iv)
        val secretKeySpec = SecretKeySpec(key, AES)
        val cipher = Cipher.getInstance(HASH_CIPHER)
        cipher.init(Cipher.DECRYPT_MODE, secretKeySpec, IvParameterSpec(iv))
        val decryptedBytes = cipher.doFinal(cipherBytes)
        return String(decryptedBytes, Charsets.UTF_8)
    }

    private fun evpkdf(password: ByteArray, salt: ByteArray, resultKey: ByteArray, resultIv: ByteArray) {
        val keySizeWords = KEY_SIZE_BITS / 32
        val ivSizeWords = IV_SIZE_BITS / 32
        val totalWords = keySizeWords + ivSizeWords
        val derivedBytes = ByteArray(totalWords * 4)
        val md = MessageDigest.getInstance(KDF_DIGEST)
        var block: ByteArray? = null
        var i = 0
        while (i < totalWords) {
            if (block != null) md.update(block)
            md.update(password)
            block = md.digest(salt)
            md.reset()
            System.arraycopy(block, 0, derivedBytes, i * 4, minOf(block.size, (totalWords - i) * 4))
            i += block.size / 4
        }
        System.arraycopy(derivedBytes, 0, resultKey, 0, keySizeWords * 4)
        System.arraycopy(derivedBytes, keySizeWords * 4, resultIv, 0, ivSizeWords * 4)
    }

    private fun generateSalt(size: Int): ByteArray {
        val salt = ByteArray(size)
        SecureRandom().nextBytes(salt)
        return salt
    }
}
//endregion

//region: Logic giải mã Javascript
private object UnwiseHelper {
    private fun unwise1(w: String): String {
        val sb = StringBuilder()
        var i = 0
        while (i < w.length) {
            val substring = w.substring(i, i + 2)
            val charCode = substring.toInt(36)
            require(charCode in 0..65535) { "Invalid Char code: $charCode" }
            sb.append(charCode.toChar())
            i += 2
        }
        return sb.toString()
    }

    private fun unwise(w: String, i: String, s: String, e: String, wi: Int, ii: Int, si: Int, ei: Int): String {
        val sb = StringBuilder()
        val sb2 = StringBuilder()
        var wPos = 0; var iPos = 0; var sPos = 0; var ePos = 0
        do {
            if (w.isNotEmpty()) { if (wPos < wi) sb2.append(w[wPos]) else if (wPos < w.length) sb.append(w[wPos]); wPos++ }
            if (i.isNotEmpty()) { if (iPos < ii) sb2.append(i[iPos]) else if (iPos < i.length) sb.append(i[iPos]); iPos++ }
            if (s.isNotEmpty()) { if (sPos < si) sb2.append(s[sPos]) else if (sPos < s.length) sb.append(s[sPos]); sPos++ }
            if (e.isNotEmpty()) { if (ePos < ei) sb2.append(e[ePos]) else if (ePos < e.length) sb.append(e[ePos]); ePos++ }
        } while (w.length + i.length + s.length + e.length != sb.length + sb2.length)
        val result = StringBuilder()
        var j = 0; var k = 0
        while (j < sb.length) {
            val charCode = sb.substring(j, j + 2).toInt(36) - if (sb2[k].code % 2 != 0) 1 else -1
            require(charCode in 0..65535) { "Invalid Char code: $charCode" }
            result.append(charCode.toChar())
            k = (k + 1) % sb2.length
            j += 2
        }
        return result.toString()
    }

    fun unwiseProcess(packedJs: String): String {
        var currentJs = packedJs
        while (true) {
            val evalMatch = Regex(";?eval\\s*\\(\\s*function\\s*\\(\\s*w\\s*,\\s*i\\s*,\\s*s\\s*,\\s*e\\s*\\).+?['\"]\\s*\\)\\s*\\)(?:\\s*;)?").find(currentJs) ?: return currentJs
            val evalBlock = evalMatch.value
            val paramsMatch = Regex("\\}\\s*\\(\\s*['\"](\\w+)['\"]\\s*,\\s*['\"](\\w+)['\"]\\s*,\\s*['\"](\\w+)['\"]\\s*,\\s*['\"](\\w+)['\"]").find(evalBlock)
            if (paramsMatch == null) {
                currentJs = currentJs.replace(evalBlock, ""); continue
            }
            val (p_w, p_i, p_s, p_e) = paramsMatch.destructured
            val params = arrayOf(p_w, p_i, p_s, p_e)
            if (!evalBlock.contains("while")) {
                currentJs = currentJs.replace(evalBlock, unwise1(params[0])); continue
            }
            val vars = arrayOf("", "", "", ""); val lens = IntArray(4)
            val whileMatch = Regex("while(.+?)var\\s*\\w+\\s*=\\s*\\w+\\.join\\(\\s*['\"]['\"]\\s*\\)").find(evalBlock)
            if (whileMatch != null) {
                val lenMatches = Regex("if\\s*\\(\\s*\\w*\\s*<\\s*(\\d+)\\)\\s*\\w+\\.push").findAll(whileMatch.groupValues[1]).toList()
                lenMatches.forEachIndexed { index, matchResult ->
                    vars[index] = params[index]; lens[index] = matchResult.groupValues[1].toInt()
                }
            }
            currentJs = currentJs.replace(evalBlock, unwise(vars[0], vars[1], vars[2], vars[3], lens[0], lens[1], lens[2], lens[3]))
        }
    }
}
//endregion

//region: Các hàm tiện ích
private fun hexStringToByteArray(s: String): ByteArray {
    val data = ByteArray(s.length / 2)
    for (i in s.indices step 2) {
        data[i / 2] = ((Character.digit(s[i], 16) shl 4) + Character.digit(s[i + 1], 16)).toByte()
    }
    return data
}

private fun md5Hash(text: String): String {
    val digest = MessageDigest.getInstance("MD5").digest(text.toByteArray(Charsets.UTF_8))
    return digest.joinToString("") { "%02x".format(it) }
}

private fun getBaseUrl(url: String): String {
    val uri = URI(url)
    return "${uri.scheme}://${uri.host}"
}
//endregion

class TvPhimProvider : MainAPI() {
    private val TAG = "TvPhimProvider"

    override var mainUrl = "https://tvphim.bid"
    override var name = "TVPhim"
    override val hasMainPage = true
    override var lang = "vi"
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)

    private val cfInterceptor = CloudflareKiller()

    private val DECRYPTION_KEY_1 = "jcLycoRJT6OWjoWspgLMOZwS3aSS0lEn"
    private val DECRYPTION_KEY_2 = "PZZ3J3LDbLT0GY7qSA5wW5vchqgpO36O"
    private val ENCRYPTION_KEY = "vlVbUQhkOhoSfyteyzGeeDzU0BHoeTyZ"
    private val MD5_SALT = "KRWN3AdgmxEMcd2vLN1ju9qKe8Feco5h"
    private val FINAL_M3U8_DECRYPTION_KEY = "oJwmvmVBajMaRCTklxbfjavpQO7SZpsL"

    private fun Element.toSearchResponse(): SearchResponse? {
        val title = this.selectFirst("div.h-14 span")?.text()?.trim() ?: return null
        val href = this.selectFirst("a")?.attr("href") ?: return null
        val posterUrl = this.selectFirst("img")?.attr("src")
        val year = this.selectFirst("div.year")?.text()?.toIntOrNull()
        val statusText = this.selectFirst("span.bg-red-800")?.text() ?: ""
        val isTvSeries = "/" in statusText || "tập" in statusText.lowercase()
        return if (isTvSeries) {
            newTvSeriesSearchResponse(title, href) { this.posterUrl = posterUrl; this.year = year }
        } else {
            newMovieSearchResponse(title, href) { this.posterUrl = posterUrl; this.year = year }
        }
    }

    override val mainPage = mainPageOf(
        "$mainUrl/phim-le/" to "Phim Lẻ Mới",
        "$mainUrl/phim-bo/" to "Phim Bộ Mới",
        "$mainUrl/phim-hay/" to "Phim Hay Đề Cử",
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = if (page == 1) request.data else "${request.data}page/$page/"
        val document = app.get(url, interceptor = cfInterceptor).document
        val home = document.select("div.grid div.relative").mapNotNull { it.toSearchResponse() }
        return newHomePageResponse(request.name, home)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val document = app.get("$mainUrl/search/$query/", interceptor = cfInterceptor).document
        return document.select("div.grid div.relative").mapNotNull { it.toSearchResponse() }
    }

    override suspend fun load(url: String): LoadResponse {
        try {
            val document = app.get(url, interceptor = cfInterceptor).document
            val title = document.selectFirst("h1[itemprop=name]")?.text()?.trim()
                ?: document.selectFirst("h1.entry-title")?.text()?.trim() ?: "Không tìm thấy tiêu đề"
            val poster = document.selectFirst("img[itemprop=image]")?.attr("src")
            val year = document.selectFirst("span.text-gray-400:contains(Năm Sản Xuất) + span")?.text()?.toIntOrNull()
            var plot = document.selectFirst("div[itemprop=description] p")?.text()
                ?: document.selectFirst("div.entry-content p")?.text()
            document.selectFirst("span.liked")?.text()?.trim()?.toIntOrNull()?.let { plot += "\n\n❤️ Yêu thích: $it" }
            val tags = document.select("span.text-gray-400:contains(Thể loại) a").map { it.text() }
            val actors = document.select("span.text-gray-400:contains(Diễn viên) a").map { ActorData(Actor(it.text())) }
            val recommendations = document.select("div.mt-2:has(span:contains(Cùng Series)) a").mapNotNull {
                val recTitle = it.selectFirst("span")?.text()
                val recHref = it.attr("href")
                if (recTitle != null && recHref.isNotBlank()) {
                    newMovieSearchResponse(recTitle, recHref) { this.posterUrl = it.selectFirst("img")?.attr("src") }
                } else null
            }
            val status = document.selectFirst("span:contains(Tình Trạng:) + span")?.text()?.lowercase() ?: ""
            val isTvSeries = status.contains("/") || status.contains("tập")

            return if (isTvSeries) {
                val watchUrl = document.selectFirst("a:contains(Xem Phim)")?.attr("href")
                val episodes = if (watchUrl != null) {
                    val watchDocument = app.get(watchUrl, interceptor = cfInterceptor).document
                    watchDocument.select("div.episodelist a").mapNotNull { ep ->
                        val epName = ep.attr("title").ifBlank { ep.text() }; val epUrl = ep.attr("href")
                        if (epUrl.isBlank()) return@mapNotNull null
                        newEpisode(epUrl) { name = epName }
                    }.reversed()
                } else emptyList()
                newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
                    this.posterUrl = poster; this.year = year; this.plot = plot; this.tags = tags; this.actors = actors; this.recommendations = recommendations
                }
            } else {
                val dataUrl = document.selectFirst("a:contains(Xem Phim)")?.attr("href") ?: url
                newMovieLoadResponse(title, url, TvType.Movie, dataUrl) {
                    this.posterUrl = poster; this.year = year; this.plot = plot; this.tags = tags; this.actors = actors; this.recommendations = recommendations
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "load error: ${e.localizedMessage}", e)
            throw ErrorLoadingException("Không thể tải thông tin phim. Vui lòng thử lại.")
        }
    }

    override suspend fun loadLinks(data: String, isCasting: Boolean, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit): Boolean {
        try {
            Log.e(TAG, "loadLinks started for url: $data")
            val watchPageDoc = app.get(data, interceptor = cfInterceptor).document

            Log.e(TAG, "Step 1: Finding packed script...")
            val packedScript = watchPageDoc.select("script").lastOrNull { it.data().contains("eval(function(w,i,s,e)") }?.data()
                ?: throw ErrorLoadingException("Không tìm thấy script video.")
            Log.e(TAG, "Step 1: Found packed script.")

            Log.e(TAG, "Step 2: Unpacking script...")
            val unpackedScript = UnwiseHelper.unwiseProcess(packedScript)
            val iframeSrc = unpackedScript.substringAfter("?link=").substringBefore("\"")
            if (!iframeSrc.startsWith("https://play.plhqtvhay.xyz/")) throw ErrorLoadingException("Link video không được hỗ trợ.")
            Log.e(TAG, "Step 2: Got iframeSrc: $iframeSrc")

            val baseUrl = getBaseUrl(data)
            val iframeDoc = app.get(iframeSrc, interceptor = cfInterceptor, referer = "$baseUrl/").document

            Log.e(TAG, "Step 3: Finding encrypted data script...")
            val iframeScript = iframeDoc.select("script").firstOrNull { it.data().contains("const idfile_enc = \"") }?.data()
                ?: throw ErrorLoadingException("Không tìm thấy script chứa dữ liệu mã hoá.")

            val idfileEnc = iframeScript.substringAfter("const idfile_enc = \"").substringBefore("\";")
            val idUserEnc = iframeScript.substringAfter("const idUser_enc = \"").substringBefore("\";")
            val domainApi = iframeScript.substringAfter("const DOMAIN_API = '").substringBefore("';")
            Log.e(TAG, "Step 3: Found encrypted data. API domain: $domainApi")

            val publicIp = app.get("https://api.ipify.org/").text

            Log.e(TAG, "Step 4: Decrypting IDs...")
            val idfile = Crypto.decrypt(Base64.getEncoder().encodeToString(hexStringToByteArray(idfileEnc)), DECRYPTION_KEY_1)
            val iduser = Crypto.decrypt(Base64.getEncoder().encodeToString(hexStringToByteArray(idUserEnc)), DECRYPTION_KEY_2)
            Log.e(TAG, "Step 4: Decrypted IDs successfully.")

            Log.e(TAG, "Step 5: Building and sending payload...")
            // Create the payload object with the corrected structure and default values from Java files
            val payload = Payload(
                idfile = idfile,
                iduser = iduser,
                domain_play = baseUrl,
                ip_clien = publicIp,
                time_request = Instant.now().toEpochMilli().toString(),
                jwplayer = JWPlayer(
                    Browser = JWPlayerBrowser(
                        androidNative = true,
                        chrome = true,
                        edge = false,
                        facebook = false,
                        firefox = false,
                        ie = false,
                        msie = false,
                        safari = false,
                        version = JWPlayerBrowserVersion(version = "129.0", major = 129, minor = 0)
                    ),
                    OS = JWPlayerOS(
                        android = true,
                        iOS = false,
                        mobile = true,
                        mac = false,
                        iPad = false,
                        iPhone = false,
                        windows = false,
                        tizen = false,
                        tizenApp = false,
                        version = JWPlayerOSVersion(version = "10.0", major = 10, minor = 0)
                    ),
                    Features = JWPlayerFeatures(
                        iframe = true,
                        passiveEvents = true,
                        backgroundLoading = true
                    )
                )
            )

            val payloadJson = com.google.gson.Gson().toJson(payload)
            val encryptedPayloadHex = Crypto.encrypt(payloadJson, ENCRYPTION_KEY)
            val encryptedData = String(Base64.getDecoder().decode(encryptedPayloadHex), Charsets.UTF_8)
            val hash = md5Hash(encryptedData + MD5_SALT)

            val headers = mapOf(
                "Origin" to "https://play.plhqtvhay.xyz",
                "Referer" to "https://play.plhqtvhay.xyz/"
            )

            val response = app.post(
                url = "$domainApi/playiframe",
                headers = headers,
                data = mapOf("data" to "$encryptedData|$hash")
            )
            val responseText = response.text
            Log.e(TAG, "RAW API Response Text: $responseText")

            val apiResponse = response.parsed<ApiResponse>()
            Log.e(TAG, "Parsed API response object: $apiResponse")

            if (apiResponse.status == 1 && apiResponse.type == "url-blob-tvhayv1" && apiResponse.data != null) {
                Log.e(TAG, "Step 6: Decrypting M3U8 URL...")
                val decryptedDataB64 = Base64.getEncoder().encodeToString(hexStringToByteArray(apiResponse.data))
                val m3u8Url = Crypto.decrypt(decryptedDataB64, FINAL_M3U8_DECRYPTION_KEY)
                Log.e(TAG, "Step 6: Got M3U8 URL: $m3u8Url")

                callback(
                    ExtractorLink(
                        source = this.name, name = "S.PRO", url = m3u8Url, referer = "$baseUrl/", quality = Qualities.P720.value,
                        type = ExtractorLinkType.M3U8
                    )
                )
                return true
            } else {
                throw ErrorLoadingException("API không trả về link video hợp lệ. Phản hồi từ server: $responseText")
            }
        } catch (e: Exception) {
            Log.e(TAG, "loadLinks error for url $data", e)
            if (e is ErrorLoadingException) {
                throw e
            } else {
                throw ErrorLoadingException("Không thể tải link phim. Chi tiết: ${e.message}")
            }
        }
    }

    override fun getVideoInterceptor(link: ExtractorLink): Interceptor? {
        return object : Interceptor {
            override fun intercept(chain: Interceptor.Chain): Response {
                val request = chain.request()
                if (request.url.toString().contains("plhqtvhay") && !request.url.toString().contains("/m3u8")) {
                    return chain.proceed(
                        request.newBuilder()
                            .removeHeader("Host")
                            .header("Referer", "https://play.plhqtvhay.xyz/")
                            .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/116.0.0.0 Safari/537.36")
                            .build()
                    )
                }
                return chain.proceed(request)
            }
        }
    }
}
