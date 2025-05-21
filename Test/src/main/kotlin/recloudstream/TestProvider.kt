// === File: AnimeVietsubProvider.kt ===
// Version: 2025-05-21 - Ưu tiên Big Banner cho Poster, sau đó đến Poster chuẩn
package recloudstream

// ... (Các import giữ nguyên) ...
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addActors
import com.lagradost.cloudstream3.LoadResponse.Companion.addDuration
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.newExtractorLink
import android.util.Log
import com.fasterxml.jackson.annotation.JsonProperty
import com.google.gson.Gson
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.net.URLEncoder
import java.net.URL
import kotlin.math.roundToInt
import kotlin.text.Regex

import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.MediaType.Companion.toMediaTypeOrNull

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

    private suspend fun getBaseUrl(): String {
        // ... (giữ nguyên logic getBaseUrl) ...
        if (domainResolutionAttempted && !currentActiveUrl.contains("bit.ly")) {
            return currentActiveUrl
        }
        var resolvedDomain: String? = null
        val urlToAttemptResolution = if (currentActiveUrl.contains("bit.ly") || !domainResolutionAttempted) bitlyResolverUrl else currentActiveUrl
        try {
            val response = app.get(urlToAttemptResolution, allowRedirects = true, timeout = 15_000)
            val finalUrlString = response.url
            if (finalUrlString.startsWith("http") && !finalUrlString.contains("bit.ly")) {
                val urlObject = URL(finalUrlString)
                resolvedDomain = "${urlObject.protocol}://${urlObject.host}"
            } else {
                Log.w("AnimeVietsubProvider", "Cannot resolve Bitly to valid domain. Final URL: $finalUrlString")
            }
        } catch (e: Exception) {
            Log.e("AnimeVietsubProvider", "Error resolving Bitly link '$urlToAttemptResolution': ${e.message}")
        }
        domainResolutionAttempted = true
        if (resolvedDomain != null) {
            currentActiveUrl = resolvedDomain
            this.mainUrl = resolvedDomain
        } else {
            if (currentActiveUrl.contains("bit.ly") || urlToAttemptResolution != ultimateFallbackDomain) {
                 currentActiveUrl = ultimateFallbackDomain
                 this.mainUrl = ultimateFallbackDomain
                 Log.w("AnimeVietsubProvider", "Bitly resolution failed. Using fallback: $ultimateFallbackDomain")
            }
        }
        return currentActiveUrl
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        // ... (giữ nguyên) ...
        if (page > 1) return newHomePageResponse(emptyList(), false)
        val lists = mutableListOf<HomePageList>()
        try {
            val baseUrl = getBaseUrl()
            val document = app.get(baseUrl).document
            document.select("section#single-home ul.MovieList.Rows li.TPostMv")
                .mapNotNull { it.toSearchResponse(this, baseUrl) }
                .takeIf { it.isNotEmpty() }?.let { lists.add(HomePageList("Mới cập nhật", it)) }
            document.select("section#new-home ul.MovieList.Rows li.TPostMv")
                .mapNotNull { it.toSearchResponse(this, baseUrl) }
                .takeIf { it.isNotEmpty() }?.let { lists.add(HomePageList("Sắp chiếu", it)) }
            document.select("section#hot-home ul.MovieList.Rows li.TPostMv")
                .mapNotNull { it.toSearchResponse(this, baseUrl) }
                .takeIf { it.isNotEmpty() }?.let {
                    val hotListName = document.selectFirst("section#hot-home div.Top a.STPb.Current")?.text() ?: "Đề cử"
                    lists.add(HomePageList(hotListName, it))
                }
            if (lists.isEmpty()) throw ErrorLoadingException("No lists found on homepage.")
            return newHomePageResponse(lists, hasNext = false)
        } catch (e: Exception) {
            Log.e("AnimeVietsubProvider", "Error in getMainPage", e)
            if (e is ErrorLoadingException) throw e
            throw RuntimeException("Unknown error loading main page: ${e.message}")
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        // ... (giữ nguyên) ...
        try {
            val baseUrl = getBaseUrl()
            val searchUrl = "$baseUrl/tim-kiem/${query.encodeUri()}/"
            val document = app.get(searchUrl).document
            return document.selectFirst("ul.MovieList.Rows")?.select("li.TPostMv")
                ?.mapNotNull { it.toSearchResponse(this, baseUrl) } ?: emptyList()
        } catch (e: Exception) {
            Log.e("AnimeVietsubProvider", "Error in search for query: $query", e)
            return emptyList()
        }
    }

    override suspend fun load(url: String): LoadResponse? {
        // ... (giữ nguyên) ...
        val baseUrl = getBaseUrl()
        val infoUrl = url
        val watchPageUrl = if (infoUrl.endsWith("/")) "${infoUrl}xem-phim.html" else "$infoUrl/xem-phim.html"
        try {
            val infoDocument = app.get(infoUrl, headers = mapOf("Referer" to baseUrl)).document
            var watchPageDocument: Document? = null
            try {
                watchPageDocument = app.get(watchPageUrl, referer = infoUrl).document
            } catch (e: Exception) {
                Log.w("AnimeVietsubProvider", "Failed to load watch page ($watchPageUrl). Error: ${e.message}")
            }
            return infoDocument.toLoadResponse(this, infoUrl, baseUrl, watchPageDocument)
        } catch (e: Exception) {
            Log.e("AnimeVietsubProvider", "FATAL Error loading main info page ($infoUrl)", e)
            return null
        }
    }

    private fun Element.toSearchResponse(provider: MainAPI, baseUrl: String): SearchResponse? {
        // ... (giữ nguyên) ...
        return try {
            val linkElement = this.selectFirst("article.TPost > a") ?: return null
            val href = fixUrl(linkElement.attr("href"), baseUrl) ?: return null
            val titleFromElement = linkElement.selectFirst("h2.Title")?.text()?.trim()?.takeIf { it.isNotBlank() } ?: return null
            val posterUrlRaw = linkElement.selectFirst("div.Image img")?.let { img ->
                img.attr("data-src").ifBlank { img.attr("src") }
            }
            val posterUrl = fixUrl(posterUrlRaw, baseUrl)
            var finalTvType: TvType? = null
            val hasEpisodeSpan = this.selectFirst("span.mli-eps") != null
            val statusSpanText = this.selectFirst("span.mli-st")?.text() ?: ""

            if (titleFromElement.contains("OVA", ignoreCase = true) ||
                titleFromElement.contains("ONA", ignoreCase = true) ||
                titleFromElement.contains("Movie", ignoreCase = true) ||
                titleFromElement.contains("Phim Lẻ", ignoreCase = true) ||
                (!hasEpisodeSpan && statusSpanText.contains("Full", ignoreCase = true)) ||
                (!hasEpisodeSpan && statusSpanText.contains("Hoàn Tất", ignoreCase = true) && !hasEpisodeSpan)
            ) {
                finalTvType = if (provider.name.contains("Anime", ignoreCase = true) ||
                                 titleFromElement.contains("Anime", ignoreCase = true) &&
                                 !titleFromElement.contains("Trung Quốc", ignoreCase = true) && 
                                 !titleFromElement.contains("Donghua", ignoreCase = true) ) {
                    TvType.Anime
                } else {
                    TvType.Movie
                }
            } else if (hasEpisodeSpan) {
                finalTvType = if (provider.name.contains("Anime", ignoreCase = true) || titleFromElement.contains("Anime", ignoreCase = true)) {
                    TvType.Anime
                } else {
                    TvType.TvSeries 
                }
            }
            if (finalTvType == null) {
                finalTvType = if (hasEpisodeSpan || this.selectFirst("span.mli-quality") == null) TvType.Anime else TvType.Movie
            }
            // Log.d("AnimeVietsubProvider (Search)", "TvType for '$titleFromElement': $finalTvType")
            provider.newMovieSearchResponse(titleFromElement, href, finalTvType ?: TvType.TvSeries) {
                this.posterUrl = posterUrl
            }
        } catch (e: Exception) {
            Log.e("AnimeVietsubProvider", "Error parsing search result item: ${this.html()}", e)
            null
        }
    }

    data class EpisodeData(
        val url: String,
        val dataId: String?,
        val duHash: String?
    )

    private fun Document.getCountry(): String? {
        // ... (giữ nguyên) ...
        return this.selectFirst("div.mvici-left li.AAIco-adjust:contains(Quốc gia) a, ul.InfoList li:has(strong:containsOwn(Quốc gia)) a")
            ?.attr("title")?.trim()?.takeIf { it.isNotBlank() }
            ?: this.selectFirst("div.mvici-left li.AAIco-adjust:contains(Quốc gia) a, ul.InfoList li:has(strong:containsOwn(Quốc gia)) a")
                ?.text()?.trim()?.takeIf { it.isNotBlank() }
    }

    private suspend fun Document.toLoadResponse(
        provider: MainAPI,
        infoUrl: String,
        baseUrl: String,
        watchPageDoc: Document?
    ): LoadResponse? {
        val infoDoc = this
        try {
            val title = infoDoc.selectFirst("div.TPost.Single div.Title")?.text()?.trim()
                ?: infoDoc.selectFirst("meta[property=og:title]")?.attr("content")?.substringBefore(" Tập")?.trim()
                ?: run { Log.e("AnimeVietsubProvider", "Could not find title on info page $infoUrl"); return null }
            // Log.d("AnimeVietsubProvider", "Processing LoadResponse for: '$title'")

            // --- THAY ĐỔI LOGIC LẤY POSTER URL THEO YÊU CẦU MỚI ---
            var posterUrlForResponse: String? = null

            // Ưu tiên 1: Ảnh banner lớn (ví dụ từ TPostBg hoặc cấu trúc tương tự nếu bạn có selector tốt hơn cho "item")
            // Dựa trên HTML `load.html`, `div.TPostBg img.TPostBg` là ảnh nền lớn.
            posterUrlForResponse = infoDoc.selectFirst("div.TPostBg.Objf img.TPostBg")?.attr("src")
            if (!posterUrlForResponse.isNullOrBlank()) {
                Log.d("AnimeVietsubProvider", "Poster from Big Banner (TPostBg): $posterUrlForResponse")
            }

            // Ưu tiên 2: Ảnh poster chuẩn (nếu banner không có hoặc bạn muốn ảnh nhỏ hơn làm chính)
            if (posterUrlForResponse.isNullOrBlank()) {
                posterUrlForResponse = infoDoc.selectFirst("div.TPost.Single div.Image figure.Objf img")?.attr("src")
                if (!posterUrlForResponse.isNullOrBlank()) {
                    Log.d("AnimeVietsubProvider", "Poster from Standard Figure (div.Image figure.Objf img): $posterUrlForResponse")
                }
            }
            
            // Ưu tiên 3: Ảnh poster từ div.Image img (ít cụ thể hơn)
            if (posterUrlForResponse.isNullOrBlank()) {
                posterUrlForResponse = infoDoc.selectFirst("div.TPost.Single div.Image img")?.attr("src")
                if (!posterUrlForResponse.isNullOrBlank()) {
                    Log.d("AnimeVietsubProvider", "Poster from Standard Div (div.Image img): $posterUrlForResponse")
                }
            }

            // Ưu tiên 4: Thẻ Meta (ưu tiên link chứa "/poster/")
            if (posterUrlForResponse.isNullOrBlank()) {
                val metaImages = infoDoc.select("meta[property=og:image], meta[itemprop=image]")
                // Log.d("AnimeVietsubProvider", "Found ${metaImages.size} meta image tags for '$title'.")
                
                posterUrlForResponse = metaImages.mapNotNull { it.attr("content").takeIf { c -> c.isNotBlank() } }
                                     .firstOrNull { it.contains("/poster/", ignoreCase = true) }
                
                if (!posterUrlForResponse.isNullOrBlank()) {
                    // Log.d("AnimeVietsubProvider", "Poster for '$title' from meta (preferred /poster/): $posterUrlForResponse")
                } else {
                    posterUrlForResponse = metaImages.mapNotNull { it.attr("content").takeIf { c -> c.isNotBlank() } }.firstOrNull()
                    // if (!posterUrlForResponse.isNullOrBlank()) {
                    //     Log.d("AnimeVietsubProvider", "Poster for '$title' from meta (first available): $posterUrlForResponse")
                    // }
                }
            }
            posterUrlForResponse = fixUrl(posterUrlForResponse, baseUrl)
            Log.i("AnimeVietsubProvider", "Final posterUrl for '$title': $posterUrlForResponse")
            // --- KẾT THÚC LẤY POSTER URL ---


            val descriptionFromDiv = infoDoc.selectFirst("article.TPost.Single div.Description")?.text()?.trim()
            val description = if (!descriptionFromDiv.isNullOrBlank()) {
                descriptionFromDiv
            } else {
                infoDoc.selectFirst("meta[property=og:description]")?.attr("content")?.trim()
            }

            val infoSection = infoDoc.selectFirst("div.Info") ?: infoDoc
            val genres = infoSection.select("li:has(strong:containsOwn(Thể loại)) a[href*=the-loai], div.mvici-left li.AAIco-adjust:contains(Thể loại) a")
                .mapNotNull { it.text()?.trim() }.distinct()
            val yearText = infoSection.select("li:has(strong:containsOwn(Năm))")?.firstOrNull()?.ownText()?.trim()
                ?: infoDoc.selectFirst("p.Info span.Date a")?.text()?.trim()
            val year = yearText?.filter { it.isDigit() }?.toIntOrNull()

            val ratingTextRaw = infoSection.select("li:has(strong:containsOwn(Điểm))")?.firstOrNull()?.ownText()?.trim()?.substringBefore("/")
                 ?: infoDoc.selectFirst("div#star[data-score]")?.attr("data-score")?.trim()
                 ?: infoDoc.selectFirst("input#score_current[value]")?.attr("value")?.trim()
                 ?: infoDoc.selectFirst("div.VotesCn div.post-ratings strong#average_score")?.text()?.trim()
            var ratingValue: Int? = null
            if (ratingTextRaw != null) {
                val normalizedRatingText = ratingTextRaw.replace(",", ".")
                val ratingDouble = normalizedRatingText.toDoubleOrNull()
                if (ratingDouble != null) {
                    ratingValue = (ratingDouble * 1000).roundToInt().coerceIn(0, 10000)
                }
                 Log.d("AnimeVietsubProvider", "Rating for '$title': Value=$ratingValue (Raw='$ratingTextRaw', Double=$ratingDouble)")
            } else { Log.w("AnimeVietsubProvider", "Rating: Raw text was null for '$title'.") }

            val statusTextOriginal = infoSection.select("li:has(strong:containsOwn(Trạng thái))")?.firstOrNull()?.ownText()?.trim()
                ?: infoDoc.select("div.mvici-left li.AAIco-adjust:contains(Trạng thái)")
                    .firstOrNull()?.textNodes()?.lastOrNull()?.text()?.trim()?.replace("Trạng thái:", "")?.trim()
            
            val parsedEpisodes = if (watchPageDoc != null) { /* ... (giữ nguyên) ... */ } else { emptyList<Episode>() }
            val episodesCount = parsedEpisodes.size
            val firstEpisodeOrNull = parsedEpisodes.firstOrNull()
            val currentShowStatus = when { /* ... (giữ nguyên) ... */ }
            val actorsDataList = infoDoc.select("div#MvTb-Cast ul.ListCast li a").mapNotNull { /* ... (giữ nguyên) ... */ }
            val recommendations = mutableListOf<SearchResponse>() // ... (giữ nguyên logic parse recommendations)

            // --- LOGIC XÁC ĐỊNH TVTYPE ---
            var finalTvType: TvType? = null
            val country = infoDoc.getCountry()?.lowercase()
            val hasAnimeLeTag = genres.any { it.equals("Anime lẻ", ignoreCase = true) } || statusTextOriginal?.contains("Anime lẻ", ignoreCase = true) == true
            val firstEpisodeNameLower = firstEpisodeOrNull?.name?.lowercase()
            val isSingleEpisodeActuallyMovie = episodesCount == 1 &&
                                             (firstEpisodeNameLower == "tập 1" || firstEpisodeNameLower == "tập full" || firstEpisodeNameLower == "full" || firstEpisodeNameLower?.matches(Regex("""^(tập\s*)?0*1$""")) == true ||
                                              (firstEpisodeNameLower?.matches(Regex("""^(tập\s*)?\d+$""")) == true && firstEpisodeNameLower.filter { it.isDigit() }.toIntOrNull() == 1))
            val isMovieHintFromTitle = title.contains("Movie", ignoreCase = true) || title.contains("Phim Lẻ", ignoreCase = true)
            val isJapaneseContext = country == "nhật bản" || country == "japan" ||
                                   (country == null && (title.contains("Anime", ignoreCase = true) || genres.any{ it.contains("Anime", ignoreCase = true) && !it.contains("Trung Quốc", ignoreCase = true)} ))

            if (title.contains("OVA", ignoreCase = true) || title.contains("ONA", ignoreCase = true) || (hasAnimeLeTag && episodesCount > 1 && isJapaneseContext) ) {
                finalTvType = TvType.Anime
            }
            else if (isMovieHintFromTitle || (hasAnimeLeTag && episodesCount <= 1) || isSingleEpisodeActuallyMovie) {
                finalTvType = if (isJapaneseContext) { TvType.Anime }
                              else if (country == "trung quốc" || country == "china") { TvType.Movie }
                              else { TvType.Movie }
            }
            else { // Series
                finalTvType = when {
                    isJapaneseContext -> TvType.Anime
                    country == "trung quốc" || country == "china" -> TvType.Cartoon
                    genres.any {g -> g.contains("Hoạt Hình", ignoreCase = true) || g.contains("Animation", ignoreCase = true)} -> TvType.Cartoon
                    else -> TvType.TvSeries
                }
            }
            if (finalTvType == null) { // Fallback
                finalTvType = if (episodesCount > 1 || statusTextOriginal?.contains("Anime bộ", ignoreCase = true) == true) {
                    when { isJapaneseContext -> TvType.Anime; country == "trung quốc" || country == "china" -> TvType.Cartoon; else -> TvType.TvSeries }
                } else {
                     when { isJapaneseContext && (isMovieHintFromTitle || isSingleEpisodeActuallyMovie || hasAnimeLeTag) -> TvType.Anime; isJapaneseContext -> TvType.Anime; else -> TvType.Movie }
                }
            }
            Log.i("AnimeVietsubProvider", "Final TvType for '$title' ($infoUrl): $finalTvType, ShowStatus: $currentShowStatus")

            val isSeriesStructure = episodesCount > 1 || 
                                    finalTvType == TvType.TvSeries || 
                                    finalTvType == TvType.Cartoon ||
                                    (finalTvType == TvType.Anime && episodesCount > 1)

            return if (isSeriesStructure) {
                provider.newTvSeriesLoadResponse(title, infoUrl, finalTvType ?: TvType.Anime, episodes = parsedEpisodes) {
                    this.posterUrl = posterUrlForResponse; this.plot = description; this.tags = genres; this.year = year; this.rating = ratingValue; this.showStatus = currentShowStatus;
                    this.actors = actorsDataList; this.recommendations = recommendations
                }
            } else {
                val actualMovieTvType = finalTvType ?: if(isJapaneseContext && (isMovieHintFromTitle || isSingleEpisodeActuallyMovie || hasAnimeLeTag)) TvType.Anime else TvType.Movie
                val durationText = infoSection.select("li:has(strong:containsOwn(Thời lượng))")?.firstOrNull()?.ownText()?.trim()
                    ?: infoDoc.select("ul.InfoList li.AAIco-adjust:contains(Thời lượng)")
                        .firstOrNull()?.ownText()?.trim()
                val durationMinutes = durationText?.filter { it.isDigit() }?.toIntOrNull()
                val movieDataForLoadLinks = if (parsedEpisodes.isNotEmpty()) { /* ... (giữ nguyên) ... */ } else { /* ... (giữ nguyên) ... */ }

                provider.newMovieLoadResponse(title, infoUrl, actualMovieTvType, movieDataForLoadLinks) {
                    this.posterUrl = posterUrlForResponse; this.plot = description; this.tags = genres; this.year = year; this.rating = ratingValue; durationMinutes?.let { addDuration(it.toString()) };
                    this.actors = actorsDataList; this.recommendations = recommendations
                }
            }
        } catch (e: Exception) {
            Log.e("AnimeVietsubProvider", "Lỗi trong toLoadResponse xử lý cho url: $infoUrl", e); return null
        }
    }

    private data class AjaxPlayerResponse(
        @JsonProperty("success") val success: Int? = null,
        @JsonProperty("link") val link: List<LinkSource>? = null
    )
    private data class LinkSource(
        @JsonProperty("file") val file: String? = null,
        @JsonProperty("type") val type: String? = null,
        @JsonProperty("label") val label: String? = null
    )

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        // ... (giữ nguyên) ...
        var foundLinks = false
        val baseUrl = getBaseUrl()
        val ajaxUrl = "$baseUrl/ajax/player?v=2019a"
        val decryptApiUrl = "https://m3u8.013666.xyz/animevietsub/decrypt"
        val textPlainMediaType = "text/plain".toMediaTypeOrNull()
        val episodeData = try { gson.fromJson(data, EpisodeData::class.java) } catch (e: Exception) {
            Log.e("AnimeVietsubProvider", "Không thể parse EpisodeData JSON: '$data'", e); return false
        }
        val episodePageUrl = episodeData.url
        val episodeId = episodeData.dataId
        val episodeHash = episodeData.duHash
        if (episodeId == null || episodePageUrl.isBlank()) {
            Log.e("AnimeVietsubProvider", "Thiếu ID tập phim (dataId) hoặc URL trang: $data"); return false
        }
        try {
            val postData = mutableMapOf("id" to episodeId, "play" to "api")
            if (!episodeHash.isNullOrBlank()) { postData["link"] = episodeHash }
            val headers = mapOf(
                "Content-Type" to "application/x-www-form-urlencoded; charset=UTF-8",
                "Accept" to "application/json, text/javascript, */*; q=0.01",
                "X-Requested-With" to "XMLHttpRequest", "User-Agent" to USER_AGENT, "Referer" to episodePageUrl
            )
            val ajaxResponse = app.post(ajaxUrl, data = postData, headers = headers, referer = episodePageUrl)
            val playerResponse = try { gson.fromJson(ajaxResponse.text, AjaxPlayerResponse::class.java) } catch (e: Exception) {
                Log.e("AnimeVietsubProvider", "Lỗi parse JSON từ ajax/player: ${ajaxResponse.text}", e); null
            }
            if (playerResponse?.success != 1 || playerResponse.link.isNullOrEmpty()) {
                Log.e("AnimeVietsubProvider", "Request ajax/player thất bại: ${ajaxResponse.text}"); return false
            }
            playerResponse.link.forEach { linkSource ->
                val dataEncOrDirectLink = linkSource.file
                if (dataEncOrDirectLink.isNullOrBlank()) { return@forEach }
                val finalStreamUrl: String = if (dataEncOrDirectLink.startsWith("http") && (dataEncOrDirectLink.contains(".m3u8") || dataEncOrDirectLink.contains(".mp4"))) {
                    dataEncOrDirectLink
                } else {
                    val decryptHeaders = mapOf("User-Agent" to USER_AGENT, "Referer" to episodePageUrl)
                    val decryptResponse = app.post(decryptApiUrl, headers = decryptHeaders, requestBody = dataEncOrDirectLink.toRequestBody(textPlainMediaType))
                    decryptResponse.text.trim()
                }
                if (finalStreamUrl.startsWith("http") && (finalStreamUrl.endsWith(".m3u8") || finalStreamUrl.contains(".mp4")) ) {
                    val streamType = if (finalStreamUrl.endsWith(".m3u8")) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                    val streamHeaders = mapOf("User-Agent" to USER_AGENT, "Origin" to baseUrl, "Referer" to episodePageUrl)
                    val extractorLink = newExtractorLink(
                        source = this.name,
                        name = "AnimeVietsub API" + (linkSource.label?.let { " - $it" } ?: ""),
                        url = finalStreamUrl,
                        type = streamType
                    ) {
                        this.referer = episodePageUrl
                        this.quality = Qualities.Unknown.value
                        this.headers = streamHeaders
                    }
                    callback(extractorLink)
                    foundLinks = true
                } else {
                    Log.e("AnimeVietsubProvider", "API giải mã không trả về URL M3U8/MP4 hợp lệ. Response: '$finalStreamUrl'")
                }
            }
        } catch (e: Exception) {
            Log.e("AnimeVietsubProvider", "Lỗi trích xuất link API cho ID $episodeId", e)
        }
        return foundLinks
    }

    private fun String?.encodeUri(): String {
        if (this == null) return ""
        return try { URLEncoder.encode(this, "UTF-8").replace("+", "%20") }
        catch (e: Exception) { Log.e("AnimeVietsubProvider", "Lỗi URL encode: $this", e); this }
    }

    private fun Double?.toAnimeVietsubRatingInt(): Int? = this?.takeIf { !it.isNaN() }?.let { (it * 1000).roundToInt().coerceIn(0, 10000) }

    private fun fixUrl(url: String?, baseUrl: String): String? {
        if (url.isNullOrBlank()) return null
        return try {
            when {
                url.startsWith("http") -> url
                url.startsWith("//") -> "https:$url"
                url.startsWith("/") -> URL(URL(baseUrl), url).toString()
                else -> URL(URL(baseUrl), "/$url".removePrefix("//")).toString()
            }
        } catch (e: java.net.MalformedURLException) {
            Log.e("AnimeVietsubProvider", "URL không hợp lệ khi fix: base='$baseUrl', url='$url'", e)
            if (url.startsWith("http")) return url; null
        } catch (e: Exception) {
            Log.e("AnimeVietsubProvider", "Lỗi không xác định khi fix URL: base='$baseUrl', url='$url'", e); null
        }
    }
}
