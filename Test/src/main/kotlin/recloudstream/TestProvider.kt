package recloudstream

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.network.CloudflareKiller
import org.jsoup.nodes.Element

// Thêm các thư viện Java cần thiết cho việc giải mã
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec
import java.util.Base64

class Anime47Provider : MainAPI() {
    override var mainUrl = "https://anime47.shop"
    override var name = "Anime47"
    override val hasMainPage = true
    override var lang = "vi"
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(
        TvType.Anime,
        TvType.AnimeMovie,
        TvType.OVA
    )

    private val interceptor = CloudflareKiller()

    override val mainPage = mainPageOf(
        "$mainUrl/danh-sach/phim-moi/1.html" to "Anime Mới Cập Nhật",
        "$mainUrl/the-loai/hoat-hinh-trung-quoc-75/1.html" to "Hoạt Hình Trung Quốc",
        "$mainUrl/danh-sach/anime-mua-moi-update.html" to "Anime Mùa Mới",
    )

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {
        val url = if (page == 1) request.data else request.data.replace("/1.html", "/$page.html")
        val document = app.get(url, interceptor = interceptor).document
        val home = document.select("ul.last-film-box > li").mapNotNull {
            it.toSearchResult()
        }
        return newHomePageResponse(request.name, home)
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val title = this.selectFirst("div.movie-title-1")?.text()?.trim() ?: return null
        val href = fixUrl(this.selectFirst("a")!!.attr("href"))
        val posterUrl = this.selectFirst("div.public-film-item-thumb")?.attr("style")
            ?.substringAfter("url('")?.substringBefore("')")
        val ribbon = this.selectFirst("span.ribbon")?.text()?.trim()

        return newAnimeSearchResponse(title, href, TvType.Anime) {
            this.posterUrl = posterUrl
            if (ribbon != null) {
                this.otherName = "Tập $ribbon"
            }
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        // SỬA LỖI: Cập nhật lại URL tìm kiếm chính xác
        val searchUrl = "$mainUrl/tim-nang-cao/?keyword=$query&nam=&season=&status=&sapxep=1"
        val document = app.get(searchUrl, interceptor = interceptor).document
        
        // Logic parse kết quả vẫn giữ nguyên vì cấu trúc HTML không đổi
        return document.select("ul.last-film-box > li").mapNotNull {
            it.toSearchResult()
        }
    }

    override suspend fun load(url: String): LoadResponse? {
        val document = app.get(url, interceptor = interceptor).document

        val title = document.selectFirst("h1.movie-title span.title-1")?.text()?.trim() ?: return null
        val poster = document.selectFirst("div.movie-l-img img")?.attr("src")
        val plot = document.selectFirst("div#film-content > .news-article")?.text()?.trim()
        val tags = document.select("dd.movie-dd.dd-cat a").map { it.text() }
        val year = document.selectFirst("span.title-year")?.text()?.removeSurrounding("(", ")")?.toIntOrNull()

        val script = document.select("script").find { it.data().contains("anyEpisodes =") }?.data()
        val episodesHtml = script?.substringAfter("anyEpisodes = '")?.substringBefore("';")
        
        if (episodesHtml == null) {
             return newAnimeLoadResponse(title, url, TvType.Anime) {
                this.posterUrl = poster
                this.plot = plot
                this.tags = tags
                this.year = year
            }
        }
        
        val episodesDoc = org.jsoup.Jsoup.parse(episodesHtml)
        val episodes = episodesDoc.select("div.episodes ul li a").map {
            val epHref = fixUrl(it.attr("href"))
            val epNum = it.attr("title").ifEmpty { it.text() }.toIntOrNull()
            val epName = "Tập " + it.attr("title").ifEmpty { it.text() }
            Episode(epHref, name = epName, episode = epNum)
        }.reversed()


        return newTvSeriesLoadResponse(title, url, TvType.Anime, episodes) {
            this.posterUrl = poster
            this.plot = plot
            this.tags = tags
            this.year = year
        }
    }
    
    // Data classes để parse JSON
    private data class Track(val file: String, val label: String?, val kind: String?)
    private data class CryptoJsJson(val ct: String, val iv: String, val s: String)
    private data class VideoSource(val file: String)

    /**
     * Hàm giải mã AES tùy chỉnh để xử lý định dạng của CryptoJS JSON.
     * @param data Base64 encoded string chứa JSON object với ciphertext và IV.
     * @param key Chuỗi key để giải mã.
     * @return Chuỗi đã giải mã (dự kiến là JSON chứa link video).
     */
    private fun decryptCryptoJsData(data: String, key: String): String? {
        try {
            // 1. Giải mã Base64 bên ngoài để lấy JSON
            val decodedJsonData = String(Base64.getDecoder().decode(data))
            val cryptoData = AppUtils.parseJson<CryptoJsJson>(decodedJsonData)

            // 2. Lấy IV (dưới dạng hex) và CipherText (dưới dạng Base64)
            val iv = hexStringToByteArray(cryptoData.iv)
            val cipherText = Base64.getDecoder().decode(cryptoData.ct)

            // 3. Chuẩn bị key và IV cho việc giải mã
            val secretKeySpec = SecretKeySpec(key.toByteArray(), "AES")
            val ivParameterSpec = IvParameterSpec(iv)

            // 4. Thực hiện giải mã
            val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
            cipher.init(Cipher.DECRYPT_MODE, secretKeySpec, ivParameterSpec)
            return String(cipher.doFinal(cipherText))
        } catch (e: Exception) {
            e.printStackTrace()
            return null
        }
    }

    // Hàm tiện ích để chuyển đổi chuỗi Hex thành ByteArray
    private fun hexStringToByteArray(s: String): ByteArray {
        val len = s.length
        val data = ByteArray(len / 2)
        var i = 0
        while (i < len) {
            data[i / 2] = ((Character.digit(s[i], 16) shl 4) + Character.digit(s[i + 1], 16)).toByte()
            i += 2
        }
        return data
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val document = app.get(data, interceptor = interceptor).document
        
        val scriptContent = document.select("script").find { it.data().contains("var id_ep =") }?.data()
        val episodeId = scriptContent?.substringAfter("var id_ep = ")?.substringBefore(";")?.trim() 
            ?: throw Exception("Không thể tìm thấy ID tập phim")

        // `apmap` sẽ tự động xử lý exception cho từng server, 
        // vì vậy chúng ta không cần khối try-catch lớn bên ngoài nữa.
        document.select("#clicksv > span.btn").apmap { serverElement ->
            val serverId = serverElement.id().removePrefix("sv")
            val serverName = serverElement.attr("title")

            val playerResponseText = app.post(
                url = "$mainUrl/player/player.php",
                data = mapOf("ID" to episodeId, "SV" to serverId),
                referer = data,
                headers = mapOf("X-Requested-With" to "XMLHttpRequest"),
                interceptor = interceptor
            ).text

            // === CẢI THIỆN XỬ LÝ LỖI ===
            val encryptedData = playerResponseText.substringAfter("var thanhhoa = atob(\"").substringBefore("\");")
            // Ném lỗi nếu không tìm thấy dữ liệu mã hóa
            if (encryptedData.isBlank()) throw Exception("[$serverName] Không tìm thấy dữ liệu mã hóa")

            val decryptionKey = "caphedaklak"
            
            val decryptedString = decryptCryptoJsData(encryptedData, decryptionKey)
                // Ném lỗi nếu giải mã thất bại
                ?: throw Exception("[$serverName] Giải mã dữ liệu thất bại")
            
            val videoUrl = AppUtils.parseJson<VideoSource>(decryptedString).file
            // Ném lỗi nếu URL video rỗng sau khi giải mã
            if (videoUrl.isBlank()) throw Exception("[$serverName] Không tìm thấy URL video sau khi giải mã")
            
            // Trích xuất phụ đề
            val tracksRegex = """"tracks"\s*:\s*(\[.*?\])""".toRegex()
            val tracksMatch = tracksRegex.find(playerResponseText)
            if (tracksMatch != null) {
                val tracksArrayJson = tracksMatch.groupValues[1]
                val tracks = AppUtils.parseJson<List<Track>>(tracksArrayJson)
                tracks.forEach { track ->
                    subtitleCallback.invoke(
                        SubtitleFile(
                            lang = track.label ?: "Unknown",
                            url = fixUrl(track.file)
                        )
                    )
                }
            }
            
            callback.invoke(
                ExtractorLink(
                    source = this.name,
                    name = "$name - $serverName",
                    url = videoUrl,
                    referer = "$mainUrl/",
                    quality = Qualities.Unknown.value,
                    type = ExtractorLinkType.M3U8,
                )
            )
        }
        
        return true
    }
}
