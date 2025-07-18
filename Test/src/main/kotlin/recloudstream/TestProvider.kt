package recloudstream

// === Imports ===
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.network.CloudflareKiller
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
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

    // =================== HÀM LOAD ĐÃ ĐƯỢC HOÀN THIỆN ===================
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

        val episodeContainer = document.selectFirst("div.block2.servers")
            ?: return null

        val episodeList = mutableListOf<Episode>()
        
        episodeContainer.select("div.episodes ul li a").mapNotNull {
            val epHref = fixUrl(it.attr("href"))
            val epName = "Tập " + it.attr("title").ifEmpty { it.text() }
            Episode(epHref, name = epName)
        }.let { episodeList.addAll(it) }

        val pagination = episodeContainer.select("ul.pagination li a")
        if (pagination.isNotEmpty()) {
            val filmId = pagination.attr("onclick").substringAfter("(").substringBefore(",")
            val lastPage = pagination.getOrNull(pagination.size - 2)?.text()?.toIntOrNull() ?: 1

            if (lastPage > 1) {
                (2..lastPage).toList().apmap { page ->
                    try {
                        app.post(
                            "$mainUrl/player/ajax_episodes.php",
                            data = mapOf("film_id" to filmId, "page" to page.toString())
                        ).document.select("li a").mapNotNull {
                            val epHref = fixUrl(it.attr("href"))
                            val epName = "Tập " + it.attr("title").ifEmpty { it.text() }
                            Episode(epHref, name = epName)
                        }.let { episodeList.addAll(it) }
                    } catch (_: Exception) {}
                }
            }
        }

        // SỬA LỖI SẮP XẾP: Bỏ .reversed() để sắp xếp theo thứ tự tăng dần (1, 2, 3, ...)
        val sortedEpisodes = episodeList.distinctBy { it.data }.sortedBy {
            it.name?.substringAfter("Tập")?.trim()?.substringBefore("-")?.toIntOrNull() ?: 0
        }
        
        if (sortedEpisodes.isEmpty()) return null

        return newTvSeriesLoadResponse(title, url, tvType, sortedEpisodes) {
            this.posterUrl = poster
            this.plot = plot
            this.tags = tags
            this.year = year
        }
    }
    
    private data class CryptoJsJson(val ct: String, val iv: String, val s: String)
    
    private fun evpBytesToKey(password: ByteArray, salt: ByteArray, keySize: Int, ivSize: Int): Pair<ByteArray, ByteArray> {
        var derivedBytes = byteArrayOf()
        var lastDigest: ByteArray? = null
        val md5 = MessageDigest.getInstance("MD5")
        while (derivedBytes.size < keySize + ivSize) {
            var dataToHash = byteArrayOf()
            if (lastDigest != null) { dataToHash += lastDigest }
            dataToHash += password
            dataToHash += salt
            lastDigest = md5.digest(dataToHash)
            derivedBytes += lastDigest
        }
        return Pair(derivedBytes.copyOf(keySize), derivedBytes.copyOfRange(keySize, keySize + ivSize))
    }

    private fun decryptSource(encryptedDataB64: String, passwordStr: String): String? {
        return try {
            val encryptedJsonStr = String(Base64.decode(encryptedDataB64, Base64.DEFAULT))
            val encryptedSource = parseJson<CryptoJsJson>(encryptedJsonStr)
            val salt = hexStringToByteArray(encryptedSource.s)
            val iv = hexStringToByteArray(encryptedSource.iv)
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
        val len = s.length
        val data = ByteArray(len / 2)
        for (i in 0 until len step 2) {
            data[i / 2] = ((Character.digit(s[i], 16) shl 4) + Character.digit(s[i + 1], 16)).toByte()
        }
        return data
    }
    
    // =================== HÀM LOADLINKS ĐÃ ĐƯỢC HOÀN THIỆN ===================
    override suspend fun loadLinks(
        data: String, isCasting: Boolean, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit
    ): Boolean {
        val episodeId = data.substringAfterLast('/').substringBefore('.').trim()
        val playerResponseText = app.post(
            "$mainUrl/player/player.php", data = mapOf("ID" to episodeId, "SV" to "4"),
            referer = data, headers = mapOf("X-Requested-With" to "XMLHttpRequest")
        ).text
        
        val encryptedDataB64 = Regex("""var thanhhoa = atob\("([^"]+)"\)""").find(playerResponseText)?.groupValues?.get(1)
            ?: throw Exception("Không tìm thấy dữ liệu mã hóa")

        val videoUrl = decryptSource(encryptedDataB64, "caphedaklak")
            ?: throw Exception("Giải mã video thất bại")

        callback(
            ExtractorLink(
                source = name, name = "Anime47 HLS", url = videoUrl, referer = "$mainUrl/",
                quality = Qualities.Unknown.value, type = ExtractorLinkType.M3U8,
            )
        )
        
        // **SỬA LỖI PHỤ ĐỀ:** Dùng logic Regex ổn định
        val tracksJsonBlock = Regex("""tracks:\s*(\[.*?\])""").find(playerResponseText)?.groupValues?.get(1)
        if (tracksJsonBlock != null) {
            val subtitleRegex = Regex("""\{file:\s*["']([^"']+)["'],\s*label:\s*["']([^"']+)["']""")
            subtitleRegex.findAll(tracksJsonBlock).forEach { match ->
                val file = match.groupValues[1]
                val label = match.groupValues[2]
                if (file.isNotBlank()) {
                    subtitleCallback(SubtitleFile(label, fixUrl(file)))
                }
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
            start = i
            break
        }
    }
    return if (start > 0) byteArray.copyOfRange(start, byteArray.size) else byteArray
}
