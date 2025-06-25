package com.lagradost.recloudstream

import android.util.Base64
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import org.jsoup.nodes.Element
import java.net.URLEncoder
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec
import java.security.MessageDigest

class Anime47Provider : MainAPI() {
    override var mainUrl = "https://anime47.shop"
    override var name = "Anime47"
    override val hasMainPage = true
    override var lang = "vi"
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(TvType.Anime, TvType.AnimeMovie, TvType.OVA)

    // Lớp dữ liệu cho JSON được mã hóa và giải mã
    private data class EncryptedSource(val ct: String, val iv: String, val s: String)
    private data class DecryptedSource(val file: String?)

    // =================================================================================
    // HÀM HELPER TÁI CẤU TRÚC
    // =================================================================================

    /**
     * Hàm helper mới để parse thông tin từ một thẻ phim (movie card).
     * Dùng chung cho cả getMainPage và search để tránh lặp code.
     */
    private fun parseMovieCard(element: Element): SearchResponse? {
        // Selector chung cho thẻ a chứa thông tin phim
        val movieLink = element.selectFirst("a.movie-item") ?: return null
        val href = movieLink.attr("href")?.let { fixUrl(it) }
        
        // Lấy tiêu đề từ span.movie-title-1, phù hợp với cả trang chủ và trang tìm kiếm
        val title = movieLink.selectFirst("span.movie-title-1")?.text()?.trim()

        // Lấy ảnh nền từ div.movie-thumbnail hoặc div.public-film-item-thumb
        val imageHolder = movieLink.selectFirst("div.movie-thumbnail, div.public-film-item-thumb")
        val image = getBackgroundImageUrl(imageHolder)

        if (href != null && title != null) {
            return newAnimeSearchResponse(title, href, TvType.Anime) {
                this.posterUrl = fixUrlNull(image)
            }
        }
        return null
    }

    private fun getBackgroundImageUrl(element: Element?): String? {
        return element?.attr("style")?.let {
            Regex("""background-image:\s*url\(['"]?(.*?)['"]?\)""").find(it)?.groupValues?.getOrNull(1)
        }
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse? {
        if (page > 1) return null
        val document = app.get(mainUrl).document
        val lists = mutableListOf<HomePageList>()

        // Lấy danh sách phim mới cập nhật
        document.select("div.last-film-box ul li")?.let { elements ->
            val homePageList = elements.mapNotNull { parseMovieCard(it) }
            if (homePageList.isNotEmpty()) {
                lists.add(HomePageList("Mới Cập Nhật", homePageList))
            }
        }
        // Lấy danh sách phim đề cử
        document.select("div.nominated-movie ul#movie-carousel-top li")?.let { elements ->
            val nominatedList = elements.mapNotNull { parseMovieCard(it) }
            if (nominatedList.isNotEmpty()) {
                lists.add(HomePageList("Phim Đề Cử", nominatedList))
            }
        }
        return if (lists.isEmpty()) null else HomePageResponse(lists)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        // Thêm tham số sapxep=1 (mới nhất) để có kết quả phù hợp hơn
        val searchUrl = "$mainUrl/tim-nang-cao/?keyword=${URLEncoder.encode(query, "UTF-8")}&sapxep=1"
        return try {
            val document = app.get(searchUrl).document
            // *** ĐÂY LÀ DÒNG ĐƯỢC SỬA LẠI DỰA TRÊN FILE HTML BẠN CUNG CẤP ***
            document.select("ul.movie-last-movie li").mapNotNull {
                parseMovieCard(it)
            }
        } catch (e: Exception) {
            e.printStackTrace()
            emptyList()
        }
    }

    override suspend fun load(url: String): LoadResponse? {
        val document = app.get(url).document
        val title = document.selectFirst("h1.movie-title span.title-1")?.text()?.trim() ?: "Không có tiêu đề"
        val poster = fixUrlNull(document.selectFirst("div.movie-l-img img")?.attr("src"))
        val description = document.selectFirst("div.content-film")?.text()?.trim()
        
        val dlInfo = document.selectFirst("dl.movie-dl")
        val statusText = dlInfo?.selectFirst("dt:contains(Trạng thái:) + dd")?.text()?.trim()
        val status = when {
            statusText?.contains("Hoàn thành", true) == true -> ShowStatus.Completed
            statusText?.contains("Đang tiến hành", true) == true -> ShowStatus.Ongoing
            else -> null
        }
        val year = document.selectFirst("h1.movie-title span.title-year")?.text()
            ?.replace(Regex("[()]"), "")?.trim()?.toIntOrNull()

        val episodes = document.select("div.episodes ul li a").mapNotNull {
            val epHref = it.attr("href")?.let { eUrl -> fixUrl(eUrl) }
            val epName = it.attr("title")?.trim() ?: it.text()?.trim()
            val epNum = epName?.filter { c -> c.isDigit() }?.toIntOrNull()
            if (epHref != null && epName != null) {
                Episode(data = epHref, name = epName, episode = epNum)
            } else null
        }.reversed()

        return newTvSeriesLoadResponse(title, url, TvType.Anime, episodes) {
            this.posterUrl = poster
            this.plot = description
            this.year = year
            this.showStatus = status
        }
    }

    // --- Các hàm giải mã giữ nguyên ---
    private fun evpBytesToKey(password: ByteArray, salt: ByteArray, keySize: Int, ivSize: Int): Pair<ByteArray, ByteArray> {
        var derivedBytes = ByteArray(0)
        var result: ByteArray
        val hasher = MessageDigest.getInstance("MD5")
        var data = ByteArray(0)
        while (derivedBytes.size < keySize + ivSize) {
            hasher.update(data)
            hasher.update(password)
            hasher.update(salt)
            result = hasher.digest()
            derivedBytes += result
            data = result // For next iteration
            hasher.reset()
        }
        return Pair(derivedBytes.copyOfRange(0, keySize), derivedBytes.copyOfRange(keySize, keySize + ivSize))
    }

    private fun decryptSource(encryptedDataB64: String, passwordStr: String): String? {
        return try {
            val encryptedJsonStr = String(Base64.decode(encryptedDataB64, Base64.DEFAULT))
            val encryptedSource = parseJson<EncryptedSource>(encryptedJsonStr)
            val salt = hexStringToByteArray(encryptedSource.s)
            val iv = hexStringToByteArray(encryptedSource.iv)
            val ciphertext = Base64.decode(encryptedSource.ct, Base64.DEFAULT)
            val (key, _) = evpBytesToKey(passwordStr.toByteArray(), salt, 32, 16)
            val secretKey = SecretKeySpec(key, "AES")
            val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
            cipher.init(Cipher.DECRYPT_MODE, secretKey, IvParameterSpec(iv))
            val decryptedBytes = cipher.doFinal(ciphertext)
            val decryptedJsonStr = String(decryptedBytes)
            parseJson<DecryptedSource>(decryptedJsonStr).file
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }
    
    private fun hexStringToByteArray(s: String): ByteArray {
        val data = ByteArray(s.length / 2)
        for (i in s.indices step 2) {
            data[i / 2] = ((Character.digit(s[i], 16) shl 4) + Character.digit(s[i + 1], 16)).toByte()
        }
        return data
    }
    
    override suspend fun loadLinks(data: String, isCasting: Boolean, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit): Boolean {
        val episodeId = data.substringAfterLast('/').substringBefore('.').trim()
        val playerResponse = app.post("$mainUrl/player/player.php", data = mapOf("ID" to episodeId, "SV" to "4"), referer = data, headers = mapOf("X-Requested-With" to "XMLHttpRequest")).document
        val scriptContent = playerResponse.select("script:containsData(var thanhhoa)").html()
        val thanhhoaB64 = Regex("""var\s+thanhhoa\s*=\s*atob\(['"]([^'"]+)['"]\)""").find(scriptContent)?.groupValues?.getOrNull(1)

        if (thanhhoaB64 != null) {
            val videoUrl = decryptSource(thanhhoaB64, "caphedaklak")
            if (!videoUrl.isNullOrBlank()) {
                callback(ExtractorLink(this.name, "${this.name} HLS", videoUrl, "$mainUrl/", Qualities.Unknown.value, ExtractorLinkType.M3U8))
                Regex("""tracks:\s*(\[.*?\])""", RegexOption.DOT_MATCHES_ALL).find(scriptContent)?.groupValues?.getOrNull(1)?.let { tracksJson ->
                    Regex("""file:\s*["'](.*?)["'].*?label:\s*["'](.*?)["']""").findAll(tracksJson).forEach { match ->
                        subtitleCallback(SubtitleFile(match.groupValues[2], fixUrl(match.groupValues[1])))
                    }
                }
                return true
            }
        }
        playerResponse.select("iframe[src]").forEach { iframe ->
            val iframeSrc = iframe.attr("src").let { if (it.startsWith("//")) "https:$it" else it }
            if (loadExtractor(iframeSrc, data, subtitleCallback, callback)) return true
        }
        return false
    }
}
