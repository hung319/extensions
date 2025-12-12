package recloudstream

import android.widget.Toast
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addDuration
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.utils.AppUtils.toJson
import com.lagradost.cloudstream3.CommonActivity.showToast
import kotlinx.coroutines.*
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.ResponseBody.Companion.toResponseBody
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.io.ByteArrayOutputStream
import java.net.URL
import java.net.URLEncoder
import java.security.MessageDigest
import java.util.*
import java.util.concurrent.ConcurrentHashMap
import java.util.zip.Inflater
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

class AnimeVietsubProvider : MainAPI() {

    override var mainUrl = "https://bit.ly/animevietsubtv"
    override var name = "AnimeVietsub"
    override val supportedTypes = setOf(
        TvType.Anime,
        TvType.Cartoon,
        TvType.Movie
    )
    override var lang = "vi"
    override val hasMainPage = true

    // ================== LOGIC GIẢI MÃ & INTERCEPTOR ==================
    
    // [Optimized] Use ConcurrentHashMap for thread safety inside async blocks
    private val m3u8Contents = ConcurrentHashMap<String, String>()
    
    private val keyStringB64 = "ZG1fdGhhbmdfc3VjX3ZhdF9nZXRfbGlua19hbl9kYnQ="
    private val aesKeyBytes: ByteArray by lazy {
        val decodedKeyBytes = Base64.getDecoder().decode(keyStringB64)
        MessageDigest.getInstance("SHA-256").digest(decodedKeyBytes)
    }

    // [Optimized] Pre-compile Regex
    private val b64CleanRegex = Regex("[^A-Za-z0-9+/=]")
    private val quoteRegex = Regex("^\"|\"$")

    private fun decryptAndDecompress(encryptedDataB64: String): String? {
        return try {
            val cleanedB64 = encryptedDataB64.replace(b64CleanRegex, "")
            val encryptedBytes = Base64.getDecoder().decode(cleanedB64)
            if (encryptedBytes.size < 16) return null
            
            val ivBytes = encryptedBytes.sliceArray(0..15)
            val ciphertextBytes = encryptedBytes.sliceArray(16 until encryptedBytes.size)
            
            val decipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
            decipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(aesKeyBytes, "AES"), IvParameterSpec(ivBytes))
            
            val decryptedBytesPadded = decipher.doFinal(ciphertextBytes)
            
            // [Optimized] Use Inflater properly with try-resource pattern logic if possible, 
            // but Inflater needs explicit 'end()'.
            val inflater = Inflater(true)
            val outputStream = ByteArrayOutputStream(decryptedBytesPadded.size * 2) // Hint size
            val buffer = ByteArray(1024)
            
            try {
                inflater.setInput(decryptedBytesPadded)
                while (!inflater.finished()) {
                    val count = inflater.inflate(buffer)
                    if (count == 0) break 
                    outputStream.write(buffer, 0, count)
                }
            } finally {
                inflater.end()
            }
            
            outputStream.toString("UTF-8")
                .trim()
                .replace(quoteRegex, "")
                .replace("\\n", "\n")
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    override fun getVideoInterceptor(extractorLink: ExtractorLink): Interceptor {
        return Interceptor { chain ->
            val request = chain.request()
            val url = request.url.toString()
            if (url.contains("hdev.io/animevietsub")) {
                val key = url.substringAfterLast("/")
                val m3u8Content = m3u8Contents[key]
                if (m3u8Content != null) {
                    val responseBody =
                        m3u8Content.toResponseBody("application/vnd.apple.mpegurl".toMediaTypeOrNull())
                    chain.proceed(request).newBuilder()
                        .code(200).message("OK").body(responseBody)
                        .build()
                } else {
                    chain.proceed(request)
                }
            } else {
                chain.proceed(request)
            }
        }
    }

    // ================== LOGIC DOMAIN RESOLVER ==================

    private val bitlyResolverUrl = "https://bit.ly/animevietsubtv"
    private val secondaryFallbackDomain = "https://animevietsub.link"
    private val ultimateFallbackDomain = "https://animevietsub.cam"
    private var currentActiveUrl = bitlyResolverUrl
    private var domainResolutionAttempted = false

    private suspend fun getBaseUrl(): String {
        if (domainResolutionAttempted) {
            return currentActiveUrl
        }
        var resolvedDomain: String? = null
        try {
            val response = app.head(secondaryFallbackDomain, allowRedirects = false)
            val location = response.headers["Location"]
            if (!location.isNullOrBlank()) {
                val urlObject = URL(location)
                resolvedDomain = "${urlObject.protocol}://${urlObject.host}"
            }
        } catch (_: Exception) { }

        if (resolvedDomain == null) {
            try {
                val response = app.get(bitlyResolverUrl, allowRedirects = true, timeout = 10_000)
                val finalUrlString = response.url
                if (finalUrlString.startsWith("http") && !finalUrlString.contains("bit.ly")) {
                    val urlObject = URL(finalUrlString)
                    resolvedDomain = "${urlObject.protocol}://${urlObject.host}"
                }
            } catch (_: Exception) { }
        }

        domainResolutionAttempted = true
        currentActiveUrl = resolvedDomain ?: ultimateFallbackDomain
        return currentActiveUrl
    }

    // ================== MAIN PAGE ==================

    override val mainPage = mainPageOf(
        "/anime-moi/" to "Mới Cập Nhật",
        "/anime-sap-chieu/" to "Sắp Chiếu",
        "/bang-xep-hang/day.html" to "Xem Nhiều Trong Ngày"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        withContext(Dispatchers.Main) {
            CommonActivity.activity?.let { activity ->
                showToast(activity, "Optimized by AI", Toast.LENGTH_SHORT)
            }
        }

        val baseUrl = getBaseUrl()
        val url = if (page == 1) {
            "$baseUrl${request.data}"
        } else {
            if (request.data.contains("bang-xep-hang")) "$baseUrl${request.data}"
            else "$baseUrl${request.data.removeSuffix("/")}/trang-$page.html"
        }
        
        val document = app.get(url).document
        
        // [Optimized] Use faster selector logic
        val home = if (request.data.contains("bang-xep-hang")) {
            document.select("ul.bxh-movie-phimletv li.group").mapNotNull { element ->
                val titleElement = element.selectFirst("h3.title-item a") ?: return@mapNotNull null
                val title = titleElement.text().trim()
                val href = fixUrl(titleElement.attr("href"), baseUrl) ?: return@mapNotNull null
                val posterUrl = fixUrl(element.selectFirst("a.thumb img")?.attr("src"), baseUrl)
                newMovieSearchResponse(title, href, TvType.Anime) {
                    this.posterUrl = posterUrl
                }
            }
        } else {
            // [Optimized] Based on provided HTML: animevietsub.show.html
            // Structure: <ul class="MovieList Rows AX A06..."> <li class="TPostMv"> <article ...>
            document.select("ul.MovieList li.TPostMv article").mapNotNull { 
                it.toSearchResponse(this, baseUrl) 
            }
        }
        
        // [Optimized] Check pagination efficiently
        val hasNext = if (request.data.contains("bang-xep-hang")) false
        else document.selectFirst("div.wp-pagenavi a.next, div.wp-pagenavi a.larger:contains(Trang Cuối)") != null
        
        return newHomePageResponse(HomePageList(request.name, home), hasNext)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        return try {
            val baseUrl = getBaseUrl()
            val requestUrl = "$baseUrl/tim-kiem/${query.encodeUri()}/"
            val document = app.get(requestUrl).document
            // [Optimized] Reuse selector logic
            document.select("ul.MovieList li.TPostMv article")
                .mapNotNull { it.toSearchResponse(this, baseUrl) }
        } catch (e: Exception) {
            emptyList()
        }
    }

    // ================== LOAD DETAILS ==================

    override suspend fun load(url: String): LoadResponse? {
        val baseUrl = getBaseUrl()
        try {
            val infoDocument = app.get(url, headers = mapOf("Referer" to baseUrl)).document
            
            // [Optimized] Extract all meta info in one pass from the DOM
            val (genres, year, rating, duration, status, country) = infoDocument.extractInfoMeta()
            
            // Only fetch watch page if not "Sắp chiếu" and we need episodes
            val isUpcoming = genres.any { it.equals("Anime sắp chiếu", ignoreCase = true) }
            
            val watchPageDoc = if (!isUpcoming) {
                try {
                    val watchPageUrl = if (url.endsWith("/")) "${url}xem-phim.html" else "$url/xem-phim.html"
                    app.get(watchPageUrl, referer = url).document
                } catch (e: Exception) { null }
            } else null

            return infoDocument.toLoadResponse(
                this, 
                url, 
                baseUrl, 
                watchPageDoc, 
                genres, year, rating, duration, status, country, isUpcoming
            )
        } catch (e: Exception) {
            return null
        }
    }

    // ================== LOGIC ĐA NGUỒN (MULTI-SOURCE) ==================

    data class ServerData(val serverName: String, val hash: String, val id: String)
    data class LinkData(val sources: List<ServerData>)

    private fun Document.parseEpisodes(): List<Episode> {
        // [Optimized] Based on watch HTML structure: div.list-server -> div.server-group -> ul.list-episode
        val episodesMap = LinkedHashMap<String, MutableList<ServerData>>()
        
        // Use ID selector for speed
        val serverContainer = this.getElementById("list-server") ?: return emptyList()
        
        serverContainer.select("div.server-group").forEach { group ->
            val serverName = group.selectFirst("h3.server-name")?.text()?.trim() ?: "VIP"
            
            // Select direct children to avoid deep tree traversal
            group.select("ul.list-episode > li > a.btn-episode").forEach { epEl ->
                val epNum = epEl.attr("title").ifBlank { epEl.text() }.trim()
                val id = epEl.attr("data-id")
                val hash = epEl.attr("data-hash")
                
                if (id.isNotBlank() && hash.isNotBlank()) {
                    val svData = ServerData(serverName, hash, id)
                    episodesMap.getOrPut(epNum) { mutableListOf() }.add(svData)
                }
            }
        }

        return episodesMap.map { (epName, sourceList) ->
            val dataJson = LinkData(sourceList).toJson()
            newEpisode(dataJson) {
                this.name = epName
            }
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val linkData = AppUtils.parseJson<LinkData>(data)
        val baseUrl = getBaseUrl()

        coroutineScope {
            linkData.sources.map { source ->
                async {
                    try {
                        val response = app.post(
                            "$baseUrl/ajax/player",
                            headers = mapOf(
                                "content-type" to "application/x-www-form-urlencoded; charset=UTF-8",
                                "x-requested-with" to "XMLHttpRequest",
                                "Referer" to baseUrl,
                            ),
                            data = mapOf("link" to source.hash, "id" to source.id)
                        ).text

                        // [Optimized] Substring is faster than JSON parsing for this specific extracted field
                        if (response.contains("""[{"file":"""")) {
                            val encrypted = response.substringAfter("""[{"file":"""").substringBefore("""\u0022}""")
                                .replace("""\"""", "") // Clean escaped quotes if any
                                .substringBefore("\"}") // Fallback

                            val decryptedM3u8 = decryptAndDecompress(encrypted)
                            
                            if (decryptedM3u8 != null) {
                                val key = "${source.hash}${source.id}"
                                m3u8Contents[key] = decryptedM3u8 // Thread-safe put
                                callback.invoke(
                                    newExtractorLink(
                                        source = name,
                                        name = "$name - ${source.serverName}", 
                                        url = "https://hdev.io/animevietsub/$key",
                                        type = ExtractorLinkType.M3U8
                                    ) {
                                        this.quality = Qualities.Unknown.value
                                        this.referer = baseUrl
                                    }
                                )
                            }
                        }
                    } catch (e: Exception) { }
                }
            }.awaitAll()
        }
        return true
    }

    // ================== HELPER FUNCTIONS ==================

    // [Optimized] Single pass meta extraction
    private fun Document.extractInfoMeta(): InfoMeta {
        var genres = emptyList<String>()
        var year: Int? = null
        var rating: Float? = null
        var duration: Int? = null
        var status: ShowStatus? = null
        var country = ""

        // Locate Info List - Based on `animevietsub.show_phim_...html`
        // It's usually in <div class="MvTbCn" id="MvTb-Info"> -> .mvici-left & .mvici-right -> ul.InfoList
        
        val infoItems = this.select("ul.InfoList li")
        for (item in infoItems) {
            val text = item.text()
            val lowerText = text.lowercase()
            
            when {
                lowerText.contains("thể loại:") -> {
                    genres = item.select("a").mapNotNull { it.text()?.trim() }
                }
                lowerText.contains("năm:") || lowerText.contains("season:") -> {
                    // Try to find year in text (e.g. 2025)
                    val yearStr = item.select("a").text().filter { it.isDigit() }
                    if (yearStr.length == 4) year = yearStr.toIntOrNull()
                }
                lowerText.contains("thời lượng:") -> {
                    duration = text.filter { it.isDigit() }.toIntOrNull()
                }
                lowerText.contains("trạng thái:") -> {
                    status = when {
                        lowerText.contains("đang chiếu") || lowerText.contains("cập nhật") -> ShowStatus.Ongoing
                        lowerText.contains("hoàn thành") || lowerText.contains("full") -> ShowStatus.Completed
                        else -> null
                    }
                }
                lowerText.contains("quốc gia:") -> {
                    country = item.select("a").text().lowercase()
                }
            }
        }

        // Fallback for rating
        if (rating == null) {
            rating = this.selectFirst("div#star")?.attr("data-score")?.toFloatOrNull()
        }

        return InfoMeta(genres, year, rating, duration, status, country)
    }

    data class InfoMeta(
        val genres: List<String>,
        val year: Int?,
        val rating: Float?,
        val duration: Int?,
        val status: ShowStatus?,
        val country: String
    )

    private fun Element.toSearchResponse(provider: MainAPI, baseUrl: String): SearchResponse? {
        return try {
            // Selector specific to `animevietsub.show_tim-kiem_g.html` and `show.html`
            val linkElement = this.selectFirst("a") ?: return null
            val href = fixUrl(linkElement.attr("href"), baseUrl) ?: return null
            
            // Title is inside h2.Title
            val title = this.selectFirst("h2.Title")?.text()?.trim() ?: return null
            
            // Image is inside div.Image figure img
            val imgTag = this.selectFirst("div.Image img")
            val posterUrl = fixUrl(imgTag?.attr("data-src")?.ifBlank { imgTag.attr("src") }, baseUrl)
            
            val episodeString = this.selectFirst("span.mli-eps")?.text()
            // If eps string contains "TẬP", implies series. If null or different, maybe movie.
            // HTML logic: <span class="mli-eps">TẬP<i>11</i></span>
            val isSeries = episodeString?.contains("TẬP", true) == true || episodeString?.contains("HOÀN TẤT", true) == true
            
            val episodeCount = episodeString?.filter { it.isDigit() }?.toIntOrNull()
            val tvType = if (isSeries) TvType.Anime else TvType.Movie

            provider.newAnimeSearchResponse(title, href, tvType) {
                this.posterUrl = posterUrl
                this.dubStatus = EnumSet.of(DubStatus.Subbed) // Defaulting
                if (episodeCount != null) {
                    this.episodes = mutableMapOf(DubStatus.Subbed to episodeCount)
                }
            }
        } catch (e: Exception) { null }
    }

    private suspend fun Document.toLoadResponse(
        provider: MainAPI,
        infoUrl: String,
        baseUrl: String,
        watchPageDoc: Document?,
        genres: List<String>,
        year: Int?,
        rating: Float?,
        duration: Int?,
        status: ShowStatus?,
        country: String,
        isUpcoming: Boolean
    ): LoadResponse? {
        try {
            // HTML: <h1 class="Title">...</h1>
            val title = this.selectFirst("h1.Title")?.text()?.trim()
                ?: this.selectFirst("meta[property=og:title]")?.attr("content")?.substringBefore(" Tập")?.trim()
                ?: return null

            // HTML: div.Image figure.Objf img
            val posterUrl = fixUrl(this.selectFirst("div.Image figure.Objf img")?.attr("src"), baseUrl)
            
            // HTML: div.Description
            val plot = this.selectFirst("div.Description")?.text()?.trim()

            val actors = this.select("div#MvTb-Cast ul.ListCast li a").mapNotNull {
                val name = it.attr("title").removePrefix("Nhân vật ").trim()
                val img = fixUrl(it.selectFirst("img")?.attr("src"), baseUrl)
                if (name.isNotBlank()) ActorData(Actor(name, image = img)) else null
            }

            val recommendations = this.select("div.MovieListRelated div.TPostMv article").mapNotNull {
                it.toSearchResponse(provider, baseUrl)
            }
            
            val episodes = watchPageDoc?.parseEpisodes() ?: emptyList()
            
            // Determine final type logic
            val finalTvType = when {
                title.contains("movie", true) || title.contains("phim lẻ", true) -> TvType.AnimeMovie
                country.contains("nhật bản") || genres.any { it.contains("Anime", true) } -> TvType.Anime
                genres.any { it.contains("hoạt hình", true) } -> TvType.Cartoon
                else -> TvType.Anime
            }

            if (isUpcoming) {
                return provider.newAnimeLoadResponse(title, infoUrl, TvType.Anime) {
                    this.posterUrl = posterUrl; this.plot = plot; this.tags = genres; this.year = year
                    this.score = rating?.let { Score.from10(it) }; this.actors = actors; this.recommendations = recommendations
                    this.comingSoon = true
                }
            }

            return provider.newAnimeLoadResponse(title, infoUrl, finalTvType) {
                this.posterUrl = posterUrl
                this.plot = plot
                this.tags = genres
                this.year = year
                this.score = rating?.let { Score.from10(it) }
                this.actors = actors
                this.recommendations = recommendations

                val isMovie = finalTvType == TvType.Movie || finalTvType == TvType.AnimeMovie || (episodes.size <= 1 && status == ShowStatus.Completed)

                if (isMovie) {
                    if (episodes.isNotEmpty()) {
                        this.episodes = mutableMapOf(DubStatus.Subbed to episodes)
                    }
                    duration?.let { addDuration(it.toString()) }
                } else {
                    this.episodes = mutableMapOf(DubStatus.Subbed to episodes)
                    this.showStatus = status ?: if (episodes.isNotEmpty()) ShowStatus.Ongoing else null
                }
            }
        } catch (e: Exception) {
            return null
        }
    }

    private fun String?.encodeUri(): String {
        if (this == null) return ""
        return try { URLEncoder.encode(this, "UTF-8").replace("+", "%20") } catch (e: Exception) { this }
    }

    private fun fixUrl(url: String?, baseUrl: String): String? {
        if (url.isNullOrBlank()) return null
        return when {
            url.startsWith("http") -> url
            url.startsWith("//") -> "https:$url"
            else -> URL(URL(baseUrl), url).toString()
        }
    }
}
