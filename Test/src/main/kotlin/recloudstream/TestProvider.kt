package com.lagradost.recloudstream // Đã thêm package

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
    override var mainUrl = "https://anime47.shop" // Đã cập nhật domain
    override var name = "Anime47"
    override val hasMainPage = true
    override var lang = "vi"
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(TvType.Anime, TvType.AnimeMovie, TvType.OVA)

    // Lớp dữ liệu cho JSON được mã hóa trong biến 'thanhhoa'
    private data class EncryptedSource(
        val ct: String,
        val iv: String,
        val s: String
    )

    // Lớp dữ liệu cho JSON sau khi giải mã, chứa link HLS
    private data class DecryptedSource(
        val file: String?
    )

    private fun getBackgroundImageUrl(element: Element?): String? {
        val style = element?.attr("style")
        return style?.let {
            Regex("""background-image:\s*url\(['"]?(.*?)['"]?\)""").find(it)?.groupValues?.getOrNull(1)
        }
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse? {
        if (page > 1) return null

        val document = app.get(mainUrl).document
        val lists = mutableListOf<HomePageList>()

        // Phần "Mới Cập Nhật"
        document.selectFirst("div.block.update div.content[data-name=all] ul.last-film-box")?.let { container ->
            val homePageList = container.select("li").mapNotNull {
                val movieItem = it.selectFirst("a.movie-item")
                val href = movieItem?.attr("href")?.let { url -> fixUrl(url) }
                val title = movieItem?.selectFirst("div.movie-title-1")?.text()?.trim()
                val image = getBackgroundImageUrl(movieItem?.selectFirst("div.public-film-item-thumb"))

                if (href != null && title != null) {
                    newAnimeSearchResponse(title, href, TvType.Anime) {
                        this.posterUrl = fixUrlNull(image)
                    }
                } else null
            }
            if (homePageList.isNotEmpty()) {
                lists.add(HomePageList("Mới Cập Nhật", homePageList))
            }
        }

        // Phần "Phim Đề Cử"
        document.selectFirst("div.nominated-movie ul#movie-carousel-top")?.let { container ->
            val nominatedList = container.select("li").mapNotNull {
                val movieItem = it.selectFirst("a.movie-item")
                val href = movieItem?.attr("href")?.let { url -> fixUrl(url) }
                val title = movieItem?.selectFirst("div.movie-title-1")?.text()?.trim()
                val image = getBackgroundImageUrl(movieItem?.selectFirst("div.public-film-item-thumb"))

                if (href != null && title != null) {
                    newAnimeSearchResponse(title, href, TvType.Anime) {
                        this.posterUrl = fixUrlNull(image)
                    }
                } else null
            }
            if (nominatedList.isNotEmpty()) {
                lists.add(HomePageList("Phim Đề Cử", nominatedList))
            }
        }

        return if (lists.isEmpty()) null else HomePageResponse(lists)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val searchUrl = "$mainUrl/tim-kiem/?keyword=${URLEncoder.encode(query, "UTF-8")}"
        val document = app.get(searchUrl).document
        return document.select("ul.last-film-box li").mapNotNull {
            val movieItem = it.selectFirst("a.movie-item")
            val href = movieItem?.attr("href")?.let { url -> fixUrl(url) }
            val title = movieItem?.selectFirst("div.movie-title-1")?.text()?.trim()
            val image = getBackgroundImageUrl(movieItem?.selectFirst("div.public-film-item-thumb"))

            if (href != null && title != null) {
                newAnimeSearchResponse(title, href, TvType.Anime) {
                    this.posterUrl = fixUrlNull(image)
                }
            } else null
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

    // =================================================================================
    // HÀM GIẢI MÃ MỚI
    // =================================================================================

    /**
     * Tạo ra khóa và IV từ mật khẩu và salt, mô phỏng lại cách hoạt động của
     * CryptoJS.EVP_BytesToKey.
     * Chỉ cần lấy khóa (key) từ hàm này.
     */
    private fun evpBytesToKey(password: ByteArray, salt: ByteArray, keySize: Int, ivSize: Int): Pair<ByteArray, ByteArray> {
        var derivedBytes = ByteArray(0)
        var result = ByteArray(0)
        var hasher = MessageDigest.getInstance("MD5")

        while (derivedBytes.size < keySize + ivSize) {
            hasher.update(result)
            hasher.update(password)
            hasher.update(salt)
            result = hasher.digest()
            derivedBytes += result
            hasher = MessageDigest.getInstance("MD5")
        }
        return Pair(derivedBytes.copyOfRange(0, keySize), derivedBytes.copyOfRange(keySize, keySize + ivSize))
    }

    /**
     * Hàm chính để giải mã chuỗi 'thanhhoa'
     */
    private fun decryptSource(encryptedDataB64: String, passwordStr: String): String? {
        try {
            // 1. Giải mã Base64 để lấy chuỗi JSON
            val encryptedJsonStr = String(Base64.decode(encryptedDataB64, Base64.DEFAULT))
            val encryptedSource = parseJson<EncryptedSource>(encryptedJsonStr)

            // 2. Trích xuất ciphertext, iv, và salt
            val salt = hexStringToByteArray(encryptedSource.s)
            val iv = hexStringToByteArray(encryptedSource.iv)
            val ciphertext = Base64.decode(encryptedSource.ct, Base64.DEFAULT)

            // 3. Tạo khóa từ mật khẩu và salt
            // AES-256 sử dụng khóa 32-byte (256/8)
            val (key, _) = evpBytesToKey(passwordStr.toByteArray(), salt, 32, 16)

            // 4. Giải mã dữ liệu
            val secretKey = SecretKeySpec(key, "AES")
            val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
            cipher.init(Cipher.DECRYPT_MODE, secretKey, IvParameterSpec(iv))
            val decryptedBytes = cipher.doFinal(ciphertext)

            // 5. Chuyển kết quả sang chuỗi, parse JSON và lấy link
            val decryptedJsonStr = String(decryptedBytes)
            val decryptedSource = parseJson<DecryptedSource>(decryptedJsonStr)
            
            return decryptedSource.file
        } catch (e: Exception) {
            e.printStackTrace()
            return null
        }
    }
    
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
        data: String, // URL của trang xem phim
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        // Trích xuất ID tập phim từ URL
        val episodeId = data.substringAfterLast('/').substringBefore('.').trim()

        // Lấy nội dung từ player.php
        val playerResponse = app.post(
            "$mainUrl/player/player.php",
            data = mapOf("ID" to episodeId, "SV" to "4"), // Mặc định dùng server Fe (ID=4)
            referer = data,
            headers = mapOf("X-Requested-With" to "XMLHttpRequest")
        ).document

        // Tìm biến 'thanhhoa' trong script
        val scriptContent = playerResponse.select("script:containsData(var thanhhoa)").html()
        val thanhhoaB64 = Regex("""var\s+thanhhoa\s*=\s*atob\(['"]([^'"]+)['"]\)""").find(scriptContent)?.groupValues?.getOrNull(1)

        if (thanhhoaB64 != null) {
            // Giải mã để lấy link video
            val videoUrl = decryptSource(thanhhoaB64, "caphedaklak")

            if (!videoUrl.isNullOrBlank()) {
                callback(
                    ExtractorLink(
                        source = this.name,
                        name = "${this.name} HLS",
                        url = videoUrl,
                        referer = "$mainUrl/",
                        quality = Qualities.Unknown.value,
                        type = ExtractorLinkType.M3U8
                    )
                )

                // Lấy phụ đề
                val tracksRegex = Regex("""tracks:\s*(\[.*?\])""", RegexOption.DOT_MATCHES_ALL)
                tracksRegex.find(scriptContent)?.groupValues?.getOrNull(1)?.let { tracksJson ->
                    val subtitleRegex = Regex("""file:\s*["'](.*?)["'].*?label:\s*["'](.*?)["']""")
                    subtitleRegex.findAll(tracksJson).forEach { match ->
                        val subUrl = fixUrl(match.groupValues[1])
                        val subLabel = match.groupValues[2]
                        subtitleCallback(SubtitleFile(subLabel, subUrl))
                    }
                }
                return true
            }
        }

        // Fallback: nếu không giải mã được, thử tìm iframe
        playerResponse.select("iframe[src]").forEach { iframe ->
            val iframeSrc = iframe.attr("src").let { if (it.startsWith("//")) "https:$it" else it }
            if (loadExtractor(iframeSrc, data, subtitleCallback, callback)) {
                return true
            }
        }

        return false
    }
}
