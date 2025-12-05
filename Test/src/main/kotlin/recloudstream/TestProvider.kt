package recloudstream

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addActors
import com.lagradost.cloudstream3.LoadResponse.Companion.addDuration
import com.lagradost.cloudstream3.utils.AppUtils
import com.lagradost.cloudstream3.utils.AppUtils.toJson
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.newExtractorLink
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
import java.util.zip.Inflater
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec
import java.util.EnumSet
import com.lagradost.cloudstream3.CommonActivity.showToast
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import android.widget.Toast
// Imports mới cho xử lý song song và static map
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import java.util.concurrent.ConcurrentHashMap

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

    // ================== COMPANION OBJECT (FIX LỖI 404) ==================
    // Dữ liệu này phải là STATIC để tồn tại giữa các phiên load và play
    companion object {
        private val m3u8Contents = ConcurrentHashMap<String, String>()
    }

    // ================== LOGIC GIẢI MÃ ==================
    
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

    // ================== INTERCEPTOR ==================

    override fun getVideoInterceptor(extractorLink: ExtractorLink): Interceptor {
        return Interceptor { chain ->
            val request = chain.request()
            val url = request.url.toString()
            
            // Logic gốc: kiểm tra domain giả
            if (url.contains("hdev.io/animevietsub")) {
                val key = url.substringAfterLast("/")
                
                // Lấy từ companion object map
                val m3u8Content = m3u8Contents[key]
                
                if (m3u8Content != null) {
                    val responseBody =
                        m3u8Content.toResponseBody("application/vnd.apple.mpegurl".toMediaTypeOrNull())
                    chain.proceed(request).newBuilder()
                        .code(200)
                        .message("OK")
                        .body(responseBody)
                        .build()
                } else {
                    // Nếu không tìm thấy trong map (lỗi 404 thật), vẫn proceed để log lỗi
                    chain.proceed(request)
                }
            } else {
                chain.proceed(request)
            }
        }
    }

    // ================== DOMAIN RESOLVER (LOGIC GỐC) ==================

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

        if (resolvedDomain != null) {
            currentActiveUrl = resolvedDomain
        } else {
            currentActiveUrl = ultimateFallbackDomain
        }
        return currentActiveUrl
    }

    // ================== MAIN PAGE & SEARCH (LOGIC GỐC) ==================

    override val mainPage = mainPageOf(
        "/anime-moi/" to "Mới Cập Nhật",
        "/anime-sap-chieu/" to "Sắp Chiếu",
        "/bang-xep-hang/day.html" to "Xem Nhiều Trong Ngày"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        withContext(Dispatchers.Main) {
            CommonActivity.activity?.let { activity ->
                showToast(activity, "Free Repo From H4RS", Toast.LENGTH_LONG)
            }
        }
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
        
        try {
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
                            e.printStackTrace()
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
        } catch (e: Exception) {
            e.printStackTrace()
            return newHomePageResponse(HomePageList(request.name, emptyList()), false)
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        return try {
            val baseUrl = getBaseUrl()
            val requestUrl = "$baseUrl/tim-kiem/${query.encodeUri()}/"
            val document = app.get(requestUrl).document
            document.select("ul.MovieList.Rows li.TPostMv")
                .mapNotNull { it.toSearchResponse(this, baseUrl) }
        } catch (e: Exception) {
            e.printStackTrace()
            emptyList()
        }
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
            val episodeString = this.selectFirst("span.mli-eps")?.text()
            val episodeCount = episodeString?.filter { it.isDigit() }?.toIntOrNull()
            val tvType = if (episodeCount == null) TvType.Movie else TvType.Anime

            provider.newAnimeSearchResponse(title, href, tvType) {
                this.posterUrl = posterUrl
                this.dubStatus = EnumSet.of(DubStatus.Subbed)
                if (episodeCount != null) {
                    this.episodes = mutableMapOf(
                        DubStatus.Subbed to episodeCount
                    )
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    // ================== LOAD DETAILS & PARSE EPISODES ==================

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
                    System.out.println("Failed to load watch page. Error: ${e.message}")
                    null
                }
            } else {
                null
            }
            return infoDocument.toLoadResponse(this, url, baseUrl, watchPageDoc)
        } catch (e: Exception) {
            e.printStackTrace()
            return null
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
            
            // Xử lý episodes với logic gộp nguồn mới
            val episodes = watchPageDoc?.parseEpisodes(baseUrl) ?: emptyList()
            
            val status = this.getShowStatus(episodes.size)
            val finalTvType = this.determineFinalTvType(title, tags, episodes.size)

            if (tags.any { it.equals("Anime sắp chiếu", ignoreCase = true) }) {
                return provider.newAnimeLoadResponse(title, infoUrl, TvType.Anime) {
                    this.posterUrl = posterUrl
                    this.plot = plot
                    this.tags = tags
                    this.year = year
                    this.score = rating?.let { Score.from10(it) }
                    this.actors = actors
                    this.recommendations = recommendations
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
                    // Logic tạo episode cho phim lẻ dựa trên cấu trúc mới
                    val episodeDataStr = if (episodes.isNotEmpty()) {
                        episodes.first().data
                    } else {
                        val fallbackId = this@toLoadResponse.getDataIdFallback(infoUrl) ?: ""
                        EpisodeData(listOf(ServerInfo("Server VIP", "", fallbackId))).toJson()
                    }

                    val movieEpisode = newEpisode(episodeDataStr) {
                        name = title
                    }
                    this.episodes = mutableMapOf(DubStatus.Subbed to listOf(movieEpisode))

                    val duration = this@toLoadResponse.extractDuration()
                    duration?.let { addDuration(it.toString()) }
                } else {
                    this.episodes = mutableMapOf(DubStatus.Subbed to episodes)
                    this.showStatus = status
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
            return null
        }
    }

    // Logic gộp các nguồn (server)
    private fun Document.parseEpisodes(baseUrl: String): List<Episode> {
        val episodeMap = mutableMapOf<String, MutableList<ServerInfo>>()
        val orderedNames = mutableListOf<String>()

        this.select("div.server.server-group").forEach { serverGroup ->
            val serverName = serverGroup.selectFirst("h3.server-name")?.text()?.trim() ?: "Server"
            
            serverGroup.select("ul.list-episode li a.btn-episode").forEach { el ->
                val episodeName = el.attr("title").ifBlank { el.text() }.trim()
                val dataId = el.attr("data-id").ifBlank { null }
                val dataHash = el.attr("data-hash").ifBlank { null }

                if (dataId != null && dataHash != null) {
                    if (!episodeMap.containsKey(episodeName)) {
                        episodeMap[episodeName] = mutableListOf()
                        orderedNames.add(episodeName)
                    }
                    episodeMap[episodeName]?.add(ServerInfo(serverName, dataHash, dataId))
                }
            }
        }

        return orderedNames.mapNotNull { epName ->
            val servers = episodeMap[epName] ?: return@mapNotNull null
            val data = EpisodeData(servers).toJson()
            newEpisode(data) {
                name = epName
            }
        }
    }

    // ================== LOAD LINKS (PARALLEL) ==================

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        // Parse data theo cấu trúc mới, fallback nếu dữ liệu cũ
        val episodeData = try {
            AppUtils.parseJson<EpisodeData>(data)
        } catch (e: Exception) {
            try {
                val oldData = AppUtils.parseJson<LinkDataOld>(data)
                EpisodeData(listOf(ServerInfo("Server Chính", oldData.hash, oldData.id)))
            } catch (ex: Exception) {
                return false
            }
        }

        val baseUrl = getBaseUrl()

        // Xử lý song song bằng async/await
        withContext(Dispatchers.IO) {
            episodeData.servers.map { server ->
                async {
                    try {
                        val response = app.post(
                            "$baseUrl/ajax/player",
                            headers = mapOf(
                                "content-type" to "application/x-www-form-urlencoded; charset=UTF-8",
                                "x-requested-with" to "XMLHttpRequest",
                                "Referer" to baseUrl,
                            ),
                            data = mapOf("link" to server.hash, "id" to server.id)
                        ).text

                        if (response.contains("[{\"file\":\"")) {
                            val encrypted = response.substringAfter("[{\"file\":\"").substringBefore("\"}")
                            val decryptedM3u8 = decryptAndDecompress(encrypted)
                            
                            // Tạo key giống hệt logic gốc để Interceptor bắt được
                            val key = "${server.hash}${server.id}"

                            if (decryptedM3u8 != null) {
                                // Lưu vào STATIC map
                                m3u8Contents[key] = decryptedM3u8
                                
                                callback.invoke(
                                    newExtractorLink(
                                        source = server.name,
                                        name = "${this@AnimeVietsubProvider.name} - ${server.name}",
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
                        e.printStackTrace()
                    }
                }
            }.awaitAll() // Chờ tất cả request hoàn thành
        }
        return true
    }

    // ================== HELPER METHODS (LOGIC GỐC) ==================

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

    private fun Document.extractRating(): Float? {
        val ratingText = this.selectFirst(
            "li:has(strong:containsOwn(Điểm)), div#star[data-score], input#score_current[value], div.VotesCn strong#average_score"
        )?.let {
            it.ownText().ifBlank { it.attr("data-score").ifBlank { it.attr("value").ifBlank { it.text() } } }
        }?.substringBefore("/")?.replace(",", ".")
        return ratingText?.toFloatOrNull()
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
                e.printStackTrace()
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

    private fun String?.encodeUri(): String {
        if (this == null) return ""
        return try { URLEncoder.encode(this, "UTF-8").replace("+", "%20") }
        catch (e: Exception) { e.printStackTrace(); this }
    }

    private fun fixUrl(url: String?, baseUrl: String): String? {
        if (url.isNullOrBlank()) return null
        return when {
            url.startsWith("http") -> url
            url.startsWith("//") -> "https:$url"
            else -> URL(URL(baseUrl), url).toString()
        }
    }

    // ================== DATA CLASSES ==================
    data class LinkDataOld(val hash: String, val id: String)

    data class ServerInfo(
        val name: String,
        val hash: String,
        val id: String
    )

    data class EpisodeData(
        val servers: List<ServerInfo>
    )
}
