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
import java.util.concurrent.atomic.AtomicBoolean
import android.util.Log

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
    
    // Helper classes for JSON parsing
    private data class ApiResponse(val status: Int?, val type: String?, val data: String)
    private data class Payload(val idfile: String, val iduser: String, val refer: String, val type: String, val user_ip: String, val time: String)

    // Crypto functions
    private fun cryptoHandler(data: String, key: String, decrypt: Boolean = false): String? {
        return try {
            val keyBytes = key.toByteArray(Charsets.UTF_8)
            val skey = SecretKeySpec(keyBytes, "AES")
            val iv = IvParameterSpec(keyBytes)
            val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
            if (decrypt) {
                cipher.init(Cipher.DECRYPT_MODE, skey, iv)
                String(cipher.doFinal(Base64.getDecoder().decode(data)))
            } else {
                cipher.init(Cipher.ENCRYPT_MODE, skey, iv)
                Base64.getEncoder().encodeToString(cipher.doFinal(data.toByteArray(Charsets.UTF_8)))
            }
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }
    
    // Deobfuscator for the site's specific JS packer
    private fun deobfuscateScript(packedScript: String): String? {
        try {
            val regex = Regex("""eval\(function\(w,i,s,e\)\{.*\}\('(.+)',\s*'(.+)',\s*'(.+)',\s*'(.+)'\)\)""")
            val (w, i, s, _) = regex.find(packedScript)?.destructured ?: return null

            val ll1l = mutableListOf<Char>()
            val l1lI = mutableListOf<Char>()

            var wIdx = 0
            var iIdx = 0
            var sIdx = 0

            while (true) {
                if (wIdx < 5) l1lI.add(w[wIdx]) else if (wIdx < w.length) ll1l.add(w[wIdx])
                wIdx++
                if (iIdx < 5) l1lI.add(i[iIdx]) else if (iIdx < i.length) ll1l.add(i[iIdx])
                iIdx++
                if (sIdx < 5) l1lI.add(s[sIdx]) else if (sIdx < s.length) ll1l.add(s[sIdx])
                sIdx++
                if (w.length + i.length + s.length == ll1l.size + l1lI.size) break
            }

            val lI1l = ll1l.joinToString("")
            val I1lI = l1lI.joinToString("")
            var ll1IIdx = 0
            val l1ll = mutableListOf<Char>()

            var lIllIdx = 0
            while (lIllIdx < ll1l.size) {
                var ll11 = -1
                if (I1lI[ll1IIdx].code % 2 != 0) ll11 = 1
                val charCode = lI1l.substring(lIllIdx, lIllIdx + 2).toLong(36).toInt() - ll11
                l1ll.add(charCode.toChar())
                ll1IIdx++
                if (ll1IIdx >= l1lI.length) ll1IIdx = 0
                lIllIdx += 2
            }
            return l1ll.joinToString("")
        } catch (e: Exception) {
            return null
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val logs = mutableListOf("--- TVPhim Debug Log ---")
        val isSuccessful = AtomicBoolean(false)
        val document = app.get(data, interceptor = cfInterceptor).document
        val servers = document.select("ul.episodelistsv a")
        logs.add("Found ${servers.size} servers.")
        if (servers.isEmpty()) {
            throw Exception("Không tìm thấy server nào.")
        }

        servers.apmap { server ->
            if (isSuccessful.get()) return@apmap
            val serverName = server.attr("title")
            try {
                val serverUrl = server.attr("href")
                logs.add("Processing Server: '$serverName' ($serverUrl)")
                val serverDoc = app.get(serverUrl, referer = data).document

                val packedScript = serverDoc.selectFirst("script:containsData(eval(function(w,i,s,e))")?.data()
                    ?: throw Exception("Không tìm thấy script của trình phát video.")
                val deobfuscated = deobfuscateScript(packedScript)
                    ?: throw Exception("Giải mã script thất bại.")
                val iframeUrl = Regex("""src\s*=\s*['"]([^'"]+)""").find(deobfuscated)?.groupValues?.get(1)
                    ?: throw Exception("Không tìm thấy iframe URL sau khi giải mã.")
                logs.add("-> Found iframe URL: $iframeUrl")

                if ("plhqtvhay" in iframeUrl) {
                    val iframeDoc = app.get(iframeUrl, referer = serverUrl).document
                    val script = iframeDoc.selectFirst("script:containsData(const idfile_enc)")?.data()
                        ?: throw Exception("Không tìm thấy script chứa dữ liệu mã hóa.")
                    logs.add("-> Found script with encrypted data.")
                    
                    val idFileEnc = Regex("""const idfile_enc = "([^"]+)";""").find(script)?.groupValues?.get(1)
                    val idUserEnc = Regex("""const idUser_enc = "([^"]+)";""").find(script)?.groupValues?.get(1)
                    val domainApi = Regex("""const DOMAIN_API = '([^']+)';""").find(script)?.groupValues?.get(1)
                    logs.add("-> Extracted Encrypted Data -> idfile: $idFileEnc, iduser: $idUserEnc, domain: $domainApi")
                    if (idFileEnc == null || idUserEnc == null || domainApi == null) {
                        throw Exception("Không thể trích xuất dữ liệu mã hóa.")
                    }

                    val idFile = cryptoHandler(idFileEnc, "jcLycoRJT6OWjoWspgLMOZwS3aSS0lEn", true)
                        ?: throw Exception("Giải mã idfile thất bại.")
                    val idUser = cryptoHandler(idUserEnc, "PZZ3J3LDbLT0GY7qSA5wW5vchqgpO36O", true)
                        ?: throw Exception("Giải mã iduser thất bại.")
                    logs.add("-> Decrypted Data -> idfile: $idFile, iduser: $idUser")
                    
                    val ip = app.get("https://api.ipify.org/").text
                    val timestamp = System.currentTimeMillis().toString()
                    val payload = Payload(idFile, idUser, serverUrl, "noplf", ip, timestamp)
                    val encryptedPayload = cryptoHandler(payload.toJson(), "vlVbUQhkOhoSfyteyzGeeDzU0BHoeTyZ")
                        ?: throw Exception("Mã hóa payload thất bại.")
                    val hash = (encryptedPayload + "KRWN3AdgmxEMcd2vLN1ju9qKe8Feco5h").md5()

                    logs.add("-> POSTing to: $domainApi/playiframe")
                    val apiResponse = app.post(
                        "$domainApi/playiframe",
                        data = mapOf("data" to "$encryptedPayload|$hash"),
                        referer = iframeUrl
                    ).parsedSafe<ApiResponse>()
                        ?: throw Exception("Phản hồi API không hợp lệ.")
                    logs.add("-> API Response: ${apiResponse.toJson()}")

                    if (apiResponse.status == 1 && apiResponse.type == "url-m3u8-encv1") {
                        val finalUrl = cryptoHandler(apiResponse.data.replace("\"", ""), "oJwmvmVBajMaRCTklxbfjavpQO7SZpsL", true)
                            ?: throw Exception("Giải mã link M3U8 cuối cùng thất bại.")
                        logs.add("-> Final Decrypted URL: $finalUrl")
                        
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
                        isSuccessful.set(true)
                    } else {
                        throw Exception("API trả về lỗi hoặc trạng thái không thành công.")
                    }
                } else {
                    logs.add("-> Error: Trình phát video từ '$iframeUrl' không được hỗ trợ.")
                }
            } catch (e: Exception) {
                // Ghi lại lỗi chi tiết vào log của ứng dụng và log thu thập
                Log.e("TvPhimProvider", "Lỗi xử lý server '$serverName'", e)
                logs.add("-> Lỗi xử lý server '$serverName': ${e.message}")
            }
        }
        
        if (!isSuccessful.get()) {
            throw Exception("Đã thử tất cả các server nhưng không thể lấy được link video.\n\n--- Log Chi Tiết ---\n${logs.joinToString("\n")}")
        }
        
        return true
    }
}
