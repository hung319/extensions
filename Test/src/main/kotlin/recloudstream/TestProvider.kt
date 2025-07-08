package recloudstream

import com.lagradost.cloudstream3.* import com.lagradost.cloudstream3.LoadResponse.Companion.addActors
import com.lagradost.cloudstream3.LoadResponse.Companion.addDuration
import android.util.Log
import com.google.gson.Gson
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.net.URL
import java.net.URLEncoder
import java.security.MessageDigest
import java.util.Base64
import java.util.zip.Inflater
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlin.math.roundToInt
import kotlinx.coroutines.*

// Import Ktor
import io.ktor.server.application.*
import io.ktor.server.engine.*
import io.ktor.server.netty.*
import io.ktor.server.response.*
import io.ktor.server.routing.*

// Import các thành phần API cần thiết
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.newExtractorLink

class AnimeVietsubProvider : MainAPI() {

    private val gson = Gson()
    override var mainUrl = "https://bit.ly/animevietsubtv"
    override var name = "AnimeVietsub"
    override val supportedTypes = setOf(
        TvType.Anime,
        TvType.Cartoon,
        TvType.Movie
    )
    override var lang = "vi"
    override val hasMainPage = true
    private val USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/100.0.4896.127 Safari/537.36"

    private val bitlyResolverUrl = "https://bit.ly/animevietsubtv"
    private val ultimateFallbackDomain = "https://animevietsub.lol"
    private var currentActiveUrl = bitlyResolverUrl
    private var domainResolutionAttempted = false

    companion object {
        private var localServer: NettyApplicationEngine? = null
        private var serverJob: Job? = null
        private const val serverPort = 9999

        private fun startKtorServer() {
            if (localServer?.application?.isActive == true) return

            serverJob?.cancel()
            serverJob = CoroutineScope(Dispatchers.IO).launch {
                localServer = embeddedServer(Netty, port = serverPort) {
                    routing {
                        get("/proxy") {
                            val targetUrl = call.request.queryParameters["url"]
                            if (targetUrl.isNullOrBlank()) {
                                call.respondText("Missing 'url' parameter")
                                return@get
                            }

                            Log.d("KtorProxy", "Proxying URL: $targetUrl")
                            try {
                                val response = app.get(targetUrl)
                                call.respondBytes(response.body.bytes())
                            } catch (e: Exception) {
                                Log.e("KtorProxy", "Failed to proxy $targetUrl", e)
                                call.respondText("Failed to proxy URL: ${e.message}")
                            }
                        }
                    }
                }.start(wait = true)
            }
        }
    }

    private val KEY_STRING_B64 = "ZG1fdGhhbmdfc3VjX3ZhdF9nZXRfbGlua19hbl9kYnQ="

    private val aesKey by lazy {
        try {
            val decodedKeyBytes = Base64.getDecoder().decode(KEY_STRING_B64)
            val sha256Hasher = MessageDigest.getInstance("SHA-256")
            val hashedKey = sha256Hasher.digest(decodedKeyBytes)
            SecretKeySpec(hashedKey, "AES")
        } catch (e: Exception) {
            Log.e(name, "Failed to calculate AES key", e)
            null
        }
    }

    private fun decryptM3u8Content(encryptedDataB64: String): String? {
        val key = aesKey ?: return null
        try {
            val cleanedEncDataB64 = encryptedDataB64.replace(Regex("[^A-Za-z0-9+/=]"), "")
            val encryptedBytes = Base64.getDecoder().decode(cleanedEncDataB64)
            if (encryptedBytes.size < 16) throw Exception("Encrypted data is too short to contain IV.")
            val ivBytes = encryptedBytes.copyOfRange(0, 16)
            val ciphertextBytes = encryptedBytes.copyOfRange(16, encryptedBytes.size)
            val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
            cipher.init(Cipher.DECRYPT_MODE, key, IvParameterSpec(ivBytes))
            val decryptedBytesPadded = cipher.doFinal(ciphertextBytes)
            val inflater = Inflater(true)
            inflater.setInput(decryptedBytesPadded)
            val result = ByteArray(1024 * 1024)
            val decompressedSize = inflater.inflate(result)
            inflater.end()
            val decompressedBytes = result.copyOfRange(0, decompressedSize)
            return decompressedBytes.toString(Charsets.UTF_8)
                .trim().removeSurrounding("\"")
                .replace("\\n", "\n")
        } catch (error: Exception) {
            Log.e(name, "Decryption/Decompression failed: ${error.message}", error)
            return null
        }
    }

    override val mainPage = mainPageOf(
        "/anime-moi/" to "Mới Cập Nhật",
        "/anime-sap-chieu/" to "Sắp Chiếu",
        "/bang-xep-hang/day.html" to "Xem Nhiều Trong Ngày"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val baseUrl = getBaseUrl()
        val url = if (page == 1) {
            "$baseUrl${request.data}"
        } else {
            if (request.data.contains("bang-xep-hang")) {
                "$baseUrl${request.data}"
            } else {
                val slug = request.data.removeSuffix("/")
                "$baseUrl$slug/trang-$page.html"
            }
        }
        val document = app.get(url).document
        val home = when {
            request.data.contains("bang-xep-hang") -> {
                document.select("ul.bxh-movie-phimletv li.group").mapNotNull { element ->
                    try {
                        val titleElement = element.selectFirst("h3.title-item a") ?: return@mapNotNull null
                        val title = titleElement.text().trim()
                        val href = fixUrl(titleElement.attr("href"), baseUrl) ?: return@mapNotNull null
                        val posterUrl = fixUrl(element.selectFirst("a.thumb img")?.attr("src"), baseUrl)
                        newMovieSearchResponse(title, href, TvType.Anime) {
                            this.posterUrl = posterUrl
                        }
                    } catch (e: Exception) {
                        Log.e(name, "Error parsing ranking item", e)
                        null
                    }
                }
            }
            else -> {
                document.select("ul.MovieList.Rows li.TPostMv").mapNotNull {
                    it.toSearchResponse(this, baseUrl)
                }
            }
        }
        val hasNext = if (request.data.contains("bang-xep-hang")) {
            false
        } else {
            document.selectFirst("div.wp-pagenavi span.current + a.page, div.wp-pagenavi a.larger:contains(Trang Cuối)") != null
        }
        return newHomePageResponse(
            list = HomePageList(
                name = request.name,
                list = home
            ),
            hasNext = hasNext
        )
    }

    override suspend fun search(query: String): List<SearchResponse> {
        return try {
            val baseUrl = getBaseUrl()
            val requestUrl = "$baseUrl/tim-kiem/${query.encodeUri()}/"
            val document = app.get(requestUrl).document
            document.select("ul.MovieList.Rows li.TPostMv")
                .mapNotNull { it.toSearchResponse(this, baseUrl) }
        } catch (e: Exception) {
            Log.e(name, "Error in search with query '$query'", e)
            emptyList()
        }
    }

    private suspend fun getBaseUrl(): String {
        if (domainResolutionAttempted && !currentActiveUrl.contains("bit.ly")) {
            return currentActiveUrl
        }
        var resolvedDomain: String? = null
        val urlToAttemptResolution = if (currentActiveUrl.contains("bit.ly") || !domainResolutionAttempted) {
            bitlyResolverUrl
        } else {
            currentActiveUrl
        }
        try {
            val response = app.get(urlToAttemptResolution, allowRedirects = true, timeout = 15_000)
            val finalUrlString = response.url
            if (finalUrlString.startsWith("http") && !finalUrlString.contains("bit.ly")) {
                val urlObject = URL(finalUrlString)
                resolvedDomain = "${urlObject.protocol}://${urlObject.host}"
            } else {
                Log.w(name, "Bitly resolution did not lead to a valid different domain. Final URL: $finalUrlString")
            }
        } catch (e: Exception) {
            Log.e(name, "Error resolving domain link '$urlToAttemptResolution': ${e.message}", e)
        }
        domainResolutionAttempted = true
        if (resolvedDomain != null) {
            if (currentActiveUrl != resolvedDomain) {
                Log.i(name, "Domain updated: $currentActiveUrl -> $resolvedDomain")
            }
            currentActiveUrl = resolvedDomain
        } else {
            if (currentActiveUrl.contains("bit.ly") || (urlToAttemptResolution != ultimateFallbackDomain && currentActiveUrl != ultimateFallbackDomain)) {
                Log.w(name, "Domain resolution failed for '$urlToAttemptResolution'. Using fallback: $ultimateFallbackDomain")
                currentActiveUrl = ultimateFallbackDomain
            } else {
                Log.e(name, "All domain resolution attempts failed. Sticking with last known: $currentActiveUrl")
            }
        }
        return currentActiveUrl
    }

    override suspend fun load(url: String): LoadResponse? {
        val baseUrl = getBaseUrl()
        try {
            val infoDocument = app.get(url, headers = mapOf("Referer" to baseUrl)).document
            val genres = infoDocument.getGenres()
            val watchPageDoc = if (!genres.any { it.equals("Anime sắp chiếu", ignoreCase = true) }) {
                try {
                    val watchPageUrl = if (url.endsWith("/")) "${url}xem-phim.html" else "$url/xem-phim.html"
                    app.get(watchPageUrl, referer = url).document
                } catch (e: Exception) {
                    Log.w(name, "Failed to load watch page. Error: ${e.message}")
                    null
                }
            } else {
                null
            }
            return infoDocument.toLoadResponse(this, url, baseUrl, watchPageDoc)
        } catch (e: Exception) {
            Log.e(name, "FATAL Error loading main info page ($url): ${e.message}", e)
            return null
        }
    }
    
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        startKtorServer()

        val episodeData = gson.fromJson(data, EpisodeData::class.java)
        val episodeId = episodeData.dataId ?: throw ErrorLoadingException("Missing episode ID")
        val episodePageUrl = episodeData.url
        val baseUrl = getBaseUrl()

        val ajaxResponse = app.post(
            url = "$baseUrl/ajax/player?v=2019a",
            data = mapOf("id" to episodeId, "play" to "api").toMutableMap().apply {
                episodeData.duHash?.let { put("link", it) }
            },
            referer = episodePageUrl
        ).text

        val playerResponse = gson.fromJson(ajaxResponse, AjaxPlayerResponse::class.java)
        if (playerResponse?.success != 1 || playerResponse.link.isNullOrEmpty()) {
            throw ErrorLoadingException("Failed to get links from AJAX response: $ajaxResponse")
        }

        playerResponse.link.forEach { linkSource ->
            val encryptedUrl = linkSource.file ?: return@forEach
            val decryptedM3u8 = decryptM3u8Content(encryptedUrl) ?: return@forEach

            val rewrittenM3u8 = decryptedM3u8.lines().joinToString("\n") { line ->
                if (line.trim().startsWith("http")) {
                    val encodedUrl = URLEncoder.encode(line.trim(), "UTF-8")
                    "http://127.0.0.1:$serverPort/proxy?url=$encodedUrl"
                } else {
                    line
                }
            }

            val m3u8DataUri = "data:application/vnd.apple.mpegurl;charset=utf-8,${URLEncoder.encode(rewrittenM3u8, "UTF-8")}"

            newExtractorLink(
                source = name,
                name = name,
                url = m3u8DataUri,
                referer = episodePageUrl, // referer là một tham số chính
                type = ExtractorLinkType.M3U8
            ) { // Khối builder để đặt các thuộc tính còn lại
                this.quality = Qualities.Unknown.value
            }.let { callback(it) }
        }

        return true
    }

    private fun Element.toSearchResponse(provider: MainAPI, baseUrl: String): SearchResponse? {
        return try {
            val linkElement = this.selectFirst("article.TPost > a") ?: return null
            val href = fixUrl(linkElement.attr("href"), baseUrl) ?: return null
            val title = linkElement.selectFirst("h2.Title")?.text()?.trim()?.takeIf { it.isNotBlank() } ?: return null
            val posterUrlRaw = linkElement.selectFirst("div.Image img")?.let { img ->
                img.attr("data-src").ifBlank { img.attr("src") }
            }
            val posterUrl = fixUrl(posterUrlRaw, baseUrl)
            val isMovie = listOf("OVA", "ONA", "Movie", "Phim Lẻ").any { title.contains(it, true) } ||
                    this.selectFirst("span.mli-eps") == null
            val tvType = if (isMovie) TvType.Movie else TvType.Anime
            provider.newMovieSearchResponse(title, href, tvType) {
                this.posterUrl = posterUrl
            }
        } catch (e: Exception) {
            Log.e(name, "Error parsing search result item", e)
            null
        }
    }

    private suspend fun Document.toLoadResponse(
        provider: MainAPI,
        infoUrl: String,
        baseUrl: String,
        watchPageDoc: Document?
    ): LoadResponse? {
        try {
            val title = this.selectFirst("div.TPost.Single div.Title")?.text()?.trim()
                ?: this.selectFirst("meta[property=og:title]")?.attr("content")?.substringBefore(" Tập")?.trim()
                ?: throw ErrorLoadingException("Could not find title on info page $infoUrl")
            val posterUrl = this.extractPosterUrl(baseUrl)
            val plot = this.extractPlot()
            val tags = this.getGenres()
            val year = this.extractYear()
            val rating = this.extractRating()
            val actors = this.extractActors(baseUrl)
            val recommendations = this.extractRecommendations(provider, baseUrl)
            if (tags.any { it.equals("Anime sắp chiếu", ignoreCase = true) }) {
                Log.i(name, "'$title' is an upcoming anime. Returning LoadResponse without episodes.")
                return provider.newAnimeLoadResponse(title, infoUrl, TvType.Anime) {
                    this.posterUrl = posterUrl; this.plot = plot; this.tags = tags; this.year = year
                    this.rating = rating; this.actors = actors; this.recommendations = recommendations
                }
            }
            val episodes = watchPageDoc?.parseEpisodes(baseUrl, infoUrl) ?: emptyList()
            val status = this.getShowStatus(episodes.size)
            val isSeries = episodes.size > 1 || status == ShowStatus.Ongoing
            val finalTvType = this.determineFinalTvType(title, tags, episodes.size)
            return if (isSeries) {
                if (finalTvType == TvType.Anime) {
                    provider.newAnimeLoadResponse(title, infoUrl, finalTvType) {
                        this.episodes = mutableMapOf(DubStatus.Subbed to episodes)
                        this.posterUrl = posterUrl; this.plot = plot; this.tags = tags; this.year = year
                        this.rating = rating; this.showStatus = status; this.actors = actors; this.recommendations = recommendations
                    }
                } else {
                    provider.newTvSeriesLoadResponse(title, infoUrl, finalTvType, episodes) {
                        this.posterUrl = posterUrl; this.plot = plot; this.tags = tags; this.year = year
                        this.rating = rating; this.showStatus = status; this.actors = actors; this.recommendations = recommendations
                    }
                }
            } else {
                val duration = this.extractDuration()
                val data = episodes.firstOrNull()?.data
                    ?: gson.toJson(EpisodeData(infoUrl, this.getDataIdFallback(infoUrl), null))
                provider.newMovieLoadResponse(title, infoUrl, finalTvType, data) {
                    this.posterUrl = posterUrl; this.plot = plot; this.tags = tags; this.year = year
                    this.rating = rating; this.actors = actors; this.recommendations = recommendations
                    duration?.let { addDuration(it.toString()) }
                }
            }
        } catch (e: Exception) {
            Log.e(name, "Error in toLoadResponse for url: $infoUrl", e)
            return null
        }
    }

    private fun Document.extractPosterUrl(baseUrl: String): String? {
        val selectors = listOf(
            "meta[property=og:image]",
            "meta[itemprop=image]",
            "div.TPostBg.Objf img.TPostBg",
            "div.TPost.Single div.Image figure.Objf img",
            "div.TPost.Single div.Image img"
        )
        for (selector in selectors) {
            val url = this.selectFirst(selector)?.attr("src")?.ifBlank { this.selectFirst(selector)?.attr("content") }
            if (!url.isNullOrBlank()) return fixUrl(url, baseUrl)
        }
        return null
    }

    private fun Document.extractPlot(): String? {
        val descriptionFromDiv = this.selectFirst("article.TPost.Single div.Description")?.text()?.trim()
        if (!descriptionFromDiv.isNullOrBlank()) {
            return descriptionFromDiv
        }
        return this.selectFirst("meta[property=og:description]")?.attr("content")?.trim()
    }

    private fun Document.getGenres(): List<String> {
        return this.select("li:has(strong:containsOwn(Thể loại)) a, div.mvici-left li:contains(Thể loại) a")
            .mapNotNull { it.text()?.trim() }.distinct()
    }

    private fun Document.extractYear(): Int? {
        return this.selectFirst("li:has(strong:containsOwn(Năm)), p.Info span.Date a")
            ?.text()?.filter { it.isDigit() }?.toIntOrNull()
    }

    private fun Document.extractRating(): Int? {
        val ratingText = this.selectFirst(
            "li:has(strong:containsOwn(Điểm)), div#star[data-score], input#score_current[value], div.VotesCn strong#average_score"
        )?.let {
            it.ownText().ifBlank { it.attr("data-score").ifBlank { it.attr("value").ifBlank { it.text() } } }
        }?.substringBefore("/")?.replace(",", ".")
        return ratingText?.toDoubleOrNull()?.let { (it * 10).roundToInt() }
    }

    private fun Document.extractDuration(): Int? {
        return this.selectFirst("li:has(strong:containsOwn(Thời lượng)), li.AAIco-adjust:contains(Thời lượng)")
            ?.ownText()?.filter { it.isDigit() }?.toIntOrNull()
    }

    private fun Document.extractActors(baseUrl: String): List<ActorData> {
        return this.select("div#MvTb-Cast ul.ListCast li a").mapNotNull {
            val name = it.attr("title").removePrefix("Nhân vật ").trim()
            if (name.isNotBlank()) {
                ActorData(Actor(name, image = fixUrl(it.selectFirst("img")?.attr("src"), baseUrl)))
            } else null
        }
    }

    private fun Document.extractRecommendations(provider: MainAPI, baseUrl: String): List<SearchResponse> {
        return this.select("div.Wdgt div.MovieListRelated.owl-carousel div.TPostMv").mapNotNull { item ->
            try {
                val linkElement = item.selectFirst("a") ?: return@mapNotNull null
                val href = fixUrl(linkElement.attr("href"), baseUrl) ?: return@mapNotNull null
                val title = linkElement.selectFirst(".Title")?.text()?.trim() ?: return@mapNotNull null
                val posterUrl = fixUrl(linkElement.selectFirst("img")?.attr("src"), baseUrl)
                val isMovie = linkElement.selectFirst("span.mli-eps") == null
                val tvType = if (isMovie) TvType.Movie else TvType.Anime
                provider.newMovieSearchResponse(title, href, tvType) {
                    this.posterUrl = posterUrl
                }
            } catch (e: Exception) {
                Log.e(name, "Error parsing recommendation item", e)
                null
            }
        }
    }

    private fun Document.getShowStatus(episodeCount: Int): ShowStatus {
        val statusText = this.selectFirst("li:has(strong:containsOwn(Trạng thái)), div.mvici-left li:contains(Trạng thái)")
            ?.ownText()?.lowercase() ?: ""
        return when {
            statusText.contains("đang chiếu") || statusText.contains("đang tiến hành") -> ShowStatus.Ongoing
            statusText.contains("hoàn thành") || statusText.contains("full") -> ShowStatus.Completed
            episodeCount <= 1 -> ShowStatus.Completed
            else -> ShowStatus.Ongoing
        }
    }

    private fun Document.determineFinalTvType(title: String, genres: List<String>, episodeCount: Int): TvType {
        val country = this.selectFirst("li:has(strong:containsOwn(Quốc gia)) a")?.text()?.lowercase() ?: ""
        return when {
            title.contains("movie", true) || title.contains("phim lẻ", true) || episodeCount <= 1 -> {
                if (country == "nhật bản" || genres.any { it.contains("Anime", true) }) TvType.Anime
                else TvType.Movie
            }
            country == "nhật bản" -> TvType.Anime
            country == "trung quốc" -> TvType.Cartoon
            genres.any { it.contains("hoạt hình", true) } -> TvType.Cartoon
            else -> TvType.Anime
        }
    }

    private fun Document.getDataIdFallback(infoUrl: String): String? {
        return this.selectFirst("a.watch_button_more[href*=xem-phim]")?.attr("href")
            ?.substringAfterLast("a")?.substringBefore("/")
            ?: infoUrl.substringAfterLast("/")?.substringBefore("-")?.filter { it.isDigit() }
                ?.ifEmpty { infoUrl.substringAfterLast("-")?.filter { it.isDigit() } }
                ?.takeIf { it.isNotBlank() }
    }

    private fun Document.parseEpisodes(baseUrl: String, infoUrl: String): List<Episode> {
        return this.select("div.server ul.list-episode li a.btn-episode").mapNotNull { el ->
            try {
                val dataId = el.attr("data-id").ifBlank { null } ?: return@mapNotNull null
                val name = el.attr("title").ifBlank { el.text() }.trim()
                val url = fixUrl(el.attr("href"), baseUrl) ?: infoUrl
                val data = gson.toJson(EpisodeData(url, dataId, el.attr("data-hash").ifBlank { null }))
                newEpisode(data) { this.name = name }
            } catch (e: Exception) {
                Log.e(this@AnimeVietsubProvider.name, "Error parsing episode item", e)
                null
            }
        }
    }

    data class EpisodeData(val url: String, val dataId: String?, val duHash: String?)
    data class AjaxPlayerResponse(val success: Int?, val link: List<LinkSource>?)
    data class LinkSource(val file: String?, val type: String?, val label: String?)

    private fun String?.encodeUri(): String {
        if (this == null) return ""
        return try { URLEncoder.encode(this, "UTF-8").replace("+", "%20") }
        catch (e: Exception) { Log.e("AnimeVietsubProvider", "URL encode error: $this", e); this }
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
