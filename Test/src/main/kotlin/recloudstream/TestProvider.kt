/**
 * Tệp Provider cho AnimeTVN
 * Phiên bản được cải tiến và tích hợp logic giải mã phức tạp.
 */
package recloudstream

// ===== CÁC THƯ VIỆN CẦN THIẾT =====
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import org.jsoup.nodes.Element
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec
import java.security.MessageDigest
import java.util.Base64

/**
 * Đây là lớp chính của plugin.
 * @author Coder (YourName)
 */
class AnimeTVNProvider : MainAPI() {
    companion object {
        // Sử dụng `const val` cho các hằng số để tối ưu hóa hiệu suất
        private const val MAIN_URL = "https://animetvn4.com"
    }

    override var mainUrl = MAIN_URL
    override var name = "AnimeTVN"
    override val hasMainPage = true
    override var lang = "vi"
    override val hasDownloadSupport = true

    override val supportedTypes = setOf(
        TvType.Anime,
        TvType.AnimeMovie,
        TvType.OVA,
        TvType.Movie,
        TvType.TvSeries,
        TvType.Cartoon
    )

    // ===================================================
    // ===== CÁC HÀM CHÍNH CỦA PROVIDER (TRANG CHỦ, TÌM KIẾM) =====
    // ===================================================

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val pages = listOf(
            Triple("/nhom/anime.html", "Anime Mới", TvType.Anime),
            Triple("/bang-xep-hang.html", "Bảng Xếp Hạng", TvType.Anime),
            Triple("/nhom/japanese-drama.html", "Live Action", TvType.TvSeries),
            Triple("/nhom/sieu-nhan.html", "Siêu Nhân", TvType.Cartoon),
            Triple("/nhom/cartoon.html", "Cartoon", TvType.Cartoon)
        )

        val all = coroutineScope {
            pages.map { (url, name, type) ->
                async {
                    try {
                        val pageUrl = "$mainUrl$url?page=$page"
                        val document = app.get(pageUrl).document

                        val home = if (name == "Bảng Xếp Hạng") {
                            document.select("ul.rank-film-list > li.item").mapNotNull { it.toSearchResult(type) }
                        } else {
                            document.select("div.film_item").mapNotNull { it.toSearchResult(type) }
                        }
                        
                        if (home.isNotEmpty()) HomePageList(name, home) else null
                    } catch (e: Exception) {
                        e.printStackTrace() 
                        null
                    }
                }
            }.mapNotNull { it.await() }
        }
        
        return HomePageResponse(all, all.any { it.items.size >= 20 })
    }

    private fun Element.toSearchResult(type: TvType): SearchResponse? {
        val linkElement = this.selectFirst("a") ?: return null
        val href = linkElement.attr("href")
        val fullHref = if (href.startsWith("http")) href else "$mainUrl$href"
        
        val title = this.selectFirst("h3.title, .name-vi")?.text() ?: return null
        val posterUrl = this.selectFirst("img.thumb")?.attr("src")

        return newAnimeSearchResponse(title, fullHref, type) {
            this.posterUrl = posterUrl
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val searchUrl = "$mainUrl/tim-kiem/${java.net.URLEncoder.encode(query, "utf-8")}.html"
        val document = app.get(searchUrl).document
        
        return document.select("div.film_item").mapNotNull {
            it.toSearchResult(TvType.Anime)
        }
    }
    
    // ===================================================
    // ===== HÀM LOAD (LẤY THÔNG TIN PHIM VÀ DANH SÁCH TẬP) =====
    // ===================================================

    override suspend fun load(url: String): LoadResponse? {
        val document = app.get(url).document
        val title = document.selectFirst("h2.name-vi")?.text() ?: return null
        val poster = document.selectFirst("div.small_img img")?.attr("src")
        val description = document.selectFirst("div#tab-film-content div.content")?.text()
        val genres = document.select("li.has-color:contains(Thể loại) a").map { it.text() }
        val watchPageUrl = document.selectFirst("a.btn.play-now")?.attr("href")

        val episodes = if (watchPageUrl != null) {
            val watchPageDocument = app.get(watchPageUrl).document
            val episodeElements = watchPageDocument.select("div.eplist a.tapphim")
            
            episodeElements.mapNotNull { ep ->
                val epUrl = ep.attr("href") ?: return@mapNotNull null
                val epText = ep.text().replace("_", ".")
                val epNum = "[0-9]+(\\.[0-9]+)?".toRegex().find(epText)?.value?.toFloatOrNull()
                
                epNum?.let { num ->
                    // Quan trọng: Truyền `epUrl` vào `newEpisode` để `loadLinks` có thể nhận được.
                    Pair(num, newEpisode(epUrl) {
                        this.name = "Tập $epText"
                    })
                }
            }
            .distinctBy { it.first }
            .sortedByDescending { it.first }
            .map { it.second }
        } else {
            emptyList()
        }

        val tvType = when {
            genres.any { it.equals("Live Action", true) || it.equals("Japanese Drama", true) } -> 
                if (episodes.size > 1) TvType.TvSeries else TvType.Movie
            genres.any { it.equals("Siêu Nhân", true) || it.equals("Tokusatsu", true) || it.equals("Cartoon", true) } -> 
                TvType.Cartoon
            else -> 
                if (episodes.isNotEmpty()) TvType.Anime else TvType.AnimeMovie
        }

        // Dùng newTvSeriesLoadResponse cho cả phim bộ và phim lẻ để xử lý linh hoạt
        return newTvSeriesLoadResponse(title, url, tvType, episodes) {
            this.posterUrl = poster
            this.plot = description
            this.tags = genres
            if (episodes.isEmpty()) {
                // Nếu là phim lẻ, dataUrl chính là trang xem phim để loadLinks có thể dùng
                this.dataUrl = watchPageUrl ?: url
            }
        }
    }

    // =========================================================================================
    // BẮT ĐẦU: TRIỂN KHAI LOGIC LOADLINKS PHỨC TẠP
    // Tái tạo lại quy trình mã hóa và gọi API của AnimeTVN
    // =========================================================================================

    private data class ServerLink(val id: String, val name: String, val link: String)
    private data class ServerListResponse(val links: List<ServerLink>)
    private data class IframeResponse(val link: String?)
    private data class FinalApiResponse(val status: Int, val type: String?, val data: String?)

    override suspend fun loadLinks(
        data: String, // URL của tập phim được truyền từ hàm `load`
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        try {
            // ----- BƯỚC 1: Lấy `epid` từ URL -----
            val epid = "f(\\d+)".toRegex().find(data)?.groupValues?.get(1) 
                ?: throw Exception("Không thể tìm thấy epid trong URL: $data")
            
            // ----- BƯỚC 2: Lấy danh sách Server -----
            val serverListUrl = "$mainUrl/ajax/getExtraLinks"
            val serverListResponse = app.post(
                serverListUrl,
                data = mapOf("epid" to epid),
                referer = data
            ).parsed<ServerListResponse>()

            val server = serverListResponse.links.firstOrNull { it.name.contains("TVN") } 
                ?: serverListResponse.links.firstOrNull() 
                ?: throw Exception("Không tìm thấy server nào khả dụng")

            // ----- BƯỚC 3: Lấy URL của Iframe -----
            val iframeApiResponse = app.post(
                "$mainUrl/ajax/getExtraLink",
                data = mapOf("id" to server.id, "link" to server.link),
                referer = data
            ).parsed<IframeResponse>()

            val iframeUrl = iframeApiResponse.link 
                ?: throw Exception("Không thể lấy được link iframe")

            // ----- BƯỚC 4: Tải Iframe và trích xuất dữ liệu -----
            val iframeDoc = app.get(iframeUrl).document.html()
            
            val idfileEnc = "idfile_enc\\s*:\\s*'([^']*)'".toRegex().find(iframeDoc)?.groupValues?.get(1)
                ?: throw Exception("Không tìm thấy idfile_enc")
            val idUserEnc = "idUser_enc\\s*:\\s*'([^']*)'".toRegex().find(iframeDoc)?.groupValues?.get(1)
                ?: throw Exception("Không tìm thấy idUser_enc")
            
            val key1 = "var\\s+ten_file\\s*=\\s*'([^']*)'".toRegex().find(iframeDoc)?.groupValues?.get(1)
                ?: throw Exception("Không tìm thấy key giải mã (ten_file)")

            val idfile = CryptoAES.decrypt(idfileEnc, key1)
            val idUser = CryptoAES.decrypt(idUserEnc, key1)
            
            if (idfile == null || idUser == null) throw Exception("Giải mã idfile/idUser thất bại")

            // ----- BƯỚC 5: Tạo Payload và gọi API cuối cùng -----
            val timestamp = System.currentTimeMillis()
            val payloadToEncrypt = "$idfile|$idUser|$timestamp"
            val key2 = "playhq@2023@"

            val encryptedPayload = CryptoAES.encrypt(payloadToEncrypt, key2)
                ?: throw Exception("Mã hóa payload thất bại")

            // Logic tạo chữ ký - cần xác minh từ JS nếu có lỗi
            val signatureKey = "x@2023@$key2&y@2024@$idfile"
            val signature = CryptoAES.md5(encryptedPayload + signatureKey)
            
            val finalPayload = "$encryptedPayload|$signature"

            val finalApiUrl = "https://api-play-151024.playhbq.xyz/api/tp1ts/playiframe"
            val finalApiResponse = app.post(
                finalApiUrl,
                headers = mapOf("Referer" to iframeUrl),
                data = mapOf("data" to finalPayload)
            ).parsed<FinalApiResponse>()

            if (finalApiResponse.status != 1 || finalApiResponse.data == null) {
                throw Exception("API cuối cùng trả về lỗi hoặc không có dữ liệu")
            }
            
            // ----- BƯỚC 6: Giải mã Link M3U8 cuối cùng -----
            val key3 = "playhq@2023@" // Giả định key cuối cùng giống key2
            
            val m3u8Url = CryptoAES.decrypt(finalApiResponse.data, key3)
                ?: throw Exception("Giải mã link M3U8 cuối cùng thất bại")

            // ----- HOÀN TẤT: Gửi link đã giải mã về cho trình phát -----
            callback(
                ExtractorLink(
                    source = this.name,
                    name = "${this.name} - ${server.name}",
                    url = m3u8Url,
                    referer = iframeUrl,
                    quality = Qualities.Unknown.value,
                    isM3u8 = true
                )
            )
            return true

        } catch (e: Exception) {
            e.printStackTrace()
            return false
        }
    }

    /**
     * ===================================================================
     * HELPER OBJECT: CRYPTO AES
     * Xử lý mã hóa/giải mã tương thích với CryptoJS (AES, CBC, PKCS5Padding)
     * ===================================================================
     */
    private object CryptoAES {

        fun md5(input: String): String {
            val md = MessageDigest.getInstance("MD5")
            return md.digest(input.toByteArray()).joinToString("") { "%02x".format(it) }
        }

        private fun getDerivedKey(password: ByteArray, salt: ByteArray): Pair<SecretKeySpec, IvParameterSpec> {
            val keySize = 32 // 256 bits
            val ivSize = 16  // 128 bits
            val derivedKey = ByteArray(keySize + ivSize)
            var lastDigest: ByteArray? = null
            var currentDigest = ByteArray(0)
            
            while (currentDigest.size < keySize + ivSize) {
                var dataToHash = lastDigest ?: byteArrayOf()
                dataToHash += password
                dataToHash += salt
                
                val md = MessageDigest.getInstance("MD5")
                lastDigest = md.digest(dataToHash)
                currentDigest += lastDigest
            }
            
            val key = SecretKeySpec(currentDigest.copyOfRange(0, keySize), "AES")
            val iv = IvParameterSpec(currentDigest.copyOfRange(keySize, keySize + ivSize))
            return Pair(key, iv)
        }

        fun decrypt(encryptedBase64: String, key: String): String? {
            return try {
                val encryptedBytes = Base64.getDecoder().decode(encryptedBase64)
                // "Salted__" (8 bytes) + salt (8 bytes)
                val salt = encryptedBytes.copyOfRange(8, 16)
                val cipherText = encryptedBytes.copyOfRange(16, encryptedBytes.size)
                
                val (secretKey, iv) = getDerivedKey(key.toByteArray(), salt)
                
                val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
                cipher.init(Cipher.DECRYPT_MODE, secretKey, iv)
                String(cipher.doFinal(cipherText))
            } catch (e: Exception) {
                //e.printStackTrace()
                null
            }
        }

        fun encrypt(plainText: String, key: String): String? {
            return try {
                // Tạo salt ngẫu nhiên 8-byte
                val salt = ByteArray(8).apply { java.security.SecureRandom().nextBytes(this) }
                val (secretKey, iv) = getDerivedKey(key.toByteArray(), salt)

                val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
                cipher.init(Cipher.ENCRYPT_MODE, secretKey, iv)
                
                val cipherText = cipher.doFinal(plainText.toByteArray())
                val saltedPrefix = "Salted__".toByteArray()
                val finalEncrypted = saltedPrefix + salt + cipherText
                
                Base64.getEncoder().encodeToString(finalEncrypted)
            } catch (e: Exception) {
                //e.printStackTrace()
                null
            }
        }
    }
}
