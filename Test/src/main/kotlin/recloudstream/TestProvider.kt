package recloudstream

import android.util.Log
import android.widget.Toast
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addDuration
import com.lagradost.cloudstream3.utils.AppUtils
import com.lagradost.cloudstream3.utils.AppUtils.toJson
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.newExtractorLink
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.ResponseBody.Companion.toResponseBody
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.io.ByteArrayOutputStream
import java.net.URL
import java.net.URLEncoder
import java.security.MessageDigest
import java.util.Base64
import java.util.EnumSet
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

    // ================== LOGIC GIẢI MÃ & INTERCEPTOR (GIỮ NGUYÊN) ==================
    
    private val m3u8Contents = mutableMapOf<String, String>()
    private val keyStringB64 = "ZG1fdGhhbmdfc3VjX3ZhdF9nZXRfbGlua19hbl9kYnQ="
    private val aesKeyBytes: ByteArray by lazy {
        val decodedKeyBytes = Base64.getDecoder().decode(keyStringB64)
        MessageDigest.getInstance("SHA-256").digest(decodedKeyBytes)
    }

    private fun decryptAndDecompress(encryptedDataB64: String): String? {
        return try {
            val cleanedB64 = encryptedDataB64.replace(Regex("[^A-Za-z0-9+/=]"), "")
            val encryptedBytes = Base64.getDecoder().decode(cleanedB64)
            if (encryptedBytes.size < 16) return null
            val ivBytes = encryptedBytes.sliceArray(0..15)
            val ciphertextBytes = encryptedBytes.sliceArray(16 until encryptedBytes.size)
            val decipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
            decipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(aesKeyBytes, "AES"), IvParameterSpec(ivBytes))
            val decryptedBytesPadded = decipher.doFinal(ciphertextBytes)
            val inflater = Inflater(true)
            inflater.setInput(decryptedBytesPadded)
            val outputStream = ByteArrayOutputStream()
            val buffer = ByteArray(1024)
            while (!inflater.finished()) {
                val count = inflater.inflate(buffer)
                outputStream.write(buffer, 0, count)
            }
            inflater.end()
            var m3u8ContentRaw = outputStream.toString("UTF-8")
            m3u8ContentRaw = m3u8ContentRaw.trim().replace(Regex("^\"|\"$"), "")
            m3u8ContentRaw.replace("\\n", "\n")
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

    // ================== LOGIC DOMAIN RESOLVER (GIỮ NGUYÊN) ==================

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

    // ================== PHẦN MAIN PAGE & SEARCH (GIỮ NGUYÊN CƠ BẢN) ==================

    override val mainPage = mainPageOf(
        "/anime-moi/" to "Mới Cập Nhật",
        "/anime-sap-chieu/" to "Sắp Chiếu",
        "/bang-xep-hang/day.html" to "Xem Nhiều Trong Ngày"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        withContext(Dispatchers.Main) {
            CommonActivity.activity?.let { activity ->
                showToast(activity, "Provider Updated: Multi-Server Support", Toast.LENGTH_SHORT)
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
                    } catch (e: Exception) { null }
                }
            }
            else -> {
                document.select("ul.MovieList.Rows li.TPostMv").mapNotNull {
                    it.toSearchResponse(this, baseUrl)
                }
            }
        }
        val hasNext = if (request.data.contains("bang-xep-hang")) false
        else document.selectFirst("div.wp-pagenavi span.current + a.page, div.wp-pagenavi a.larger:contains(Trang Cuối)") != null
        
        return newHomePageResponse(HomePageList(request.name, home), hasNext)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        return try {
            val baseUrl = getBaseUrl()
            val requestUrl = "$baseUrl/tim-kiem/${query.encodeUri()}/"
            val document = app.get(requestUrl).document
            document.select("ul.MovieList.Rows li.TPostMv")
                .mapNotNull { it.toSearchResponse(this, baseUrl) }
        } catch (e: Exception) {
            emptyList()
        }
    }

    // ================== PHẦN LOAD DETAILS (CẬP NHẬT GỌI HÀM PARSE) ==================

    override suspend fun load(url: String): LoadResponse? {
        val baseUrl = getBaseUrl()
        try {
            val infoDocument = app.get(url, headers = mapOf("Referer" to baseUrl)).document
            val genres = infoDocument.getGenres()
            
            // Logic cũ: Chỉ load trang xem phim nếu không phải "Sắp chiếu"
            val watchPageDoc = if (!genres.any { it.equals("Anime sắp chiếu", ignoreCase = true) }) {
                try {
                    val watchPageUrl = if (url.endsWith("/")) "${url}xem-phim.html" else "$url/xem-phim.html"
                    app.get(watchPageUrl, referer = url).document
                } catch (e: Exception) {
                    null
                }
            } else null

            return infoDocument.toLoadResponse(this, url, baseUrl, watchPageDoc)
        } catch (e: Exception) {
            Log.e(name, "FATAL Error loading main info page ($url): ${e.message}", e)
            return null
        }
    }

    // ================== [LOGIC MỚI QUAN TRỌNG] PARSE EPISODES ĐA NGUỒN ==================

    // Model dữ liệu mới
    data class ServerData(val serverName: String, val hash: String, val id: String)
    data class LinkData(val sources: List<ServerData>)

    private fun Document.parseEpisodes(baseUrl: String): List<Episode> {
        val episodesMap = mutableMapOf<String, MutableList<ServerData>>()
        
        // 1. Quét qua từng nhóm server (div.server-group)
        this.select("div.server.server-group").forEach { group ->
            // Lấy tên server, vd: "AnimeVsub", "Kanefusa Fansub"
            val serverName = group.select("h3.server-name").text().trim().ifBlank { "VIP" }
            
            // 2. Lấy danh sách tập trong server đó
            group.select("ul.list-episode li a.btn-episode").forEach { epEl ->
                val epNum = epEl.attr("title").ifBlank { epEl.text() }.trim() // Vd: "Tập 01" hoặc "01"
                val id = epEl.attr("data-id")
                val hash = epEl.attr("data-hash")
                
                if (id.isNotBlank() && hash.isNotBlank()) {
                    val svData = ServerData(serverName, hash, id)
                    
                    // Gom nhóm theo tên tập (key)
                    if (episodesMap.containsKey(epNum)) {
                        episodesMap[epNum]?.add(svData)
                    } else {
                        episodesMap[epNum] = mutableListOf(svData)
                    }
                }
            }
        }

        // 3. Chuyển đổi Map thành List<Episode> của Cloudstream
        return episodesMap.map { (epName, sourceList) ->
            // sourceList chứa tất cả server của tập đó
            val dataJson = LinkData(sourceList).toJson()
            newEpisode(dataJson) {
                this.name = epName
            }
        }
    }

    // ================== [LOGIC MỚI QUAN TRỌNG] LOAD LINKS SONG SONG ==================

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val linkData = AppUtils.parseJson<LinkData>(data)
        val baseUrl = getBaseUrl()

        // Sử dụng coroutineScope để chạy song song (Parallel) các request
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

                        if (response.contains("[{\"file\":\"")) {
                            val encrypted = response.substringAfter("[{\"file\":\"").substringBefore("\"}")
                            val decryptedM3u8 = decryptAndDecompress(encrypted)
                            
                            // Key duy nhất cho interceptor
                            val key = "${source.hash}${source.id}"

                            if (decryptedM3u8 != null) {
                                m3u8Contents[key] = decryptedM3u8
                                callback.invoke(
                                    newExtractorLink(
                                        source = name,
                                        // Đặt tên nguồn bao gồm tên Server gốc (Vd: AnimeVietsub - Kanefusa Fansub)
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
                    } catch (e: Exception) {
                        Log.e(name, "Error loading link for server ${source.serverName}", e)
                    }
                }
            }.awaitAll() // Đợi tất cả request hoàn tất
        }
        return true
    }

    // ================== HELPER FUNCTIONS & PARSING CHI TIẾT (GIỮ NGUYÊN/UPDATE NHẸ) ==================

    private fun Element.toSearchResponse(provider: MainAPI, baseUrl: String): SearchResponse? {
        return try {
            val linkElement = this.selectFirst("article.TPost > a") ?: return null
            val href = fixUrl(linkElement.attr("href"), baseUrl) ?: return null
            val title = linkElement.selectFirst("h2.Title")?.text()?.trim()?.takeIf { it.isNotBlank() } ?: return null
            val posterUrlRaw = linkElement.selectFirst("div.Image img")?.let { img ->
                img.attr("data-src").ifBlank { img.attr("src") }
            }
            val posterUrl = fixUrl(posterUrlRaw, baseUrl)
            val episodeString = this.selectFirst("span.mli-eps")?.text()
            val episodeCount = episodeString?.filter { it.isDigit() }?.toIntOrNull()
            val tvType = if (episodeCount == null) TvType.Movie else TvType.Anime

            provider.newAnimeSearchResponse(title, href, tvType) {
                this.posterUrl = posterUrl
                this.dubStatus = EnumSet.of(DubStatus.Subbed)
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
        watchPageDoc: Document?
    ): LoadResponse? {
        try {
            val title = this.selectFirst("div.TPost.Single div.Title")?.text()?.trim()
                ?: this.selectFirst("meta[property=og:title]")?.attr("content")?.substringBefore(" Tập")?.trim()
                ?: return null

            val posterUrl = this.extractPosterUrl(baseUrl)
            val plot = this.extractPlot()
            val tags = this.getGenres()
            val year = this.extractYear()
            val rating = this.extractRating()
            val actors = this.extractActors(baseUrl)
            val recommendations = this.extractRecommendations(provider, baseUrl)
            
            // Gọi hàm parseEpisodes mới
            val episodes = watchPageDoc?.parseEpisodes(baseUrl) ?: emptyList()
            
            val status = this.getShowStatus(episodes.size)
            val finalTvType = this.determineFinalTvType(title, tags, episodes.size)

            if (tags.any { it.equals("Anime sắp chiếu", ignoreCase = true) }) {
                return provider.newAnimeLoadResponse(title, infoUrl, TvType.Anime) {
                    this.posterUrl = posterUrl; this.plot = plot; this.tags = tags; this.year = year
                    this.score = rating?.let { Score.from10(it) }; this.actors = actors; this.recommendations = recommendations
                    this.comingSoon = true
                }
            }

            return provider.newAnimeLoadResponse(title, infoUrl, finalTvType) {
                this.posterUrl = posterUrl
                this.plot = plot
                this.tags = tags
                this.year = year
                this.score = rating?.let { Score.from10(it) }
                this.actors = actors
                this.recommendations = recommendations

                val isMovie = finalTvType == TvType.Movie || finalTvType == TvType.AnimeMovie || episodes.size <= 1

                if (isMovie) {
                    // Logic xử lý phim lẻ: Nếu có episodes (từ parseEpisodes), dùng nó. 
                    // Nếu không (trang info), fallback tạo data giả (cần check kỹ nếu trang info ko có link server)
                    if (episodes.isNotEmpty()) {
                        this.episodes = mutableMapOf(DubStatus.Subbed to episodes)
                    } else {
                        // Fallback logic cũ cho phim lẻ nếu không tìm thấy list tập
                        val dataId = this@toLoadResponse.getDataIdFallback(infoUrl) ?: ""
                        // Lưu ý: Fallback này có thể không hoạt động với logic Multi-server mới nếu không có hash
                        // Nên ở đây ta giả định parseEpisodes đã làm việc tốt ở trang watchPageDoc
                    }
                    val duration = this@toLoadResponse.extractDuration()
                    duration?.let { addDuration(it.toString()) }
                } else {
                    this.episodes = mutableMapOf(DubStatus.Subbed to episodes)
                    this.showStatus = status
                }
            }
        } catch (e: Exception) {
            return null
        }
    }

    private fun Document.extractPosterUrl(baseUrl: String): String? {
        val selectors = listOf("meta[property=og:image]", "meta[itemprop=image]", "div.TPostBg.Objf img.TPostBg", "div.TPost.Single div.Image figure.Objf img")
        for (selector in selectors) {
            val url = this.selectFirst(selector)?.attr("src")?.ifBlank { this.selectFirst(selector)?.attr("content") }
            if (!url.isNullOrBlank()) return fixUrl(url, baseUrl)
        }
        return null
    }

    private fun Document.extractPlot(): String? = 
        this.selectFirst("article.TPost.Single div.Description")?.text()?.trim() 
        ?: this.selectFirst("meta[property=og:description]")?.attr("content")?.trim()

    private fun Document.getGenres(): List<String> = 
        this.select("li:has(strong:containsOwn(Thể loại)) a, div.mvici-left li:contains(Thể loại) a")
        .mapNotNull { it.text()?.trim() }.distinct()

    private fun Document.extractYear(): Int? = 
        this.selectFirst("li:has(strong:containsOwn(Năm)), p.Info span.Date a")
        ?.text()?.filter { it.isDigit() }?.toIntOrNull()

    private fun Document.extractRating(): Float? = 
        this.selectFirst("li:has(strong:containsOwn(Điểm)), div#star[data-score]")?.let {
            it.ownText().ifBlank { it.attr("data-score") }
        }?.substringBefore("/")?.replace(",", ".")?.toFloatOrNull()

    private fun Document.extractDuration(): Int? = 
        this.selectFirst("li:has(strong:containsOwn(Thời lượng)), li.AAIco-adjust:contains(Thời lượng)")
        ?.ownText()?.filter { it.isDigit() }?.toIntOrNull()

    private fun Document.extractActors(baseUrl: String): List<ActorData> {
        return this.select("div#MvTb-Cast ul.ListCast li a").mapNotNull {
            val name = it.attr("title").removePrefix("Nhân vật ").trim()
            if (name.isNotBlank()) ActorData(Actor(name, image = fixUrl(it.selectFirst("img")?.attr("src"), baseUrl))) else null
        }
    }

    private fun Document.extractRecommendations(provider: MainAPI, baseUrl: String): List<SearchResponse> {
        return this.select("div.Wdgt div.MovieListRelated.owl-carousel div.TPostMv").mapNotNull { item ->
            val linkElement = item.selectFirst("a") ?: return@mapNotNull null
            val href = fixUrl(linkElement.attr("href"), baseUrl) ?: return@mapNotNull null
            val title = linkElement.selectFirst(".Title")?.text()?.trim() ?: return@mapNotNull null
            val posterUrl = fixUrl(linkElement.selectFirst("img")?.attr("src"), baseUrl)
            provider.newMovieSearchResponse(title, href, TvType.Anime) { this.posterUrl = posterUrl }
        }
    }

    private fun Document.getShowStatus(episodeCount: Int): ShowStatus {
        val statusText = this.selectFirst("li:has(strong:containsOwn(Trạng thái))")?.ownText()?.lowercase() ?: ""
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
                if (country == "nhật bản" || genres.any { it.contains("Anime", true) }) TvType.Anime else TvType.Movie
            }
            country == "nhật bản" -> TvType.Anime
            genres.any { it.contains("hoạt hình", true) } -> TvType.Cartoon
            else -> TvType.Anime
        }
    }

    private fun Document.getDataIdFallback(infoUrl: String): String? {
        return this.selectFirst("a.watch_button_more[href*=xem-phim]")?.attr("href")
            ?.substringAfterLast("a")?.substringBefore("/")
            ?: infoUrl.substringAfterLast("/")?.substringBefore("-")?.filter { it.isDigit() }
                ?.ifEmpty { infoUrl.substringAfterLast("-")?.filter { it.isDigit() } }?.takeIf { it.isNotBlank() }
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
