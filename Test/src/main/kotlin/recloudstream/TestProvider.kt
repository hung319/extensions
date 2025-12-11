package recloudstream

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.AppUtils
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.newExtractorLink
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.HomePageResponse
import com.lagradost.cloudstream3.HomePageList
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.Episode
import org.jsoup.nodes.Element
import com.lagradost.cloudstream3.network.CloudflareKiller
import com.lagradost.cloudstream3.SearchQuality

import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec
import java.security.Security
import java.util.Base64
import org.bouncycastle.jce.provider.BouncyCastleProvider

// Imports cho Interceptor, xử lý URL động, và coroutines
import okhttp3.Interceptor
import okhttp3.Response
import okhttp3.ResponseBody
import okhttp3.ResponseBody.Companion.toResponseBody
import java.net.URL
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import com.lagradost.cloudstream3.CommonActivity.showToast
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import android.widget.Toast
import java.text.Normalizer // Import quan trọng để xử lý dấu tiếng Việt

class PhimDinhCaoProvider : MainAPI() {
    // === CƠ CHẾ DOMAIN ĐỘNG ===
    override var mainUrl = "https://phimlongtieng.link"
    private var baseUrl = "https://motchill.tv"
    private var domainCheckPerformed = false

    override var name = "Phim Đỉnh Cao"
    override val hasMainPage = true
    override var lang = "vi"
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(
        TvType.Movie,
        TvType.TvSeries
    )
    
    // === Cấu trúc trang chủ ===
    override val mainPage = mainPageOf(
        "/" to "Motchill Đề Cử",
        "phim-bo" to "Phim Bộ Mới",
        "phim-le" to "Phim Lẻ Mới"
    )

    companion object {
        private var toastShown = false
    }

    init {
        Security.getProvider("BC") ?: Security.addProvider(BouncyCastleProvider())
    }

    private val cfKiller = CloudflareKiller()
    
    private val userAgentHeader = mapOf("User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/96.0.4664.110 Safari/537.36")

    // === HÀM TIỆN ÍCH XỬ LÝ TIẾNG VIỆT ===
    private fun String.unaccent(): String {
        val temp = Normalizer.normalize(this, Normalizer.Form.NFD)
        return Regex("\\p{InCombiningDiacriticalMarks}+").replace(temp, "")
            .replace("đ", "d").replace("Đ", "D")
    }

    private suspend fun getBaseUrl(): String {
        if (domainCheckPerformed) {
            return baseUrl
        }
        try {
            val response = app.get(mainUrl, allowRedirects = true, interceptor = cfKiller)
            val finalUrl = response.url 
            
            if (finalUrl.isNotBlank()) {
                val newBase = URL(finalUrl).let { "${it.protocol}://${it.host}" }
                if (newBase != baseUrl && (newBase.contains("motchill") || newBase.contains("motphim"))) {
                    println("$name: Domain updated from $baseUrl to $newBase")
                    baseUrl = newBase
                }
            }
        } catch (e: Exception) {
            println("$name: Failed to get new domain, using default: $baseUrl. Error: ${e.message}")
        } finally {
            domainCheckPerformed = true
        }
        return baseUrl
    }

    private data class EncryptedSourceJson(
        val ciphertext: String,
        val salt: String,
        val iv: String
    )

    private fun hexStringToByteArray(s: String): ByteArray {
        val len = s.length
        val data = ByteArray(len / 2)
        for (i in 0 until len step 2) {
            data[i / 2] = ((Character.digit(s[i], 16) shl 4)
                          + Character.digit(s[i+1], 16)).toByte()
        }
        return data
    }

    private fun cryptoJSAesDecrypt(passphrase: String, encryptedJsonString: String): String? {
        return try {
            val dataToParse = if (!encryptedJsonString.trimStart().startsWith("{")) {
                   return null
            } else {
                encryptedJsonString
            }
            val encryptedData = AppUtils.parseJson<EncryptedSourceJson>(dataToParse)
            val saltBytes = hexStringToByteArray(encryptedData.salt)
            val ivBytes = hexStringToByteArray(encryptedData.iv)
            val keySizeBits = 256
            val iterations = 999
            val factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA512", "BC")
            val spec = PBEKeySpec(passphrase.toCharArray(), saltBytes, iterations, keySizeBits)
            val secret = factory.generateSecret(spec)
            val keyBytes = secret.encoded
            val secretKey = SecretKeySpec(keyBytes, "AES")
            val cipher = Cipher.getInstance("AES/CBC/PKCS7Padding", "BC")
            cipher.init(Cipher.DECRYPT_MODE, secretKey, IvParameterSpec(ivBytes))
            val ciphertextBytes = Base64.getDecoder().decode(encryptedData.ciphertext)
            val decryptedBytes = cipher.doFinal(ciphertextBytes)
            String(decryptedBytes, Charsets.UTF_8)
        } catch (e: Exception) {
            println("$name Decryption Error for '$encryptedJsonString': ${e.message}")
            null
        }
    }

    private fun getQualityFromSearchString(qualityString: String?): SearchQuality? {
        return when {
            qualityString == null -> null; qualityString.contains("1080") -> SearchQuality.HD
            qualityString.contains("720") -> SearchQuality.HD
            qualityString.contains("4K", ignoreCase = true) || qualityString.contains("2160") -> SearchQuality.FourK
            qualityString.contains("HD", ignoreCase = true) -> SearchQuality.HD
            qualityString.contains("Bản Đẹp", ignoreCase = true) -> SearchQuality.HD
            qualityString.contains("SD", ignoreCase = true) -> SearchQuality.SD
            qualityString.contains("CAM", ignoreCase = true) -> SearchQuality.CamRip
            qualityString.contains("DVD", ignoreCase = true) -> SearchQuality.DVD
            qualityString.contains("WEB", ignoreCase = true) -> SearchQuality.WebRip
            else -> null
        }
    }

    private fun getQualityForLink(url: String): Int {
        return when {
            url.contains("1080p", ignoreCase = true) -> Qualities.P1080.value
            url.contains("720p", ignoreCase = true) -> Qualities.P720.value
            url.contains("480p", ignoreCase = true) -> Qualities.P480.value
            url.contains("360p", ignoreCase = true) -> Qualities.P360.value
            else -> Qualities.Unknown.value
        }
    }
    
    // === PARSER 1: Dành cho Trang Chủ (h4.name) ===
    private fun parseMoviesFromHomePage(document: Element): List<SearchResponse> {
        return document.select("ul.list-film li").mapNotNull { item ->
            val titleElement = item.selectFirst("div.info h4.name a")
            val name = titleElement?.text()
            val movieUrl = fixUrlNull(titleElement?.attr("href"))
            
            val year = item.selectFirst("div.info h4.name")?.ownText()?.trim()?.toIntOrNull()

            var posterUrl = fixUrlNull(item.selectFirst("img")?.attr("src"))
            if (posterUrl.isNullOrEmpty() || posterUrl.contains("tiktokcdn.com")) { 
                val onerrorPoster = item.selectFirst("img")?.attr("onerror")
                if (onerrorPoster?.contains("this.src=") == true) {
                    posterUrl = fixUrlNull(onerrorPoster.substringAfter("this.src='").substringBefore("';"))
                }
            }
            if (posterUrl.isNullOrEmpty()) {
                posterUrl = fixUrlNull(item.selectFirst("img")?.attr("data-src"))
            }

            val status = item.selectFirst("div.status")?.text()?.trim()
            val type = if (status?.contains("Tập") == true || (status?.contains("/") == true && !status.contains("Full", ignoreCase = true))) TvType.TvSeries else TvType.Movie
            val qualityText = item.selectFirst("div.HD")?.text()?.trim() ?: status

            if (name != null && movieUrl != null) {
                newMovieSearchResponse(name, movieUrl) {
                    this.type = type
                    this.posterUrl = posterUrl
                    this.year = year
                    this.quality = getQualityFromSearchString(qualityText)
                }
            } else null
        }
    }

    // === PARSER 2: Dành cho Search/Category (div.name) ===
    // Đã fix CSS selector theo HTML thực tế
    private fun parseMoviesFromSearchOrCategoryPage(document: Element): List<SearchResponse> {
        return document.select("ul.list-film li").mapNotNull { item ->
            // Selector chính xác dựa trên HTML search: div.info > div.name > a
            val titleElement = item.selectFirst("div.info div.name a") ?: return@mapNotNull null
            val nameText = titleElement.text()
            // Year nằm trong div.name nhưng là text node (ownText), bên cạnh thẻ a
            val yearText = item.selectFirst("div.info div.name")?.ownText()?.trim()
            val name = nameText.trim()
            val movieUrl = fixUrlNull(titleElement.attr("href")) ?: return@mapNotNull null
            
            var posterUrl = fixUrlNull(item.selectFirst("img")?.attr("src"))
            // Check lỗi ảnh tiktok/google giống web
            if (posterUrl.isNullOrEmpty() || posterUrl.contains("tiktokcdn.com") || posterUrl.contains("googleusercontent")) {
                val onerrorPoster = item.selectFirst("img")?.attr("onerror")
                if (onerrorPoster?.contains("this.src=") == true) {
                    posterUrl = fixUrlNull(onerrorPoster.substringAfter("this.src='").substringBefore("';"))
                }
            }
            if (posterUrl.isNullOrEmpty()) {
                posterUrl = fixUrlNull(item.selectFirst("img")?.attr("data-src"))
            }

            val status = item.selectFirst("div.status")?.text()?.trim()
            val type = if (status?.contains("Tập") == true || (status?.contains("/") == true && !status.contains("Full", ignoreCase = true))) TvType.TvSeries else TvType.Movie
            val qualityText = item.selectFirst("div.HD")?.text()?.trim() ?: status

            newMovieSearchResponse(name, movieUrl) {
                this.type = type
                this.posterUrl = posterUrl
                this.year = yearText?.toIntOrNull()
                this.quality = getQualityFromSearchString(qualityText)
            }
        }
    }
    
    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        if (!toastShown) {
            withContext(Dispatchers.Main) {
                CommonActivity.activity?.let { activity ->
                    showToast(activity, "Free Repo From H4RS", Toast.LENGTH_LONG)
                }
            }
            toastShown = true
        }

        val currentUrl = getBaseUrl()

        // Xử lý pagination
        val pageSuffix = if (page <= 1) "/" else "/page/$page/" 
        
        val (urlToFetch, isHomepage) = when (request.data) {
            "/" -> Pair(currentUrl, true) // Trang chủ
            "phim-bo" -> Pair("$currentUrl/phim-bo$pageSuffix", false)
            "phim-le" -> Pair("$currentUrl/phim-le$pageSuffix", false)
            else -> return newHomePageResponse(emptyList())
        }
        
        if (isHomepage && page > 1) return newHomePageResponse(emptyList())

        val document = app.get(urlToFetch, interceptor = cfKiller).document
        
        val movies = if (isHomepage) {
            // Trang chủ: Dùng parser homepage (h4.name)
            // Chỉ lấy section "Motchill Đề Cử" (nằm trong div.col-lg-9)
            val mainListElement = document.selectFirst("div.col-lg-9")
            if (mainListElement != null) {
                 parseMoviesFromHomePage(mainListElement)
            } else {
                 parseMoviesFromHomePage(document) // Fallback
            }
        } else {
            // Trang Category: Dùng parser search/category (div.name)
            parseMoviesFromSearchOrCategoryPage(document)
        }

        return newHomePageResponse(request.name, movies, hasNext = movies.isNotEmpty())
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val currentUrl = getBaseUrl()
        // FIX: unaccent() để xử lý dấu tiếng Việt, sau đó replace space bằng dấu gạch ngang
        val searchQuery = query.unaccent().trim().replace(Regex("\\s+"), "-").lowercase()
        val searchUrl = "$currentUrl/search/$searchQuery/"
        val document = app.get(searchUrl, interceptor = cfKiller).document
        
        // Dùng parser của trang Search (div.name)
        return parseMoviesFromSearchOrCategoryPage(document)
    }

    override suspend fun load(url: String): LoadResponse? {
        val currentUrl = getBaseUrl()
        val document = app.get(url, interceptor = cfKiller, referer = currentUrl).document
        val title = document.selectFirst("h1.movie-title span.title-1")?.text()?.trim() ?: return null
        val year = document.selectFirst("h1.movie-title span.title-year")?.text()?.replace("(", "")?.replace(")", "")?.trim()?.toIntOrNull()
        var poster = fixUrlNull(document.selectFirst("div.movie-image div.poster img")?.attr("src"))
        if (poster.isNullOrEmpty() || poster.contains("p21-ad-sg.ibyteimg.com")) {
            val onerrorPoster = document.selectFirst("div.movie-image div.poster img")?.attr("onerror")
            if (onerrorPoster?.contains("this.src=") == true) poster = fixUrlNull(onerrorPoster.substringAfter("this.src='").substringBefore("';"))
        }
        if (poster.isNullOrEmpty()) poster = fixUrlNull(document.selectFirst("div.movie-image div.poster img")?.attr("data-src"))
        val plot = document.selectFirst("div#info-film div.detail-content-main")?.text()?.trim()
        val genres = document.select("dl.movie-dl dd.movie-dd.dd-cat a").mapNotNull { it.text().trim() }.toMutableList()
        document.select("div#tags div.tag-list h3 a").forEach { tagElement -> val tagText = tagElement.text().trim(); if (!genres.contains(tagText)) genres.add(tagText) }
        
        val recommendations = document.select("div#movie-hot div.owl-carousel div.item").mapNotNull { item ->
            val recLinkTag = item.selectFirst("a")
            val recUrl = fixUrlNull(recLinkTag?.attr("href")) ?: return@mapNotNull null
            
            val recName = recLinkTag?.attr("title")?.takeIf { it.isNotBlank() }
                ?: item.selectFirst("div.overlay h4.name a")?.text()?.trim()?.takeIf { it.isNotBlank() }
                ?: return@mapNotNull null

            var recPosterUrl = fixUrlNull(item.selectFirst("img")?.attr("src"))
            if (recPosterUrl.isNullOrEmpty() || recPosterUrl.contains("p21-ad-sg.ibyteimg.com")) {
                val onerrorPoster = item.selectFirst("img")?.attr("onerror")
                if (onerrorPoster?.contains("this.src=") == true) recPosterUrl = fixUrlNull(onerrorPoster.substringAfter("this.src='").substringBefore("';"))
            }
            if (recPosterUrl.isNullOrEmpty()) recPosterUrl = fixUrlNull(item.selectFirst("img")?.attr("data-src"))
            
            if (recPosterUrl.isNullOrEmpty()) {
                try {
                    val recPageDoc = app.get(recUrl, referer = url).document
                    var fallbackPoster = fixUrlNull(recPageDoc.selectFirst("div.movie-image div.poster img")?.attr("src"))
                    if (fallbackPoster.isNullOrEmpty()) {
                        fallbackPoster = fixUrlNull(recPageDoc.selectFirst("div.movie-image div.poster img")?.attr("data-src"))
                    }
                    recPosterUrl = fallbackPoster
                } catch (_: Exception) { }
            }
            
            newMovieSearchResponse(recName, recUrl) { this.type = TvType.Movie; this.posterUrl = recPosterUrl }
        }
        
        val episodes = ArrayList<Episode>()
        val episodeElements = document.select("div.page-tap ul li a")
        val isTvSeriesBasedOnNames = episodeElements.any { val epName = it.selectFirst("span")?.text()?.trim() ?: it.text().trim(); Regex("""Tập\s*\d+""", RegexOption.IGNORE_CASE).containsMatchIn(epName) && !epName.contains("Full", ignoreCase = true) }
        val isTvSeries = episodeElements.size > 1 || isTvSeriesBasedOnNames
        if (episodeElements.isNotEmpty()) {
            episodeElements.forEachIndexed { index, element ->
                val attrHref = element.attr("href")
                val episodeLink = if (attrHref.isNullOrBlank()) "" else fixUrl(attrHref)
                var episodeNameSource = element.selectFirst("span")?.text()?.trim() ?: element.text().trim()
                var finalEpisodeName: String
                if (episodeNameSource.isNullOrBlank()) finalEpisodeName = "Server ${index + 1}"
                else {
                    if (episodeNameSource.toIntOrNull() != null || (!episodeNameSource.contains("Tập ", ignoreCase = true) && episodeNameSource.matches(Regex("""\d+""")))) finalEpisodeName = "Tập $episodeNameSource"
                    else finalEpisodeName = episodeNameSource
                }
                if (episodeLink.isNotBlank()) {
                    episodes.add(newEpisode(episodeLink) {this.name = finalEpisodeName})
                }
            }
        } else {
            document.selectFirst("a#btn-film-watch.btn-red[href]")?.let { watchButton ->
               val attrHref = watchButton.attr("href")
                val movieWatchLink = if(attrHref.isNullOrBlank()) "" else fixUrl(attrHref)
                if (movieWatchLink.isNotBlank()) episodes.add(newEpisode(movieWatchLink) {this.name = title})
            }
        }
        val currentSyncData = mutableMapOf("url" to url)
        
        if (isTvSeries || (episodes.size > 1 && !episodes.all { it.name == title })) {
            return newTvSeriesLoadResponse(
                name = title,
                url = url,
                type = TvType.TvSeries,
                episodes = episodes.distinctBy { it.data }.toList()
            ) {
                this.posterUrl = poster
                this.year = year
                this.plot = plot
                this.tags = genres.distinct().toList()
                this.recommendations = recommendations
                this.syncData = currentSyncData
            }
        } else {
            val movieDataUrl = episodes.firstOrNull()?.data ?: url
            return newMovieLoadResponse(
                name = title,
                url = url,
                type = TvType.Movie,
                dataUrl = movieDataUrl
            ) {
                this.posterUrl = poster
                this.year = year
                this.plot = plot
                this.tags = genres.distinct().toList()
                this.recommendations = recommendations
                this.syncData = currentSyncData
            }
        }
    }
    
    private suspend fun extractVideoFromPlay2Page(pageUrl: String, pageRefererForGet: String): String? {
        try {
            val pageDocument = app.get(pageUrl, interceptor = cfKiller, referer = pageRefererForGet).document
            val scriptElements = pageDocument.select("script:containsData(CryptoJSAesDecrypt)")
            for (scriptElement in scriptElements) {
                val scriptContent = scriptElement.html()
                val trailerRegex = Regex("""function\s+trailer\s*\(\s*\)\s*\{\s*return\s+CryptoJSAesDecrypt\(\s*'Encrypt'\s*,\s*`([^`]*)`\s*\)\s*;\s*\}""")
                val matchResult = trailerRegex.find(scriptContent)
                if (matchResult != null) {
                    val encryptedJsonString = matchResult.groupValues[1]
                    if (encryptedJsonString.startsWith("{") && encryptedJsonString.endsWith("}")) {
                        val decryptedLink = cryptoJSAesDecrypt("Encrypt", encryptedJsonString)
                        if (decryptedLink != null) {
                            return decryptedLink
                        }
                    }
                }
            }
        } catch (e: Exception) {
            println("$name: Error fetching/parsing play2 page $pageUrl: ${e.message}")
        }
        return null
    }

    override suspend fun loadLinks(
        data: String, 
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit, 
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val currentUrl = getBaseUrl()
        val initialPageDocument = app.get(data, interceptor = cfKiller, referer = currentUrl).document
        
        val scriptElementsInitial = initialPageDocument.select("script:containsData(CryptoJSAesDecrypt)")
        var iframeUrlPlayerPage: String? = null 

        for (scriptElement in scriptElementsInitial) {
            val scriptContent = scriptElement.html()
            val regex = Regex("""function\s+trailer\s*\(\s*\)\s*\{\s*return\s+CryptoJSAesDecrypt\(\s*'Encrypt'\s*,\s*`([^`]*)`\s*\)\s*;\s*\}""")
            val matchResult = regex.find(scriptContent)
            if (matchResult != null) {
                val encryptedJsonString = matchResult.groupValues[1]
                if (encryptedJsonString.startsWith("{") && encryptedJsonString.endsWith("}")) {
                    iframeUrlPlayerPage = cryptoJSAesDecrypt("Encrypt", encryptedJsonString)
                    if (iframeUrlPlayerPage != null) break
                }
            }
        }

        if (iframeUrlPlayerPage.isNullOrBlank()) {
            println("$name: Could not get iframeUrlPlayerPage from $data.")
            return false 
        }
        
        val absoluteIframeUrlPlayerPage = fixUrl(iframeUrlPlayerPage) 
        println("$name: Player Page URL (player.html): $absoluteIframeUrlPlayerPage")
        val playerPageDocument = app.get(absoluteIframeUrlPlayerPage, interceptor = cfKiller, referer = data).document
        
        val headers = userAgentHeader + mapOf("Origin" to currentUrl)
        
        return coroutineScope {
            val foundLinks = playerPageDocument.select("div#vb_server_list span.vb_btnt-primary").map { button ->
                async {
                    val serverName = button.text()?.trim() ?: "Unknown Server"
                    if (serverName.contains("HY3", ignoreCase = true)) {
                        println("$name: Skipping server $serverName.")
                        false
                    } else {
                        val onclickAttr = button.attr("onclick")
                        val urlRegex = Regex("""vb_load_player\(\s*this\s*,\s*['"]([^'"]+)['"]\s*\)""")
                        val serverUrlStringFromButton = urlRegex.find(onclickAttr)?.groupValues?.get(1)
                        
                        if (serverUrlStringFromButton.isNullOrBlank()) {
                            false
                        } else {
                            val fullServerUrlTarget = fixUrl(serverUrlStringFromButton)
                            var directVideoUrl: String? = null

                            if (fullServerUrlTarget.contains("play2.php")) { 
                                directVideoUrl = extractVideoFromPlay2Page(fullServerUrlTarget, absoluteIframeUrlPlayerPage) 
                            } else if (fullServerUrlTarget.startsWith("http")) {
                                directVideoUrl = fullServerUrlTarget
                            }

                            if (directVideoUrl != null) {
                                println("$name: Found source for $serverName: $directVideoUrl")
                                val refererForLink = if (fullServerUrlTarget.contains("play2.php")) fullServerUrlTarget else absoluteIframeUrlPlayerPage
                                
                                callback(
                                    newExtractorLink(
                                        source = name,
                                        name = serverName,
                                        url = directVideoUrl,
                                        type = if (directVideoUrl.contains(".m3u8", ignoreCase = true)) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                                    ) {
                                        this.referer = refererForLink
                                        this.quality = getQualityForLink(directVideoUrl)
                                        this.headers = headers
                                    }
                                )
                                true
                            } else {
                                println("$name: Failed to extract from server: $serverName ($fullServerUrlTarget)")
                                false
                            }
                        }
                    }
                }
            }.awaitAll()
            
            foundLinks.any { it }
        }
    }
    
    override fun getVideoInterceptor(extractorLink: ExtractorLink): Interceptor? {
        return object : Interceptor {
            override fun intercept(chain: Interceptor.Chain): Response {
                val request = chain.request()
                val response = chain.proceed(request)
                val url = request.url.toString()

                if (url.contains("api.cloudbeta.win") || url.contains(".tiktokcdn.")) {
                    response.body?.let { body ->
                        try {
                            val fixedBytes = skipByteError(body)
                            val newBody = fixedBytes.toResponseBody(body.contentType())
                            return response.newBuilder().body(newBody).build()
                        } catch (e: Exception) {
                            // Bỏ qua
                        }
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
