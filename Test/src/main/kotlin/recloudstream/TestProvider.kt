package com.lagradost.cloudstream3

// QUAN TRỌNG: Đảm bảo bạn có đủ các import này
import com.lagradost.cloudstream3.LoadResponse.Companion.addActors
import com.lagradost.cloudstream3.LoadResponse.Companion.addDuration
import com.lagradost.cloudstream3.utils.*
import android.util.Log
import com.google.gson.Gson
import okhttp3.Interceptor
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.Protocol
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.net.URLEncoder
import java.net.URL
import kotlin.math.roundToInt
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import com.lagradost.cloudstream3.utils.CloudflareKiller

// Các import cần thiết cho logic giải mã
import java.util.Base64
import java.util.zip.Inflater
import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

class AnimeVietsubProvider : MainAPI() {

    override var name = "AnimeVietsub (Coder)"
    override var mainUrl = "https://animevietsub.lol" 
    override val supportedTypes = setOf(TvType.Anime, TvType.Cartoon, TvType.Movie)
    override var lang = "vi"
    override val hasMainPage = true

    private val USER_AGENT = "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/137.0.0.0 Mobile Safari/537.36"
    private val gson = Gson()
    
    private val bitlyResolverUrl = "https://bit.ly/animevietsubtv"
    private val ultimateFallbackDomain = "https://animevietsub.lol"
    private var currentActiveUrl = bitlyResolverUrl
    private var domainResolutionAttempted = false
    
    override val mainPage = mainPageOf(
        "/anime-moi/" to "Mới Cập Nhật",
        "/anime-sap-chieu/" to "Sắp Chiếu",
        "/bang-xep-hang/day.html" to "Xem Nhiều Trong Ngày"
    )

    companion object {
        var M3U8_CONTENT = ""
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
            val response = app.get(urlToAttemptResolution, allowRedirects = true, timeout = 15_000, interceptor = CloudflareKiller())
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
        val document = app.get(url, interceptor = CloudflareKiller()).document
        
        val home = document.select("ul.MovieList.Rows li.TPostMv, ul.bxh-movie-phimletv li.group").mapNotNull {
            it.toSearchResponse(this, baseUrl)
        }

        val hasNext = if (request.data.contains("bang-xep-hang")) {
            false
        } else {
            document.selectFirst("div.wp-pagenavi span.current + a.page, div.wp-pagenavi a.larger:contains(Trang Cuối)") != null
        }

        return newHomePageResponse(
            list = HomePageList(name = request.name, list = home),
            hasNext = hasNext
        )
    }

    override suspend fun search(query: String): List<SearchResponse> {
        return try {
            val baseUrl = getBaseUrl()
            val requestUrl = "$baseUrl/tim-kiem/${query.encodeUri()}/"
            val document = app.get(requestUrl, interceptor = CloudflareKiller()).document
            document.select("ul.MovieList.Rows li.TPostMv")
                .mapNotNull { it.toSearchResponse(this, baseUrl) }
        } catch (e: Exception) {
            Log.e(name, "Lỗi trong hàm search với query '$query'", e)
            emptyList()
        }
    }

    override suspend fun load(url: String): LoadResponse? {
        val baseUrl = getBaseUrl()
        try {
            val infoDocument = app.get(url, headers = mapOf("Referer" to baseUrl), interceptor = CloudflareKiller()).document
            val genres = infoDocument.getGenres()
            val watchPageDoc = if (!genres.any { it.equals("Anime sắp chiếu", ignoreCase = true) }) {
                try {
                    val watchPageUrl = if (url.endsWith("/")) "${url}xem-phim.html" else "$url/xem-phim.html"
                    app.get(watchPageUrl, referer = url, interceptor = CloudflareKiller()).document
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
            val playerResponse = app.post(ajaxUrl, data = postData, headers = headers, referer = episodePageUrl, interceptor = CloudflareKiller()).parsed<AjaxPlayerResponse>()

            if (playerResponse.success != 1 || playerResponse.link.isNullOrEmpty()) {
                throw ErrorLoadingException("Lấy link thất bại từ AJAX: $playerResponse")
            }

            coroutineScope {
                playerResponse.link.forEach { linkSource ->
                    launch {
                        try {
                            val encryptedUrl = linkSource.file ?: return@launch
                            val m3u8Content = decryptM3u8Content(encryptedUrl)
                            M3U8_CONTENT = m3u8Content
                            
                            val fakeM3u8Url = "https://animevietsub.m3u8.local/manifest.m3u8?id=${episodeData.dataId}&hash=${System.currentTimeMillis()}"

                            callback(newExtractorLink(
                                source = name,
                                name = "$name HLS",
                                url = fakeM3u8Url,
                                type = ExtractorLinkType.M3U8
                            ) {
                                this.quality = Qualities.Unknown.value
                                this.isM3u8 = true 
                                this.headers = mapOf("Referer" to episodePageUrl)
                                this.referer = episodePageUrl
                            })
                        } catch (e: Exception) {
                            Log.e(name, "Error processing a link source", e)
                        }
                    }
                }
            }
        } catch (e: Exception) {
            val errorName = "Lỗi: ${e.message ?: "Không rõ"}"
            callback(newExtractorLink(source = name, name = errorName, url = "https://error.debug/log", type = ExtractorLinkType.M3U8){
                this.quality = Qualities.Unknown.value
            })
        }
        return true
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
            if (encryptedBytes.size < 16) throw Exception("Dữ liệu sau decode quá ngắn để giải mã.")
            val ivBytes = encryptedBytes.copyOfRange(0, 16)
            val ciphertextBytes = encryptedBytes.copyOfRange(16, encryptedBytes.size)
            val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
            cipher.init(Cipher.DECRYPT_MODE, aesKey, IvParameterSpec(ivBytes))
            val decryptedBytesPadded = cipher.doFinal(ciphertextBytes)
            val inflater = Inflater(true)
            inflater.setInput(decryptedBytesPadded, 0, decryptedBytesPadded.size)
            val resultBuffer = ByteArray(1024 * 1024)
            val decompressedLength = inflater.inflate(resultBuffer)
            inflater.end()
            val decompressedBytes = resultBuffer.copyOfRange(0, decompressedLength)
            var m3u8Content = decompressedBytes.toString(Charsets.UTF_8)
            m3u8Content = m3u8Content.trim().removeSurrounding("\"").replace("\\n", "\n")
            return m3u8Content
        } catch (e: Exception) {
            throw ErrorLoadingException("Giải mã M3U8 thất bại: ${e.message}")
        }
    }

    override fun getVideoInterceptor(extractorLink: ExtractorLink): Interceptor? {
        if (!extractorLink.url.contains("animevietsub.m3u8.local")) return null
        return Interceptor { chain ->
            val m3u8 = M3U8_CONTENT
            if (m3u8.isBlank()) {
                return@Interceptor Response.Builder()
                    .code(404).protocol(Protocol.HTTP_2)
                    .request(chain.request()).message("M3U8 content not found in provider")
                    .body("".toResponseBody(null)).build()
            }
            val body = m3u8.toResponseBody("application/vnd.apple.mpegurl".toMediaTypeOrNull())
            Response.Builder()
                .code(200).protocol(Protocol.HTTP_2)
                .request(chain.request()).message("OK")
                .body(body).build()
        }
    }
    
    private data class EpisodeData(val url: String, val dataId: String?, val duHash: String?)
    private data class AjaxPlayerResponse(val success: Int?, val link: List<LinkSource>?)
    private data class LinkSource(val file: String?, val type: String?, val label: String?)

    private fun Element.toSearchResponse(provider: MainAPI, baseUrl: String): SearchResponse? {
        return try {
            val linkElement = this.selectFirst("article.TPost > a, div.post-item > a, a") ?: return null
            val href = fixUrl(linkElement.attr("href"), baseUrl) ?: return null
            val title = this.selectFirst("h2.Title, h3.title-item, h3.title")?.text()?.trim()?.takeIf { it.isNotBlank() } ?: return null
            val posterUrlRaw = this.selectFirst("div.Image img, a.thumb img")?.let { img ->
                img.attr("data-src").ifBlank { img.attr("src") }
            }
            val posterUrl = fixUrl(posterUrlRaw, baseUrl)
            val isMovie = listOf("OVA", "ONA", "Movie", "Phim Lẻ").any { title.contains(it, true) } || this.selectFirst("span.mli-eps") == null
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
        provider: MainAPI, infoUrl: String, baseUrl: String, watchPageDoc: Document?
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
                return provider.newAnimeLoadResponse(title, infoUrl, TvType.Anime) {
                    this.posterUrl = posterUrl; this.plot = plot; this.tags = tags; this.year = year
                    this.rating = rating; this.actors = actors; this.recommendations = recommendations
                }
            }

            val episodes = watchPageDoc?.parseEpisodes(baseUrl, infoUrl) ?: emptyList()
            val status = this.getShowStatus(episodes.size)
            val tvType = this.determineFinalTvType(title, tags, episodes.size)

            return if (tvType != TvType.Movie) {
                provider.newTvSeriesLoadResponse(title, infoUrl, tvType, episodes) {
                    this.posterUrl = posterUrl; this.plot = plot; this.tags = tags; this.year = year
                    this.rating = rating; this.showStatus = status; this.actors = actors; this.recommendations = recommendations
                }
            } else {
                val duration = this.extractDuration()
                val data = episodes.firstOrNull()?.data
                    ?: gson.toJson(EpisodeData(infoUrl, this.getDataIdFallback(infoUrl), null))
                provider.newMovieLoadResponse(title, infoUrl, TvType.Movie, data) {
                    this.posterUrl = posterUrl; this.plot = plot; this.tags = tags; this.year = year
                    this.rating = rating; this.actors = actors; this.recommendations = recommendations
                    duration?.let { addDuration(it) } // <<<<<<< ĐÂY LÀ DÒNG SỬA LỖI
                }
            }
        } catch (e: Exception) {
            Log.e(name, "Error in toLoadResponse for url: $infoUrl", e)
            return null
        }
    }

    private fun Document.extractPosterUrl(baseUrl: String): String? {
        val selectors = listOf(
            "meta[property=og:image]", "meta[itemprop=image]",
            "div.TPost.Single div.Image figure.Objf img", "div.TPost.Single div.Image img"
        )
        for (selector in selectors) {
            val url = this.selectFirst(selector)?.attr("src")?.ifBlank { this.selectFirst(selector)?.attr("content") }
            if (!url.isNullOrBlank()) return fixUrl(url, baseUrl)
        }
        return null
    }

    private fun Document.extractPlot(): String? {
        return this.selectFirst("article.TPost.Single div.Description")?.text()?.trim()
            ?: this.selectFirst("meta[property=og:description]")?.attr("content")?.trim()
    }

    private fun Document.getGenres(): List<String> = this.select("li:has(strong:containsOwn(Thể loại)) a, div.mvici-left li:contains(Thể loại) a").mapNotNull { it.text()?.trim() }.distinct()
    private fun Document.extractYear(): Int? = this.selectFirst("li:has(strong:containsOwn(Năm)) a, p.Info span.Date a")?.text()?.filter { it.isDigit() }?.toIntOrNull()
    private fun Document.extractDuration(): Int? = this.selectFirst("li:has(strong:containsOwn(Thời lượng))")?.ownText()?.filter { it.isDigit() }?.toIntOrNull()
    
    private fun Document.extractRating(): Int? {
        val ratingText = this.selectFirst("div.VotesCn strong#average_score")?.text()?.substringBefore("/")?.replace(",",".")
        return ratingText?.toDoubleOrNull()?.let { (it * 10).roundToInt() }
    }
    
    private fun Document.extractActors(baseUrl: String): List<ActorData> {
        return this.select("div#MvTb-Cast ul.ListCast li a").mapNotNull {
            val name = it.attr("title").removePrefix("Nhân vật ").trim()
            if (name.isNotBlank()) ActorData(Actor(name, image = fixUrl(it.selectFirst("img")?.attr("src"), baseUrl))) else null
        }
    }
    
    private fun Document.extractRecommendations(provider: MainAPI, baseUrl: String): List<SearchResponse> {
        return this.select("div.Wdgt div.MovieListRelated div.TPostMv").mapNotNull { item ->
            item.toSearchResponse(provider, baseUrl)
        }
    }
    
    private fun Document.getShowStatus(episodeCount: Int): ShowStatus {
        val statusText = this.selectFirst("li:has(strong:containsOwn(Trạng thái))")?.ownText()?.lowercase() ?: ""
        return when {
            statusText.contains("đang chiếu") -> ShowStatus.Ongoing
            statusText.contains("hoàn thành") -> ShowStatus.Completed
            episodeCount <= 1 -> ShowStatus.Completed
            else -> ShowStatus.Ongoing
        }
    }
    
    private fun Document.determineFinalTvType(title: String, genres: List<String>, episodeCount: Int): TvType {
        val country = this.selectFirst("li:has(strong:containsOwn(Quốc gia)) a")?.text()?.lowercase() ?: ""
        return when {
            title.contains("movie", true) || title.contains("phim lẻ", true) || episodeCount <= 1 -> TvType.Movie
            country == "nhật bản" || genres.any { it.contains("Anime", true) } -> TvType.Anime
            else -> TvType.Cartoon
        }
    }
    
    private fun Document.getDataIdFallback(infoUrl: String): String? {
        return this.selectFirst("a.watch_button_more[href*=xem-phim]")?.attr("href")?.substringAfterLast("a")?.substringBefore("/")
            ?: infoUrl.substringAfterLast("-")?.filter { it.isDigit() }?.takeIf { it.isNotBlank() }
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
        }.reversed()
    }

    private fun String?.encodeUri(): String = this?.let { URLEncoder.encode(it, "UTF-8") } ?: ""
    private fun fixUrl(url: String?, baseUrl: String): String? {
        if (url.isNullOrBlank()) return null
        return when {
            url.startsWith("http") -> url
            url.startsWith("//") -> "https:$url"
            else -> URL(URL(baseUrl), url).toString()
        }
    }
}
