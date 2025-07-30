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

class TvPhimProvider : MainAPI() {
    override var mainUrl = "https://tvphim.bid"
    override var name = "TVPhim"
    override val hasMainPage = true
    override var lang = "vi"
    override val supportedTypes = setOf(
        TvType.Movie,
        TvType.TvSeries
    )

    // Interceptor to handle Cloudflare
    private val cfInterceptor = CloudflareKiller()

    // Helper function to parse search results from HTML elements
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

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {
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
                newMovieSearchResponse(recTitle, recHref) {
                    this.posterUrl = recPoster
                }
            } else null
        }

        val watchUrl = document.selectFirst("a:contains(Xem Phim)")?.attr("href")
        if (watchUrl == null || !watchUrl.startsWith("http")) {
            return newMovieLoadResponse(title, url, TvType.Movie, url) {
                this.posterUrl = poster
                this.year = year
                this.plot = plot
                this.tags = tags
                this.actors = actors.map { ActorData(Actor(it)) }
                this.recommendations = recommendations
                this.comingSoon = true
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
                newEpisode(epUrl) {
                    name = epName
                }
            }
            
            return newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
                this.posterUrl = poster
                this.year = year
                this.plot = plot
                this.tags = tags
                this.actors = actors.map { ActorData(Actor(it)) }
                this.recommendations = recommendations
            }
        } else {
            return newMovieLoadResponse(title, url, TvType.Movie, watchUrl) {
                this.posterUrl = poster
                this.year = year
                this.plot = plot
                this.tags = tags
                this.actors = actors.map { ActorData(Actor(it)) }
                this.recommendations = recommendations
            }
        }
    }
    
    // Data classes based on decompiled Java files
    private data class ApiResponse(val status: Int?, val type: String?, val data: String)
    private data class Payload(val idfile: String, val iduser: String, val domain_play: String, val ip_clien: String, val time_request: String)
    
    // **NEW**: Accurate CryptoJS implementation based on provided files
    private object CryptoJS {
        private fun evpkdf(password: ByteArray, keySize: Int, ivSize: Int, salt: ByteArray): Pair<ByteArray, ByteArray> {
            val keySizeWords = keySize / 32
            val ivSizeWords = ivSize / 32
            val totalWords = keySizeWords + ivSizeWords
            val resultBytes = ByteArray(totalWords * 4)
            var currentPos = 0
            var derivedBytes = ByteArray(0)
            val md5 = MessageDigest.getInstance("MD5")

            while (currentPos < totalWords * 4) {
                md5.update(derivedBytes)
                md5.update(password)
                md5.update(salt)
                derivedBytes = md5.digest()
                System.arraycopy(derivedBytes, 0, resultBytes, currentPos, minOf(derivedBytes.size, resultBytes.size - currentPos))
                currentPos += derivedBytes.size
            }
            return Pair(resultBytes.copyOfRange(0, keySizeWords * 4), resultBytes.copyOfRange(keySizeWords * 4, totalWords * 4))
        }

        fun decrypt(password: String, cipherText: String): String? {
            return try {
                val ctBytes = Base64.getDecoder().decode(cipherText)
                val salt = ctBytes.copyOfRange(8, 16)
                val ciphertextBytes = ctBytes.copyOfRange(16, ctBytes.size)
                val (key, iv) = evpkdf(password.toByteArray(Charsets.UTF_8), 256, 128, salt)
                val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
                cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(iv))
                String(cipher.doFinal(ciphertextBytes))
            } catch (e: Exception) {
                e.printStackTrace()
                null
            }
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val document = app.get(data, interceptor = cfInterceptor).document
        
        val server = document.select("ul.episodelistsv a").find { 
            it.attr("title").equals("Server S.PRO", ignoreCase = true) 
        } ?: throw Exception("Không tìm thấy Server S.PRO.")

        val serverName = server.attr("title")
        try {
            val serverUrl = server.attr("href")
            val serverDoc = app.get(serverUrl, referer = data).document

            val iframeUrl = serverDoc.selectFirst("iframe")?.attr("src")
                ?: throw Exception("Không tìm thấy iframe.")

            if ("plhqtvhay" in iframeUrl) {
                val iframeDoc = app.get(iframeUrl, referer = serverUrl).document
                val script = iframeDoc.selectFirst("script:containsData(const idfile_enc)")?.data()
                    ?: throw Exception("Không tìm thấy script chứa dữ liệu mã hóa.")

                val idFileEnc = Regex("""const idfile_enc = "([^"]+)";""").find(script)?.groupValues?.get(1)
                val idUserEnc = Regex("""const idUser_enc = "([^"]+)";""").find(script)?.groupValues?.get(1)
                val domainApi = Regex("""const DOMAIN_API = '([^']+)';""").find(script)?.groupValues?.get(1)
                if (idFileEnc == null || idUserEnc == null || domainApi == null) {
                    throw Exception("Không thể trích xuất dữ liệu mã hóa.")
                }

                // **FIXED**: Using the new accurate CryptoJS decrypt
                val idFile = CryptoJS.decrypt("jcLycoRJT6OWjoWspgLMOZwS3aSS0lEn", idFileEnc)
                    ?: throw Exception("Giải mã idfile thất bại.")
                val idUser = CryptoJS.decrypt("PZZ3J3LDbLT0GY7qSA5wW5vchqgpO36O", idUserEnc)
                    ?: throw Exception("Giải mã iduser thất bại.")
                
                val ip = app.get("https://api.ipify.org/").text
                val timestamp = System.currentTimeMillis().toString()
                
                val payload = Payload(idFile, idUser, serverUrl, ip, timestamp)
                val payloadJson = payload.toJson()

                // The site's encryption for the payload is simpler than the one for the final link
                val encryptedPayload = cryptoHandler(payloadJson, "vlVbUQhkOhoSfyteyzGeeDzU0BHoeTyZ")
                    ?: throw Exception("Mã hóa payload thất bại.")
                val hash = (encryptedPayload + "KRWN3AdgmxEMcd2vLN1ju9qKe8Feco5h").md5()

                val apiResponse = app.post(
                    "$domainApi/playiframe",
                    data = mapOf("data" to "$encryptedPayload|$hash"),
                    referer = iframeUrl
                ).parsedSafe<ApiResponse>()
                    ?: throw Exception("Phản hồi API không hợp lệ.")

                if (apiResponse.status == 1 && apiResponse.type == "url-m3u8-encv1") {
                    val finalUrl = CryptoJS.decrypt("oJwmvmVBajMaRCTklxbfjavpQO7SZpsL", apiResponse.data.replace("\"", ""))
                        ?: throw Exception("Giải mã link M3U8 cuối cùng thất bại.")
                    
                    callback.invoke(
                        ExtractorLink(
                            source = this.name,
                            name = serverName,
                            url = finalUrl,
                            referer = iframeUrl,
                            quality = Qualities.Unknown.value,
                            type = ExtractorLinkType.M3U8
                        )
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
