package recloudstream

// === Imports ===
import android.util.Log // THÊM IMPORT CHO LOG
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.network.CloudflareKiller
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import com.lagradost.cloudstream3.utils.AppUtils.toJson
import org.jsoup.nodes.Element
import java.net.URLEncoder
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec
import java.security.MessageDigest
import android.util.Base64
import okhttp3.Interceptor
import okhttp3.Response
import okhttp3.ResponseBody
import okhttp3.ResponseBody.Companion.toResponseBody
import java.util.concurrent.atomic.AtomicBoolean

// === Provider Class ===
class Anime47Provider : MainAPI() {
    override var mainUrl = "https://anime47.shop"
    override var name = "Anime47"
    override val hasMainPage = true
    override var lang = "vi"
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(TvType.Anime, TvType.AnimeMovie, TvType.OVA, TvType.Cartoon)

    private val interceptor = CloudflareKiller()

    override val mainPage = mainPageOf(
        "$mainUrl/danh-sach/phim-moi/1.html" to "Anime Mới Cập Nhật",
        "$mainUrl/the-loai/hoat-hinh-trung-quoc-75/1.html" to "Hoạt Hình Trung Quốc",
        "$mainUrl/danh-sach/anime-mua-moi-update.html" to "Anime Mùa Mới",
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = if (page == 1) request.data else request.data.replace("/1.html", "/$page.html")
        val document = app.get(url, interceptor = interceptor).document
        val home = document.select("ul.last-film-box > li").mapNotNull { it.toSearchResult() }
        return newHomePageResponse(request.name, home)
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val title = this.selectFirst("div.movie-title-1")?.text()?.trim() ?: return null
        val rawHref = this.selectFirst("a")!!.attr("href")
        val cleanHref = if (rawHref.startsWith("./")) rawHref.substring(1) else rawHref
        val href = fixUrl(cleanHref)

        val posterUrl = this.selectFirst("div.public-film-item-thumb")?.attr("style")
            ?.substringAfter("url('")?.substringBefore("')")
        val ribbon = this.selectFirst("span.ribbon")?.text()?.trim()

        return newAnimeSearchResponse(title, href, TvType.Anime) {
            this.posterUrl = posterUrl
            if (ribbon != null) { this.otherName = "Tập $ribbon" }
        }
    }
    
    override suspend fun search(query: String): List<SearchResponse> {
        val searchUrl = "$mainUrl/tim-nang-cao/?keyword=${URLEncoder.encode(query, "UTF-8")}&sapxep=1"
        val document = app.get(searchUrl, interceptor = interceptor).document
        return document.select("ul.last-film-box > li").mapNotNull { it.toSearchResult() }
    }

    private data class EpisodeInfo(val name: String, val sources: MutableMap<String, String>)

    override suspend fun load(url: String): LoadResponse? {
        val document = app.get(url, interceptor = interceptor).document

        val title = document.selectFirst("h1.movie-title span.title-1")?.text()?.trim() ?: return null
        val poster = document.selectFirst("div.movie-l-img img")?.attr("src")
        val plot = document.selectFirst("div#film-content > .news-article")?.text()?.trim()
        val tags = document.select("dd.movie-dd.dd-cat a").map { it.text() }
        val year = document.selectFirst("span.title-year")?.text()?.removeSurrounding("(", ")")?.toIntOrNull()
        
        val tvType = if (tags.any { it.contains("Hoạt hình Trung Quốc", ignoreCase = true) }) {
            TvType.Cartoon
        } else {
            TvType.Anime
        }

        val scriptWithWatchUrl = document.select("script:containsData(let playButton, episodePlay)").html()
        val relativeWatchUrl = Regex("""episodePlay\s*=\s*'(.*?)';""").find(scriptWithWatchUrl)?.groupValues?.get(1)
        val watchUrl = relativeWatchUrl?.let { fixUrl(it) } ?: return null

        val episodesByNumber = mutableMapOf<Int, EpisodeInfo>()

        try {
            val watchPageDoc = app.get(watchUrl, interceptor = interceptor).document
            
            watchPageDoc.select("div.server").forEach { serverBlock ->
                val serverName = serverBlock.selectFirst(".name span")?.text()?.trim() ?: "Server"
                val episodeElements = serverBlock.select("div.episodes ul li a, div.tab-content div.tab-pane ul li a")

                episodeElements.forEach {
                    val epHref = fixUrl(it.attr("href"))
                    val epRawName = it.attr("title").ifEmpty { it.text() }.trim()
                    
                    // YÊU CẦU 1: Đơn giản hóa việc tạo Episode, để CloudStream tự xử lý số tập
                    val epName = "Tập $epRawName"
                    val epNum = epRawName.substringBefore("-").filter { c -> c.isDigit() }.toIntOrNull()

                    if (epNum != null) {
                        val episodeInfo = episodesByNumber.getOrPut(epNum) { EpisodeInfo(name = epName, sources = mutableMapOf()) }
                        episodeInfo.sources[serverName] = epHref
                    }
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }

        if (episodesByNumber.isEmpty()) {
            return newMovieLoadResponse(title, url, tvType, watchUrl) {
                this.posterUrl = poster; this.plot = plot; this.tags = tags; this.year = year
            }
        }

        val episodes = episodesByNumber.entries.map { (epNum, epInfo) ->
            val data = epInfo.sources.toJson()
            Episode(data = data, name = epInfo.name) // Bỏ `episode = epNum`
        }.sortedBy { it.name?.substringAfter("Tập")?.trim()?.substringBefore("-")?.toIntOrNull() ?: 0 }

        return newTvSeriesLoadResponse(title, url, tvType, episodes) {
            this.posterUrl = poster
            this.plot = plot
            this.tags = tags
            this.year = year
        }
    }
    
    private data class CryptoJsJson(val ct: String, val iv: String, val s: String)
    
    private fun evpBytesToKey(password: ByteArray, salt: ByteArray, keySize: Int, ivSize: Int): Pair<ByteArray, ByteArray> {
        var derivedBytes = byteArrayOf(); var lastDigest: ByteArray? = null
        val md5 = MessageDigest.getInstance("MD5")
        while (derivedBytes.size < keySize + ivSize) {
            var dataToHash = byteArrayOf()
            if (lastDigest != null) { dataToHash += lastDigest }
            dataToHash += password; dataToHash += salt
            lastDigest = md5.digest(dataToHash)
            derivedBytes += lastDigest
        }
        return Pair(derivedBytes.copyOf(keySize), derivedBytes.copyOfRange(keySize, keySize + ivSize))
    }

    private fun decryptSource(encryptedDataB64: String, passwordStr: String): String? {
        return try {
            val encryptedJsonStr = String(Base64.decode(encryptedDataB64, Base64.DEFAULT))
            val encryptedSource = parseJson<CryptoJsJson>(encryptedJsonStr)
            val salt = hexStringToByteArray(encryptedSource.s); val iv = hexStringToByteArray(encryptedSource.iv)
            val ciphertext = Base64.decode(encryptedSource.ct, Base64.DEFAULT)
            val (key, _) = evpBytesToKey(passwordStr.toByteArray(), salt, 32, 16)
            val secretKey = SecretKeySpec(key, "AES")
            val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
            cipher.init(Cipher.DECRYPT_MODE, secretKey, IvParameterSpec(iv))
            val decryptedBytes = cipher.doFinal(ciphertext)
            parseJson<String>(String(decryptedBytes))
        } catch (e: Exception) {
            null
        }
    }

    private fun hexStringToByteArray(s: String): ByteArray {
        val len = s.length; val data = ByteArray(len / 2)
        for (i in 0 until len step 2) {
            data[i / 2] = ((Character.digit(s[i], 16) shl 4) + Character.digit(s[i + 1], 16)).toByte()
        }
        return data
    }
    
    override suspend fun loadLinks(
        data: String, isCasting: Boolean, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit
    ): Boolean {
        val sources = try { parseJson<Map<String, String>>(data) } catch(e: Exception) { mapOf("Default" to data) }
        val hasLoadedSubtitles = AtomicBoolean(false)

        sources.apmap { (sourceName, url) ->
            try {
                val episodeId = url.substringAfterLast('/').substringBefore('.').trim()
                val serverId = "4"
                val postData = mapOf("ID" to episodeId, "SV" to serverId, "SV4" to serverId)

                val playerResponseText = app.post(
                    "$mainUrl/player/player.php", data = postData,
                    referer = url, headers = mapOf("X-Requested-With" to "XMLHttpRequest")
                ).text
                
                val encryptedDataB64 = Regex("""var thanhhoa = atob\("([^"]+)"\)""").find(playerResponseText)?.groupValues?.get(1)
                    ?: throw Exception("Không tìm thấy dữ liệu mã hóa")

                val videoUrl = decryptSource(encryptedDataB64, "caphedaklak")
                    ?: throw Exception("Giải mã video thất bại")

                // **THAY ĐỔI: Tạo link thành công với tên "OK"**
                callback(
                    ExtractorLink(
                        source = this.name, name = "$sourceName - OK", url = videoUrl, referer = "$mainUrl/",
                        quality = Qualities.Unknown.value, type = ExtractorLinkType.M3U8,
                    )
                )
                
                if (hasLoadedSubtitles.compareAndSet(false, true)) {
                    val tracksJsonBlock = Regex("""tracks:\s*(\[.*?\])""").find(playerResponseText)?.groupValues?.get(1)
                    if (tracksJsonBlock != null) {
                        val subtitleRegex = Regex("""\{file:\s*["']([^"']+)["'],\s*label:\s*["']([^"']+)["']""")
                        subtitleRegex.findAll(tracksJsonBlock).forEach { match ->
                            val file = match.groupValues[1]; val label = match.groupValues[2]
                            if (file.isNotBlank()) {
                                subtitleCallback(SubtitleFile(label, fixUrl(file)))
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                // **THAY ĐỔI: Tạo link thất bại với tên là LỖI + lý do**
                callback(
                    ExtractorLink(
                        source = this.name,
                        // Giới hạn độ dài của thông báo lỗi để không làm vỡ giao diện
                        name = "$sourceName - LỖI: ${e.message?.take(50)}", 
                        url = "https://example.com/error", // Link giả để không bị crash
                        referer = mainUrl,
                        quality = Qualities.Unknown.value,
                        type = ExtractorLinkType.M3U8,
                    )
                )
            }
        }
        
        return true
    }

    override fun getVideoInterceptor(extractorLink: ExtractorLink): Interceptor? {
        return object : Interceptor {
            override fun intercept(chain: Interceptor.Chain): Response {
                val request = chain.request()
                val response = chain.proceed(request)
                val url = request.url.toString()
                
                if (url.contains("nonprofit.asia")) {
                    response.body?.let { body ->
                        try {
                            val fixedBytes = skipByteError(body)
                            val newBody = fixedBytes.toResponseBody(body.contentType())
                            return response.newBuilder().body(newBody).build()
                        } catch (_: Exception) {}
                    }
                }
                return response
            }
        }
    }
}

private fun skipByteError(responseBody: ResponseBody): ByteArray {
    val source = responseBody.source()
    source.request(Long.MAX_VALUE)
    val buffer = source.buffer.clone()
    source.close()
    val byteArray = buffer.readByteArray()
    val length = byteArray.size - 188
    var start = 0
    for (i in 0 until length) {
        val nextIndex = i + 188
        if (nextIndex < byteArray.size && byteArray[i].toInt() == 71 && byteArray[nextIndex].toInt() == 71) {
            start = i; break
        }
    }
    return if (start > 0) byteArray.copyOfRange(start, byteArray.size) else byteArray
}
