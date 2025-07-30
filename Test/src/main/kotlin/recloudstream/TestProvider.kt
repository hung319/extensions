// TvPhimProvider.kt

package recloudstream

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Element
import com.lagradost.cloudstream3.network.CloudflareKiller
import com.lagradost.cloudstream3.ActorData
import com.lagradost.cloudstream3.utils.AppUtils.toJson
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec
import java.util.Base64
import java.math.BigInteger
import java.security.MessageDigest
import android.util.Log
import kotlin.text.Charsets

// Helper function to calculate MD5 hash
private fun String.md5(): String {
    val md = MessageDigest.getInstance("MD5")
    return BigInteger(1, md.digest(toByteArray())).toString(16).padStart(32, '0')
}

// Helper function to convert Hex String to Byte Array
private fun String.hexStringToByteArray(): ByteArray {
    check(length % 2 == 0) { "Must have an even length" }
    return chunked(2)
        .map { it.toInt(16).toByte() }
        .toByteArray()
}

class TvPhimProvider : MainAPI() {
    override var mainUrl = "https://tvphim.bid"
    override var name = "TVPhim"
    override val hasMainPage = true
    override var lang = "vi"
    override val supportedTypes = setOf(
        TvType.Movie,
        TvType.TvSeries
    )

    private val cfInterceptor = CloudflareKiller()

    private fun Element.toSearchResponse(): SearchResponse? {
        val title = this.selectFirst("div.h-14 span")?.text()?.trim() ?: return null
        val href = this.selectFirst("a")?.attr("href") ?: return null
        val posterUrl = this.selectFirst("img")?.attr("src")
        val year = this.selectFirst("div.year")?.text()?.toIntOrNull()

        val statusText = this.selectFirst("span.bg-red-800")?.text() ?: ""
        val isTvSeries = "/" in statusText || "tập" in statusText.lowercase()

        return if (isTvSeries) {
            newTvSeriesSearchResponse(title, href) {
                this.posterUrl = posterUrl
                this.year = year
            }
        } else {
            newMovieSearchResponse(title, href) {
                this.posterUrl = posterUrl
                this.year = year
            }
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
        val home = document.select("div.grid div.relative").mapNotNull {
            it.toSearchResponse()
        }
        return newHomePageResponse(request.name, home)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val searchUrl = "$mainUrl/search/$query/"
        val document = app.get(searchUrl, interceptor = cfInterceptor).document
        return document.select("div.grid div.relative").mapNotNull {
            it.toSearchResponse()
        }
    }

    override suspend fun load(url: String): LoadResponse {
        val document = app.get(url, interceptor = cfInterceptor).document
        val title = document.selectFirst("h1[itemprop=name]")?.text()?.trim()
            ?: document.selectFirst("h1.entry-title")?.text()?.trim() ?: "Không tìm thấy tiêu đề"
        val poster = document.selectFirst("img[itemprop=image]")?.attr("src")
        val year = document.selectFirst("span.text-gray-400:contains(Năm Sản Xuất) + span")?.text()?.toIntOrNull()
        var plot = document.selectFirst("div[itemprop=description] p")?.text()
            ?: document.selectFirst("div.entry-content p")?.text()
        val votes = document.selectFirst("span.liked")?.text()?.trim()?.toIntOrNull()
        if (votes != null) {
            plot = "$plot\n\n❤️ Yêu thích: $votes"
        }
        val tags = document.select("span.text-gray-400:contains(Thể loại) a").map { it.text() }
        val actors = document.select("span.text-gray-400:contains(Diễn viên) a").map { it.text() }
        val recommendations = document.select("div.mt-2:has(span:contains(Cùng Series)) a").mapNotNull {
            val recTitle = it.selectFirst("span")?.text()
            val recHref = it.attr("href")
            val recPoster = it.selectFirst("img")?.attr("src")
            if (recTitle != null && recHref.isNotBlank()) {
                newMovieSearchResponse(recTitle, recHref) { this.posterUrl = recPoster }
            } else null
        }

        val watchUrl = document.selectFirst("a:contains(Xem Phim)")?.attr("href")
        if (watchUrl == null || !watchUrl.startsWith("http")) {
            return newMovieLoadResponse(title, url, TvType.Movie, url) {
                this.posterUrl = poster; this.year = year; this.plot = plot; this.tags = tags
                this.actors = actors.map { ActorData(Actor(it)) }; this.recommendations = recommendations; this.comingSoon = true
            }
        }

        val status = document.selectFirst("span:contains(Tình Trạng:) + span")?.text()?.lowercase() ?: ""
        val isTvSeries = status.contains("/") || status.contains("tập")
        if (isTvSeries) {
            val watchDocument = app.get(watchUrl, interceptor = cfInterceptor).document
            val episodes = watchDocument.select("div.episodelist a").mapNotNull { ep ->
                val epName = ep.attr("title").ifBlank { ep.text() }
                val epUrl = ep.attr("href")
                if (epUrl.isBlank()) return@mapNotNull null
                newEpisode(epUrl) { name = epName }
            }
            
            return newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
                this.posterUrl = poster; this.year = year; this.plot = plot; this.tags = tags
                this.actors = actors.map { ActorData(Actor(it)) }; this.recommendations = recommendations
            }
        } else {
            return newMovieLoadResponse(title, url, TvType.Movie, watchUrl) {
                this.posterUrl = poster; this.year = year; this.plot = plot; this.tags = tags
                this.actors = actors.map { ActorData(Actor(it)) }; this.recommendations = recommendations
            }
        }
    }
    
    private data class ApiResponse(val status: Int?, val type: String?, val data: String)
    private data class Payload(val idfile: String, val iduser: String, val domain_play: String, val ip_clien: String, val time_request: String)
    
    private object Util {
        fun decrypt(data: String, key: String): String? = CryptoJS.decrypt(key, Base64.getEncoder().encodeToString(data.hexStringToByteArray()))
    }

    private object CryptoJS {
        private fun evpkdf(password: ByteArray, keySize: Int, ivSize: Int, salt: ByteArray): Pair<ByteArray, ByteArray> {
            val keySizeWords = keySize / 32; val ivSizeWords = ivSize / 32
            val totalWords = keySizeWords + ivSizeWords
            val resultBytes = ByteArray(totalWords * 4)
            var currentPos = 0
            var derivedBytes = ByteArray(0)
            val md5 = MessageDigest.getInstance("MD5")
            while (currentPos < resultBytes.size) {
                md5.update(derivedBytes); md5.update(password); md5.update(salt)
                derivedBytes = md5.digest()
                System.arraycopy(derivedBytes, 0, resultBytes, currentPos, minOf(derivedBytes.size, resultBytes.size - currentPos))
                currentPos += derivedBytes.size
            }
            return Pair(resultBytes.copyOfRange(0, keySizeWords * 4), resultBytes.copyOfRange(keySizeWords * 4, totalWords * 4))
        }

        fun decrypt(password: String, cipherTextB64: String): String? {
            return try {
                val ctBytes = Base64.getDecoder().decode(cipherTextB64)
                val salt = ctBytes.copyOfRange(8, 16)
                val ciphertextBytes = ctBytes.copyOfRange(16, ctBytes.size)
                val (key, iv) = evpkdf(password.toByteArray(Charsets.UTF_8), 256, 128, salt)
                if (iv.size != 16) throw Exception("Invalid IV size: ${iv.size}")
                val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
                cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(iv))
                String(cipher.doFinal(ciphertextBytes))
            } catch (e: Exception) { null }
        }
    }

    private object UnwiseHelper {
        private fun unwise1(w: String): String {
            val sb = StringBuilder()
            var i = 0
            while (i < w.length) {
                val i2 = i + 2
                sb.append(w.substring(i, i2).toInt(36).toChar())
                i = i2
            }
            return sb.toString()
        }

        private fun unwise(w: String, i: String, s: String, e: String, wi: Int, ii: Int, si: Int, ei: Int): String {
            val sb = StringBuilder(); val sb2 = StringBuilder()
            var i5 = 0; var i6 = 0; var i7 = 0; var i8 = 0
            do {
                if (w.isNotEmpty()) {
                    if (i5 < wi) sb2.append(w[i5]) else if (i5 < w.length) sb.append(w[i5])
                    i5++
                }
                if (i.isNotEmpty()) {
                    if (i6 < ii) sb2.append(i[i6]) else if (i6 < i.length) sb.append(i[i6])
                    i6++
                }
                if (s.isNotEmpty()) {
                    if (i7 < si) sb2.append(s[i7]) else if (i7 < s.length) sb.append(s[i7])
                    i7++
                }
                if (e.isNotEmpty()) {
                    if (i8 < ei) sb2.append(e[i8]) else if (i8 < e.length) sb.append(e[i8])
                    i8++
                }
            } while (w.length + i.length + s.length + e.length != sb.length + sb2.length)
            
            val sb3 = StringBuilder()
            var i13 = 0; var i14 = 0
            while (i13 < sb.length) {
                val i15 = i13 + 2
                val parseInt = (sb.substring(i13, i15).toInt(36)) - (if (sb2[i14].code % 2 != 0) 1 else -1)
                sb3.append(parseInt.toChar())
                i14++
                if (i14 >= sb2.length) i14 = 0
                i13 = i15
            }
            return sb3.toString()
        }

        fun unwiseProcess(script: String): String {
            var processedScript = script
            while (true) {
                val match = Regex(";?eval\\s*\\(\\s*function\\s*\\(\\s*w\\s*,\\s*i\\s*,\\s*s\\s*,\\s*e\\s*\\).*?}\\s*\\((.*?)\\)\\s*\\)(?:;|)").find(processedScript) ?: return processedScript
                val fullMatch = match.value
                val argsBlock = match.groupValues.getOrNull(1) ?: ""
                val args = Regex("['\"](.*?)['\"]").findAll(argsBlock).map { it.groupValues[1] }.toList()
                if (args.size < 4) {
                    processedScript = processedScript.replace(fullMatch, ""); continue
                }

                val deobfuscated = if (!fullMatch.contains("while")) {
                    unwise1(args[0])
                } else {
                    val whileBlock = Regex("while\\((.*?)\\)").find(fullMatch)?.groupValues?.get(1) ?: ""
                    val limits = Regex("if\\s*\\(\\s*\\w*\\s*<\\s*(\\d+)\\)").findAll(whileBlock).map { it.groupValues[1].toInt() }.toList()
                    if (limits.size >= 4) {
                        unwise(args[0], args[1], args[2], args[3], limits[0], limits[1], limits[2], limits[3])
                    } else { "" }
                }
                processedScript = processedScript.replace(fullMatch, deobfuscated)
            }
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        try {
            val document = app.get(data, interceptor = cfInterceptor).document
            
            val server = document.select("ul.episodelistsv a").find { 
                it.attr("title").equals("Server S.PRO", ignoreCase = true) 
            } ?: throw Exception("Không tìm thấy Server S.PRO.")

            val serverName = server.attr("title")
            val serverUrl = server.attr("href")
            val serverDoc = app.get(serverUrl, referer = data).document

            var iframeUrl: String?

            val scriptContent = serverDoc.select("script").find { it.data().contains("eval(function(w,i,s,e)") || it.data().contains("setAttribute(\"src\"") }?.data()
                ?: throw Exception("Không tìm thấy script của trình phát video.")
            
            val deobfuscated = UnwiseHelper.unwiseProcess(scriptContent)
            Log.d("TvPhimProvider", "Deobfuscated Script Content: $deobfuscated")
            
            val setAttrMatch = Regex("""setAttribute\(\s*["']src["']\s*,\s*["'](.*?)["']\)""").find(deobfuscated)
            if (setAttrMatch != null) {
                val rawUrl = setAttrMatch.groupValues[1].replace(" ", "")
                iframeUrl = Regex("""link=([^'"]+)""").find(rawUrl)?.groupValues?.get(1)
            } else {
                iframeUrl = Regex("""src\s*=\s*['"]([^'"]+)""").find(deobfuscated)?.groupValues?.get(1)
            }

            if (iframeUrl.isNullOrBlank()) {
                throw Exception("Không tìm thấy iframe URL sau khi giải mã. Nội dung đã giải mã: $deobfuscated")
            }

            if ("plhqtvhay" in iframeUrl) {
                val iframeDoc = app.get(iframeUrl, referer = serverUrl).document
                val script = iframeDoc.selectFirst("script:containsData(const idfile_enc)")?.data()
                    ?: throw Exception("Không tìm thấy script chứa dữ liệu mã hóa.")

                val idFileEnc = Regex("""const idfile_enc = "([^"]+)";""").find(script)?.groupValues?.get(1)
                val idUserEnc = Regex("""const idUser_enc = "([^"]+)";""").find(script)?.groupValues?.get(1)
                val domainApi = Regex("""const DOMAIN_API = '([^']+)';""").find(script)?.groupValues?.get(1)
                if (idFileEnc == null || idUserEnc == null || domainApi == null) throw Exception("Không thể trích xuất dữ liệu mã hóa.")

                val idFile = Util.decrypt(idFileEnc, "jcLycoRJT6OWjoWspgLMOZwS3aSS0lEn")
                    ?: throw Exception("Giải mã idfile thất bại. Dữ liệu mã hóa: $idFileEnc")
                val idUser = Util.decrypt(idUserEnc, "PZZ3J3LDbLT0GY7qSA5wW5vchqgpO36O")
                    ?: throw Exception("Giải mã iduser thất bại. Dữ liệu mã hóa: $idUserEnc")
                
                val ip = app.get("https://api.ipify.org/").text
                val timestamp = System.currentTimeMillis().toString()
                val payload = Payload(idFile, idUser, serverUrl, ip, timestamp)
                
                val keyBytes = "vlVbUQhkOhoSfyteyzGeeDzU0BHoeTyZ".toByteArray(Charsets.UTF_8)
                val skey = SecretKeySpec(keyBytes, "AES")
                val iv = IvParameterSpec(keyBytes.copyOfRange(0, 16))
                val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
                cipher.init(Cipher.ENCRYPT_MODE, skey, iv)
                val encryptedPayload = Base64.getEncoder().encodeToString(cipher.doFinal(payload.toJson().toByteArray(Charsets.UTF_8)))
                
                val hash = (encryptedPayload + "KRWN3AdgmxEMcd2vLN1ju9qKe8Feco5h").md5()

                val apiResponse = app.post(
                    "$domainApi/playiframe",
                    data = mapOf("data" to "$encryptedPayload|$hash"),
                    referer = iframeUrl
                ).parsedSafe<ApiResponse>()
                    ?: throw Exception("Phản hồi API không hợp lệ.")

                if (apiResponse.status == 1 && apiResponse.type == "url-m3u8-encv1") {
                    val finalUrl = Util.decrypt(apiResponse.data.replace("\"", ""), "oJwmvmVBajMaRCTklxbfjavpQO7SZpsL")
                        ?: throw Exception("Giải mã link M3U8 cuối cùng thất bại.")
                    
                    callback.invoke(
                        ExtractorLink(this.name, serverName, finalUrl, iframeUrl, Qualities.Unknown.value, type = ExtractorLinkType.M3U8)
                    )
                    return true
                } else {
                    throw Exception("API trả về lỗi hoặc trạng thái không thành công: ${apiResponse.toJson()}")
                }
            } else {
                throw Exception("Trình phát video từ '$iframeUrl' không được hỗ trợ.")
            }
        } catch (e: Exception) {
            Log.e("TvPhimProvider", "Lỗi xử lý server '$serverName'", e)
            throw e
        }
    }
}
