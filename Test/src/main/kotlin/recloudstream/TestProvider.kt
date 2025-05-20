// === File: AnimeVietsubProvider.kt ===
// Version: 2025-05-21 - Tinh chỉnh TvType (AnimeMovie, OVA), giữ nguyên logic rating (Int)
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
        TvType.Anime, TvType.AnimeMovie, TvType.OVA,
        TvType.Cartoon, TvType.Movie
    )
    override var lang = "vi"
    override val hasMainPage = true
    private val USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/100.0.4896.127 Safari/537.36"

    private val bitlyResolverUrl = "https://bit.ly/animevietsubtv"
    private val ultimateFallbackDomain = "https://animevietsub.lol"
    private var currentActiveUrl = bitlyResolverUrl
    private var domainResolutionAttempted = false

    private suspend fun getBaseUrl(): String {
        // ... (giữ nguyên logic getBaseUrl)
        if (domainResolutionAttempted && !currentActiveUrl.contains("bit.ly")) {
            return currentActiveUrl
        }
        Log.d("AnimeVietsubProvider", "Cần phân giải domain. URL hiện tại để thử: $currentActiveUrl (hoặc bitly nếu current là bitly)")
        var resolvedDomain: String? = null
        val urlToAttemptResolution = if (currentActiveUrl.contains("bit.ly") || !domainResolutionAttempted) bitlyResolverUrl else currentActiveUrl
        try {
            Log.d("AnimeVietsubProvider", "Đang thử phân giải từ: $urlToAttemptResolution")
            val response = app.get(urlToAttemptResolution, allowRedirects = true, timeout = 15_000)
            val finalUrlString = response.url
            if (finalUrlString.startsWith("http") && !finalUrlString.contains("bit.ly")) {
                val urlObject = URL(finalUrlString)
                resolvedDomain = "${urlObject.protocol}://${urlObject.host}"
                Log.i("AnimeVietsubProvider", "Phân giải thành công '$urlToAttemptResolution' -> Domain thực: $resolvedDomain")
            } else {
                Log.w("AnimeVietsubProvider", "Không thể phân giải '$urlToAttemptResolution' thành domain hợp lệ. URL cuối cùng: $finalUrlString")
            }
        } catch (e: Exception) {
            Log.e("AnimeVietsubProvider", "Lỗi khi phân giải '$urlToAttemptResolution': ${e.message}")
        }
        domainResolutionAttempted = true
        if (resolvedDomain != null) {
            currentActiveUrl = resolvedDomain
            this.mainUrl = resolvedDomain
            Log.i("AnimeVietsubProvider", "Domain hoạt động được cập nhật thành: $currentActiveUrl")
        } else {
            Log.w("AnimeVietsubProvider", "Phân giải thất bại. Sử dụng fallback cuối cùng: $ultimateFallbackDomain (nếu currentActiveUrl vẫn là Bitly hoặc lần đầu thất bại)")
            if (currentActiveUrl.contains("bit.ly") || urlToAttemptResolution != ultimateFallbackDomain) {
                 currentActiveUrl = ultimateFallbackDomain
                 this.mainUrl = ultimateFallbackDomain
            } else {
                Log.e("AnimeVietsubProvider", "Cả Bitly và fallback $ultimateFallbackDomain đều có vẻ không hoạt động. Giữ $currentActiveUrl")
            }
        }
        Log.d("AnimeVietsubProvider", "getBaseUrl trả về: $currentActiveUrl")
        return currentActiveUrl
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        // ... (giữ nguyên)
        if (page > 1) return newHomePageResponse(emptyList(), false)
        val lists = mutableListOf<HomePageList>()
        try {
            val baseUrl = getBaseUrl()
            Log.d("AnimeVietsubProvider", "Đang tải trang chủ từ $baseUrl")
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
            if (lists.isEmpty()) {
                Log.w("AnimeVietsubProvider", "Không tìm thấy danh sách nào trên trang chủ, kiểm tra selector hoặc website.")
                throw ErrorLoadingException("Không thể tải dữ liệu trang chủ. Selector có thể đã thay đổi.")
            }
            return newHomePageResponse(lists, hasNext = false)
        } catch (e: Exception) {
            Log.e("AnimeVietsubProvider", "Lỗi trong getMainPage", e)
            if (e is ErrorLoadingException) throw e
            throw RuntimeException("Lỗi không xác định khi tải trang chủ: ${e.message}")
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        // ... (giữ nguyên)
        try {
            val baseUrl = getBaseUrl()
            val searchUrl = "$baseUrl/tim-kiem/${query.encodeUri()}/"
            Log.d("AnimeVietsubProvider", "Đang tìm kiếm '$query' bằng URL: $searchUrl")
            val document = app.get(searchUrl).document
            return document.selectFirst("ul.MovieList.Rows")?.select("li.TPostMv")
                ?.mapNotNull { it.toSearchResponse(this, baseUrl) } ?: emptyList()
        } catch (e: Exception) {
            Log.e("AnimeVietsubProvider", "Lỗi trong search cho query: $query", e)
            return emptyList()
        }
    }

    override suspend fun load(url: String): LoadResponse? {
        // ... (giữ nguyên)
        val baseUrl = getBaseUrl()
        val infoUrl = url
        val watchPageUrl = if (infoUrl.endsWith("/")) "${infoUrl}xem-phim.html" else "$infoUrl/xem-phim.html"
        Log.d("AnimeVietsubProvider", "Đang tải chi tiết. Info URL: $infoUrl, Watch Page URL: $watchPageUrl")
        try {
            val infoDocument = app.get(infoUrl, headers = mapOf("Referer" to baseUrl)).document
            Log.d("AnimeVietsubProvider", "Đã tải thành công trang thông tin: $infoUrl")
            var watchPageDocument: Document? = null
            try {
                watchPageDocument = app.get(watchPageUrl, referer = infoUrl).document
                Log.d("AnimeVietsubProvider", "Đã tải thành công trang xem phim: $watchPageUrl")
            } catch (e: Exception) {
                Log.w("AnimeVietsubProvider", "Không thể tải trang xem phim ($watchPageUrl). Lỗi: ${e.message}")
            }
            return infoDocument.toLoadResponse(this, infoUrl, baseUrl, watchPageDocument)
        } catch (e: Exception) {
            Log.e("AnimeVietsubProvider", "LỖI NGHIÊM TRỌNG khi tải trang thông tin chính ($infoUrl)", e)
            return null
        }
    }

    private fun Element.toSearchResponse(provider: MainAPI, baseUrl: String): SearchResponse? {
        // ... (giữ nguyên logic toSearchResponse như phiên bản trước, vì ít thông tin hơn để phân loại chi tiết)
        return try {
            val linkElement = this.selectFirst("article.TPost > a") ?: return null
            val href = fixUrl(linkElement.attr("href"), baseUrl) ?: return null
            val titleFromElement = linkElement.selectFirst("h2.Title")?.text()?.trim()?.takeIf { it.isNotBlank() } ?: return null
            val posterUrlRaw = linkElement.selectFirst("div.Image img")?.let { img ->
                img.attr("data-src").ifBlank { img.attr("src") }
            }
            val posterUrl = fixUrl(posterUrlRaw, baseUrl)
            var finalTvType: TvType? = null

            if (titleFromElement.contains("OVA", ignoreCase = true) || titleFromElement.contains("ONA", ignoreCase = true) ) {
                finalTvType = TvType.OVA
            }

            if (finalTvType == null) {
                val hasEpisodeSpan = this.selectFirst("span.mli-eps") != null
                val statusSpanText = this.selectFirst("span.mli-st")?.text() ?: ""
                if (titleFromElement.contains("Movie", ignoreCase = true) ||
                    titleFromElement.contains("Phim Lẻ", ignoreCase = true) ||
                    (!hasEpisodeSpan && statusSpanText.contains("Full", ignoreCase = true)) ||
                    (!hasEpisodeSpan && statusSpanText.contains("Hoàn Tất", ignoreCase = true) && !hasEpisodeSpan)
                ) {
                    finalTvType = if (provider.name.contains("Anime", ignoreCase = true) ||
                                     titleFromElement.contains("Anime", ignoreCase = true) &&
                                     !titleFromElement.contains("Trung Quốc", ignoreCase = true) && 
                                     !titleFromElement.contains("Donghua", ignoreCase = true) ) {
                        TvType.AnimeMovie
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
            }
            if (finalTvType == null) {
                val isTvSeriesFallback = this.selectFirst("span.mli-eps") != null || this.selectFirst("span.mli-quality") == null
                finalTvType = if (isTvSeriesFallback) TvType.TvSeries else TvType.Movie
            }
            provider.newMovieSearchResponse(titleFromElement, href, finalTvType ?: TvType.TvSeries) {
                this.posterUrl = posterUrl
            }
        } catch (e: Exception) {
            Log.e("AnimeVietsubProvider", "Lỗi parse item search result: ${this.html()}", e)
            null
        }
    }

    data class EpisodeData(
        val url: String,
        val dataId: String?,
        val duHash: String?
    )

    private fun Document.getCountry(): String? {
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
                ?: run { Log.e("AnimeVietsubProvider", "Không tìm thấy tiêu đề trên trang info $infoUrl"); return null }
            Log.d("AnimeVietsubProvider", "Processing LoadResponse for: '$title'")

            var posterUrl = infoDoc.selectFirst("div.TPost.Single div.Image img")?.attr("src")
                ?: infoDoc.selectFirst("meta[property=og:image]")?.attr("content")
            posterUrl = fixUrl(posterUrl, baseUrl)
            val description = infoDoc.selectFirst("div.TPost.Single div.Description")?.text()?.trim()
                ?: infoDoc.selectFirst("meta[property=og:description]")?.attr("content")
            val infoSection = infoDoc.selectFirst("div.Info") ?: infoDoc
            val genres = infoSection.select("li:has(strong:containsOwn(Thể loại)) a[href*=the-loai], div.mvici-left li.AAIco-adjust:contains(Thể loại) a")
                .mapNotNull { it.text()?.trim() }.distinct()
            val yearText = infoSection.select("li:has(strong:containsOwn(Năm))")?.firstOrNull()?.ownText()?.trim()
                ?: infoDoc.selectFirst("p.Info span.Date a")?.text()?.trim()
            val year = yearText?.filter { it.isDigit() }?.toIntOrNull()

            // Giữ nguyên logic parse rating, đã có log chi tiết
            val ratingTextRaw = infoSection.select("li:has(strong:containsOwn(Điểm))")?.firstOrNull()?.ownText()?.trim()?.substringBefore("/")
                 ?: infoDoc.selectFirst("div.VotesCn div.post-ratings #average_score")?.text()?.trim()
            Log.d("AnimeVietsubProvider", "Rating: 1. Raw text: '$ratingTextRaw'")
            var rating: Int? = null
            if (ratingTextRaw != null) {
                val normalizedRatingText = ratingTextRaw.replace(",", ".")
                Log.d("AnimeVietsubProvider", "Rating: 2. Normalized text: '$normalizedRatingText'")
                val ratingDouble = normalizedRatingText.toDoubleOrNull()
                Log.d("AnimeVietsubProvider", "Rating: 3. Parsed double: $ratingDouble")
                if (ratingDouble != null) {
                    rating = (ratingDouble * 100).roundToInt().coerceIn(0, 1000)
                    Log.d("AnimeVietsubProvider", "Rating: 4. Final Int (0-1000): $rating")
                } else { Log.w("AnimeVietsubProvider", "Rating: Failed to parse '$normalizedRatingText' to double.")}
            } else { Log.w("AnimeVietsubProvider", "Rating: Raw text was null.") }

            val statusTextOriginal = infoSection.select("li:has(strong:containsOwn(Trạng thái))")?.firstOrNull()?.ownText()?.trim()
                ?: infoDoc.select("div.mvici-left li.AAIco-adjust:contains(Trạng thái)")
                    .firstOrNull()?.textNodes()?.lastOrNull()?.text()?.trim()?.replace("Trạng thái:", "")?.trim()
            Log.d("AnimeVietsubProvider", "Status text original for '$title': $statusTextOriginal")
            
            val episodes = if (watchPageDoc != null) { /* ... (giữ nguyên logic parse episodes) ... */
                watchPageDoc.select("div.server ul.list-episode li a.btn-episode").mapNotNull { epLink ->
                    val epUrl = fixUrl(epLink.attr("href"), baseUrl)
                    val epNameFull = epLink.attr("title").ifBlank { epLink.text() }.trim()
                    val dataId = epLink.attr("data-id").ifBlank { null }
                    val duHash = epLink.attr("data-hash").ifBlank { null }
                    val episodeInfoForLoadLinks = EpisodeData(url = epUrl ?: infoUrl, dataId = dataId, duHash = duHash)
                    val episodeNumberString = epNameFull.replace(Regex("""[^\d]"""), "")
                    val episodeNumber = episodeNumberString.toIntOrNull()
                    val cleanEpName = epNameFull.replace(Regex("""^(Tập\s*\d+\s*-\s*|\d+\s*-\s*|\s*Tập\s*\d+\s*|\s*\d+\s*)""", RegexOption.IGNORE_CASE), "").trim()
                    var displayEpNumPart = episodeNumber?.toString()?.padStart(2,'0')
                    if (displayEpNumPart.isNullOrEmpty()) { displayEpNumPart = episodeNumberString.padStart(2,'0') }
                    if (displayEpNumPart.isEmpty() && epNameFull.equals("Full", ignoreCase = true)) {
                        displayEpNumPart = "Full"
                    } else if (displayEpNumPart.isEmpty()) {
                        displayEpNumPart = epNameFull.filter { it.isLetterOrDigit() || it.isWhitespace() }.trim().ifEmpty { epNameFull }
                    }
                    val finalEpNameBase = if (displayEpNumPart.equals("Full", ignoreCase = true)) "Tập Full" else "Tập $displayEpNumPart"
                    if (dataId != null && !epNameFull.isNullOrBlank() && epUrl != null) {
                        newEpisode(data = gson.toJson(episodeInfoForLoadLinks)) {
                            this.name = if (cleanEpName.isNotBlank() && cleanEpName.lowercase() != "full" && cleanEpName.lowercase() != displayEpNumPart.lowercase() && !finalEpNameBase.contains(cleanEpName, ignoreCase = true) && epNameFull.lowercase() != "full") {
                                "$finalEpNameBase: $cleanEpName"
                            } else { finalEpNameBase }
                            this.episode = episodeNumber
                        }
                    } else { null }
                }.sortedBy { it.episode ?: Int.MAX_VALUE }
            } else { emptyList<Episode>() }

            val showStatus = when { /* ... (giữ nguyên logic showStatus) ... */
                statusTextOriginal?.contains("Đang chiếu", ignoreCase = true) == true ||
                statusTextOriginal?.contains("Đang tiến hành", ignoreCase = true) == true ||
                (statusTextOriginal?.matches(Regex("""Tập\s*\d+\s*/\s*\?""")) == true && episodes.isNotEmpty())
                -> ShowStatus.Ongoing
                statusTextOriginal?.contains("Hoàn thành", ignoreCase = true) == true ||
                (statusTextOriginal?.matches(Regex("""Tập\s*\d+\s*/\s*\d+""")) == true &&
                 statusTextOriginal.split("/").let { parts ->
                     if (parts.size == 2) {
                         val currentEp = parts[0].filter(Char::isDigit).toIntOrNull()
                         val totalEp = parts[1].filter(Char::isDigit).toIntOrNull()
                         currentEp != null && totalEp != null && currentEp == totalEp
                     } else false
                 } && episodes.isNotEmpty()
                ) || (statusTextOriginal?.contains("Full", ignoreCase = true) == true && episodes.size == 1 && !title.contains("OVA", ignoreCase = true) && !title.contains("ONA", ignoreCase = true) )
                -> ShowStatus.Completed
                else -> if (episodes.isNotEmpty() && episodes.any { it.episode != null }) null else ShowStatus.Completed
            }
            
            val actors = infoDoc.select("div#MvTb-Cast ul.ListCast li a").mapNotNull { actorLinkElement ->
                val name = actorLinkElement.attr("title").removePrefix("Nhân vật ").trim()
                if (name.isNotBlank()) Actor(name) else null
            }
            val recommendations = mutableListOf<SearchResponse>() // ... (giữ nguyên)

            // --- LOGIC XÁC ĐỊNH TVTYPE MỚI ---
            var finalTvType: TvType? = null
            val country = infoDoc.getCountry()?.lowercase()
            Log.d("AnimeVietsubProvider", "TvType Detection for '$title': Country='$country', Episodes=${episodes.size}, StatusText='$statusTextOriginal', Genres='$genres'")

            val hasAnimeLeTag = genres.any { it.equals("Anime lẻ", ignoreCase = true) } || statusTextOriginal?.contains("Anime lẻ", ignoreCase = true) == true
            val isSingleFullEpisode = episodes.size == 1 && episodes.firstOrNull()?.name?.equals("Tập Full", ignoreCase = true) == true
            val isMovieHintFromTitle = title.contains("Movie", ignoreCase = true) || title.contains("Phim Lẻ", ignoreCase = true)
            val isJapaneseContext = country == "nhật bản" || country == "japan" ||
                                   (country == null && (title.contains("Anime", ignoreCase = true) || genres.any { it.contains("Anime", ignoreCase = true) && !it.contains("Trung Quốc", ignoreCase = true) }))


            // 1. Ưu tiên tuyệt đối cho OVA nếu title chứa "OVA" hoặc "ONA"
            if (title.contains("OVA", ignoreCase = true) || title.contains("ONA", ignoreCase = true)) {
                finalTvType = TvType.OVA
                Log.i("AnimeVietsubProvider", "TvType DEFINITIVELY SET to OVA for '$title' due to title keyword.")
            }
            // 2. Xử lý trường hợp "Anime lẻ" VÀ nhiều tập VÀ là của Nhật -> OVA
            //    (Điều kiện này được ưu tiên hơn isSingleFullEpisode và isLikelyMovieOverall cho trường hợp OVA đặc biệt)
            else if (hasAnimeLeTag && episodes.size > 1 && isJapaneseContext) {
                finalTvType = TvType.OVA
                Log.i("AnimeVietsubProvider", "TvType SET to OVA for '$title' due to 'Anime lẻ' tag, multiple episodes, and Japanese context.")
            }
            // 3. Xử lý trường hợp 1 tập tên "Full" (ưu tiên hơn "Anime lẻ" nếu cả hai cùng có) -> Movie/AnimeMovie
            else if (isSingleFullEpisode) {
                Log.d("AnimeVietsubProvider", "'$title' has a single 'Full' episode.")
                finalTvType = if (isJapaneseContext) TvType.AnimeMovie
                              else if (country == "trung quốc" || country == "china") TvType.Movie
                              else TvType.Movie // Mặc định là Movie nếu không rõ ràng Nhật/Trung
                Log.i("AnimeVietsubProvider", "TvType SET to $finalTvType for '$title' due to single 'Full' episode.")
            }
            // 4. Các trường hợp còn lại nếu chưa được xác định (bao gồm phim lẻ thông thường và series)
            else {
                // "Anime lẻ" với 1 tập hoặc 0 tập (và không phải "Full" đã xử lý ở trên) -> Movie/AnimeMovie
                val isLikelyRegularMovie = isMovieHintFromTitle || (hasAnimeLeTag && episodes.size <= 1)
                
                Log.d("AnimeVietsubProvider", "For '$title': isMovieHintFromTitle=$isMovieHintFromTitle, hasAnimeLeTag (for movie)=$hasAnimeLeTag, isLikelyRegularMovie=$isLikelyRegularMovie")

                if (isLikelyRegularMovie) {
                    Log.d("AnimeVietsubProvider", "'$title' considered generally movie-like.")
                    finalTvType = if (isJapaneseContext) TvType.AnimeMovie
                                  else if (country == "trung quốc" || country == "china") TvType.Movie
                                  else TvType.Movie // Mặc định Movie nếu không rõ
                } else { // Likely a series
                    Log.d("AnimeVietsubProvider", "'$title' considered generally series-like (episodes: ${episodes.size}).")
                    finalTvType = when {
                        country == "trung quốc" || country == "china" -> TvType.Cartoon
                        isJapaneseContext -> TvType.Anime
                        // Fallback cho series với quốc gia không rõ nhưng có context hoạt hình
                        genres.any {g -> g.contains("Hoạt Hình", ignoreCase = true) || g.contains("Animation", ignoreCase = true)} -> TvType.Cartoon
                        else -> TvType.TvSeries // Series chung nếu không có dấu hiệu hoạt hình rõ ràng
                    }
                }
            }

            // Fallback cuối cùng nếu tất cả logic trên vẫn chưa xác định được TvType
            if (finalTvType == null) {
                Log.w("AnimeVietsubProvider", "TvType for '$title' still undetermined, using final fallback logic based on episode count and country.")
                finalTvType = if (episodes.size > 1 || statusTextOriginal?.contains("Anime bộ", ignoreCase = true) == true) {
                    when {
                        country == "trung quốc" || country == "china" -> TvType.Cartoon
                        isJapaneseContext -> TvType.Anime
                        else -> TvType.TvSeries // An toàn nhất là TvSeries nếu không rõ
                    }
                } else { // Mặc định là movie/animemovie nếu ít hơn 2 tập hoặc không có gợi ý series
                     when {
                        isJapaneseContext -> TvType.AnimeMovie
                        else -> TvType.Movie
                    }
                }
            }
            Log.i("AnimeVietsubProvider", "Final TvType for '$title' ($infoUrl): $finalTvType, ShowStatus: $showStatus")

            // Tạo LoadResponse dựa trên finalTvType
            return if (setOf(TvType.Anime, TvType.TvSeries, TvType.Cartoon, TvType.OVA).contains(finalTvType)) {
                provider.newTvSeriesLoadResponse(title, infoUrl, finalTvType ?: TvType.TvSeries, episodes = episodes) {
                    this.posterUrl = posterUrl; this.plot = description; this.tags = genres; this.year = year; this.rating = rating; this.showStatus = showStatus;
                    addActors(actors); this.recommendations = recommendations
                }
            } else { // Movie, AnimeMovie
                val durationText = infoSection.select("li:has(strong:containsOwn(Thời lượng))")?.firstOrNull()?.ownText()?.trim()
                    ?: infoDoc.select("ul.InfoList li.AAIco-adjust:contains(Thời lượng)")
                        .firstOrNull()?.ownText()?.trim()
                val durationMinutes = durationText?.filter { it.isDigit() }?.toIntOrNull()
                val movieDataForLoadLinks = if (episodes.isNotEmpty()) {
                     val firstEpisodeDataString = episodes.first().data
                        try {
                            val parsedEpisodeData = gson.fromJson(firstEpisodeDataString, EpisodeData::class.java)
                            if (parsedEpisodeData.dataId != null) { 
                                firstEpisodeDataString
                            } else {
                                val idAttempt = infoDoc.selectFirst("a.watch_button_more[href*=xem-phim]")?.attr("href")?.substringAfterLast("a")?.substringBefore("/") ?: infoUrl.substringAfterLast("a").substringBefore("/")
                                gson.toJson(EpisodeData(url = infoUrl, dataId = idAttempt, duHash = null))
                            }
                        } catch (e: Exception) {
                            val idAttempt = infoDoc.selectFirst("a.watch_button_more[href*=xem-phim]")?.attr("href")?.substringAfterLast("a")?.substringBefore("/") ?: infoUrl.substringAfterLast("a").substringBefore("/")
                            gson.toJson(EpisodeData(url = infoUrl, dataId = idAttempt, duHash = null))
                        }
                } else {
                    val idAttempt = infoDoc.selectFirst("a.watch_button_more[href*=xem-phim]")?.attr("href")?.substringAfterLast("a")?.substringBefore("/") ?: infoUrl.substringAfterLast("a").substringBefore("/")
                    gson.toJson(EpisodeData(url = infoUrl, dataId = idAttempt, duHash = null))
                }
                provider.newMovieLoadResponse(title, infoUrl, finalTvType ?: TvType.Movie, movieDataForLoadLinks) {
                    this.posterUrl = posterUrl; this.plot = description; this.tags = genres; this.year = year; this.rating = rating; durationMinutes?.let { addDuration(it.toString()) };
                    addActors(actors); this.recommendations = recommendations
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
        // ... (giữ nguyên)
        var foundLinks = false
        val baseUrl = getBaseUrl()
        val ajaxUrl = "$baseUrl/ajax/player?v=2019a"
        val decryptApiUrl = "https://m3u8.013666.xyz/animevietsub/decrypt"
        val textPlainMediaType = "text/plain".toMediaTypeOrNull()
        Log.d("AnimeVietsubProvider", "LoadLinks nhận được data: $data")
        val episodeData = try { gson.fromJson(data, EpisodeData::class.java) } catch (e: Exception) {
            Log.e("AnimeVietsubProvider", "Không thể parse EpisodeData JSON: '$data'", e); return false
        }
        val episodePageUrl = episodeData.url
        val episodeId = episodeData.dataId
        val episodeHash = episodeData.duHash
        if (episodeId == null || episodePageUrl.isBlank()) {
            Log.e("AnimeVietsubProvider", "Thiếu ID tập phim (dataId) hoặc URL trang: $data"); return false
        }
        Log.i("AnimeVietsubProvider", "Đang xử lý ID tập phim: $episodeId cho URL: $episodePageUrl")
        try {
            val postData = mutableMapOf("id" to episodeId, "play" to "api")
            if (!episodeHash.isNullOrBlank()) { postData["link"] = episodeHash }
            val headers = mapOf(
                "Content-Type" to "application/x-www-form-urlencoded; charset=UTF-8",
                "Accept" to "application/json, text/javascript, */*; q=0.01",
                "X-Requested-With" to "XMLHttpRequest", "User-Agent" to USER_AGENT, "Referer" to episodePageUrl
            )
            val ajaxResponse = app.post(ajaxUrl, data = postData, headers = headers, referer = episodePageUrl)
            Log.d("AnimeVietsubProvider", "AJAX API Response Status: ${ajaxResponse.code}")
            val playerResponse = try { gson.fromJson(ajaxResponse.text, AjaxPlayerResponse::class.java) } catch (e: Exception) {
                Log.e("AnimeVietsubProvider", "Lỗi parse JSON từ ajax/player: ${ajaxResponse.text}", e); null
            }
            if (playerResponse?.success != 1 || playerResponse.link.isNullOrEmpty()) {
                Log.e("AnimeVietsubProvider", "Request ajax/player thất bại: ${ajaxResponse.text}"); return false
            }
            playerResponse.link.forEach { linkSource ->
                val dataEncOrDirectLink = linkSource.file
                if (dataEncOrDirectLink.isNullOrBlank()) { Log.w("AnimeVietsubProvider", "Bỏ qua link source vì 'file' rỗng."); return@forEach }
                val finalStreamUrl: String = if (dataEncOrDirectLink.startsWith("http") && (dataEncOrDirectLink.contains(".m3u8") || dataEncOrDirectLink.contains(".mp4"))) {
                    Log.i("AnimeVietsubProvider", "Lấy được link trực tiếp từ API: $dataEncOrDirectLink"); dataEncOrDirectLink
                } else {
                    Log.d("AnimeVietsubProvider", "Lấy được 'dataenc': ${dataEncOrDirectLink.take(50)}...")
                    val decryptHeaders = mapOf("User-Agent" to USER_AGENT, "Referer" to episodePageUrl)
                    val decryptResponse = app.post(decryptApiUrl, headers = decryptHeaders, requestBody = dataEncOrDirectLink.toRequestBody(textPlainMediaType))
                    Log.d("AnimeVietsubProvider", "API giải mã Response Status: ${decryptResponse.code}")
                    decryptResponse.text.trim()
                }
                if (finalStreamUrl.startsWith("http") && (finalStreamUrl.endsWith(".m3u8") || finalStreamUrl.contains(".mp4")) ) {
                    Log.i("AnimeVietsubProvider", "Link M3U8/MP4 cuối cùng: $finalStreamUrl")
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
        if (!foundLinks) { Log.w("AnimeVietsubProvider", "Không có link stream nào cho ID $episodeId ($episodePageUrl)") }
        return foundLinks
    }

    private fun String?.encodeUri(): String {
        if (this == null) return ""
        return try { URLEncoder.encode(this, "UTF-8").replace("+", "%20") }
        catch (e: Exception) { Log.e("AnimeVietsubProvider", "Lỗi URL encode: $this", e); this }
    }

    private fun Double?.toAnimeVietsubRatingInt(): Int? = this?.let { (it * 1000).roundToInt().coerceIn(0, 10000) }

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
