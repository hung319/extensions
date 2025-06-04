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
    private val ultimateFallbackDomain = "https://animevietsub.lol" // Đảm bảo đây là domain chính xác nếu bit.ly lỗi
    private var currentActiveUrl = bitlyResolverUrl 
    private var domainResolutionAttempted = false

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
                Log.w(name, "Bitly resolution did not lead to a valid different domain. Final URL: $finalUrlString. Current active: $currentActiveUrl")
                 // Nếu phân giải bit.ly vẫn ra bit.ly (có thể do cache hoặc lỗi tạm thời), và chưa thử fallback thì thử fallback
                if (finalUrlString.contains("bit.ly") && currentActiveUrl.contains("bit.ly") && urlToAttemptResolution != ultimateFallbackDomain) {
                    Log.w(name, "Bitly led to bitly. Attempting ultimate fallback: $ultimateFallbackDomain")
                    currentActiveUrl = ultimateFallbackDomain // Sửa ở đây: Gán currentActiveUrl để lần gọi getBaseUrl tiếp theo (nếu có đệ quy) dùng fallback
                    domainResolutionAttempted = false // Cho phép thử lại với ultimateFallbackDomain
                    return getBaseUrl() // Gọi lại để phân giải ultimateFallbackDomain
                }
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
            if (currentActiveUrl.contains("bit.ly") || (urlToAttemptResolution != ultimateFallbackDomain && currentActiveUrl != ultimateFallbackDomain) ) {
                 // Chỉ dùng fallback nếu URL hiện tại là bit.ly hoặc chưa từng thử fallback (và currentActiveUrl chưa phải là fallback)
                Log.w(name, "Domain resolution failed for '$urlToAttemptResolution'. Using fallback: $ultimateFallbackDomain")
                currentActiveUrl = ultimateFallbackDomain
            } else {
                Log.e(name, "All domain resolution attempts failed. Sticking with last known: $currentActiveUrl")
            }
        }
        return currentActiveUrl
    }
    
    // Helper function to parse pagination
    private fun Document.parseHasNext(currentPage: Int): Boolean {
        val pagenavi = this.selectFirst("div.wp-pagenavi") ?: return false

        var totalPages: Int? = null

        // 1. Try "Trang Cuối" link's data attribute
        val lastPageLinkData = pagenavi.selectFirst("a.page.larger[title~=Trang Cuối][data], a.last[data]")
        if (lastPageLinkData != null) {
            totalPages = lastPageLinkData.attr("data").toIntOrNull()
        }

        // 2. Try "Trang Cuối" link's href if data attribute failed
        if (totalPages == null) {
            val lastPageLinkHref = pagenavi.selectFirst("a.page.larger[title~=Trang Cuối][href], a.last[href]")
            if (lastPageLinkHref != null) {
                totalPages = lastPageLinkHref.attr("href")
                    ?.let { Regex("""(?:trang-|page[/-])(\d+)""").find(it)?.groupValues?.get(1)?.toIntOrNull() }
            }
        }
        
        // 3. Fallback: Try "span.pages" text like "Trang X của Y"
        if (totalPages == null) {
            totalPages = pagenavi.selectFirst("span.pages")?.text()
                ?.substringAfterLast("của ")?.trim()?.split(" ")?.firstOrNull()?.toIntOrNull()
        }

        // 4. Fallback: Find the maximum page number from all page links if other methods fail
        if (totalPages == null) {
            val pageNumbers = pagenavi.select("a.page[data], a.page[href], a.larger[data], a.larger[href]")
                .mapNotNull { link ->
                    link.attr("data").toIntOrNull() ?: link.attr("href")?.let { href ->
                        Regex("""(?:trang-|page[/-])(\d+)""").find(href)?.groupValues?.get(1)?.toIntOrNull()
                    }
                }
            if (pageNumbers.isNotEmpty()) {
                totalPages = pageNumbers.maxOrNull()
            }
        }
        
        // 5. Edge case: If only a "current" page span exists and no other 'a.page' links, assume it's the only page.
        if (totalPages == null && pagenavi.select("span.current").isNotEmpty() && pagenavi.select("a.page").isEmpty()) {
            totalPages = currentPage
        }
        
        return if (totalPages != null) currentPage < totalPages else false
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val baseUrl = getBaseUrl()
        val lists = mutableListOf<HomePageList>()
        // `request.key` (nếu có) sẽ là số trang tiếp theo dưới dạng String. Mặc định là `page` ban đầu (thường là 1).
        val pageToLoadForList = request.key?.toIntOrNull() ?: page 

        // Xử lý trường hợp yêu cầu tải trang tiếp theo cho một danh sách cụ thể
        if (pageToLoadForList > 1 && request.name.isNotBlank()) {
            val listName = request.name
            val urlToLoad: String
            // Selector chung cho các item trên các trang phân trang
            val itemsSelector = "ul.MovieList.Rows li.TPostMv, div.posts-list article, ul.list-film li"


            when (listName) {
                "Mới cập nhật" -> urlToLoad = "$baseUrl/anime-moi/trang-$pageToLoadForList.html"
                "Sắp chiếu" -> urlToLoad = "$baseUrl/anime-sap-chieu/trang-$pageToLoadForList.html"
                // Thêm các case khác nếu có danh sách khác cần phân trang
                else -> {
                    Log.w(name, "Pagination requested for unknown list or list not configured for pagination on page > 1: $listName")
                    return HomePageResponse(emptyList()) // Trả về rỗng nếu không phải danh sách hỗ trợ
                }
            }

            try {
                Log.d(name, "Loading $listName page $pageToLoadForList from $urlToLoad")
                val document = app.get(urlToLoad, referer = baseUrl).document // Thêm referer có thể hữu ích
                val items = document.select(itemsSelector)
                    .mapNotNull { it.toSearchResponse(this, baseUrl) }
                
                if (items.isEmpty() && pageToLoadForList > 1) {
                     Log.w(name, "No items found on $listName page $pageToLoadForList ($urlToLoad)")
                     return HomePageResponse(listOf(HomePageList(listName, emptyList(), nextKey = null)))
                }

                val hasNext = document.parseHasNext(pageToLoadForList)
                val nextKey = if (hasNext) (pageToLoadForList + 1).toString() else null
                
                // Trả về chỉ danh sách đang được phân trang
                return HomePageResponse(listOf(HomePageList(listName, items, nextKey = nextKey)))
            } catch (e: Exception) {
                Log.e(name, "Error loading page $pageToLoadForList for $listName from $urlToLoad: ${e.message}", e)
                return HomePageResponse(emptyList()) // Lỗi thì trả về rỗng
            }
        }

        // Nếu page == 1 (hoặc không có request.name cụ thể cho phân trang), tải các danh sách ban đầu
        
        // 1. Section: Mới cập nhật
        try {
            val moiCapNhatUrl = "$baseUrl/anime-moi/" // Trang gốc của "Mới cập nhật"
            Log.d(name, "Loading initial 'Mới cập nhật' from $moiCapNhatUrl")
            val moiCapNhatDoc = app.get(moiCapNhatUrl, referer = baseUrl).document
            val moiCapNhatItems = moiCapNhatDoc.select("ul.MovieList.Rows li.TPostMv, div.posts-list article, ul.list-film li")
                .mapNotNull { it.toSearchResponse(this, baseUrl) }
            if (moiCapNhatItems.isNotEmpty()) {
                val hasNext = moiCapNhatDoc.parseHasNext(1) // Trang hiện tại là 1
                lists.add(HomePageList("Mới cập nhật", moiCapNhatItems, nextKey = if (hasNext) "2" else null))
            } else {
                 Log.w(name, "No items found for 'Mới cập nhật' on $moiCapNhatUrl")
            }
        } catch (e: Exception) {
            Log.e(name, "Failed to load 'Mới cập nhật': ${e.message}")
        }

        // 2. Section: Sắp chiếu
        try {
            val sapChieuUrl = "$baseUrl/anime-sap-chieu/" // Trang gốc của "Sắp chiếu"
             Log.d(name, "Loading initial 'Sắp chiếu' from $sapChieuUrl")
            val sapChieuDoc = app.get(sapChieuUrl, referer = baseUrl).document
            val sapChieuItems = sapChieuDoc.select("ul.MovieList.Rows li.TPostMv, div.posts-list article, ul.list-film li")
                .mapNotNull { it.toSearchResponse(this, baseUrl) }
            if (sapChieuItems.isNotEmpty()) {
                val hasNext = sapChieuDoc.parseHasNext(1) // Trang hiện tại là 1
                lists.add(HomePageList("Sắp chiếu", sapChieuItems, nextKey = if (hasNext) "2" else null))
            } else {
                Log.w(name, "No items found for 'Sắp chiếu' on $sapChieuUrl")
            }
        } catch (e: Exception) {
            Log.e(name, "Failed to load 'Sắp chiếu': ${e.message}")
        }
        
        // 3. Section: Đề cử (Hot - từ trang chủ)
        try {
            // Chỉ tải trang chủ nếu các danh sách trên không lấy từ nó, hoặc cần danh sách này riêng
            // Nếu "Mới cập nhật" hoặc "Sắp chiếu" đã load từ baseUrl, có thể không cần tải lại mainDocument
            // Tuy nhiên, để rõ ràng, ta vẫn có thể lấy mainDocument cho các list chỉ có trên homepage
            Log.d(name, "Loading 'Đề cử' from main page: $baseUrl")
            val mainDocumentForHot = app.get(baseUrl).document 
            val hotItems = mainDocumentForHot.select("section#hot-home ul.MovieList.Rows li.TPostMv") // Selector đặc thù cho trang chủ
                .mapNotNull { it.toSearchResponse(this, baseUrl) }
            if (hotItems.isNotEmpty()) {
                val hotListName = mainDocumentForHot.selectFirst("section#hot-home div.Top a.STPb.Current")?.text() ?: "Đề cử"
                lists.add(HomePageList(hotListName, hotItems, nextKey = null)) // Giả sử không phân trang cho list này
            } else {
                 Log.w(name, "No items found for 'Đề cử' on main page.")
            }
        } catch (e: Exception) {
            Log.e(name, "Failed to load 'Đề cử' from main page: ${e.message}")
        }
        
        return HomePageResponse(lists.filter { it.list.isNotEmpty() }) // Chỉ trả về các danh sách có item
    }

    // ... (Phần còn lại của code: toSearchResponse, EpisodeData, getCountry, toLoadResponse, loadLinks, etc. giữ nguyên)


    private fun Element.toSearchResponse(provider: MainAPI, baseUrl: String): SearchResponse? {
        return try {
            val linkElement = this.selectFirst("article.TPost > a, div.TPost > a, div.item > a, h3.title-film > a") ?: return null
            var href = fixUrl(linkElement.attr("href"), baseUrl) ?: return null
            
            // Đảm bảo href là URL đầy đủ và trỏ đúng vào trang chi tiết phim
             if (!href.contains(Regex("""/\d+- фильм_\d+|phimmoi|phimle|\.html""")) && href.count { it == '-' } < 2 && !href.endsWith("/")) {
                 // Heuristic: if href is very short and not like a typical movie detail slug, it might be a category link.
                 // This is a guess, might need refinement based on actual "bad" hrefs.
                 val potentialMovieLink = this.selectFirst("a[href*=-]")?.attr("href") // Try to find a more specific link if current one is too generic
                 if (potentialMovieLink != null) href = fixUrl(potentialMovieLink, baseUrl) ?: return null
             }


            val titleFromElement = linkElement.attr("title").ifBlank { linkElement.selectFirst("h2.Title, h3.film-name, .film-title, .name, .title, p.name")?.text()?.trim() }?.takeIf { it.isNotBlank() } 
                ?: this.selectFirst("h2.Title, h3.film-name, .film-title, .name, .title, p.name")?.text()?.trim()?.takeIf { it.isNotBlank() }
                ?: return null // Nếu không có tiêu đề thì bỏ qua

            val imgElement = linkElement.selectFirst("div.Image img, div.thumbnail img, div.img-film img, figure.্টার-অ্যানিমেশন-হোম-পেইজ-এর-জন্য-পোস্টার-কার্ড-ডিজাইন img")
            val posterUrlRaw = imgElement?.let { img ->
                img.attr("data-src").ifBlank { img.attr("src") }
            }
            val posterUrl = fixUrl(posterUrlRaw, baseUrl)

            var finalTvType: TvType? = null
            val hasEpisodeSpan = this.selectFirst("span.mli-eps, .episode, .tag.Episode") != null || 
                                 (this.selectFirst(".ribbon-top-left span")?.text()?.contains(Regex("""\d+/\d+|\d+/\?""")) == true)

            val statusSpanText = this.selectFirst("span.mli-st, .status, .ribbon-top-left span")?.text() ?: ""


            if (titleFromElement.contains("OVA", ignoreCase = true) ||
                titleFromElement.contains("ONA", ignoreCase = true) ||
                titleFromElement.contains("Movie", ignoreCase = true) ||
                titleFromElement.contains("Phim Lẻ", ignoreCase = true) ||
                titleFromElement.contains("Đặc Biệt", ignoreCase = true) ||
                (!hasEpisodeSpan && statusSpanText.contains("Full", ignoreCase = true)) ||
                (!hasEpisodeSpan && statusSpanText.contains("Hoàn Tất", ignoreCase = true) && !hasEpisodeSpan) ||
                (statusSpanText.contains("Movie", ignoreCase = true) || statusSpanText.contains("Full", ignoreCase = true) && !hasEpisodeSpan)
            ) {
                finalTvType = if (provider.name.contains("Anime", ignoreCase = true) ||
                                (titleFromElement.contains("Anime", ignoreCase = true) &&
                                !titleFromElement.contains("Trung Quốc", ignoreCase = true) && 
                                !titleFromElement.contains("Donghua", ignoreCase = true)) ||
                                genres.any{it.contains("Anime", ignoreCase = true)} // Check genres from parent if available
                                ) {
                    TvType.AnimeMovie // More specific type
                } else {
                    TvType.Movie
                }
            } else if (hasEpisodeSpan || statusSpanText.contains(Regex("""Tập|Episode"""), ignoreCase = true) ) {
                 finalTvType = if (provider.name.contains("Anime", ignoreCase = true) || 
                                 titleFromElement.contains("Anime", ignoreCase = true) ||
                                 genres.any{it.contains("Anime", ignoreCase = true)}) {
                    TvType.Anime // Anime Series
                } else if (genres.any{it.contains("Hoạt Hình", ignoreCase = true) || it.contains("Cartoon", ignoreCase = true)}){
                    TvType.Cartoon // Cartoon Series
                }
                 else {
                    TvType.TvSeries 
                }
            }
            
            if (finalTvType == null) {
                finalTvType = if (hasEpisodeSpan || this.selectFirst("span.mli-quality, .quality") == null) TvType.Anime else TvType.Movie
            }
            // Default to Anime if type is AnimeMovie for search response consistency with supportedTypes
            val searchResponseType = if (finalTvType == TvType.AnimeMovie) TvType.Anime else finalTvType

            provider.newMovieSearchResponse(titleFromElement, href, searchResponseType ?: TvType.Anime) { 
                this.posterUrl = posterUrl
            }
        } catch (e: Exception) {
            Log.e(name, "Error parsing search result item: ${this.html().take(150)}... Error: ${e.message}", e)
            null
        }
    }

    data class EpisodeData(
        val url: String, 
        val dataId: String?, 
        val duHash: String?  
    )

    private fun Document.getCountry(): String? {
        return this.selectFirst("div.mvici-left li.AAIco-adjust:contains(Quốc gia) a, ul.InfoList li:has(strong:containsOwn(Quốc gia)) a, div.extra-info li:has(span:containsOwn(Quốc gia)) a")
            ?.attr("title")?.trim()?.takeIf { it.isNotBlank() }
            ?: this.selectFirst("div.mvici-left li.AAIco-adjust:contains(Quốc gia) a, ul.InfoList li:has(strong:containsOwn(Quốc gia)) a, div.extra-info li:has(span:containsOwn(Quốc gia)) a")
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
            val title = infoDoc.selectFirst("div.TPost.Single div.Title, h1.title, h1.film_title, span.title")?.text()?.trim()
                ?: infoDoc.selectFirst("meta[property=og:title]")?.attr("content")?.substringBefore(" Tập")?.trim()
                ?: run { Log.e(name, "Could not find title on info page $infoUrl"); return null }

            var posterUrlForResponse: String? = null
            var rawPosterUrl: String?
            
            val metaBigBanner = infoDoc.select("meta[property=og:image], meta[itemprop=image]")
                .mapNotNull { it.attr("content").takeIf { c -> c.isNotBlank() && c.contains("/data/big_banner/", ignoreCase = true) } }
                .firstOrNull()
            if (!metaBigBanner.isNullOrBlank()) {
                posterUrlForResponse = fixUrl(metaBigBanner, baseUrl)
            }

            if (posterUrlForResponse.isNullOrBlank()) {
                rawPosterUrl = infoDoc.selectFirst("div.TPostBg.Objf img.TPostBg, div.thumb.cover img.lazy")?.attr("src")
                if (!rawPosterUrl.isNullOrBlank() && rawPosterUrl.contains("/data/big_banner/", ignoreCase = true)) {
                    posterUrlForResponse = fixUrl(rawPosterUrl, baseUrl)
                }
            }
            
            if (posterUrlForResponse.isNullOrBlank()) {
                rawPosterUrl = infoDoc.selectFirst("div.TPost.Single div.Image figure.Objf img, div.poster img, div.thumb img")?.attr("src")
                if (!rawPosterUrl.isNullOrBlank()) {
                    posterUrlForResponse = fixUrl(rawPosterUrl, baseUrl)
                }
            }
             if (posterUrlForResponse.isNullOrBlank()) { 
                rawPosterUrl = infoDoc.selectFirst("div.TPost.Single div.Image img, img.film-poster")?.attr("src") // General image selector
                if (!rawPosterUrl.isNullOrBlank()) {
                    posterUrlForResponse = fixUrl(rawPosterUrl, baseUrl)
                }
            }

            if (posterUrlForResponse.isNullOrBlank()) {
                val metaImages = infoDoc.select("meta[property=og:image], meta[itemprop=image]")
                    .mapNotNull { it.attr("content").takeIf { c -> c.isNotBlank() } }
                    .distinct()

                if (metaImages.isNotEmpty()) {
                    rawPosterUrl = metaImages.firstOrNull { it.contains("/poster/", ignoreCase = true) && !it.contains("big_banner",true) } // Prefer non-banner poster
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

            val descriptionFromDiv = infoDoc.selectFirst("article.TPost.Single div.Description, div.film-content, div.detail div.content, section#info-film div.content")?.text()?.trim()
            val description = if (!descriptionFromDiv.isNullOrBlank()) descriptionFromDiv 
                                else infoDoc.selectFirst("meta[property=og:description]")?.attr("content")?.trim()

            val infoSection = infoDoc.selectFirst("div.Info, div.film_meta_info, div.extra-info") ?: infoDoc 
            val genres = infoSection.select("li:has(strong:containsOwn(Thể loại)) a[href*=the-loai], div.mvici-left li.AAIco-adjust:contains(Thể loại) a, div.extra-info li:has(span:containsOwn(Thể loại)) a, p.type.text-category a")
                .mapNotNull { it.text()?.trim() }.distinct()
            val yearText = infoSection.select("li:has(strong:containsOwn(Năm)) a, li:has(strong:containsOwn(Năm phát hành)) a, div.extra-info li:has(span:containsOwn(Năm sản xuất)) a")?.firstOrNull()?.text()?.trim()
                ?: infoDoc.selectFirst("p.Info span.Date a, span.year, p.released")?.text()?.trim() 
            val year = yearText?.filter { it.isDigit() }?.take(4)?.toIntOrNull()


            val ratingTextRaw = infoSection.select("li:has(strong:containsOwn(Điểm))")?.firstOrNull()?.ownText()?.trim()?.substringBefore("/")
                ?: infoDoc.selectFirst("div#star[data-score], div.starstruck[data-score]")?.attr("data-score")?.trim() 
                ?: infoDoc.selectFirst("input#score_current[value]")?.attr("value")?.trim() 
                ?: infoDoc.selectFirst("div.VotesCn div.post-ratings strong#average_score, span.user_score")?.text()?.trim() 

            var ratingValue: Int? = null
            if (ratingTextRaw != null) {
                val normalizedRatingText = ratingTextRaw.replace(",", ".").filter { it.isDigit() || it == '.' }
                val ratingDouble = normalizedRatingText.toDoubleOrNull()
                if (ratingDouble != null) {
                     if (ratingDouble <= 10) { // Assume scale of 10 (e.g. 8.5)
                        ratingValue = (ratingDouble * 1000).roundToInt().coerceIn(0, 10000)
                    } else if (ratingDouble <= 100) { // Assume scale of 100 (e.g. 85)
                         ratingValue = (ratingDouble * 100).roundToInt().coerceIn(0, 10000)
                    } else { // Assume already on 0-10000 scale or just use as is if very large
                        ratingValue = ratingDouble.roundToInt().coerceIn(0, 10000)
                    }
                }
            }
            
            val statusTextOriginal = infoSection.select("li:has(strong:containsOwn(Trạng thái))")?.firstOrNull()?.ownText()?.trim()
                ?: infoDoc.select("div.mvici-left li.AAIco-adjust:contains(Trạng thái), div.extra-info li:has(span:containsOwn(Trạng thái))")
                    .firstOrNull()?.ownText()?.trim()?.replace("Trạng thái:", "")?.trim()
            
            val parsedEpisodes = if (watchPageDoc != null) {
                Log.d(name, "Watch page document is not null. Processing episodes...")
                watchPageDoc.select("div.server ul.list-episode li a.btn-episode, div#episodes-list-content a, div.list_episodes a").mapNotNull { epLink ->
                    val epLinkHtml = epLink.outerHtml()
                    //Log.d(name, "Processing epLink HTML: $epLinkHtml")

                    val epUrl = fixUrl(epLink.attr("href"), baseUrl)
                    val epNameFull = epLink.attr("title").ifBlank { epLink.text() }.trim()
                    val dataId = epLink.attr("data-id").ifBlank { epLink.attr("data-value") }.ifBlank { null } // Added data-value
                    val duHash = epLink.attr("data-hash").ifBlank { null }

                   // Log.d(name, "Parsed attributes: epUrl=$epUrl, epNameFull='$epNameFull', dataId=$dataId, duHash=$duHash")

                    val episodeInfoForLoadLinks = EpisodeData(url = epUrl ?: infoUrl, dataId = dataId, duHash = duHash)

                    var episodeIntForSort: Int? = null 
                    var episodeStringForDisplay: String = epNameFull 

                    val numMatch = Regex("""(?:Tập\s+|Tap\s+|EP\s*)?(\d+(?:\.\d{1,2})?)""").find(epNameFull) // Updated regex for flexibility

                    if (numMatch != null) {
                        val numberStr = numMatch.groupValues[1] 
                       // Log.d(name, "Found numberStr: '$numberStr' in epNameFull: '$epNameFull'")

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

                        val titlePart = epNameFull.replaceFirst(Regex("""^(?:.*?[\s-])?(?:Tập\s+|Tap\s+|EP\s*)?${Regex.escape(numberStr)}\s*(?:-\s*)?""", RegexOption.IGNORE_CASE), "").trim()
                       // Log.d(name, "Parsed titlePart: '$titlePart'")
                        
                        var prefix = "Tập $numberStr" 
                        if (epNameFull.contains("OVA", ignoreCase = true) && mainEpisodeNum != null) { // Looser check for OVA/Special
                            prefix = "OVA $numberStr"
                        } else if (epNameFull.contains("Special", ignoreCase = true) && mainEpisodeNum != null) {
                            prefix = "Special $numberStr"
                        }

                        episodeStringForDisplay = if (titlePart.isNotEmpty() && titlePart.lowercase() != numberStr.lowercase() && titlePart.length > 3) { // Avoid short/numeric titlePart
                            "$prefix: $titlePart"
                        } else {
                            prefix
                        }
                    } else if (epNameFull.equals("Full", ignoreCase = true) || epNameFull.equals("Tập Full", ignoreCase = true)) {
                       // Log.d(name, "epNameFull is 'Full' or 'Tập Full': '$epNameFull'")
                        episodeStringForDisplay = "Tập Full"
                        episodeIntForSort = 1 
                    } else {
                       // Log.d(name, "No standard number pattern found for epNameFull: '$epNameFull'. Using full name for display.")
                    }

                   // Log.d(name, "Final parsed values: episodeIntForSort=$episodeIntForSort, episodeStringForDisplay='$episodeStringForDisplay'")

                    if ((dataId != null || duHash != null) && epUrl != null && episodeStringForDisplay.isNotBlank()) { // dataId OR duHash can be present
                       // Log.d(name, "All conditions met. Creating newEpisode for: name='${episodeStringForDisplay}', dataId='$dataId', duHash='$duHash'")
                        newEpisode(data = gson.toJson(episodeInfoForLoadLinks)) {
                            this.name = episodeStringForDisplay 
                            this.episode = null // *** Đặt lại thành null theo yêu cầu ***
                        }
                    } else {
                       // Log.w(name, "Skipping episode due to unmet conditions: dataId=$dataId, duHash=$duHash, epUrl=$epUrl, name='$episodeStringForDisplay'")
                        null
                    }
                }.sortedBy { it.episode ?: Int.MAX_VALUE } 
            } else {
                Log.w(name, "Watch page document is NULL. No episodes will be parsed.")
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
            
            val actorsDataList = infoDoc.select("div#MvTb-Cast ul.ListCast li a, div.entry-casts ul li a").mapNotNull { actorLinkElement -> // Added another selector
                val name = actorLinkElement.attr("title").removePrefix("Nhân vật ").ifBlank { actorLinkElement.text() }.trim() 
                val imageUrl = fixUrl(actorLinkElement.selectFirst("img")?.attr("src"), baseUrl)
                if (name.isNotBlank()) { ActorData(Actor(name, image = imageUrl), roleString = null) } else { null }
            }
            
            val recommendations = mutableListOf<SearchResponse>()
            infoDoc.select("div.Wdgt div.MovieListRelated.owl-carousel div.TPostMv, ul.related-list li, div.related-posts article").forEach { item -> // Added selectors
                try {
                    val linkElement = item.selectFirst("div.TPost > a, a") ?: return@forEach // Simpler 'a'
                    val recHref = fixUrl(linkElement.attr("href"), baseUrl)
                    val recTitle = linkElement.attr("title").ifBlank { linkElement.selectFirst("div.Title, h3.title, .post-title")?.text()?.trim() } // Added selectors
                    val recPosterUrlRaw = linkElement.selectFirst("div.Image img, img")?.let { img -> img.attr("data-src").ifBlank {img.attr("src")} } // Added selector
                    val recPosterUrl = fixUrl(recPosterUrlRaw, baseUrl)


                    if (recHref != null && recTitle != null) {
                        val isTvSeriesRec = linkElement.selectFirst("span.mli-eps, .episode-meta") != null || recTitle.contains("tập", ignoreCase = true) || linkElement.selectFirst("span.mli-quality, .resolution-meta") == null
                        val recTvType = if (isTvSeriesRec) TvType.TvSeries else TvType.Movie 
                        recommendations.add(provider.newMovieSearchResponse(recTitle, recHref, recTvType) { this.posterUrl = recPosterUrl })
                    }
                } catch (e: Exception) { 
                    // Log.e(name, "Error parsing recommendation item for $title: ${item.html().take(50)}", e) 
                }
            }

            var finalTvType: TvType? = null
            val country = infoDoc.getCountry()?.lowercase() 
            val hasAnimeLeTag = genres.any { it.equals("Anime lẻ", ignoreCase = true) } || statusTextOriginal?.contains("Anime lẻ", ignoreCase = true) == true
            
            val firstEpisodeNameLower = firstEpisodeOrNull?.name?.lowercase()
            
            val isSingleEpisodeActuallyMovie = episodesCount == 1 &&
                (
                    firstEpisodeNameLower == "tập full" ||
                    firstEpisodeNameLower == "full" ||
                    (firstEpisodeNameLower?.matches(Regex("""^(?:tập\s+|tap\s+|ep\s*)?0*1$""")) ?: false) // Updated regex
                )

            val isMovieHintFromTitle = title.contains("Movie", ignoreCase = true) || title.contains("Phim Lẻ", ignoreCase = true) || title.contains("Đặc Biệt", ignoreCase = true)
            
            val isJapaneseContext = country == "nhật bản" || country == "japan" ||
                                    (country == null && (title.contains("Anime", ignoreCase = true) || genres.any{ it.contains("Anime", ignoreCase = true) && !it.contains("Trung Quốc", ignoreCase = true)} ))

            if (title.contains("OVA", ignoreCase = true) || title.contains("ONA", ignoreCase = true) || (hasAnimeLeTag && episodesCount > 1 && isJapaneseContext) ) {
                finalTvType = TvType.Anime 
            }
            else if (isMovieHintFromTitle || (hasAnimeLeTag && episodesCount <= 1) || isSingleEpisodeActuallyMovie) {
                finalTvType = if (isJapaneseContext) TvType.AnimeMovie // More specific type
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
            
            Log.i(name, "Final TvType for '$title' ($infoUrl): $finalTvType. Country: $country, Episodes: $episodesCount, Status: $statusTextOriginal, Genres: $genres")

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
                // If it's AnimeMovie, use TvType.Anime for LoadResponse as per supportedTypes (AnimeMovie isn't a direct supported type)
                val actualMovieTvType = if (finalTvType == TvType.AnimeMovie) TvType.Anime else (finalTvType ?: TvType.Movie)
                
                val durationText = infoSection.select("li:has(strong:containsOwn(Thời lượng))")?.firstOrNull()?.ownText()?.trim()
                    ?: infoDoc.select("ul.InfoList li.AAIco-adjust:contains(Thời lượng), div.extra-info li:has(span:containsOwn(Thời lượng))") 
                        .firstOrNull()?.ownText()?.trim() 
                val durationMinutes = durationText?.filter { it.isDigit() }?.toIntOrNull()

                val movieDataForLoadLinks = if (parsedEpisodes.isNotEmpty()) {
                    val firstEpisode = parsedEpisodes.first() 
                    val firstEpisodeDataString = firstEpisode.data 
                        try {
                            val parsedEpisodeData = gson.fromJson(firstEpisodeDataString, EpisodeData::class.java)
                            if (parsedEpisodeData.dataId != null || parsedEpisodeData.duHash != null) { firstEpisodeDataString } 
                            else { 
                                val idAttempt = infoDoc.selectFirst("a.watch_button_more[href*=xem-phim], a.btn-see[href*=xem-phim]")?.attr("href")?.substringAfterLast("a")?.substringBefore("/") ?: infoUrl.substringAfterLast("/").substringBefore("-").filter{it.isDigit()}.ifEmpty { infoUrl.substringAfterLast("-").filter{it.isDigit()} }
                                gson.toJson(EpisodeData(url = infoUrl, dataId = idAttempt.ifBlank { null }, duHash = null))
                            }
                        } catch (e: Exception) { 
                             val idAttempt = infoDoc.selectFirst("a.watch_button_more[href*=xem-phim], a.btn-see[href*=xem-phim]")?.attr("href")?.substringAfterLast("a")?.substringBefore("/") ?: infoUrl.substringAfterLast("/").substringBefore("-").filter{it.isDigit()}.ifEmpty { infoUrl.substringAfterLast("-").filter{it.isDigit()} }
                             gson.toJson(EpisodeData(url = infoUrl, dataId = idAttempt.ifBlank { null }, duHash = null))
                        }
                } else {
                    val idAttempt = infoDoc.selectFirst("a.watch_button_more[href*=xem-phim], a.btn-see[href*=xem-phim]")?.attr("href")?.substringAfterLast("a")?.substringBefore("/") ?: infoUrl.substringAfterLast("/").substringBefore("-").filter{it.isDigit()}.ifEmpty { infoUrl.substringAfterLast("-").filter{it.isDigit()} }
                    gson.toJson(EpisodeData(url = infoUrl, dataId = idAttempt.ifBlank { null }, duHash = null))
                }

                provider.newMovieLoadResponse(title, infoUrl, actualMovieTvType, movieDataForLoadLinks) {
                    this.posterUrl = posterUrlForResponse; this.plot = description; this.tags = genres; this.year = year; this.rating = ratingValue; durationMinutes?.let { addDuration(it.toString()) };
                    this.actors = actorsDataList; this.recommendations = recommendations
                }
            }

        } catch (e: Exception) {
            Log.e(name, "Lỗi trong toLoadResponse xử lý cho url: $infoUrl", e); return null
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
        val ajaxUrl = "$baseUrl/ajax/player?v=2019a" // Hoặc endpoint tương tự
        val decryptApiUrl = "https://m3u8.013666.xyz/animevietsub/decrypt" // Cập nhật nếu cần
        val textPlainMediaType = "text/plain".toMediaTypeOrNull()

        val episodeData = try {
            gson.fromJson(data, EpisodeData::class.java)
        } catch (e: Exception) {
            Log.e(name, "Không thể parse EpisodeData JSON: '$data'", e); return false
        }

        val episodePageUrl = episodeData.url 
        val episodeId = episodeData.dataId     
        val episodeHash = episodeData.duHash     

        if ((episodeId == null && episodeHash == null) || episodePageUrl.isBlank()) { // Cần ít nhất id hoặc hash
            Log.e(name, "Thiếu ID/Hash tập phim hoặc URL trang (episodeData.url): $data"); return false
        }

        try {
            val postData = mutableMapOf("play" to "api")
            episodeId?.let { postData["id"] = it }
            episodeHash?.let { postData["link"] = it } // "link" thường là hash


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
                Log.e(name, "Lỗi parse JSON từ ajax/player: ${ajaxResponse.text}", e); null
            }

            if (playerResponse?.success != 1 || playerResponse.link.isNullOrEmpty()) {
                Log.e(name, "Request ajax/player thất bại hoặc không có link: ${ajaxResponse.text}"); return false
            }

            playerResponse.link.forEach { linkSource ->
                val dataEncOrDirectLink = linkSource.file 
                if (dataEncOrDirectLink.isNullOrBlank()) {
                    Log.w(name, "Link source 'file' rỗng cho ID $episodeId, label ${linkSource.label}")
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
                        this.quality = Qualities.Unknown.value // Hoặc parse từ label nếu có thể
                        this.headers = streamHeaders 
                    }
                    callback(extractorLink)
                    foundLinks = true
                } else {
                    Log.e(name, "API giải mã không trả về URL M3U8/MP4 hợp lệ hoặc link không hợp lệ. Response/Link: '$finalStreamUrl'")
                }
            }
        } catch (e: Exception) {
            Log.e(name, "Lỗi trích xuất link API cho ID $episodeId (Data: $data)", e)
        }
        return foundLinks
    }

    private fun String?.encodeUri(): String {
        if (this == null) return ""
        return try { URLEncoder.encode(this, "UTF-8").replace("+", "%20") }
        catch (e: Exception) { Log.e(name, "Lỗi URL encode: $this", e); this }
    }
    
    private fun Double?.toAnimeVietsubRatingInt(): Int? = this?.takeIf { !it.isNaN() }?.let { (it * 1000).roundToInt().coerceIn(0, 10000) }

    private fun fixUrl(url: String?, baseUrl: String): String? {
        if (url.isNullOrBlank()) return null
        // Kiểm tra nếu url đã là một URL đầy đủ hợp lệ
        if (url.startsWith("http://") || url.startsWith("https://")) {
            try {
                URL(url).toURI() // Thử parse để kiểm tra tính hợp lệ
                return url
            } catch (e: Exception) {
                // URL có http nhưng không hợp lệ, có thể cần xử lý thêm hoặc bỏ qua
                 Log.w(name, "Malformed full URL: $url - Proceeding with caution or fix if possible.")
                 // Không return null ngay, để logic bên dưới thử sửa
            }
        }


        return try {
            when {
                url.startsWith("//") -> "https:$url" 
                url.startsWith("/") -> URL(URL(baseUrl), url).toString() 
                // Nếu không phải là URL đầy đủ và không bắt đầu bằng /, thử nối trực tiếp với baseUrl (nếu baseUrl là domain gốc)
                // Điều này cần cẩn thận vì baseUrl có thể chứa path
                !url.startsWith("http") && baseUrl.let {val u = URL(it); u.path.isEmpty() || u.path == "/"} -> URL(URL(baseUrl), "/${url.trimStart('/')}").toString()
                !url.startsWith("http") -> URL(URL(baseUrl), url).toString() // Trường hợp tổng quát cho relative path
                else -> url // Đã là URL đầy đủ từ đầu (sau khi check ở trên)
            }
        } catch (e: java.net.MalformedURLException) {
            Log.e(name, "URL không hợp lệ khi fix: base='$baseUrl', url='$url'", e)
            if (url.startsWith("http")) return url; null 
        } catch (e: Exception) {
            Log.e(name, "Lỗi không xác định khi fix URL: base='$baseUrl', url='$url'", e); null
        }
    }
}
