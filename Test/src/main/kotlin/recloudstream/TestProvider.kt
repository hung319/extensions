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
        } else {
            // Nếu phân giải thất bại
            if (currentActiveUrl.contains("bit.ly") || (urlToAttemptResolution != ultimateFallbackDomain && currentActiveUrl != ultimateFallbackDomain)) {
                Log.w("AnimeVietsubProvider", "Domain resolution failed for '$urlToAttemptResolution'. Using fallback: $ultimateFallbackDomain")
                currentActiveUrl = ultimateFallbackDomain
            } else {
                Log.e("AnimeVietsubProvider", "All domain resolution attempts failed. Sticking with last known: $currentActiveUrl")
            }
        }
        return currentActiveUrl 
    }

    // Helper function to parse anime items from a list page Document
    private fun Document.parseAnimeListPageItems(provider: MainAPI, baseUrl: String): List<SearchResponse> {
        // Selector based on moicapnhap.html and sapchieu.html
        return this.select("ul.MovieList.Rows.AX.A06.B04.C03.E20 li.TPostMv")
            .mapNotNull { it.toSearchResponse(provider, baseUrl) }
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        // If page > 1, it means CloudStream is trying to paginate the main page itself for more categories.
        // Your existing logic correctly states that there are no more categories beyond what's loaded on page 1.
        if (page > 1) return HomePageResponse(emptyList(), false)

        val baseUrl = getBaseUrl()
        val lists = mutableListOf<HomePageList>()

        // 1. "Anime Mới Cập Nhật" - Sourced from /anime-moi/
        try {
            val animeMoiUrl = "$baseUrl/anime-moi/"
            Log.d("AnimeVietsubProvider", "Fetching Mới Cập Nhật from: $animeMoiUrl")
            val animeMoiDoc = app.get(animeMoiUrl).document
            val animeMoiItems = animeMoiDoc.parseAnimeListPageItems(this, baseUrl)
            if (animeMoiItems.isNotEmpty()) {
                lists.add(HomePageList("Anime Mới Cập Nhật", animeMoiItems))
                Log.d("AnimeVietsubProvider", "Added ${animeMoiItems.size} items for Mới Cập Nhật")
            } else {
                Log.d("AnimeVietsubProvider", "No items found for Mới Cập Nhật at $animeMoiUrl")
            }
        } catch (e: Exception) {
            Log.e("AnimeVietsubProvider", "Error loading /anime-moi/ for getMainPage: ${e.message}", e)
        }

        // 2. "Anime Sắp Chiếu" - Sourced from /anime-sap-chieu/
        try {
            val sapChieuUrl = "$baseUrl/anime-sap-chieu/"
            Log.d("AnimeVietsubProvider", "Fetching Sắp Chiếu from: $sapChieuUrl")
            val sapChieuDoc = app.get(sapChieuUrl).document
            val sapChieuItems = sapChieuDoc.parseAnimeListPageItems(this, baseUrl)
            if (sapChieuItems.isNotEmpty()) {
                lists.add(HomePageList("Anime Sắp Chiếu", sapChieuItems))
                Log.d("AnimeVietsubProvider", "Added ${sapChieuItems.size} items for Sắp Chiếu")
            } else {
                Log.d("AnimeVietsubProvider", "No items found for Sắp Chiếu at $sapChieuUrl")
            }
        } catch (e: Exception) {
            Log.e("AnimeVietsubProvider", "Error loading /anime-sap-chieu/ for getMainPage: ${e.message}", e)
        }

        // 3. "Đề cử" (Hot) - Sourced from the main page (baseUrl) as per original logic
        try {
            Log.d("AnimeVietsubProvider", "Fetching Đề cử from main page: $baseUrl")
            val mainPageDoc = app.get(baseUrl).document // Fetch main page for this section
            val hotItems = mainPageDoc.select("section#hot-home ul.MovieList.Rows li.TPostMv") // Original selector
                .mapNotNull { it.toSearchResponse(this, baseUrl) }
            if (hotItems.isNotEmpty()) {
                val hotListName = mainPageDoc.selectFirst("section#hot-home div.Top a.STPb.Current")?.text() ?: "Đề cử"
                lists.add(HomePageList(hotListName, hotItems))
                Log.d("AnimeVietsubProvider", "Added ${hotItems.size} items for $hotListName")
            } else {
                Log.d("AnimeVietsubProvider", "No items found for Đề cử on main page")
            }
        } catch (e: Exception) {
            Log.e("AnimeVietsubProvider", "Error loading #hot-home from main page for getMainPage: ${e.message}", e)
        }
        
        if (lists.isEmpty()) {
             Log.w("AnimeVietsubProvider", "No lists were populated for the homepage.")
            // Consider if throwing an error is desired or returning an empty response.
            // Original code threw: throw ErrorLoadingException("No lists found on homepage.")
            // Let's return empty with hasNext = false to avoid crashing if some sections fail.
            return HomePageResponse(emptyList(), false)
        }

        // hasNext is false because if page > 1, we've already returned.
        // This means the getMainPage itself doesn't have more "pages" of categories.
        return HomePageResponse(lists, hasNext = false)
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
            
            // Check for episode span first, common for series
            val hasEpisodeSpan = this.selectFirst("span.mli-eps") != null
            // Check for "SẮP CHIẾU" span, common on sapchieu.html
            val isUpcoming = this.selectFirst("span.mli-timeschedule:contains(SẮP CHIẾU)") != null
            
            val statusSpanText = this.selectFirst("span.mli-st")?.text() ?: ""

            if (titleFromElement.contains("OVA", ignoreCase = true) ||
                titleFromElement.contains("ONA", ignoreCase = true) ||
                titleFromElement.contains("Movie", ignoreCase = true) ||
                titleFromElement.contains("Phim Lẻ", ignoreCase = true) ||
                (!hasEpisodeSpan && !isUpcoming && statusSpanText.contains("Full", ignoreCase = true)) ||
                (!hasEpisodeSpan && !isUpcoming && statusSpanText.contains("Hoàn Tất", ignoreCase = true))
            ) {
                finalTvType = if (provider.name.contains("Anime", ignoreCase = true) ||
                                (titleFromElement.contains("Anime", ignoreCase = true) &&
                                !titleFromElement.contains("Trung Quốc", ignoreCase = true) && 
                                !titleFromElement.contains("Donghua", ignoreCase = true)) ) {
                    TvType.Anime // Anime Movie/OVA/ONA
                } else {
                    TvType.Movie
                }
            } else if (hasEpisodeSpan || isUpcoming) { // isUpcoming also implies it's likely a series
                finalTvType = if (provider.name.contains("Anime", ignoreCase = true) || titleFromElement.contains("Anime", ignoreCase = true)) {
                    TvType.Anime // Anime Series (ongoing, completed, or upcoming)
                } else {
                    TvType.TvSeries 
                }
            }
            
            // Fallback if type is still not determined
            if (finalTvType == null) {
                // If it has episode info or is upcoming, or no quality tag (often for series), assume series-like (Anime/TvSeries).
                // Otherwise, assume movie-like.
                finalTvType = if (hasEpisodeSpan || isUpcoming || this.selectFirst("span.mli-quality") == null) TvType.Anime else TvType.Movie
            }
            
            provider.newMovieSearchResponse(titleFromElement, href, finalTvType ?: TvType.Anime) { 
                this.posterUrl = posterUrl
            }
        } catch (e: Exception) {
            Log.e("AnimeVietsubProvider", "Error parsing search result item: ${this.html().take(100)}", e)
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
                ?: run { Log.e("AnimeVietsubProvider", "Could not find title on info page $infoUrl"); return null }

            var posterUrlForResponse: String? = null
            var rawPosterUrl: String?
            
            val metaBigBanner = infoDoc.select("meta[property=og:image], meta[itemprop=image]")
                .mapNotNull { it.attr("content").takeIf { c -> c.isNotBlank() && c.contains("/data/big_banner/", ignoreCase = true) } }
                .firstOrNull()
            if (!metaBigBanner.isNullOrBlank()) {
                posterUrlForResponse = fixUrl(metaBigBanner, baseUrl)
            }

            if (posterUrlForResponse.isNullOrBlank()) {
                rawPosterUrl = infoDoc.selectFirst("div.TPostBg.Objf img.TPostBg")?.attr("src")
                if (!rawPosterUrl.isNullOrBlank() && rawPosterUrl.contains("/data/big_banner/", ignoreCase = true)) {
                    posterUrlForResponse = fixUrl(rawPosterUrl, baseUrl)
                }
            }
            
            if (posterUrlForResponse.isNullOrBlank()) {
                rawPosterUrl = infoDoc.selectFirst("div.TPost.Single div.Image figure.Objf img")?.attr("src")
                if (!rawPosterUrl.isNullOrBlank()) {
                    posterUrlForResponse = fixUrl(rawPosterUrl, baseUrl)
                }
            }
             if (posterUrlForResponse.isNullOrBlank()) { 
                rawPosterUrl = infoDoc.selectFirst("div.TPost.Single div.Image img")?.attr("src")
                if (!rawPosterUrl.isNullOrBlank()) {
                    posterUrlForResponse = fixUrl(rawPosterUrl, baseUrl)
                }
            }

            if (posterUrlForResponse.isNullOrBlank()) {
                val metaImages = infoDoc.select("meta[property=og:image], meta[itemprop=image]")
                    .mapNotNull { it.attr("content").takeIf { c -> c.isNotBlank() } }
                    .distinct()

                if (metaImages.isNotEmpty()) {
                    rawPosterUrl = metaImages.firstOrNull { it.contains("/poster/", ignoreCase = true) }
                    if (!rawPosterUrl.isNullOrBlank()) {
                        posterUrlForResponse = fixUrl(rawPosterUrl, baseUrl)
                    } else {
                        rawPosterUrl = metaImages.firstOrNull { !it.contains("/data/big_banner/", ignoreCase = true) } ?: metaImages.firstOrNull()
                        if (!rawPosterUrl.isNullOrBlank()) {
                            posterUrlForResponse = fixUrl(rawPosterUrl, baseUrl)
                        }
                    }
                }
            }

            val descriptionFromDiv = infoDoc.selectFirst("article.TPost.Single div.Description")?.text()?.trim()
            val description = if (!descriptionFromDiv.isNullOrBlank()) descriptionFromDiv 
                                else infoDoc.selectFirst("meta[property=og:description]")?.attr("content")?.trim()

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
            }
            
            val statusTextOriginal = infoSection.select("li:has(strong:containsOwn(Trạng thái))")?.firstOrNull()?.ownText()?.trim()
                ?: infoDoc.select("div.mvici-left li.AAIco-adjust:contains(Trạng thái)")
                    .firstOrNull()?.textNodes()?.lastOrNull()?.text()?.trim()?.replace("Trạng thái:", "")?.trim()
            
            val parsedEpisodes = if (watchPageDoc != null) {
                Log.d("AnimeVietsubProvider", "Watch page document is not null. Processing episodes...")
                watchPageDoc.select("div.server ul.list-episode li a.btn-episode").mapNotNull { epLink ->
                    val epLinkHtml = epLink.outerHtml()
                    Log.d("AnimeVietsubProvider", "Processing epLink HTML: $epLinkHtml")

                    val epUrl = fixUrl(epLink.attr("href"), baseUrl)
                    val epNameFull = epLink.attr("title").ifBlank { epLink.text() }.trim()
                    val dataId = epLink.attr("data-id").ifBlank { null }
                    val duHash = epLink.attr("data-hash").ifBlank { null }

                    Log.d("AnimeVietsubProvider", "Parsed attributes: epUrl=$epUrl, epNameFull='$epNameFull', dataId=$dataId, duHash=$duHash")

                    val episodeInfoForLoadLinks = EpisodeData(url = epUrl ?: infoUrl, dataId = dataId, duHash = duHash)

                    var episodeIntForSort: Int? = null // Vẫn tính toán để log hoặc dùng sau nếu cần
                    var episodeStringForDisplay: String = epNameFull 

                    val numMatch = Regex("""(?:Tập\s+)?(\d+(?:\.\d+)?)""").find(epNameFull)

                    if (numMatch != null) {
                        val numberStr = numMatch.groupValues[1] 
                        Log.d("AnimeVietsubProvider", "Found numberStr: '$numberStr' in epNameFull: '$epNameFull'")

                        val parts = numberStr.split('.')
                        val mainEpisodeNum = parts.getOrNull(0)?.toIntOrNull()
                        
                        if (mainEpisodeNum != null) {
                            var subEpisodeNum = 0
                            if (parts.size > 1) {
                                subEpisodeNum = parts.getOrNull(1)?.take(2)?.toIntOrNull() ?: 0
                            }
                            episodeIntForSort = mainEpisodeNum * 100 + subEpisodeNum 
                        } else {
                            episodeIntForSort = null
                        }

                        val titlePart = epNameFull.replaceFirst(Regex("""^(?:.*?[\s-])?${Regex.escape(numberStr)}\s*(?:-\s*)?""", RegexOption.IGNORE_CASE), "").trim()
                        Log.d("AnimeVietsubProvider", "Parsed titlePart: '$titlePart'")
                        
                        var prefix = "Tập $numberStr" 
                        if (epNameFull.startsWith("OVA", ignoreCase = true) && mainEpisodeNum != null) {
                            prefix = "OVA $numberStr"
                        } else if (epNameFull.startsWith("Special", ignoreCase = true) && mainEpisodeNum != null) {
                            prefix = "Special $numberStr"
                        }

                        episodeStringForDisplay = if (titlePart.isNotEmpty() && titlePart.lowercase() != numberStr.lowercase()) {
                            "$prefix: $titlePart"
                        } else {
                            prefix
                        }
                    } else if (epNameFull.equals("Full", ignoreCase = true) || epNameFull.equals("Tập Full", ignoreCase = true)) {
                        Log.d("AnimeVietsubProvider", "epNameFull is 'Full' or 'Tập Full': '$epNameFull'")
                        episodeStringForDisplay = "Tập Full"
                        episodeIntForSort = 1 
                    } else {
                        Log.d("AnimeVietsubProvider", "No standard number pattern found for epNameFull: '$epNameFull'. Using full name for display.")
                    }

                    Log.d("AnimeVietsubProvider", "Final parsed values: episodeIntForSort=$episodeIntForSort, episodeStringForDisplay='$episodeStringForDisplay'")

                    if (dataId != null && epUrl != null && episodeStringForDisplay.isNotBlank()) {
                        Log.d("AnimeVietsubProvider", "All conditions met. Creating newEpisode for: name='${episodeStringForDisplay}', dataId='$dataId', episodeSortKey(unused for this.episode)=$episodeIntForSort")
                        newEpisode(data = gson.toJson(episodeInfoForLoadLinks)) {
                            this.name = episodeStringForDisplay 
                            this.episode = null // *** Đặt lại thành null theo yêu cầu ***
                        }
                    } else {
                        Log.w("AnimeVietsubProvider", "Skipping episode due to unmet conditions: dataId=$dataId, epUrl=$epUrl, episodeStringForDisplay='$episodeStringForDisplay' (isNotBlank=${episodeStringForDisplay.isNotBlank()})")
                        null
                    }
                }.sortedBy { it.episode ?: Int.MAX_VALUE } // Sắp xếp sẽ không hiệu quả nếu this.episode luôn là null
            } else {
                Log.w("AnimeVietsubProvider", "Watch page document is NULL. No episodes will be parsed.")
                emptyList<Episode>()
            }

            val episodesCount = parsedEpisodes.size
            val firstEpisodeOrNull = parsedEpisodes.firstOrNull()

            val currentShowStatus = when {
                statusTextOriginal?.contains("Đang chiếu", ignoreCase = true) == true ||
                statusTextOriginal?.contains("Đang tiến hành", ignoreCase = true) == true ||
                (statusTextOriginal?.matches(Regex("""Tập\s*\d+\s*/\s*\?""")) == true && episodesCount > 0) 
                -> ShowStatus.Ongoing

                statusTextOriginal?.contains("Hoàn thành", ignoreCase = true) == true ||
                statusTextOriginal?.contains("Full", ignoreCase = true) == true ||
                (statusTextOriginal?.matches(Regex("""Tập\s*\d+\s*/\s*\d+""")) == true && 
                    statusTextOriginal.substringBefore("/").filter { it.isDigit() } == statusTextOriginal.substringAfterLast("/").filter { it.isDigit() } && episodesCount > 0 ) ||
                (episodesCount == 1 && (firstEpisodeOrNull?.name?.contains("Full", ignoreCase = true) == true || firstEpisodeOrNull?.name?.contains("Movie", ignoreCase = true) == true))
                -> ShowStatus.Completed
                
                else -> if (episodesCount > 0 && parsedEpisodes.any { it.episode != null }) null else ShowStatus.Completed
            }
            
            val actorsDataList = infoDoc.select("div#MvTb-Cast ul.ListCast li a").mapNotNull { actorLinkElement ->
                val name = actorLinkElement.attr("title").removePrefix("Nhân vật ").trim() 
                val imageUrl = fixUrl(actorLinkElement.selectFirst("img")?.attr("src"), baseUrl)
                if (name.isNotBlank()) { ActorData(Actor(name, image = imageUrl), roleString = null) } else { null }
            }
            
            val recommendations = mutableListOf<SearchResponse>()
            infoDoc.select("div.Wdgt div.MovieListRelated.owl-carousel div.TPostMv").forEach { item ->
                try {
                    val linkElement = item.selectFirst("div.TPost > a") ?: return@forEach
                    val recHref = fixUrl(linkElement.attr("href"), baseUrl)
                    val recTitle = linkElement.selectFirst("div.Title")?.text()?.trim()
                    val recPosterUrl = fixUrl(linkElement.selectFirst("div.Image img")?.attr("src"), baseUrl)

                    if (recHref != null && recTitle != null) {
                        val isTvSeriesRec = linkElement.selectFirst("span.mli-eps") != null || recTitle.contains("tập", ignoreCase = true) || linkElement.selectFirst("span.mli-quality") == null
                        val recTvType = if (isTvSeriesRec) TvType.TvSeries else TvType.Movie 
                        recommendations.add(provider.newMovieSearchResponse(recTitle, recHref, recTvType) { this.posterUrl = recPosterUrl })
                    }
                } catch (e: Exception) { 
                    // Log.e("AnimeVietsubProvider", "Error parsing recommendation item for $title: ${item.html().take(50)}", e) 
                }
            }

            var finalTvType: TvType? = null
            val country = infoDoc.getCountry()?.lowercase() 
            val hasAnimeLeTag = genres.any { it.equals("Anime lẻ", ignoreCase = true) } || statusTextOriginal?.contains("Anime lẻ", ignoreCase = true) == true
            
            val firstEpisodeNameLower = firstEpisodeOrNull?.name?.lowercase()
            
            // *** Đã CHỈNH SỬA LOGIC isSingleEpisodeActuallyMovie Ở ĐÂY (từ lần trước) ***
            val isSingleEpisodeActuallyMovie = episodesCount == 1 &&
                (
                    firstEpisodeNameLower == "tập full" ||
                    firstEpisodeNameLower == "full" ||
                    (firstEpisodeNameLower?.matches(Regex("""^(tập\s*)?0*1$""")) ?: false)
                )

            val isMovieHintFromTitle = title.contains("Movie", ignoreCase = true) || title.contains("Phim Lẻ", ignoreCase = true)
            
            val isJapaneseContext = country == "nhật bản" || country == "japan" ||
                                    (country == null && (title.contains("Anime", ignoreCase = true) || genres.any{ it.contains("Anime", ignoreCase = true) && !it.contains("Trung Quốc", ignoreCase = true)} ))

            if (title.contains("OVA", ignoreCase = true) || title.contains("ONA", ignoreCase = true) || (hasAnimeLeTag && episodesCount > 1 && isJapaneseContext) ) {
                finalTvType = TvType.Anime 
            }
            else if (isMovieHintFromTitle || (hasAnimeLeTag && episodesCount <= 1) || isSingleEpisodeActuallyMovie) {
                finalTvType = if (isJapaneseContext) TvType.Anime 
                                else if (country == "trung quốc" || country == "china") TvType.Movie 
                                else TvType.Movie 
            }
            else { 
                finalTvType = when {
                    isJapaneseContext -> TvType.Anime 
                    country == "trung quốc" || country == "china" -> TvType.Cartoon 
                    genres.any {g -> g.contains("Hoạt Hình", ignoreCase = true) || g.contains("Animation", ignoreCase = true)} -> TvType.Cartoon 
                    else -> TvType.TvSeries 
                }
            }

            // *** KHỐI "if (finalTvType == null)" DƯ THỪA ĐÃ ĐƯỢC XÓA BỎ Ở ĐÂY ***
            
            Log.i("AnimeVietsubProvider", "Final TvType for '$title' ($infoUrl): $finalTvType. Country: $country, Episodes: $episodesCount, Status: $statusTextOriginal, Genres: $genres")

            val isSeriesStructure = episodesCount > 1 || 
                                    finalTvType == TvType.TvSeries || 
                                    finalTvType == TvType.Cartoon ||
                                    (finalTvType == TvType.Anime && episodesCount > 1 && !isSingleEpisodeActuallyMovie && !hasAnimeLeTag) 

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

                val movieDataForLoadLinks = if (parsedEpisodes.isNotEmpty()) {
                    val firstEpisode = parsedEpisodes.first() 
                    val firstEpisodeDataString = firstEpisode.data 
                        try {
                            val parsedEpisodeData = gson.fromJson(firstEpisodeDataString, EpisodeData::class.java)
                            if (parsedEpisodeData.dataId != null) { firstEpisodeDataString } 
                            else { 
                                val idAttempt = infoDoc.selectFirst("a.watch_button_more[href*=xem-phim]")?.attr("href")?.substringAfterLast("a")?.substringBefore("/") ?: infoUrl.substringAfterLast("/").substringBefore("-").filter{it.isDigit()}.ifEmpty { infoUrl.substringAfterLast("-").filter{it.isDigit()} }
                                gson.toJson(EpisodeData(url = infoUrl, dataId = idAttempt.ifBlank { null }, duHash = null))
                            }
                        } catch (e: Exception) { 
                             val idAttempt = infoDoc.selectFirst("a.watch_button_more[href*=xem-phim]")?.attr("href")?.substringAfterLast("a")?.substringBefore("/") ?: infoUrl.substringAfterLast("/").substringBefore("-").filter{it.isDigit()}.ifEmpty { infoUrl.substringAfterLast("-").filter{it.isDigit()} }
                             gson.toJson(EpisodeData(url = infoUrl, dataId = idAttempt.ifBlank { null }, duHash = null))
                        }
                } else {
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
        var foundLinks = false
        val baseUrl = getBaseUrl() 
        val ajaxUrl = "$baseUrl/ajax/player?v=2019a" 
        val decryptApiUrl = "https://m3u8.013666.xyz/animevietsub/decrypt" 
        val textPlainMediaType = "text/plain".toMediaTypeOrNull()

        val episodeData = try {
            gson.fromJson(data, EpisodeData::class.java)
        } catch (e: Exception) {
            Log.e("AnimeVietsubProvider", "Không thể parse EpisodeData JSON: '$data'", e); return false
        }

        val episodePageUrl = episodeData.url 
        val episodeId = episodeData.dataId      
        val episodeHash = episodeData.duHash      

        if (episodeId == null || episodePageUrl.isBlank()) {
            Log.e("AnimeVietsubProvider", "Thiếu ID tập phim (dataId) hoặc URL trang (episodeData.url): $data"); return false
        }

        try {
            val postData = mutableMapOf("id" to episodeId, "play" to "api")
            if (!episodeHash.isNullOrBlank()) {
                postData["link"] = episodeHash 
            }

            val headers = mapOf(
                "Content-Type" to "application/x-www-form-urlencoded; charset=UTF-8",
                "Accept" to "application/json, text/javascript, */*; q=0.01",
                "X-Requested-With" to "XMLHttpRequest",
                "User-Agent" to USER_AGENT,
                "Referer" to episodePageUrl 
            )

            val ajaxResponse = app.post(ajaxUrl, data = postData, headers = headers, referer = episodePageUrl)
            val playerResponse = try {
                gson.fromJson(ajaxResponse.text, AjaxPlayerResponse::class.java)
            } catch (e: Exception) {
                Log.e("AnimeVietsubProvider", "Lỗi parse JSON từ ajax/player: ${ajaxResponse.text}", e); null
            }

            if (playerResponse?.success != 1 || playerResponse.link.isNullOrEmpty()) {
                Log.e("AnimeVietsubProvider", "Request ajax/player thất bại hoặc không có link: ${ajaxResponse.text}"); return false
            }

            playerResponse.link.forEach { linkSource ->
                val dataEncOrDirectLink = linkSource.file 
                if (dataEncOrDirectLink.isNullOrBlank()) {
                    Log.w("AnimeVietsubProvider", "Link source 'file' rỗng cho ID $episodeId, label ${linkSource.label}")
                    return@forEach 
                }

                val finalStreamUrl: String = if (dataEncOrDirectLink.startsWith("http") && (dataEncOrDirectLink.contains(".m3u8") || dataEncOrDirectLink.contains(".mp4"))) {
                    dataEncOrDirectLink
                } else {
                    val decryptHeaders = mapOf("User-Agent" to USER_AGENT, "Referer" to episodePageUrl) 
                    val decryptResponse = app.post(decryptApiUrl, headers = decryptHeaders, requestBody = dataEncOrDirectLink.toRequestBody(textPlainMediaType))
                    decryptResponse.text.trim() 
                }

                if (finalStreamUrl.startsWith("http") && (finalStreamUrl.endsWith(".m3u8") || finalStreamUrl.contains(".mp4")) ) {
                    val streamType = if (finalStreamUrl.endsWith(".m3u8")) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                    val streamHeaders = mapOf(
                        "User-Agent" to USER_AGENT,
                        "Origin" to baseUrl, 
                        "Referer" to episodePageUrl 
                    )
                    
                    val extractorLink = newExtractorLink(
                        source = this.name, 
                        name = "AnimeVietsub" + (linkSource.label?.let { " - $it" } ?: ""), 
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
                    Log.e("AnimeVietsubProvider", "API giải mã không trả về URL M3U8/MP4 hợp lệ hoặc link không hợp lệ. Response/Link: '$finalStreamUrl'")
                }
            }
        } catch (e: Exception) {
            Log.e("AnimeVietsubProvider", "Lỗi trích xuất link API cho ID $episodeId (Data: $data)", e)
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
