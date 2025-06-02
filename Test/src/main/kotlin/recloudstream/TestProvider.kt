package recloudstream

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
import java.net.URL // Đảm bảo import này có mặt
import kotlin.math.roundToInt
import kotlin.text.Regex // Đảm bảo import này có mặt

import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.MediaType.Companion.toMediaTypeOrNull

class AnimeVietsubProvider : MainAPI() {

    private val gson = Gson()
    override var mainUrl = "https://bit.ly/animevietsubtv" // mainUrl sẽ giữ giá trị này trừ khi được thay đổi ở nơi khác
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
    private var currentActiveUrl = bitlyResolverUrl // Biến này sẽ lưu trữ URL động đã được phân giải
    private var domainResolutionAttempted = false

    // Hàm getBaseUrl đã được chỉnh sửa
    private suspend fun getBaseUrl(): String {
        // Nếu đã thử phân giải và URL hiện tại không phải là bit.ly nữa, trả về luôn
        if (domainResolutionAttempted && !currentActiveUrl.contains("bit.ly")) {
            return currentActiveUrl
        }

        var resolvedDomain: String? = null
        // Quyết định URL nào sẽ được thử phân giải
        val urlToAttemptResolution = if (currentActiveUrl.contains("bit.ly") || !domainResolutionAttempted) {
            bitlyResolverUrl
        } else {
            currentActiveUrl
        }

        try {
            // Log.d("AnimeVietsubProvider", "Attempting to resolve domain for: $urlToAttemptResolution") // Optional: for debugging
            val response = app.get(urlToAttemptResolution, allowRedirects = true, timeout = 15_000)
            val finalUrlString = response.url // URL cuối cùng sau khi theo dõi chuyển hướng
            
            if (finalUrlString.startsWith("http") && !finalUrlString.contains("bit.ly")) {
                val urlObject = URL(finalUrlString)
                resolvedDomain = "${urlObject.protocol}://${urlObject.host}"
                // Log.i("AnimeVietsubProvider", "Successfully resolved domain: $resolvedDomain from $urlToAttemptResolution") // Optional: for debugging
            } else {
                Log.w("AnimeVietsubProvider", "Bitly resolution did not lead to a valid different domain. Final URL: $finalUrlString")
            }
        } catch (e: Exception) {
            Log.e("AnimeVietsubProvider", "Error resolving domain link '$urlToAttemptResolution': ${e.message}", e)
        }

        domainResolutionAttempted = true // Đánh dấu đã thử phân giải

        if (resolvedDomain != null) {
            // Nếu phân giải thành công
            if (currentActiveUrl != resolvedDomain) {
                Log.i("AnimeVietsubProvider", "Domain updated: $currentActiveUrl -> $resolvedDomain")
            }
            currentActiveUrl = resolvedDomain
            // KHÔNG CẬP NHẬT this.mainUrl ở đây nữa
            // this.mainUrl = resolvedDomain 
        } else {
            // Nếu phân giải thất bại
            // Chỉ sử dụng fallback nếu URL hiện tại là bit.ly hoặc chưa từng thử fallback trước đó (và currentActiveUrl chưa phải là fallback)
            if (currentActiveUrl.contains("bit.ly") || (urlToAttemptResolution != ultimateFallbackDomain && currentActiveUrl != ultimateFallbackDomain)) {
                Log.w("AnimeVietsubProvider", "Domain resolution failed for '$urlToAttemptResolution'. Using fallback: $ultimateFallbackDomain")
                currentActiveUrl = ultimateFallbackDomain
                // KHÔNG CẬP NHẬT this.mainUrl ở đây nữa
                // this.mainUrl = ultimateFallbackDomain
            } else {
                // Nếu đã thử fallback mà vẫn lỗi, hoặc currentActiveUrl không phải bit.ly và đã lỗi
                Log.e("AnimeVietsubProvider", "All domain resolution attempts failed. Sticking with last known: $currentActiveUrl")
            }
        }
        // Log.d("AnimeVietsubProvider", "getBaseUrl returning: $currentActiveUrl") // Optional: for debugging
        return currentActiveUrl // Trả về URL đã được phân giải (hoặc fallback)
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
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
            Log.e("AnimeVietsubProvider", "Error in getMainPage: ${e.message}", e)
            if (e is ErrorLoadingException) throw e
            throw RuntimeException("Unknown error loading main page: ${e.message}")
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        try {
            val baseUrl = getBaseUrl()
            val searchUrl = "$baseUrl/tim-kiem/${query.encodeUri()}/"
            val document = app.get(searchUrl).document
            return document.selectFirst("ul.MovieList.Rows")?.select("li.TPostMv")
                ?.mapNotNull { it.toSearchResponse(this, baseUrl) } ?: emptyList()
        } catch (e: Exception) {
            Log.e("AnimeVietsubProvider", "Error in search for query '$query': ${e.message}", e)
            return emptyList()
        }
    }

    override suspend fun load(url: String): LoadResponse? {
        val baseUrl = getBaseUrl() // Lấy baseUrl động
        val infoUrl = url // infoUrl là url được truyền vào, đã được fix từ search/mainpage
        val watchPageUrl = if (infoUrl.endsWith("/")) "${infoUrl}xem-phim.html" else "$infoUrl/xem-phim.html"
        try {
            val infoDocument = app.get(infoUrl, headers = mapOf("Referer" to baseUrl)).document // Referer là baseUrl động
            var watchPageDocument: Document? = null
            try {
                watchPageDocument = app.get(watchPageUrl, referer = infoUrl).document
            } catch (e: Exception) {
                Log.w("AnimeVietsubProvider", "Failed to load watch page ($watchPageUrl). Error: ${e.message}")
            }
            return infoDocument.toLoadResponse(this, infoUrl, baseUrl, watchPageDocument)
        } catch (e: Exception) {
            Log.e("AnimeVietsubProvider", "FATAL Error loading main info page ($infoUrl): ${e.message}", e)
            return null
        }
    }

    private fun Element.toSearchResponse(provider: MainAPI, baseUrl: String): SearchResponse? {
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
                                (titleFromElement.contains("Anime", ignoreCase = true) &&
                                !titleFromElement.contains("Trung Quốc", ignoreCase = true) && 
                                !titleFromElement.contains("Donghua", ignoreCase = true)) ) {
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
            // Fallback type determination if still null
            if (finalTvType == null) {
                // Heuristic: if it has episode span or no quality span (often for series), assume Anime/TV. Otherwise Movie.
                // This is a broad fallback, specific title/genre keywords are more reliable.
                finalTvType = if (hasEpisodeSpan || this.selectFirst("span.mli-quality") == null) TvType.Anime else TvType.Movie
            }
            provider.newMovieSearchResponse(titleFromElement, href, finalTvType ?: TvType.Anime) { // Default Anime nếu null
                this.posterUrl = posterUrl
            }
        } catch (e: Exception) {
            Log.e("AnimeVietsubProvider", "Error parsing search result item: ${this.html().take(100)}", e)
            null
        }
    }

    data class EpisodeData(
        val url: String, // URL của trang chứa tập phim hoặc trang thông tin chính nếu là movie
        val dataId: String?, // ID của tập phim/movie để gọi API player
        val duHash: String?  // Hash (nếu có) để gọi API player
    )

    private fun Document.getCountry(): String? {
        return this.selectFirst("div.mvici-left li.AAIco-adjust:contains(Quốc gia) a, ul.InfoList li:has(strong:containsOwn(Quốc gia)) a")
            ?.attr("title")?.trim()?.takeIf { it.isNotBlank() }
            ?: this.selectFirst("div.mvici-left li.AAIco-adjust:contains(Quốc gia) a, ul.InfoList li:has(strong:containsOwn(Quốc gia)) a")
                ?.text()?.trim()?.takeIf { it.isNotBlank() }
    }
    
    private suspend fun Document.toLoadResponse(
        provider: MainAPI,
        infoUrl: String, // URL của trang thông tin (đã được fix)
        baseUrl: String, // Base URL động đã được resolve
        watchPageDoc: Document? // Document của trang xem phim (nếu có)
    ): LoadResponse? {
        val infoDoc = this // Document của trang thông tin
        try {
            val title = infoDoc.selectFirst("div.TPost.Single div.Title")?.text()?.trim()
                ?: infoDoc.selectFirst("meta[property=og:title]")?.attr("content")?.substringBefore(" Tập")?.trim()
                ?: run { Log.e("AnimeVietsubProvider", "Could not find title on info page $infoUrl"); return null }

            // Logic lấy poster ưu tiên banner lớn, rồi poster trong figure, rồi ảnh từ meta tags
            var posterUrlForResponse: String? = null
            var rawPosterUrl: String?
            
            // 1. Ưu tiên meta tag og:image hoặc itemprop:image có chứa /data/big_banner/
            val metaBigBanner = infoDoc.select("meta[property=og:image], meta[itemprop=image]")
                .mapNotNull { it.attr("content").takeIf { c -> c.isNotBlank() && c.contains("/data/big_banner/", ignoreCase = true) } }
                .firstOrNull()
            if (!metaBigBanner.isNullOrBlank()) {
                posterUrlForResponse = fixUrl(metaBigBanner, baseUrl)
            }

            // 2. Nếu không có, thử lấy từ div.TPostBg img (thường là banner lớn)
            if (posterUrlForResponse.isNullOrBlank()) {
                rawPosterUrl = infoDoc.selectFirst("div.TPostBg.Objf img.TPostBg")?.attr("src")
                // Chỉ chấp nhận nếu nó là big_banner, tránh lấy ảnh nền nhỏ lặp lại
                if (!rawPosterUrl.isNullOrBlank() && rawPosterUrl.contains("/data/big_banner/", ignoreCase = true)) {
                    posterUrlForResponse = fixUrl(rawPosterUrl, baseUrl)
                } else if (!rawPosterUrl.isNullOrBlank()) {
                    // Log.d("AnimeVietsubProvider", "Found poster in TPostBg but not a big_banner: $rawPosterUrl") // Optional
                }
            }
            
            // 3. Nếu vẫn không có, thử lấy từ div.Image figure img (poster chính)
            if (posterUrlForResponse.isNullOrBlank()) {
                rawPosterUrl = infoDoc.selectFirst("div.TPost.Single div.Image figure.Objf img")?.attr("src")
                if (!rawPosterUrl.isNullOrBlank()) {
                    posterUrlForResponse = fixUrl(rawPosterUrl, baseUrl)
                }
            }
            // 4. Fallback cuối cho poster: div.Image img (ít cụ thể hơn)
             if (posterUrlForResponse.isNullOrBlank()) { 
                rawPosterUrl = infoDoc.selectFirst("div.TPost.Single div.Image img")?.attr("src")
                if (!rawPosterUrl.isNullOrBlank()) {
                    posterUrlForResponse = fixUrl(rawPosterUrl, baseUrl)
                }
            }

            // 5. Nếu vẫn rỗng, thử lại meta tags nhưng ưu tiên cái nào có /poster/, rồi đến cái nào không phải big_banner, cuối cùng là bất kỳ cái nào.
            if (posterUrlForResponse.isNullOrBlank()) {
                val metaImages = infoDoc.select("meta[property=og:image], meta[itemprop=image]")
                    .mapNotNull { it.attr("content").takeIf { c -> c.isNotBlank() } }
                    .distinct()

                if (metaImages.isNotEmpty()) {
                    // Ưu tiên URL chứa /poster/
                    rawPosterUrl = metaImages.firstOrNull { it.contains("/poster/", ignoreCase = true) }
                    if (!rawPosterUrl.isNullOrBlank()) {
                        posterUrlForResponse = fixUrl(rawPosterUrl, baseUrl)
                    } else {
                        // Nếu không, lấy URL đầu tiên không phải là big_banner (để tránh lặp lại nếu đã thử ở trên)
                        // Hoặc lấy cái đầu tiên nếu chỉ có 1 ảnh meta
                        rawPosterUrl = metaImages.firstOrNull { !it.contains("/data/big_banner/", ignoreCase = true) } ?: metaImages.firstOrNull()
                        if (!rawPosterUrl.isNullOrBlank()) {
                            posterUrlForResponse = fixUrl(rawPosterUrl, baseUrl)
                        }
                    }
                }
            }
            
            // Log nếu không tìm thấy poster
            if (posterUrlForResponse.isNullOrBlank()){
                // Log.w("AnimeVietsubProvider", "Poster not found for '$title' on $infoUrl") // Optional
            } else {
                // Log.d("AnimeVietsubProvider", "Using poster: $posterUrlForResponse for '$title'") // Optional
            }

            val descriptionFromDiv = infoDoc.selectFirst("article.TPost.Single div.Description")?.text()?.trim()
            val description = if (!descriptionFromDiv.isNullOrBlank()) descriptionFromDiv 
                                else infoDoc.selectFirst("meta[property=og:description]")?.attr("content")?.trim()

            val infoSection = infoDoc.selectFirst("div.Info") ?: infoDoc // Fallback to infoDoc if div.Info not found
            val genres = infoSection.select("li:has(strong:containsOwn(Thể loại)) a[href*=the-loai], div.mvici-left li.AAIco-adjust:contains(Thể loại) a")
                .mapNotNull { it.text()?.trim() }.distinct()
            val yearText = infoSection.select("li:has(strong:containsOwn(Năm))")?.firstOrNull()?.ownText()?.trim()
                ?: infoDoc.selectFirst("p.Info span.Date a")?.text()?.trim() // Thử selector khác cho năm
            val year = yearText?.filter { it.isDigit() }?.toIntOrNull()

            val ratingTextRaw = infoSection.select("li:has(strong:containsOwn(Điểm))")?.firstOrNull()?.ownText()?.trim()?.substringBefore("/")
                ?: infoDoc.selectFirst("div#star[data-score]")?.attr("data-score")?.trim() // Từ script rating
                ?: infoDoc.selectFirst("input#score_current[value]")?.attr("value")?.trim() // Từ input ẩn
                ?: infoDoc.selectFirst("div.VotesCn div.post-ratings strong#average_score")?.text()?.trim() // Từ schema/rating khác

            var ratingValue: Int? = null
            if (ratingTextRaw != null) {
                val normalizedRatingText = ratingTextRaw.replace(",", ".") // Chuẩn hóa dấu phẩy thành chấm
                val ratingDouble = normalizedRatingText.toDoubleOrNull()
                if (ratingDouble != null) {
                    // Chuyển đổi sang thang điểm 10000 (ví dụ: 8.5 -> 8500)
                    ratingValue = (ratingDouble * 1000).roundToInt().coerceIn(0, 10000)
                }
            }
            
            val statusTextOriginal = infoSection.select("li:has(strong:containsOwn(Trạng thái))")?.firstOrNull()?.ownText()?.trim()
                ?: infoDoc.select("div.mvici-left li.AAIco-adjust:contains(Trạng thái)")
                    .firstOrNull()?.textNodes()?.lastOrNull()?.text()?.trim()?.replace("Trạng thái:", "")?.trim()
            
            // =================== START OF MODIFIED EPISODE PARSING ===================
            val parsedEpisodes = if (watchPageDoc != null) {
                watchPageDoc.select("div.server ul.list-episode li a.btn-episode").mapNotNull { epLink ->
                    val epUrl = fixUrl(epLink.attr("href"), baseUrl) // Url của tập
                    val epNameFull = epLink.attr("title").ifBlank { epLink.text() }.trim() // Tên đầy đủ của tập, ví dụ: "Tập 9.5 - Phần Đặc Biệt"
                    val dataId = epLink.attr("data-id").ifBlank { null } // data-id quan trọng
                    val duHash = epLink.attr("data-hash").ifBlank { null } // data-hash nếu có

                    val episodeInfoForLoadLinks = EpisodeData(url = epUrl ?: infoUrl, dataId = dataId, duHash = duHash)

                    var episodeIntForSort: Int? = null // Số nguyên để sắp xếp (ví dụ: 9 từ "9.5")
                    var episodeStringForDisplay: String = epNameFull // Tên tập để hiển thị, mặc định là tên đầy đủ

                    // Regex để tìm số tập dạng "X.Y" hoặc "X", có thể có tiền tố "Tập "
                    // Ví dụ: "Tập 9.5", "9.5", "Tập 10", "10"
                    val numMatch = Regex("""(?:Tập\s+)?(\d+(?:\.\d+)?)""").find(epNameFull)

                    if (numMatch != null) {
                        val numberStr = numMatch.groupValues[1] // Trích xuất phần số, ví dụ: "9.5", "10"
                        episodeIntForSort = numberStr.toDoubleOrNull()?.toInt() // Chuyển thành Int để sắp xếp

                        // Xây dựng tên hiển thị cho tập
                        // Mục tiêu: "Tập 9.5" hoặc "Tập 9.5: Tên Tập Thực Tế"
                        
                        // Lấy phần tiêu đề riêng của tập (nếu có) bằng cách loại bỏ phần số và tiền tố
                        // Ví dụ: từ "Tập 9.5 - Đặc Biệt", lấy ra "Đặc Biệt"
                        val titlePart = epNameFull.replaceFirst(Regex("""^(?:.*?[\s-])?${Regex.escape(numberStr)}\s*(?:-\s*)?""", RegexOption.IGNORE_CASE), "").trim()
                        
                        var prefix = "Tập $numberStr" // Tiền tố mặc định, ví dụ "Tập 9.5"
                        
                        // Xử lý các trường hợp đặc biệt như OVA, Special
                        if (epNameFull.startsWith("OVA", ignoreCase = true) && episodeIntForSort != null) {
                            prefix = "OVA $numberStr"
                        } else if (epNameFull.startsWith("Special", ignoreCase = true) && episodeIntForSort != null) {
                            prefix = "Special $numberStr"
                        }
                        // Bạn có thể thêm các điều kiện else if khác cho các tiền tố đặc biệt (Movie, Phim lẻ, etc.) nếu cần

                        episodeStringForDisplay = if (titlePart.isNotEmpty() && titlePart.lowercase() != numberStr.lowercase()) {
                            "$prefix: $titlePart"
                        } else {
                            prefix
                        }
                    } else if (epNameFull.equals("Full", ignoreCase = true) || epNameFull.equals("Tập Full", ignoreCase = true)) {
                        // Xử lý trường hợp tên tập là "Full" hoặc "Tập Full"
                        episodeStringForDisplay = "Tập Full"
                        episodeIntForSort = 1 // Gán số 1 (hoặc một số quy ước) để sắp xếp nếu cần
                    } else {
                        // Nếu không tìm thấy mẫu số tập chuẩn (ví dụ: "Phần Đặc Biệt", "Movie")
                        // episodeIntForSort sẽ là null (hoặc bạn có thể thử trích xuất số từ đây nếu có quy ước)
                        // episodeStringForDisplay sẽ giữ nguyên là epNameFull
                        // Log.d("AnimeVietsubProvider", "Không parse được số tập chuẩn cho: $epNameFull") // Optional: for debugging
                    }

                    if (dataId != null && epUrl != null) { // Cần data-id và epUrl
                        newEpisode(data = gson.toJson(episodeInfoForLoadLinks)) {
                            this.name = episodeStringForDisplay // Tên hiển thị đã được xử lý
                            this.episode = episodeIntForSort    // Số nguyên để sắp xếp
                        }
                    } else {
                        Log.w("AnimeVietsubProvider", "Skipping episode due to missing data-id or epUrl: $epNameFull")
                        null
                    }
                }.sortedBy { it.episode ?: Int.MAX_VALUE } // Sắp xếp theo số tập
            } else {
                Log.w("AnimeVietsubProvider", "Watch page document is null for $infoUrl. No episodes parsed.")
                emptyList<Episode>()
            }
            // =================== END OF MODIFIED EPISODE PARSING ===================

            val episodesCount = parsedEpisodes.size
            val firstEpisodeOrNull = parsedEpisodes.firstOrNull()

            val currentShowStatus = when {
                statusTextOriginal?.contains("Đang chiếu", ignoreCase = true) == true ||
                statusTextOriginal?.contains("Đang tiến hành", ignoreCase = true) == true ||
                (statusTextOriginal?.matches(Regex("""Tập\s*\d+\s*/\s*\?""")) == true && episodesCount > 0) // "Tập X/?"
                -> ShowStatus.Ongoing

                statusTextOriginal?.contains("Hoàn thành", ignoreCase = true) == true ||
                statusTextOriginal?.contains("Full", ignoreCase = true) == true ||
                (statusTextOriginal?.matches(Regex("""Tập\s*\d+\s*/\s*\d+""")) == true && // "Tập X/Y"
                    statusTextOriginal.substringBefore("/").filter { it.isDigit() } == statusTextOriginal.substringAfterLast("/").filter { it.isDigit() } && episodesCount > 0 ) ||
                (episodesCount == 1 && (firstEpisodeOrNull?.name?.contains("Full", ignoreCase = true) == true || firstEpisodeOrNull?.name?.contains("Movie", ignoreCase = true) == true))
                -> ShowStatus.Completed
                
                else -> if (episodesCount > 0 && parsedEpisodes.any { it.episode != null }) null else ShowStatus.Completed // Mặc định là Completed nếu không có manh mối rõ ràng hoặc chỉ có 1 tập "Full"
            }
            
            // Lấy thông tin diễn viên/nhân vật
            val actorsDataList = infoDoc.select("div#MvTb-Cast ul.ListCast li a").mapNotNull { actorLinkElement ->
                val name = actorLinkElement.attr("title").removePrefix("Nhân vật ").trim() // Loại bỏ "Nhân vật "
                val imageUrl = fixUrl(actorLinkElement.selectFirst("img")?.attr("src"), baseUrl)
                if (name.isNotBlank()) { ActorData(Actor(name, image = imageUrl), roleString = null) } else { null }
            }
            
            // Lấy phim đề cử
            val recommendations = mutableListOf<SearchResponse>()
            infoDoc.select("div.Wdgt div.MovieListRelated.owl-carousel div.TPostMv").forEach { item ->
                try {
                    val linkElement = item.selectFirst("div.TPost > a") ?: return@forEach
                    val recHref = fixUrl(linkElement.attr("href"), baseUrl)
                    val recTitle = linkElement.selectFirst("div.Title")?.text()?.trim()
                    val recPosterUrl = fixUrl(linkElement.selectFirst("div.Image img")?.attr("src"), baseUrl)

                    if (recHref != null && recTitle != null) {
                        val isTvSeriesRec = linkElement.selectFirst("span.mli-eps") != null || recTitle.contains("tập", ignoreCase = true) || linkElement.selectFirst("span.mli-quality") == null
                        val recTvType = if (isTvSeriesRec) TvType.TvSeries else TvType.Movie // Ước lượng type cho recommendation
                        recommendations.add(provider.newMovieSearchResponse(recTitle, recHref, recTvType) { this.posterUrl = recPosterUrl })
                    }
                } catch (e: Exception) { 
                    // Log.e("AnimeVietsubProvider", "Error parsing recommendation item for $title: ${item.html().take(50)}", e) // Optional
                }
            }
            // if (recommendations.isEmpty()) Log.w("AnimeVietsubProvider", "No recommendations found for '$title'") // Optional

            // Logic xác định TvType cho trang load
            var finalTvType: TvType? = null
            val country = infoDoc.getCountry()?.lowercase() // "nhật bản", "trung quốc", ...
            val hasAnimeLeTag = genres.any { it.equals("Anime lẻ", ignoreCase = true) } || statusTextOriginal?.contains("Anime lẻ", ignoreCase = true) == true
            
            val firstEpisodeNameLower = firstEpisodeOrNull?.name?.lowercase()
            // Kiểm tra xem có phải là movie trá hình 1 tập không (ví dụ: "Tập 1", "Tập Full", "Full")
            val isSingleEpisodeActuallyMovie = episodesCount == 1 &&
                (firstEpisodeNameLower == "tập 1" || firstEpisodeNameLower == "tập full" || firstEpisodeNameLower == "full" || firstEpisodeNameLower?.matches(Regex("""^(tập\s*)?0*1$""")) == true ||
                 (firstEpisodeNameLower?.matches(Regex("""^(tập\s*)?\d+$""")) == true && firstEpisodeNameLower.filter { it.isDigit() }.toIntOrNull() == 1))

            val isMovieHintFromTitle = title.contains("Movie", ignoreCase = true) || title.contains("Phim Lẻ", ignoreCase = true)
            
            // Xác định bối cảnh Nhật Bản (Anime)
            val isJapaneseContext = country == "nhật bản" || country == "japan" ||
                                    (country == null && (title.contains("Anime", ignoreCase = true) || genres.any{ it.contains("Anime", ignoreCase = true) && !it.contains("Trung Quốc", ignoreCase = true)} ))

            if (title.contains("OVA", ignoreCase = true) || title.contains("ONA", ignoreCase = true) || (hasAnimeLeTag && episodesCount > 1 && isJapaneseContext) ) {
                finalTvType = TvType.Anime // OVA, ONA, hoặc "Anime lẻ" có nhiều tập => Anime Series
            }
            // Movie/Anime Movie
            else if (isMovieHintFromTitle || (hasAnimeLeTag && episodesCount <= 1) || isSingleEpisodeActuallyMovie) {
                finalTvType = if (isJapaneseContext) TvType.Anime // Movie/Anime Lẻ của Nhật => Anime (Movie)
                                else if (country == "trung quốc" || country == "china") TvType.Movie // Donghua Movie => Movie
                                else TvType.Movie // Các trường hợp movie khác
            }
            // Series
            else { 
                finalTvType = when {
                    isJapaneseContext -> TvType.Anime // Anime Series
                    country == "trung quốc" || country == "china" -> TvType.Cartoon // Donghua Series => Cartoon
                    genres.any {g -> g.contains("Hoạt Hình", ignoreCase = true) || g.contains("Animation", ignoreCase = true)} -> TvType.Cartoon // Hoạt hình khác
                    else -> TvType.TvSeries // Live action series
                }
            }

            // Fallback cuối cùng nếu finalTvType vẫn null
            if (finalTvType == null) {
                finalTvType = if (episodesCount > 1 || statusTextOriginal?.contains("Anime bộ", ignoreCase = true) == true) { // Có nhiều tập hoặc ghi là "Anime bộ"
                    when { isJapaneseContext -> TvType.Anime; country == "trung quốc" || country == "china" -> TvType.Cartoon; else -> TvType.TvSeries }
                } else { // Mặc định là Movie hoặc Anime (movie) nếu chỉ có 1 tập hoặc không rõ ràng
                     when { isJapaneseContext && (isMovieHintFromTitle || isSingleEpisodeActuallyMovie || hasAnimeLeTag) -> TvType.Anime; isJapaneseContext -> TvType.Anime; else -> TvType.Movie }
                }
            }
            Log.i("AnimeVietsubProvider", "Final TvType for '$title' ($infoUrl): $finalTvType. Country: $country, Episodes: $episodesCount, Status: $statusTextOriginal, Genres: $genres")


            // Xác định cấu trúc là series hay movie để gọi đúng hàm new...LoadResponse
            val isSeriesStructure = episodesCount > 1 || 
                                    finalTvType == TvType.TvSeries || 
                                    finalTvType == TvType.Cartoon ||
                                    (finalTvType == TvType.Anime && episodesCount > 1 && !isSingleEpisodeActuallyMovie && !hasAnimeLeTag) // Anime series

            return if (isSeriesStructure) {
                provider.newTvSeriesLoadResponse(title, infoUrl, finalTvType ?: TvType.Anime, episodes = parsedEpisodes) { // Mặc định Anime nếu type vẫn null
                    this.posterUrl = posterUrlForResponse; this.plot = description; this.tags = genres; this.year = year; this.rating = ratingValue; this.showStatus = currentShowStatus;
                    this.actors = actorsDataList; this.recommendations = recommendations
                }
            } else { // Là Movie hoặc Anime (Movie)
                val actualMovieTvType = finalTvType ?: if(isJapaneseContext && (isMovieHintFromTitle || isSingleEpisodeActuallyMovie || hasAnimeLeTag)) TvType.Anime else TvType.Movie
                
                val durationText = infoSection.select("li:has(strong:containsOwn(Thời lượng))")?.firstOrNull()?.ownText()?.trim()
                    ?: infoDoc.select("ul.InfoList li.AAIco-adjust:contains(Thời lượng)") // Selector khác cho thời lượng
                        .firstOrNull()?.ownText()?.trim() 
                val durationMinutes = durationText?.filter { it.isDigit() }?.toIntOrNull()

                // Đối với movie, data cho loadLinks sẽ là thông tin của "tập" duy nhất (nếu có) hoặc thông tin chung của movie
                val movieDataForLoadLinks = if (parsedEpisodes.isNotEmpty()) {
                    // Nếu có parsedEpisodes (ví dụ: trang xem phim có nút "Full" hoặc "Tập 1" cho movie)
                    val firstEpisode = parsedEpisodes.first() // Lấy tập đầu tiên (và duy nhất)
                    val firstEpisodeDataString = firstEpisode.data // Đây là JSON của EpisodeData
                        try {
                            // Kiểm tra xem dataId có hợp lệ không
                            val parsedEpisodeData = gson.fromJson(firstEpisodeDataString, EpisodeData::class.java)
                            if (parsedEpisodeData.dataId != null) { firstEpisodeDataString } // Sử dụng data từ episode nếu dataId tồn tại
                            else { // Nếu dataId null, thử tạo lại từ infoUrl
                                val idAttempt = infoDoc.selectFirst("a.watch_button_more[href*=xem-phim]")?.attr("href")?.substringAfterLast("a")?.substringBefore("/") ?: infoUrl.substringAfterLast("/").substringBefore("-").filter{it.isDigit()}.ifEmpty { infoUrl.substringAfterLast("-").filter{it.isDigit()} }
                                gson.toJson(EpisodeData(url = infoUrl, dataId = idAttempt.ifBlank { null }, duHash = null))
                            }
                        } catch (e: Exception) { // Lỗi parse JSON, tạo data mới
                             val idAttempt = infoDoc.selectFirst("a.watch_button_more[href*=xem-phim]")?.attr("href")?.substringAfterLast("a")?.substringBefore("/") ?: infoUrl.substringAfterLast("/").substringBefore("-").filter{it.isDigit()}.ifEmpty { infoUrl.substringAfterLast("-").filter{it.isDigit()} }
                             gson.toJson(EpisodeData(url = infoUrl, dataId = idAttempt.ifBlank { null }, duHash = null))
                        }
                } else {
                    // Nếu không có parsedEpisodes (trang xem phim không load được hoặc movie không có cấu trúc tập riêng)
                    // Cố gắng lấy data-id từ nút "Xem phim" trên trang info
                    val idAttempt = infoDoc.selectFirst("a.watch_button_more[href*=xem-phim]")?.attr("href")?.substringAfterLast("a")?.substringBefore("/") ?: infoUrl.substringAfterLast("/").substringBefore("-").filter{it.isDigit()}.ifEmpty { infoUrl.substringAfterLast("-").filter{it.isDigit()} }
                    gson.toJson(EpisodeData(url = infoUrl, dataId = idAttempt.ifBlank { null }, duHash = null))
                }

                provider.newMovieLoadResponse(title, infoUrl, actualMovieTvType, movieDataForLoadLinks) {
                    this.posterUrl = posterUrlForResponse; this.plot = description; this.tags = genres; this.year = year; this.rating = ratingValue; durationMinutes?.let { addDuration(it.toString()) };
                    this.actors = actorsDataList; this.recommendations = recommendations
                }
            }

        } catch (e: Exception) {
            Log.e("AnimeVietsubProvider", "Lỗi trong toLoadResponse xử lý cho url: $infoUrl", e); return null
        }
    }

    // Data class cho response từ ajax/player
    private data class AjaxPlayerResponse(
        @JsonProperty("success") val success: Int? = null,
        @JsonProperty("link") val link: List<LinkSource>? = null // Có thể là một list các source
    )

    // Data class cho mỗi source link trong AjaxPlayerResponse
    private data class LinkSource(
        @JsonProperty("file") val file: String? = null,    // Chứa link m3u8 (đã mã hóa hoặc trực tiếp)
        @JsonProperty("type") val type: String? = null,    // Ví dụ: "hls", "mp4"
        @JsonProperty("label") val label: String? = null   // Ví dụ: "FULLHD", "HD", "SD"
    )

    override suspend fun loadLinks(
        data: String, // JSON string của EpisodeData
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        var foundLinks = false
        val baseUrl = getBaseUrl() // Lấy baseUrl động
        val ajaxUrl = "$baseUrl/ajax/player?v=2019a" // Endpoint API của AnimeVietsub
        val decryptApiUrl = "https://m3u8.013666.xyz/animevietsub/decrypt" // Endpoint giải mã (nếu cần)
        val textPlainMediaType = "text/plain".toMediaTypeOrNull()

        val episodeData = try {
            gson.fromJson(data, EpisodeData::class.java)
        } catch (e: Exception) {
            Log.e("AnimeVietsubProvider", "Không thể parse EpisodeData JSON: '$data'", e); return false
        }

        val episodePageUrl = episodeData.url // URL của trang chứa tập phim (dùng làm referer)
        val episodeId = episodeData.dataId      // data-id của tập phim
        val episodeHash = episodeData.duHash     // data-hash (nếu có)

        if (episodeId == null || episodePageUrl.isBlank()) {
            Log.e("AnimeVietsubProvider", "Thiếu ID tập phim (dataId) hoặc URL trang (episodeData.url): $data"); return false
        }

        try {
            // Chuẩn bị data cho POST request
            val postData = mutableMapOf("id" to episodeId, "play" to "api")
            if (!episodeHash.isNullOrBlank()) {
                postData["link"] = episodeHash // Thêm 'link' (hash) nếu có
            }

            val headers = mapOf(
                "Content-Type" to "application/x-www-form-urlencoded; charset=UTF-8",
                "Accept" to "application/json, text/javascript, */*; q=0.01",
                "X-Requested-With" to "XMLHttpRequest",
                "User-Agent" to USER_AGENT,
                "Referer" to episodePageUrl // Referer là URL của trang tập phim
            )

            // Gọi API ajax/player
            val ajaxResponse = app.post(ajaxUrl, data = postData, headers = headers, referer = episodePageUrl)
            val playerResponse = try {
                gson.fromJson(ajaxResponse.text, AjaxPlayerResponse::class.java)
            } catch (e: Exception) {
                Log.e("AnimeVietsubProvider", "Lỗi parse JSON từ ajax/player: ${ajaxResponse.text}", e); null
            }

            if (playerResponse?.success != 1 || playerResponse.link.isNullOrEmpty()) {
                Log.e("AnimeVietsubProvider", "Request ajax/player thất bại hoặc không có link: ${ajaxResponse.text}"); return false
            }

            // Xử lý từng link source từ response
            playerResponse.link.forEach { linkSource ->
                val dataEncOrDirectLink = linkSource.file // Đây có thể là link đã mã hóa hoặc link m3u8/mp4 trực tiếp
                if (dataEncOrDirectLink.isNullOrBlank()) {
                    Log.w("AnimeVietsubProvider", "Link source 'file' rỗng cho ID $episodeId, label ${linkSource.label}")
                    return@forEach // Bỏ qua source này nếu link rỗng
                }

                val finalStreamUrl: String = if (dataEncOrDirectLink.startsWith("http") && (dataEncOrDirectLink.contains(".m3u8") || dataEncOrDirectLink.contains(".mp4"))) {
                    // Nếu đã là link trực tiếp m3u8/mp4
                    dataEncOrDirectLink
                } else {
                    // Nếu là dữ liệu mã hóa, gửi đến API giải mã
                    // Log.d("AnimeVietsubProvider", "Link is encrypted, attempting to decrypt: $dataEncOrDirectLink") // Optional
                    val decryptHeaders = mapOf("User-Agent" to USER_AGENT, "Referer" to episodePageUrl) // Thêm Referer cho API giải mã
                    val decryptResponse = app.post(decryptApiUrl, headers = decryptHeaders, requestBody = dataEncOrDirectLink.toRequestBody(textPlainMediaType))
                    decryptResponse.text.trim() // API giải mã trả về link m3u8
                }

                if (finalStreamUrl.startsWith("http") && (finalStreamUrl.endsWith(".m3u8") || finalStreamUrl.contains(".mp4")) ) {
                    val streamType = if (finalStreamUrl.endsWith(".m3u8")) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                    val streamHeaders = mapOf(
                        "User-Agent" to USER_AGENT,
                        "Origin" to baseUrl, // Origin có thể là baseUrl đã phân giải
                        "Referer" to episodePageUrl // Referer quan trọng để stream
                    )
                    
                    val extractorLink = newExtractorLink(
                        source = this.name, // Tên provider
                        name = "AnimeVietsub" + (linkSource.label?.let { " - $it" } ?: ""), // Tên link + label (FULLHD, HD,...)
                        url = finalStreamUrl,
                        type = streamType // M3U8 hoặc VIDEO
                    ) {
                        this.referer = episodePageUrl // Referer cho player
                        this.quality = Qualities.Unknown.value // Chất lượng chưa xác định rõ từ label, để Unknown
                        this.headers = streamHeaders // Headers cần thiết để stream
                    }
                    callback(extractorLink)
                    foundLinks = true
                } else {
                    Log.e("AnimeVietsubProvider", "API giải mã không trả về URL M3U8/MP4 hợp lệ hoặc link không hợp lệ. Response/Link: '$finalStreamUrl'")
                }
            }
        } catch (e: Exception) {
            Log.e("AnimeVietsubProvider", "Lỗi trích xuất link API cho ID $episodeId (Data: $data)", e)
        }
        return foundLinks
    }

    // Hàm tiện ích để encode URI
    private fun String?.encodeUri(): String {
        if (this == null) return ""
        return try { URLEncoder.encode(this, "UTF-8").replace("+", "%20") }
        catch (e: Exception) { Log.e("AnimeVietsubProvider", "Lỗi URL encode: $this", e); this /* Trả về chuỗi gốc nếu lỗi */ }
    }
    
    // Hàm tiện ích chuyển đổi rating (không còn dùng trực tiếp trong toLoadResponse nhưng có thể hữu ích)
    private fun Double?.toAnimeVietsubRatingInt(): Int? = this?.takeIf { !it.isNaN() }?.let { (it * 1000).roundToInt().coerceIn(0, 10000) }

    // Hàm tiện ích để sửa lỗi URL (tương đối, scheme-less, v.v.)
    private fun fixUrl(url: String?, baseUrl: String): String? {
        if (url.isNullOrBlank()) return null
        return try {
            when {
                url.startsWith("http") -> url // Đã là URL đầy đủ
                url.startsWith("//") -> "https:$url" // Scheme-less URL
                url.startsWith("/") -> URL(URL(baseUrl), url).toString() // URL tương đối từ root
                else -> URL(URL(baseUrl), "/$url".removePrefix("//")).toString() // URL tương đối từ thư mục hiện tại (thêm / nếu thiếu)
            }
        } catch (e: java.net.MalformedURLException) {
            Log.e("AnimeVietsubProvider", "URL không hợp lệ khi fix: base='$baseUrl', url='$url'", e)
            if (url.startsWith("http")) return url; null // Nếu đã là http mà vẫn lỗi, trả về gốc hoặc null
        } catch (e: Exception) {
            Log.e("AnimeVietsubProvider", "Lỗi không xác định khi fix URL: base='$baseUrl', url='$url'", e); null
        }
    }
}
