package com.lagradost.cloudstream3

import android.util.Log
import com.google.gson.Gson
import com.lagradost.cloudstream3.LoadResponse.Companion.addActors
import com.lagradost.cloudstream3.LoadResponse.Companion.addDuration
import com.lagradost.cloudstream3.network.CloudflareKiller
import com.lagradost.cloudstream3.utils.*
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
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

class AnimeVietsubProvider : MainAPI() {

    companion object {
        private val m3u8Contents = mutableMapOf<String, String>()
        private const val PROXY_DOMAIN = "https://avs-final.local"
    }

    private val gson = Gson()
    
    override var name = "AnimeVietsub"
    override val supportedTypes = setOf(TvType.Anime, TvType.Movie, TvType.Cartoon)
    override var lang = "vi"
    override val hasMainPage = true

    private val cfInterceptor = CloudflareKiller()

    override var mainUrl: String = runBlocking {
        try {
            val response = app.get("https://bit.ly/animevietsubtv", interceptor = cfInterceptor, allowRedirects = true)
            val finalUrl = URL(response.url)
            "${finalUrl.protocol}://${finalUrl.host}"
        } catch (e: Exception) {
            "https://animevietsub.lol"
        }
    }

    override val mainPage = mainPageOf(
        "/anime-moi/" to "Mới Cập Nhật",
        "/anime-sap-chieu/" to "Sắp Chiếu",
        "/bang-xep-hang/day.html" to "Xem Nhiều Trong Ngày"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = if (page == 1) {
            "$mainUrl${request.data}"
        } else {
            if (request.data.contains("bang-xep-hang")) {
                "$mainUrl${request.data}"
            } else {
                val slug = request.data.removeSuffix("/")
                "$mainUrl$slug/trang-$page.html"
            }
        }
        val document = app.get(url, interceptor = cfInterceptor).document
        val home = when {
            request.data.contains("bang-xep-hang") -> {
                document.select("ul.bxh-movie-phimletv li.group").mapNotNull { element ->
                    element.toSearchResponse(this, mainUrl)
                }
            }
            else -> {
                document.select("ul.MovieList.Rows li.TPostMv").mapNotNull {
                    it.toSearchResponse(this, mainUrl)
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
        val requestUrl = "$mainUrl/tim-kiem/${query.encodeUri()}/"
        val document = app.get(requestUrl, interceptor = cfInterceptor).document
        return document.select("ul.MovieList.Rows li.TPostMv")
            .mapNotNull { it.toSearchResponse(this, mainUrl) }
    }

    override suspend fun load(url: String): LoadResponse? {
        val infoDocument = app.get(url, interceptor = cfInterceptor).document
        val genres = infoDocument.getGenres()
        val watchPageDoc = if (!genres.any { it.equals("Anime sắp chiếu", ignoreCase = true) }) {
            try {
                val watchPageUrl = if (url.endsWith("/")) "${url}xem-phim.html" else "$url/xem-phim.html"
                app.get(watchPageUrl, referer = url, interceptor = cfInterceptor).document
            } catch (e: Exception) { null }
        } else { null }
        return infoDocument.toLoadResponse(this, url, mainUrl, watchPageDoc)
    }

    private val aesKey: SecretKeySpec by lazy {
        val keyStringB64 = "ZG1fdGhhbmdfc3VjX3ZhdF9nZXRfbGlua19hbl9kYnQ="
        val decodedKeyBytes = Base64.getDecoder().decode(keyStringB64)
        val sha256Hasher = MessageDigest.getInstance("SHA-256")
        val hashedKeyBytes = sha256Hasher.digest(decodedKeyBytes)
        SecretKeySpec(hashedKeyBytes, "AES")
    }

    private fun decryptM3u8Content(encryptedDataString: String): String {
        return try {
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
            m3u8Content.replace("\\n", "\n")
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
            val ajaxUrl = "$mainUrl/ajax/player?v=2019a"
            val postData = mutableMapOf("id" to (episodeData.dataId ?: return false), "play" to "api").apply {
                episodeData.duHash?.let { put("link", it) }
            }
            
            val playerResponse = app.post(ajaxUrl, data = postData, referer = episodeData.url, interceptor = cfInterceptor).parsed<AjaxPlayerResponse>()

            if (playerResponse.success != 1 || playerResponse.link.isNullOrEmpty()) return false

            playerResponse.link.forEach { linkSource ->
                try {
                    val encryptedUrl = linkSource.file ?: return@forEach
                    val m3u8Content = decryptM3u8Content(encryptedUrl)
                    if (!m3u8Content.contains("#EXTM3U")) return@forEach

                    val refererForSegments = episodeData.url
                    val key = UUID.randomUUID().toString()
                    val b64Referer = Base64.getUrlEncoder().encodeToString(refererForSegments.toByteArray())

                    val modifiedM3u8 = m3u8Content.lines().joinToString("\n") { line ->
                        if (line.isNotBlank() && !line.startsWith("#")) {
                            val b64SegmentUrl = Base64.getUrlEncoder().encodeToString(line.toByteArray())
                            "$PROXY_DOMAIN/segment/$b64SegmentUrl?referer=$b64Referer"
                        } else {
                            line
                        }
                    }

                    m3u8Contents[key] = modifiedM3u8
                    val manifestProxyUrl = "$PROXY_DOMAIN/manifest/$key.m3u8"
                    
                    callback(newExtractorLink(
                        source = name,
                        name = linkSource.label ?: name,
                        url = manifestProxyUrl,
                        type = ExtractorLinkType.M3U8
                    ).apply {
                        this.referer = episodeData.url
                    })
                } catch (e: Exception) {
                    Log.e(name, "Lỗi xử lý link source: ${e.message}")
                }
            }
        } catch (e: Exception) {
            Log.e(name, "Lỗi nghiêm trọng trong loadLinks: ${e.message}", e)
        }
        return true
    }

    override fun getVideoInterceptor(extractorLink: ExtractorLink): Interceptor? {
        val url = extractorLink.url
        if (!url.startsWith(PROXY_DOMAIN)) return null

        val isManifestRequest = url.contains("/manifest/")
        val isSegmentRequest = url.contains("/segment/")
        
        return Interceptor { chain ->
            try {
                val request = chain.request()
                
                if (isManifestRequest) {
                    val key = url.substringAfterLast("/").substringBefore(".m3u8")
                    val m3u8Content = m3u8Contents[key]
                    m3u8Contents.remove(key)

                    if (m3u8Content != null) {
                        val responseBody = okhttp3.ResponseBody.create("application/vnd.apple.mpegurl".toMediaTypeOrNull(), m3u8Content)
                        Response.Builder()
                            .request(request).protocol(okhttp3.Protocol.HTTP_1_1)
                            .code(200).message("OK")
                            .body(responseBody).build()
                    } else {
                        Response.Builder()
                            .request(request).protocol(okhttp3.Protocol.HTTP_1_1)
                            .code(404).message("Not Found in Cache")
                            .body(okhttp3.ResponseBody.create(null, "")).build()
                    }
                } else if (isSegmentRequest) {
                    val b64Url = url.substringAfter("/segment/").substringBefore("?")
                    val b64Referer = url.substringAfter("?referer=")
                    
                    val segmentUrl = String(Base64.getUrlDecoder().decode(b64Url))
                    val referer = String(Base64.getUrlDecoder().decode(b64Referer))
                    
                    // ===== THAY ĐỔI LỚN VÀ QUAN TRỌNG NHẤT =====
                    // Bước 1: Lấy "tấm vé thông hành" (headers chứa cookie và user-agent) từ CloudflareKiller.
                    val bypassHeaders = cfInterceptor.getCookieHeaders(segmentUrl)
                    
                    // Bước 2: Tạo map header mới bao gồm "tấm vé" và referer.
                    val segmentHeaders = bypassHeaders.newBuilder().add("Referer", referer).build()
                    
                    // Bước 3: Gửi request với header đã được chuẩn bị.
                    // QUAN TRỌNG: KHÔNG dùng interceptor ở đây nữa!
                    val segmentResponse = runBlocking {
                        app.get(segmentUrl, headers = segmentHeaders)
                    }
                    // ===============================================
                    
                    Response.Builder()
                        .request(request).protocol(okhttp3.Protocol.HTTP_1_1)
                        .code(segmentResponse.code).message("OK") 
                        .headers(segmentResponse.headers)
                        .body(segmentResponse.body).build()
                } else {
                    Response.Builder()
                        .request(request).protocol(okhttp3.Protocol.HTTP_1_1)
                        .code(400).message("Bad Proxy Request")
                        .body(okhttp3.ResponseBody.create(null, "")).build()
                }
            } catch(e: Exception) {
                Response.Builder()
                    .request(chain.request()).protocol(okhttp3.Protocol.HTTP_1_1)
                    .code(500).message("Interceptor Error: ${e.message}")
                    .body(okhttp3.ResponseBody.create(null, "")).build()
            }
        }
    }
    
    // ... Phần còn lại của các hàm helper ...
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
        } catch (e: Exception) { null }
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
                return provider.newAnimeLoadResponse(title, infoUrl, TvType.Anime) {
                    this.posterUrl = posterUrl; this.plot = plot; this.tags = tags; this.year = year
                    this.rating = rating; this.actors = actors; this.recommendations = recommendations
                }
            }
            val episodes = watchPageDoc?.parseEpisodes(baseUrl, infoUrl) ?: emptyList()
            val status = this.getShowStatus(episodes.size)
            val finalTvType = this.determineFinalTvType(title, tags, episodes.size)
            return if (finalTvType == TvType.Movie || finalTvType == TvType.AnimeMovie || episodes.size <= 1) {
                val duration = this.extractDuration()
                val data = episodes.firstOrNull()?.data
                    ?: gson.toJson(EpisodeData(infoUrl, this.getDataIdFallback(infoUrl), null))
                provider.newMovieLoadResponse(title, infoUrl, finalTvType, data) {
                    this.posterUrl = posterUrl; this.plot = plot; this.tags = tags; this.year = year
                    this.rating = rating; this.actors = actors; this.recommendations = recommendations
                    duration?.let { addDuration(it.toString()) }
                }
            } else {
                 provider.newTvSeriesLoadResponse(title, infoUrl, finalTvType, episodes) {
                    this.posterUrl = posterUrl; this.plot = plot; this.tags = tags; this.year = year
                    this.rating = rating; this.showStatus = status; this.actors = actors; this.recommendations = recommendations
                }
            }
        } catch (e: Exception) {
            Log.e(name, "Error in toLoadResponse for url: $infoUrl", e)
            return null
        }
    }

    private fun Document.extractPosterUrl(baseUrl: String): String? = this.selectFirst("meta[property=og:image]")?.attr("content")?.let { fixUrl(it, baseUrl) }
    private fun Document.extractPlot(): String? = this.selectFirst("div.Description")?.text()?.trim()
    private fun Document.getGenres(): List<String> = this.select("li:has(strong:containsOwn(Thể loại)) a").mapNotNull { it.text()?.trim() }.distinct()
    private fun Document.extractYear(): Int? = this.selectFirst("a[href*=/nam-phat-hanh/]")?.text()?.toIntOrNull()
    private fun Document.extractRating(): Int? = this.selectFirst("strong#average_score")?.text()?.toDoubleOrNull()?.let { (it * 10).roundToInt() }
    private fun Document.extractDuration(): Int? = this.selectFirst("li.AAIco-adjust")?.ownText()?.filter { it.isDigit() }?.toIntOrNull()
    private fun Document.extractActors(baseUrl: String): List<ActorData> = this.select("div#MvTb-Cast ul.ListCast li a").mapNotNull {
        val actorName = it.attr("title").removePrefix("Nhân vật ").trim()
        if (actorName.isNotBlank()) ActorData(Actor(actorName, image = fixUrl(it.selectFirst("img")?.attr("src"), baseUrl))) else null
    }
    private fun Document.extractRecommendations(provider: MainAPI, baseUrl: String): List<SearchResponse> = this.select("div.MovieListRelated div.TPostMv").mapNotNull { it.toSearchResponse(provider, baseUrl) }
    private fun Document.getShowStatus(episodeCount: Int): ShowStatus {
        return when (this.selectFirst("li:has(strong:containsOwn(Trạng thái))")?.ownText()?.lowercase()) {
            "đang chiếu", "đang tiến hành" -> ShowStatus.Ongoing
            "hoàn thành", "full" -> ShowStatus.Completed
            else -> if (episodeCount > 1) ShowStatus.Ongoing else ShowStatus.Completed
        }
    }
    private fun Document.determineFinalTvType(title: String, genres: List<String>, episodeCount: Int): TvType {
        val country = this.selectFirst("a[href*=/quoc-gia/]")?.text()?.lowercase() ?: ""
        return if (episodeCount <= 1 || title.contains("movie", true)) {
            if (country == "nhật bản") TvType.AnimeMovie else TvType.Movie
        } else {
            if (country == "nhật bản") TvType.Anime else TvType.Cartoon
        }
    }
    private fun Document.getDataIdFallback(infoUrl: String): String? = infoUrl.substringAfterLast("-")?.filter { it.isDigit() }?.takeIf { it.isNotBlank() }
    private fun Document.parseEpisodes(baseUrl: String, infoUrl: String): List<Episode> {
        return this.select("div.server ul.list-episode li a.btn-episode").mapNotNull { el ->
            try {
                val dataId = el.attr("data-id").ifBlank { null } ?: return@mapNotNull null
                newEpisode(gson.toJson(EpisodeData(fixUrl(el.attr("href"), baseUrl) ?: infoUrl, dataId, el.attr("data-hash").ifBlank { null }))) {
                    this.name = el.attr("title").ifBlank { el.text() }.trim()
                }
            } catch (e: Exception) { null }
        }
    }

    data class EpisodeData(val url: String, val dataId: String?, val duHash: String?)
    data class AjaxPlayerResponse(val success: Int?, val link: List<LinkSource>?)
    data class LinkSource(val file: String?, val type: String?, val label: String?)

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
