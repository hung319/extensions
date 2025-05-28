package com.example.motchill // Make sure this matches your project structure

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.AppUtils // For parseJson and potentially other utilities
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.utils.Qualities // *** IMPORT FOR UTILS.QUALITIES ***
import org.jsoup.nodes.Element
import com.lagradost.cloudstream3.network.CloudflareKiller
import com.lagradost.cloudstream3.SearchQuality

// For Crypto
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec
import java.security.Security
// import java.security.spec.KeySpec // Not directly used, can be removed if PBEKeySpec is sufficient
import java.util.Base64
import org.bouncycastle.jce.provider.BouncyCastleProvider // *** CORRECT BOUNCY CASTLE IMPORT ***

class MotChillProvider : MainAPI() {
    override var mainUrl = "https://www.motchill86.com"
    override var name = "MotChill86"
    override val hasMainPage = true
    override var lang = "vi"
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(
        TvType.Movie,
        TvType.TvSeries
    )

    init {
        Security.getProvider("BC") ?: Security.addProvider(BouncyCastleProvider()) // Add if not already present
    }

    private val cfKiller = CloudflareKiller()

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
            val encryptedData = AppUtils.parseJson<EncryptedSourceJson>(encryptedJsonString)
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
            AppUtils.logError(e) // *** Using AppUtils.logError or this.logError if available as extension ***
            null
        }
    }

    private fun getQualityFromSearchString(qualityString: String?): SearchQuality? { // Renamed for clarity
        return when {
            qualityString == null -> null
            qualityString.contains("1080") -> SearchQuality.HD
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
    
    // Function to parse quality for ExtractorLink (returns Int)
    private fun getQualityForLink(url: String): Int {
        return when {
            url.contains("1080p", ignoreCase = true) -> Qualities.P1080.value
            url.contains("720p", ignoreCase = true) -> Qualities.P720.value
            url.contains("480p", ignoreCase = true) -> Qualities.P480.value
            url.contains("360p", ignoreCase = true) -> Qualities.P360.value
            else -> Qualities.Unknown.value 
        }
    }

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {
        if (page > 1) return newHomePageResponse(emptyList(), false)

        val document = app.get(mainUrl, interceptor = cfKiller).document
        val homePageList = ArrayList<HomePageList>()

        fun parseMovieListFromUl(element: Element?, title: String): HomePageList? {
            if (element == null) return null
            val movies = element.select("li").mapNotNull { item ->
                val titleElement = item.selectFirst("div.info h4.name a")
                val nameText = titleElement?.text()
                val yearText = item.selectFirst("div.info h4.name")?.ownText()?.trim()
                val name = nameText?.substringBeforeLast(yearText ?: "")?.trim() ?: nameText

                val movieUrl = fixUrlNull(titleElement?.attr("href"))
                var posterUrl = fixUrlNull(item.selectFirst("img")?.attr("src"))
                if (posterUrl.isNullOrEmpty() || posterUrl.contains("p21-ad-sg.ibyteimg.com")) {
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

                if (name != null && !name.startsWith("Advertisement") && movieUrl != null) {
                    newMovieSearchResponse(name, movieUrl) {
                        this.type = type
                        this.posterUrl = posterUrl
                        this.year = yearText?.toIntOrNull()
                        this.quality = getQualityFromSearchString(qualityText)
                    }
                } else {
                    null
                }
            }
            return if (movies.isNotEmpty()) HomePageList(title, movies) else null
        }

        document.selectFirst("#owl-demo.owl-carousel")?.let { owl ->
            val hotMovies = owl.select("div.item").mapNotNull { item ->
                val linkTag = item.selectFirst("a")
                val movieUrl = fixUrlNull(linkTag?.attr("href"))
                var name = linkTag?.attr("title")
                if (name.isNullOrEmpty()) {
                    name = item.selectFirst("div.overlay h4.name a")?.text()?.trim()
                }

                var posterUrl = fixUrlNull(item.selectFirst("img")?.attr("src"))
                if (posterUrl.isNullOrEmpty() || posterUrl.contains("p21-ad-sg.ibyteimg.com")) {
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
                
                if (name != null && movieUrl != null) {
                     newMovieSearchResponse(name, movieUrl) {
                        this.type = type
                        this.posterUrl = posterUrl
                        this.year = null 
                        this.quality = getQualityFromSearchString(status)
                    }
                } else null
            }
            if (hotMovies.isNotEmpty()) {
                homePageList.add(HomePageList("Phim Hot", hotMovies))
            }
        }

        document.select("div.heading-phim").forEach { sectionTitleElement ->
            val sectionTitleText = sectionTitleElement.selectFirst("h2.title_h1_st1 a span")?.text() 
                ?: sectionTitleElement.selectFirst("h2.title_h1_st1 a")?.text()
                ?: sectionTitleElement.selectFirst("h2.title_h1_st1")?.text()

            var movieListElementContainer = sectionTitleElement.nextElementSibling()
            var movieListElement = movieListElementContainer?.selectFirst("ul.list-film")
            
            if (movieListElement == null) {
                 movieListElementContainer = sectionTitleElement.parent()?.select("ul.list-film")?.first()
                 movieListElement = movieListElementContainer
            }
            
            val sectionTitle = sectionTitleText?.trim()
            if (sectionTitle != null && movieListElement != null) {
                parseMovieListFromUl(movieListElement, sectionTitle)?.let { homePageList.add(it) }
            }
        }
        return newHomePageResponse(homePageList.filter { it.list.isNotEmpty() }, false)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val searchQuery = query.trim().replace(Regex("\\s+"), "-").lowercase()
        val searchUrl = "$mainUrl/search/$searchQuery/" 
        
        val document = app.get(searchUrl, interceptor = cfKiller).document

        return document.select("ul.list-film li").mapNotNull { item ->
            val titleElement = item.selectFirst("div.info div.name a")
            val nameText = titleElement?.text()
            val movieUrl = fixUrlNull(titleElement?.attr("href"))

            val yearRegex = Regex("""\s+(\d{4})$""")
            val yearMatch = nameText?.let { yearRegex.find(it) }
            val year = yearMatch?.groupValues?.get(1)?.toIntOrNull()
            val name = yearMatch?.let { nameText.removeSuffix(it.value) }?.trim() ?: nameText?.trim()

            var posterUrl = fixUrlNull(item.selectFirst("img")?.attr("src"))
            if (posterUrl.isNullOrEmpty() || posterUrl.contains("p21-ad-sg.ibyteimg.com")) { 
                val onerrorPoster = item.selectFirst("img")?.attr("onerror")
                if (onerrorPoster?.contains("this.src=") == true) {
                     posterUrl = fixUrlNull(onerrorPoster.substringAfter("this.src='").substringBefore("';"))
                }
            }
            if (posterUrl.isNullOrEmpty()){
                 posterUrl = fixUrlNull(item.selectFirst("img")?.attr("data-src"))
             }

            val statusText = item.selectFirst("div.status")?.text()?.trim()
            val hdText = item.selectFirst("div.HD")?.text()?.trim()
            val qualityString = hdText ?: statusText

            val type = if (statusText?.contains("Tập") == true || (statusText?.contains("/") == true && statusText != "Full")) TvType.TvSeries else TvType.Movie
            
            if (name != null && movieUrl != null) {
                newMovieSearchResponse(name, movieUrl) {
                    this.type = type
                    this.posterUrl = posterUrl
                    this.year = year
                    this.quality = getQualityFromSearchString(qualityString)
                }
            } else {
                null
            }
        }
    }

    override suspend fun load(url: String): LoadResponse? {
        val document = app.get(url, interceptor = cfKiller).document

        val title = document.selectFirst("h1.movie-title span.title-1")?.text()?.trim() ?: return null
        val yearText = document.selectFirst("h1.movie-title span.title-year")?.text()?.replace("(", "")?.replace(")", "")?.trim()
        val year = yearText?.toIntOrNull()

        var poster = fixUrlNull(document.selectFirst("div.movie-image div.poster img")?.attr("src"))
        if (poster.isNullOrEmpty() || poster.contains("p21-ad-sg.ibyteimg.com")) {
            val onerrorPoster = document.selectFirst("div.movie-image div.poster img")?.attr("onerror")
            if (onerrorPoster?.contains("this.src=") == true) {
                 poster = fixUrlNull(onerrorPoster.substringAfter("this.src='").substringBefore("';"))
            }
        }
         if (poster.isNullOrEmpty()){
             poster = fixUrlNull(document.selectFirst("div.movie-image div.poster img")?.attr("data-src"))
         }

        val plot = document.selectFirst("div#info-film div.detail-content-main")?.text()?.trim()
        val genres = document.select("dl.movie-dl dd.movie-dd.dd-cat a").mapNotNull { it.text().trim() }.toMutableList()
        document.select("div#tags div.tag-list h3 a").forEach { tagElement ->
            val tagText = tagElement.text().trim()
            if (!genres.contains(tagText)) {
                genres.add(tagText)
            }
        }
        
        val recommendations = document.select("div#movie-hot div.owl-carousel div.item").mapNotNull { item ->
            val recLinkTag = item.selectFirst("a")
            val recUrl = fixUrlNull(recLinkTag?.attr("href"))
            var recName = recLinkTag?.attr("title")
             if (recName.isNullOrEmpty()) {
                recName = item.selectFirst("div.overlay h4.name a")?.text()?.trim()
            }
            var recPosterUrl = fixUrlNull(item.selectFirst("img")?.attr("src"))
             if (recPosterUrl.isNullOrEmpty() || recPosterUrl.contains("p21-ad-sg.ibyteimg.com")) {
                val onerrorPoster = item.selectFirst("img")?.attr("onerror")
                if (onerrorPoster?.contains("this.src=") == true) {
                     recPosterUrl = fixUrlNull(onerrorPoster.substringAfter("this.src='").substringBefore("';"))
                }
            }
             if (recPosterUrl.isNullOrEmpty()){
                 recPosterUrl = fixUrlNull(item.selectFirst("img")?.attr("data-src"))
             }

            if (recName != null && recUrl != null) {
                newMovieSearchResponse(recName, recUrl) {
                    this.type = TvType.Movie 
                    this.posterUrl = recPosterUrl
                }
            } else null
        }

        val episodes = ArrayList<Episode>()
        val episodeElements = document.select("div.page-tap ul li a")
        
        val isTvSeriesBasedOnNames = episodeElements.any {
            val epName = it.selectFirst("span")?.text()?.trim() ?: it.text().trim()
            Regex("""Tập\s*\d+""", RegexOption.IGNORE_CASE).containsMatchIn(epName) && !epName.contains("Full", ignoreCase = true)
        }
        val isTvSeries = episodeElements.size > 1 || isTvSeriesBasedOnNames
        
        if (episodeElements.isNotEmpty()) {
            episodeElements.forEachIndexed { index, element ->
                val episodeLink = fixUrl(element.attr("href"))
                var episodeNameSource = element.selectFirst("span")?.text()?.trim() 
                                    ?: element.text().trim()
                
                var finalEpisodeName: String
                if (episodeNameSource.isNullOrBlank()) {
                    finalEpisodeName = "Server ${index + 1}"
                } else {
                    if (episodeNameSource.toIntOrNull() != null) {
                        finalEpisodeName = "Tập $episodeNameSource"
                    } else if (!episodeNameSource.contains("Tập ", ignoreCase = true) && episodeNameSource.matches(Regex("""\d+"""))) {
                        finalEpisodeName = "Tập $episodeNameSource"
                    }
                    else {
                        finalEpisodeName = episodeNameSource
                    }
                }
                episodes.add(
                    newEpisode(episodeLink) { 
                        this.name = finalEpisodeName
                    }
                )
            }
        } else {
             document.selectFirst("a#btn-film-watch.btn-red[href]")?.let { watchButton ->
                val movieWatchLink = fixUrl(watchButton.attr("href"))
                if (movieWatchLink.isNotBlank()){
                     episodes.add(
                         newEpisode(movieWatchLink) {
                             this.name = title 
                         }
                     )
                }
            }
        }

        val currentSyncData = mutableMapOf("url" to url)

        if (isTvSeries || (episodes.size > 1 && !episodes.all { it.name == title })) {
            return newTvSeriesLoadResponse(
                title,
                url,
                TvType.TvSeries,
                episodes.distinctBy { it.data }.toList()
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
                title,
                url,
                TvType.Movie,
                movieDataUrl
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

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit, 
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val document = app.get(data, interceptor = cfKiller).document
        
        val scriptElements = document.select("script:containsData(CryptoJSAesDecrypt)")
        var decryptedUrl: String? = null

        for (scriptElement in scriptElements) {
            val scriptContent = scriptElement.html()
            val regex = Regex("""CryptoJSAesDecrypt\(\s*'Encrypt'\s*,\s*`([^`]*)`\s*\)""")
            val matchResult = regex.find(scriptContent)

            if (matchResult != null) {
                val encryptedJsonString = matchResult.groupValues[1]
                if (encryptedJsonString.startsWith("{") && encryptedJsonString.endsWith("}")) {
                    decryptedUrl = cryptoJSAesDecrypt("Encrypt", encryptedJsonString)
                    if (decryptedUrl != null) break 
                } else {
                     println("MotChillProvider: Extracted encrypted string is not valid JSON: $encryptedJsonString")
                }
            }
        }

        if (decryptedUrl != null) {
            val quality = getQualityForLink(decryptedUrl) 
            callback(
                ExtractorLink(
                    source = this.name,
                    name = "Server Decrypted", 
                    url = decryptedUrl,
                    referer = mainUrl,
                    quality = quality, 
                    type = if (decryptedUrl.contains(".m3u8", ignoreCase = true)) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                )
            )
            return true
        } else {
            document.select("script:containsData(jwplayer('phim-media').setup)").firstOrNull()?.data()?.let { scriptData ->
                val sourcesRegex = Regex("""sources\s*:\s*\[\s*(\{.*?file.*?\})\s*]""")
                sourcesRegex.find(scriptData)?.groupValues?.get(1)?.let { sourceBlock ->
                    val fileRegex = Regex("""file\s*:\s*["'](.*?)["']""")
                    val labelRegex = Regex("""label\s*:\s*["'](.*?)["']""")
                    
                    val videoUrl = fileRegex.find(sourceBlock)?.groupValues?.get(1)
                    val label = labelRegex.find(sourceBlock)?.groupValues?.get(1) ?: "Fallback"

                    if (!videoUrl.isNullOrBlank() && !videoUrl.contains("ads.mp4")) {
                        val quality = getQualityForLink(videoUrl)
                        callback(
                            ExtractorLink(
                                source = this.name,
                                name = "Server JWPlayer ($label)",
                                url = fixUrl(videoUrl),
                                referer = mainUrl,
                                quality = quality,
                                type = if (videoUrl.contains(".m3u8", ignoreCase = true)) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                            )
                        )
                        return true
                    }
                }
            }
        }
        return false 
    }
}
