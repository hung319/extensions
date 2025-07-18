package recloudstream

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Element
import java.net.URLEncoder
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec
import java.security.MessageDigest
import android.util.Base64 // Sử dụng Base64 của Android cho tính tương thích
import com.lagradost.cloudstream3.utils.AppUtils.parseJson

class Anime47Provider : MainAPI() {
    override var mainUrl = "https://anime47.shop"
    override var name = "Anime47"
    override val hasMainPage = true
    override var lang = "vi"
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(TvType.Anime, TvType.AnimeMovie, TvType.OVA)

    // --- Data Classes ---
    private data class EncryptedSource(val ct: String, val iv: String, val s: String)
    private data class Track(val file: String, val label: String?)

    // --- Parsing Logic ---
    private fun getBackgroundImageUrl(element: Element?): String? {
        return element?.attr("style")?.let {
            Regex("""url\((['"]?)(.*?)\1\)""").find(it)?.groupValues?.get(2)
        }
    }

    private fun Element.toSearchResponse(): SearchResponse? {
        val link = this.selectFirst("a.movie-item") ?: return null
        val href = link.attr("href")
        val title = link.selectFirst(".movie-title-1")?.text()?.trim() ?: return null
        val posterUrl = getBackgroundImageUrl(link.selectFirst(".public-film-item-thumb"))

        return newAnimeSearchResponse(title, href, TvType.Anime) {
            this.posterUrl = posterUrl
        }
    }
    
    // --- Main Functions ---
    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val document = app.get(mainUrl).document
        val lists = document.select("div.block.update").mapNotNull { block ->
            val title = block.selectFirst(".widget-title .title")?.text() ?: return@mapNotNull null
            val items = block.select("ul.last-film-box li").mapNotNull { it.toSearchResponse() }
            if (items.isNotEmpty()) HomePageList(title, items) else null
        }
        return HomePageResponse(lists)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val url = "$mainUrl/tim-nang-cao/?keyword=${URLEncoder.encode(query, "UTF-8")}&sapxep=1"
        val document = app.get(url).document
        return document.select("ul.last-film-box > li").mapNotNull { it.toSearchResponse() }
    }

    override suspend fun load(url: String): LoadResponse {
        val document = app.get(url).document
        val title = document.selectFirst("h1.movie-title span.title-1")?.text()?.trim() ?: "N/A"
        val poster = document.selectFirst("div.movie-l-img img")?.attr("src")
        val plot = document.selectFirst("div.news-article")?.text()
        val year = document.selectFirst("span.title-year")?.text()?.filter { it.isDigit() }?.toIntOrNull()
        val tags = document.select("dd.movie-dd.dd-cat a").map { it.text() }
        
        val scriptContent = document.select("script:containsData(anyEpisodes)").html()
        val episodesHtml = Regex("""anyEpisodes\s*=\s*'(.*?)';""").find(scriptContent)?.groupValues?.get(1)
        
        val episodes = if (episodesHtml != null) {
            org.jsoup.Jsoup.parse(episodesHtml).select("div.episodes ul li a").mapNotNull {
                val epHref = it.attr("href")
                val epName = "Tập " + (it.attr("title").ifEmpty { it.text() })
                val epNum = it.text().toIntOrNull()
                Episode(epHref, epName, episode = epNum)
            }.reversed()
        } else {
            emptyList()
        }

        return newTvSeriesLoadResponse(title, url, TvType.Anime, episodes) {
            this.posterUrl = poster
            this.plot = plot
            this.year = year
            this.tags = tags
        }
    }

    // --- Decryption Logic (Tham khảo từ recloudstream) ---
    private fun evpBytesToKey(password: ByteArray, salt: ByteArray, keySize: Int, ivSize: Int): Pair<ByteArray, ByteArray> {
        var derivedBytes = byteArrayOf()
        var lastDigest: ByteArray? = null
        val md5 = MessageDigest.getInstance("MD5")

        while (derivedBytes.size < keySize + ivSize) {
            var dataToHash = byteArrayOf()
            if (lastDigest != null) {
                dataToHash += lastDigest
            }
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
            val encryptedSource = parseJson<EncryptedSource>(encryptedJsonStr)
            
            val salt = hexStringToByteArray(encryptedSource.s)
            val iv = hexStringToByteArray(encryptedSource.iv)
            val ciphertext = Base64.decode(encryptedSource.ct, Base64.DEFAULT)
            
            // CryptoJS mặc định sử dụng 32-byte key và 16-byte IV cho AES-256
            val (key, _) = evpBytesToKey(passwordStr.toByteArray(), salt, 32, 16)

            val secretKey = SecretKeySpec(key, "AES")
            val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
            cipher.init(Cipher.DECRYPT_MODE, secretKey, IvParameterSpec(iv))
            val decryptedBytes = cipher.doFinal(ciphertext)
            
            // Kết quả giải mã là một chuỗi JSON chứa URL, cần parse để lấy giá trị chuỗi bên trong
            parseJson<String>(String(decryptedBytes))
        } catch (e: Exception) {
            e.printStackTrace()
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
    
    // --- Link Loading ---
    override suspend fun loadLinks(
        data: String, isCasting: Boolean, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit
    ): Boolean {
        val episodeId = data.substringAfterLast('/').substringBefore('.').trim()
        val playerResponse = app.post(
            "$mainUrl/player/player.php",
            data = mapOf("ID" to episodeId, "SV" to "4"), // Mặc định thử server "Fe" (id=4)
            referer = data,
            headers = mapOf("X-Requested-With" to "XMLHttpRequest")
        ).text
        
        val encryptedDataB64 = Regex("""var thanhhoa = atob\("([^"]+)"\)""").find(playerResponse)?.groupValues?.get(1)
            ?: throw Exception("Không tìm thấy dữ liệu mã hóa")

        val videoUrl = decryptSource(encryptedDataB64, "caphedaklak")
            ?: throw Exception("Giải mã video thất bại")

        callback(
            ExtractorLink(
                source = name,
                name = "Anime47 HLS",
                url = videoUrl,
                referer = "$mainUrl/",
                quality = Qualities.Unknown.value,
                type = ExtractorLinkType.M3U8,
            )
        )
        
        // Trích xuất phụ đề
        val tracksJson = Regex("""tracks:\s*(\[.*?\])""").find(playerResponse)?.groupValues?.get(1)
        if (tracksJson != null) {
            parseJson<List<Track>>(tracksJson).forEach {
                subtitleCallback(SubtitleFile(it.label ?: "Phụ đề", it.file))
            }
        }
        
        return true
    }
}
