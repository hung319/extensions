package com.lagradost.cloudstream3

import android.util.Log
import com.google.gson.Gson
import com.lagradost.cloudstream3.LoadResponse.Companion.addActors
import com.lagradost.cloudstream3.LoadResponse.Companion.addDuration
import com.lagradost.cloudstream3.network.CloudflareKiller
import com.lagradost.cloudstream3.utils.*
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.Response
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.net.URL
import java.net.URLEncoder
import java.security.MessageDigest
import java.util.*
import java.util.zip.Inflater
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlin.collections.set
import kotlin.math.roundToInt
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

class AnimeVietsubProvider : MainAPI() {

    companion object {
        private val m3u8Contents = mutableMapOf<String, String>()
        // ===== THAY ĐỔI: Sử dụng domain .local để tránh xung đột =====
        private const val PROXY_DOMAIN = "https://animevietsub.local"
    }

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
    private val USER_AGENT = "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/137.0.0.0 Mobile Safari/537.36"

    private val bitlyResolverUrl = "https://bit.ly/animevietsubtv"
    private val ultimateFallbackDomain = "https://animevietsub.lol"
    private var currentActiveUrl = bitlyResolverUrl
    private var domainResolutionAttempted = false
    private val cfInterceptor = CloudflareKiller()

    // ... các hàm từ getMainPage đến load giữ nguyên ...
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
        val document = app.get(url, interceptor = cfInterceptor).document
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
                        Log.e(name, "Lỗi parse item của Bảng xếp hạng", e)
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
            val document = app.get(requestUrl, interceptor = cfInterceptor).document
            document.select("ul.MovieList.Rows li.TPostMv")
                .mapNotNull { it.toSearchResponse(this, baseUrl) }
        } catch (e: Exception) {
            Log.e(name, "Lỗi trong hàm search với query '$query'", e)
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
            val response = app.get(urlToAttemptResolution, allowRedirects = true, timeout = 15_000, interceptor = cfInterceptor)
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
            val infoDocument = app.get(url, headers = mapOf("Referer" to baseUrl), interceptor = cfInterceptor).document
            val genres = infoDocument.getGenres()
            val watchPageDoc = if (!genres.any { it.equals("Anime sắp chiếu", ignoreCase = true) }) {
                try {
                    val watchPageUrl = if (url.endsWith("/")) "${url}xem-phim.html" else "$url/xem-phim.html"
                    app.get(watchPageUrl, referer = url, interceptor = cfInterceptor).document
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


    private val aesKey: SecretKeySpec by lazy {
        val keyStringB64 = "ZG1fdGhhbmdfc3VjX3ZhdF9nZXRfbGlua19hbl9kYnQ="
        val decodedKeyBytes = Base64.getDecoder().decode(keyStringB64)
        val sha256Hasher = MessageDigest.getInstance("SHA-256")
        val hashedKeyBytes = sha256Hasher.digest(decodedKeyBytes)
        SecretKeySpec(hashedKeyBytes, "AES")
    }

    private fun decryptM3u8Content(encryptedDataString: String): String {
        try {
            val encryptedBytes = Base64.getDecoder().decode(encryptedDataString)
            if (encryptedBytes.size < 16) throw Exception("Dữ liệu sau decode quá ngắn")
            val ivBytes = encryptedBytes.copyOfRange(0, 16)
            val ciphertextBytes = encryptedBytes.copyOfRange(16, encryptedBytes.size)
            val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
            cipher.init(Cipher.DECRYPT_MODE, aesKey, IvParameterSpec(ivBytes))
            val decryptedBytesPadded = cipher.doFinal(ciphertextBytes)
            val inflater = Inflater(true)
            inflater.setInput(decryptedBytesPadded, 0, decryptedBytesPadded.size)
            val resultStream = java.io.ByteArrayOutputStream()
            val buffer = ByteArray(1024)
            while (!inflater.finished()) {
                val count = inflater.inflate(buffer)
                resultStream.write(buffer, 0, count)
            }
            inflater.end()
            var m3u8Content = resultStream.toByteArray().toString(Charsets.UTF_8)
            m3u8Content = m3u8Content.trim().removeSurrounding("\"")
            return m3u8Content
        } catch (e: Exception) {
            throw ErrorLoadingException("Giải mã thất bại: ${e.message}")
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        try {
            val episodeData = gson.fromJson(data, EpisodeData::class.java)
            val episodePageUrl = episodeData.url
            val baseUrl = getBaseUrl()

            val postData = mutableMapOf("id" to (episodeData.dataId ?: throw ErrorLoadingException("Thiếu episode ID")), "play" to "api").apply {
                episodeData.duHash?.let { put("link", it) }
            }
            val headers = mapOf(
                "Content-Type" to "application/x-www-form-urlencoded; charset=UTF-8",
                "Accept" to "application/json, text/javascript, */*; q=0.01",
                "X-Requested-With" to "XMLHttpRequest",
                "User-Agent" to USER_AGENT,
                "Referer" to episodePageUrl
            )
            val ajaxUrl = "$baseUrl/ajax/player?v=2019a"
            
            val playerResponse = app.post(ajaxUrl, data = postData, headers = headers, referer = episodePageUrl, interceptor = cfInterceptor).parsed<AjaxPlayerResponse>()

            if (playerResponse.success != 1 || playerResponse.link.isNullOrEmpty()) {
                callback(newExtractorLink(name, "Lỗi Server: ${playerResponse.toString().take(200)}", "debug", ExtractorLinkType.M3U8) {})
                return true
            }

            playerResponse.link.forEach { linkSource ->
                try {
                    val encryptedUrl = linkSource.file ?: return@forEach
                    val m3u8Content = decryptM3u8Content(encryptedUrl)
                    
                    // ===== DEBUG LOGGING: Hiển thị thông tin M3U8 ra giao diện =====
                    val lineCount = m3u8Content.lines().size
                    callback(newExtractorLink(name, "DEBUG: M3U8 dài ${m3u8Content.length} ký tự, có $lineCount dòng.", "debug", ExtractorLinkType.M3U8) {})
                    callback(newExtractorLink(name, "ĐẦU FILE: ${m3u8Content.take(150).replace("\n", " ")}", "debug", ExtractorLinkType.M3U8) {})
                    callback(newExtractorLink(name, "CUỐI FILE: ${m3u8Content.takeLast(150).replace("\n", " ")}", "debug", ExtractorLinkType.M3U8) {})
                    // =============================================================

                    if (m3u8Content.isBlank() || !m3u8Content.contains("#EXTM3U")) {
                       callback(newExtractorLink(name, "Lỗi: Nội dung M3U8 không hợp lệ!", "debug", ExtractorLinkType.M3U8) {})
                       return@forEach // Dừng xử lý link này
                    }

                    val refererForSegments = episodePageUrl
                    val key = UUID.randomUUID().toString()
                    val b64Referer = Base64.getUrlEncoder().encodeToString(refererForSegments.toByteArray())

                    val modifiedM3u8 = m3u8Content.lines().map { line ->
                        if (line.isNotBlank() && !line.startsWith("#")) {
                            val b64SegmentUrl = Base64.getUrlEncoder().encodeToString(line.toByteArray())
                            "$PROXY_DOMAIN/m3u8-segment-proxy/$b64SegmentUrl?referer=$b64Referer"
                        } else {
                            line
                        }
                    }.joinToString("\n")
                    
                    val firstSegment = modifiedM3u8.lines().firstOrNull { it.startsWith(PROXY_DOMAIN) } ?: "Không có segment nào"
                    callback(newExtractorLink(name, "SEGMENT MẪU: ${firstSegment.take(150)}", "debug", ExtractorLinkType.M3U8) {})

                    m3u8Contents[key] = modifiedM3u8
                    val manifestProxyUrl = "$PROXY_DOMAIN/m3u8-manifest-proxy/$key.m3u8"
                    
                    callback(newExtractorLink(
                        source = name,
                        name = linkSource.label ?: "$name HLS",
                        url = manifestProxyUrl,
                        type = ExtractorLinkType.M3U8
                    ).apply {
                        this.referer = episodePageUrl
                        this.quality = Qualities.Unknown.value
                    })
                } catch (e: Exception) {
                    callback(newExtractorLink(name, "Lỗi xử lý link: ${e.message}", "debug", ExtractorLinkType.M3U8) {})
                }
            }
        } catch (e: Exception) {
            callback(newExtractorLink(name, "Lỗi Toàn cục: ${e.message}", "debug", ExtractorLinkType.M3U8) {})
            Log.e(name, "Lỗi nghiêm trọng trong loadLinks: ${e.message}", e)
        }
        return true
    }

    override fun getVideoInterceptor(extractorLink: ExtractorLink): Interceptor? {
        val url = extractorLink.url
        // ===== THAY ĐỔI: Kiểm tra bằng domain .local =====
        if (!url.startsWith(PROXY_DOMAIN)) return null

        val isManifestRequest = url.contains("/m3u8-manifest-proxy/")
        val isSegmentRequest = url.contains("/m3u8-segment-proxy/")
        
        return Interceptor { chain ->
            val request = chain.request()
            
            if (isManifestRequest) {
                val key = url.substringAfterLast("/").substringBefore(".m3u8")
                val m3u8Content = m3u8Contents[key]
                m3u8Contents.remove(key)

                if (m3u8Content != null) {
                    val responseBody = okhttp3.ResponseBody.create("application/vnd.apple.mpegurl".toMediaTypeOrNull(), m3u8Content)
                    Response.Builder()
                        .request(request)
                        .protocol(okhttp3.Protocol.HTTP_1_1)
                        .code(200).message("OK")
                        .body(responseBody)
                        .build()
                } else {
                    Response.Builder()
                        .request(request)
                        .protocol(okhttp3.Protocol.HTTP_1_1)
                        .code(404).message("M3U8 Manifest Not Found in Cache")
                        .body(okhttp3.ResponseBody.create(null, ""))
                        .build()
                }
            } else if (isSegmentRequest) {
                val b64Url = url.substringAfter("/m3u8-segment-proxy/").substringBefore("?")
                val b64Referer = url.substringAfter("?referer=")
                
                val segmentUrl = String(Base64.getUrlDecoder().decode(b64Url))
                val referer = String(Base64.getUrlDecoder().decode(b64Referer))
                
                val segmentResponse = runBlocking {
                    app.get(segmentUrl, referer = referer)
                }
                
                Response.Builder()
                    .request(request)
                    .protocol(okhttp3.Protocol.HTTP_1_1)
                    .code(segmentResponse.code)
                    .message("OK") 
                    .headers(segmentResponse.headers)
                    .body(segmentResponse.body)
                    .build()
            } else {
                 // Nếu URL không khớp, trả về lỗi
                Response.Builder()
                    .request(request)
                    .protocol(okhttp3.Protocol.HTTP_1_1)
                    .code(400).message("Bad Proxy Request")
                    .body(okhttp3.ResponseBody.create(null, ""))
                    .build()
            }
        }
    }

    // Các hàm helper còn lại
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
                } else { // Cartoon, TvSeries
                    provider.newTvSeriesLoadResponse(title, infoUrl, finalTvType, episodes) {
                        this.posterUrl = posterUrl; this.plot = plot; this.tags = tags; this.year = year
                        this.rating = rating; this.showStatus = status; this.actors = actors; this.recommendations = recommendations
                    }
                }
            } else { // Movie
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
        catch (e: Exception) { Log.e("AnimeVietsubProvider", "Lỗi URL encode: $this", e); this }
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
