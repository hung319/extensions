package com.lagradost.recloudstream

import android.util.Base64
// Thêm import cho M3u8Helper
import com.lagradost.cloudstream3.utils.M3u8Helper
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import org.jsoup.Jsoup
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

    private data class EncryptedSource(val ct: String, val iv: String, val s: String)

    private fun parseMovieCard(element: Element): SearchResponse? {
        val movieLink = element.selectFirst("a.movie-item") ?: return null
        val href = movieLink.attr("href")?.let { fixUrl(it) }
        val title = movieLink.selectFirst(".movie-title-1")?.text()?.trim()
            ?: movieLink.attr("title")?.trim()

        val imageHolder = movieLink.selectFirst(".movie-thumbnail, .public-film-item-thumb")
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

        val nominatedMovies = document.select("div.nominated-movie ul#movie-carousel-top li").mapNotNull {
            parseMovieCard(it)
        }
        if (nominatedMovies.isNotEmpty()) {
            lists.add(HomePageList("Phim Đề Cử", nominatedMovies))
        }

        val updatedMovies = document.select("div.update .content[data-name=all] ul.last-film-box li").mapNotNull {
            parseMovieCard(it)
        }
        if (updatedMovies.isNotEmpty()) {
            lists.add(HomePageList("Mới Cập Nhật", updatedMovies))
        }

        return if (lists.isEmpty()) null else HomePageResponse(lists)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val searchUrl = "$mainUrl/tim-nang-cao/?keyword=${URLEncoder.encode(query, "UTF-8")}&sapxep=1"
        return try {
            val document = app.get(searchUrl).document
            document.select("ul.last-film-box#movie-last-movie > li").mapNotNull {
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
        val description = document.selectFirst("div.news-article")?.text()?.trim()
        val year = document.selectFirst("h1.movie-title span.title-year")?.text()
            ?.replace(Regex("[()]"), "")?.trim()?.toIntOrNull()

        val statusText = document.selectFirst("dl.movie-dl dt:contains(Trạng thái:) + dd")?.text()?.trim()

        val status = when {
            statusText?.contains("Hoàn thành", true) == true -> ShowStatus.Completed
            statusText?.contains("/") == true -> {
                val parts = statusText!!.split("/").mapNotNull { it.trim().toIntOrNull() }
                if (parts.size == 2 && parts[0] == parts[1]) ShowStatus.Completed else ShowStatus.Ongoing
            }
            statusText != null -> ShowStatus.Ongoing
            else -> null
        }

        val genres = document.select("dl.movie-dl dt:contains(Thể loại:) + dd a")?.map { it.text() }

        var episodes = listOf<Episode>()
        val scriptContent = document.select("script:containsData(let playButton)").html()
        val anyEpisodesHtml = Regex("""anyEpisodes\s*=\s*'(.*?)';""").find(scriptContent)?.groupValues?.getOrNull(1)

        if (anyEpisodesHtml != null) {
            episodes = Jsoup.parse(anyEpisodesHtml).select("div.episodes ul li a").mapNotNull {
                val epHref = it.attr("href")?.let { eUrl -> fixUrl(eUrl) }
                val epName = it.attr("title")?.trim() ?: it.text()?.trim()
                val epNum = epName?.toIntOrNull()

                if (epHref != null) {
                    Episode(data = epHref, name = "Tập $epName", episode = epNum)
                } else null
            }.reversed()
        }

        return newTvSeriesLoadResponse(title, url, TvType.Anime, episodes) {
            this.posterUrl = poster
            this.plot = description
            this.year = year
            this.showStatus = status
            this.tags = genres
        }
    }

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
            data = result
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
            parseJson<String>(decryptedJsonStr)
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
        try {
            val episodeId = data.substringAfterLast('/').substringBefore('.').trim()
            val playerResponse = app.post("$mainUrl/player/player.php", data = mapOf("ID" to episodeId, "SV" to "4"), referer = data, headers = mapOf("X-Requested-With" to "XMLHttpRequest")).document
            val scriptContent = playerResponse.select("script:containsData(var thanhhoa)").html()
            
            val thanhhoaB64 = Regex("""var\s+thanhhoa\s*=\s*atob\(['"]([^'"]+)['"]\)""").find(scriptContent)?.groupValues?.getOrNull(1)

            if (thanhhoaB64 != null) {
                val videoUrl = decryptSource(thanhhoaB64, "caphedaklak")
                if (!videoUrl.isNullOrBlank()) {
                    
                    // *** ĐÂY LÀ PHẦN THAY ĐỔI CHÍNH ***
                    // Sử dụng M3u8Helper để phân tích và tạo link cho từng chất lượng
                    M3u8Helper.m3u8Generation(
                        M3u8Helper.M3u8Stream(
                            streamUrl = videoUrl,
                            headers = mapOf("Referer" to mainUrl)
                        )
                    ).forEach { stream ->
                        callback(newExtractorLink(
                            source = this.name,
                            name = if(stream.quality != null) "${this.name} ${stream.quality}p" else this.name,
                            url = stream.streamUrl,
                            type = ExtractorLinkType.M3U8
                        ) {
                            this.referer = mainUrl
                            if(stream.quality != null) {
                                this.quality = stream.quality!!
                            }
                        })
                    }

                    // Lấy phụ đề (giữ nguyên)
                    Regex("""tracks:\s*(\[.*?\])""", RegexOption.DOT_MATCHES_ALL).find(scriptContent)?.groupValues?.getOrNull(1)?.let { tracksJson ->
                        Regex("""file:\s*["'](.*?)["'].*?label:\s*["'](.*?)["']""").findAll(tracksJson).forEach { match ->
                            subtitleCallback(SubtitleFile(match.groupValues[2], fixUrl(match.groupValues[1])))
                        }
                    }
                    return true
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return false
    }
}
