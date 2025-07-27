package recloudstream

import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.utils.AppUtils.toJson
import org.jsoup.nodes.Element
import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlin.text.Charsets
import okhttp3.Interceptor
import okhttp3.Response

// ================= K_UTILITY CHO MÃ HÓA/GIẢI MÃ =================
private object K_Utility {
    private const val HASH_CIPHER = "AES/CBC/PKCS5Padding"
    private const val KDF_DIGEST = "MD5"
    private const val KEY_SIZE = 256
    private const val IV_SIZE = 128

    private fun evpkdf(password: ByteArray, keySize: Int, ivSize: Int, salt: ByteArray, resultKey: ByteArray, resultIv: ByteArray) {
        val keySizeInBytes = keySize / 32
        val ivSizeInBytes = ivSize / 32
        val targetKeySize = keySizeInBytes + ivSizeInBytes
        val derivedBytes = ByteArray(targetKeySize * 4)
        val digest = MessageDigest.getInstance(KDF_DIGEST)
        var lastDigest: ByteArray? = null
        var totalBytes = 0

        while (totalBytes < targetKeySize * 4) {
            if (lastDigest != null) {
                digest.update(lastDigest)
            }
            digest.update(password)
            val currentDigest = digest.digest(salt)
            digest.reset()

            System.arraycopy(currentDigest, 0, derivedBytes, totalBytes, currentDigest.size)
            totalBytes += currentDigest.size
            lastDigest = currentDigest
        }

        System.arraycopy(derivedBytes, 0, resultKey, 0, keySizeInBytes * 4)
        System.arraycopy(derivedBytes, keySizeInBytes * 4, resultIv, 0, ivSizeInBytes * 4)
    }

    fun decrypt(cipherText: String, password: String): String? {
        return try {
            val decodedBytes = java.util.Base64.getDecoder().decode(cipherText.toByteArray(Charsets.UTF_8))
            val salt = decodedBytes.copyOfRange(8, 16)
            val cipherBytes = decodedBytes.copyOfRange(16, decodedBytes.size)

            val key = ByteArray(KEY_SIZE / 8)
            val iv = ByteArray(IV_SIZE / 8)
            evpkdf(password.toByteArray(Charsets.UTF_8), KEY_SIZE, IV_SIZE, salt, key, iv)

            val cipher = Cipher.getInstance(HASH_CIPHER)
            cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(iv))
            String(cipher.doFinal(cipherBytes), Charsets.UTF_8)
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }
}

private fun md5(input: String): String {
    return MessageDigest.getInstance("MD5").digest(input.toByteArray()).joinToString("") {
        "%02x".format(it)
    }
}

private fun aesEncrypt(data: String, key: String): String? {
    return try {
        val cipher = Cipher.getInstance("AES/ECB/PKCS5Padding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key.toByteArray(), "AES"))
        java.util.Base64.getEncoder().encodeToString(cipher.doFinal(data.toByteArray()))
    } catch (e: Exception) {
        null
    }
}

// ================= DATA CLASSES CHO PAYLOAD =================
private data class ResponseToken(
    @JsonProperty("status") val status: Int,
    @JsonProperty("type") val type: String?,
    @JsonProperty("data") val data: String?,
)

private data class Payload(
    @JsonProperty("idfile") val idfile: String,
    @JsonProperty("iduser") val iduser: String,
    @JsonProperty("refe") val refe: String,
    @JsonProperty("plic") val plic: String,
    @JsonProperty("addr") val addr: String,
    @JsonProperty("t") val t: String,
    @JsonProperty("ispro") val ispro: Boolean,
    @JsonProperty("dev") val dev: JWPlayer,
)

private data class JWPlayer(
    @JsonProperty("browser") val browser: JWPlayerBrowser,
    @JsonProperty("os") val os: JWPlayerOS,
    @JsonProperty("features") val features: JWPlayerFeatures,
)

private data class JWPlayerBrowser(
    @JsonProperty("chrome") val chrome: Boolean,
    @JsonProperty("edge") val edge: Boolean,
    @JsonProperty("facebook") val facebook: Boolean,
    @JsonProperty("firefox") val firefox: Boolean,
    @JsonProperty("ie") val ie: Boolean,
    @JsonProperty("instagram") val instagram: Boolean,
    @JsonProperty("safari") val safari: Boolean,
    @JsonProperty("version") val version: JWPlayerBrowserVersion,
    @JsonProperty("opera") val opera: Boolean
)

private data class JWPlayerBrowserVersion(
    @JsonProperty("version") val version: String,
    @JsonProperty("major") val major: Int,
    @JsonProperty("minor") val minor: Int
)

private data class JWPlayerOS(
    @JsonProperty("android") val android: Boolean,
    @JsonProperty("androidNative") val androidNative: Boolean,
    @JsonProperty("iOS") val iOS: Boolean,
    @JsonProperty("iPad") val iPad: Boolean,
    @JsonProperty("iPhone") val iPhone: Boolean,
    @JsonProperty("linux") val linux: Boolean,
    @JsonProperty("mac") val mac: Boolean,
    @JsonProperty("windows") val windows: Boolean,
    @JsonProperty("version") val version: JWPlayerOSVersion,
    @JsonProperty("chromeOS") val chromeOS: Boolean
)

private data class JWPlayerOSVersion(
    @JsonProperty("version") val version: String,
    @JsonProperty("major") val major: Int,
    @JsonProperty("minor") val minor: Int
)

private data class JWPlayerFeatures(
    @JsonProperty("flash") val flash: Boolean,
    @JsonProperty("flashVersion") val flashVersion: Boolean,
    @JsonProperty("backgroundLoading") val backgroundLoading: Boolean
)

// ================= CLASS PROVIDER CHÍNH =================
class TvHayProvider : MainAPI() {
    override var mainUrl = "https://tvhay.io"
    override var name = "TVHay"
    override val hasMainPage = true
    override var lang = "vi"
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)
    
    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {
        val document = app.get("$mainUrl/page/$page").document
        val homePageList = ArrayList<HomePageList>()

        val movieUpdateSection = document.selectFirst("div#movie-update")
        if (movieUpdateSection != null) {
            val title = movieUpdateSection.selectFirst("h2.title")?.text() ?: "Phim mới cập nhật"
            val items = movieUpdateSection.select("ul.list-film li").mapNotNull { it.toSearchResult() }
            if (items.isNotEmpty()) {
                homePageList.add(HomePageList(title, items))
            }
        }

        val hotMovieSection = document.selectFirst("div#movie-hot")
        if (hotMovieSection != null) {
            val title = "Phim Đề Cử"
            val items = hotMovieSection.select("ul.listfilm li").mapNotNull { it.toSearchResult() }
            if (items.isNotEmpty()) {
                homePageList.add(HomePageList(title, items, true))
            }
        }
        
        return HomePageResponse(homePageList)
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val linkElement = this.selectFirst("a") ?: return null
        val href = fixUrl(linkElement.attr("href"))
        if (href.contains("/the-loai/") || href.contains("/quoc-gia/")) return null
        
        val title = this.selectFirst("div.name a")?.text() ?: this.selectFirst("img")?.attr("alt") ?: return null
        var posterUrl = fixUrlNull(this.selectFirst("img.lazy")?.attr("data-original"))
        if (posterUrl == null) {
            posterUrl = fixUrlNull(this.selectFirst("img")?.attr("src"))
        }

        return if (href.contains("/phim-bo/") || this.selectFirst("div.status")?.text()?.contains("/") == true) {
            newTvSeriesSearchResponse(title, href, TvType.TvSeries) {
                this.posterUrl = posterUrl
            }
        } else {
            newMovieSearchResponse(title, href, TvType.Movie) {
                this.posterUrl = posterUrl
            }
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val searchUrl = "$mainUrl/search/$query/"
        val document = app.get(searchUrl).document
        
        return document.select("ul.list-film li").mapNotNull {
            it.toSearchResult()
        }
    }

    override suspend fun load(url: String): LoadResponse? {
        val document = app.get(url).document

        val title = document.selectFirst("h1.title")?.text()?.trim() ?: return null
        val posterUrl = fixUrlNull(document.selectFirst("div.poster img")?.attr("src"))
        val plot = document.selectFirst("div.detail div.tab.text")?.text()?.trim()
        val tags = document.select("div.dinfo dd a[href*='/phim-']").map { it.text() }
        val year = document.selectFirst("span.year")?.text()?.removeSurrounding("(", ")")?.toIntOrNull()

        val watchButtonUrl = document.selectFirst("a.btn-watch")?.attr("href")

        if (watchButtonUrl.isNullOrBlank()) return null
        
        val watchPageDoc = app.get(fixUrl(watchButtonUrl)).document
        val servers = watchPageDoc.select("ul.episodelistsv li a")

        val isTvSeries = document.select("ul.episodelistinfo li").size > 1 || url.contains("/phim-bo/")
        
        if (isTvSeries) {
            val episodes = watchPageDoc.select("ul.episodelist li a").map {
                newEpisode(it.attr("href")) {
                    name = it.text().trim()
                }
            }.reversed()

            return newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
                this.posterUrl = posterUrl
                this.plot = plot
                this.tags = tags
                this.year = year
            }
        } else { 
             val movieServers = servers.mapNotNull {
                newEpisode(it.attr("href")) {
                    name = it.attr("title").replace("Server ", "").trim()
                }
            }
            return newMovieLoadResponse(title, url, TvType.Movie, movieServers) {
                this.posterUrl = posterUrl
                this.plot = plot
                this.tags = tags
                this.year = year
            }
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        try {
            val document = app.get(data).document
            
            val script = document.select("script").find { it.data().contains("eval(function(w,i,s,e)") }?.data() ?: return false
            val unpacked = getAndUnpack(script)
            
            val iframeUrl = Regex("""<iframe src="([^"]+)""").find(unpacked)?.groupValues?.get(1) ?: return false

            val iframeDoc = app.get(iframeUrl, referer = "$mainUrl/").document
            val iframeScript = iframeDoc.select("script").find { it.data().contains("const idfile_enc") }?.data() ?: return false

            val idfileEnc = Regex("""const idfile_enc = "([^"]+)""").find(iframeScript)?.groupValues?.get(1) ?: return false
            val iduserEnc = Regex("""const idUser_enc = "([^"]+)""").find(iframeScript)?.groupValues?.get(1) ?: return false
            val domainApi = Regex("""const DOMAIN_API = '([^']+)""").find(iframeScript)?.groupValues?.get(1) ?: return false

            val ip = app.get("https://api.ipify.org/").text
            
            val idfile = K_Utility.decrypt(idfileEnc, "jcLycoRJT6OWjoWspgLMOZwS3aSS0lEn") ?: return false
            val iduser = K_Utility.decrypt(iduserEnc, "PZZ3J3LDbLT0GY7qSA5wW5vchqgpO36O") ?: return false

            val payload = Payload(
                idfile = idfile,
                iduser = iduser,
                refe = "$mainUrl/",
                plic = "noplf",
                addr = ip,
                t = System.currentTimeMillis().toString(),
                ispro = true,
                dev = JWPlayer(
                    browser = JWPlayerBrowser(true, false, false, false, false, false, false, JWPlayerBrowserVersion("129.0", 129, 0), false),
                    os = JWPlayerOS(false, false, false, false, false, false, false, true, JWPlayerOSVersion("10.0", 10, 0), false),
                    features = JWPlayerFeatures(true, true, true)
                )
            )
            
            val encryptedPayload = aesEncrypt(payload.toJson(), "vlVbUQhkOhoSfyteyzGeeDzU0BHoeTyZ") ?: return false
            val finalData = "$encryptedPayload|${md5(encryptedPayload + "KRWN3AdgmxEMcd2vLN1ju9qKe8Feco5h")}"

            val response = app.post(
                url = "$domainApi/playiframe",
                data = mapOf("data" to finalData)
            ).parsed<ResponseToken>()

            if (response.status == 1 && response.type == "url-m3u8-encv1" && response.data != null) {
                val decryptedLink = K_Utility.decrypt(response.data.replace("\"", ""), "oJwmvmVBajMaRCTklxbfjavpQO7SZpsL") ?: return false
                
                callback(
                    ExtractorLink(
                        name = this.name,
                        source = document.selectFirst("a.activesv")?.attr("title")?.replace("Server ", "") ?: "Default",
                        url = decryptedLink,
                        referer = "https://play.plhqtvhay.xyz/",
                        quality = Qualities.Unknown.value,
                        type = ExtractorLinkType.M3U8 
                    )
                )
                return true
            }
        } catch (e: Exception) {
            // <<<< ĐÃ BỎ logError(e) THEO YÊU CẦU >>>>
            throw e
        }
        return false
    }
    
    override fun getVideoInterceptor(extractorLink: ExtractorLink): Interceptor {
        return object : Interceptor {
            override fun intercept(chain: Interceptor.Chain): Response {
                val request = chain.request()
                if (request.url.toString().contains("plhqtvhay")) {
                    return chain.proceed(
                        request
                            .newBuilder()
                            .removeHeader("Host")
                            .header("Referer", "https://play.plhqtvhay.xyz/")
                            .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/116.0.0.0 Safari/537.36")
                            .build()
                    )
                }
                return chain.proceed(request)
            }
        }
    }
}
