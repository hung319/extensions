package recloudstream 

// Import các thư viện cần thiết
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Element
// Import CloudflareKiller nếu cần
// import com.lagradost.cloudstream3.network.CloudflareKiller
import com.lagradost.cloudstream3.newTvSeriesLoadResponse
import com.lagradost.cloudstream3.newMovieLoadResponse
import com.lagradost.cloudstream3.utils.newExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.newMovieSearchResponse // Helper
import com.lagradost.cloudstream3.newTvSeriesSearchResponse // Helper
import com.lagradost.cloudstream3.newHomePageResponse
import com.lagradost.cloudstream3.newEpisode
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.mvvm.logError
import com.lagradost.cloudstream3.mvvm.normalSafeApiCall
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import com.lagradost.cloudstream3.utils.Qualities
import java.net.URLEncoder
import android.util.Log

// Data classes for Kitsu API response parsing
data class KitsuMain(val data: List<KitsuData>?)
data class KitsuData(val attributes: KitsuAttributes?)
data class KitsuAttributes(
    val canonicalTitle: String?,
    val posterImage: KitsuPoster?
    )
data class KitsuPoster(
    val original: String?,
    val large: String?,
    val medium: String?,
    val small: String?,
    val tiny: String?
    )


class BoctemProvider : MainAPI() {
    override var mainUrl = "https://boctem.com"
    override var name = "Boctem"
    override val supportedTypes = setOf( TvType.Anime, TvType.Cartoon )
    override var lang = "vi"
    override val hasMainPage = true
    override val hasChromecastSupport = true
    override val hasDownloadSupport = true

    // Hàm helper để thử xóa kích thước khỏi URL ảnh
    private fun getOriginalImageUrl(thumbnailUrl: String?): String? {
        if (thumbnailUrl.isNullOrBlank()) return null
        val imageSizeRegex = Regex("""(-\d+x\d+)(\.\w+)$""")
        return imageSizeRegex.replace(thumbnailUrl, "$2")
    }

     // === HÀM LẤY POSTER TỪ KITSU ===
     private suspend fun getKitsuPoster(title: String): String? {
         Log.d("Boctem", "Searching Kitsu for: $title")
         return try {
             val searchUrl = "https://kitsu.io/api/edge/anime?filter[text]=${URLEncoder.encode(title, "UTF-8")}&page[limit]=1"
             val response = app.get(searchUrl).parsedSafe<KitsuMain>()
             val poster = response?.data?.firstOrNull()?.attributes?.posterImage
             val kitsuPosterUrl = poster?.original ?: poster?.large ?: poster?.medium ?: poster?.small ?: poster?.tiny
             if (!kitsuPosterUrl.isNullOrBlank()) { Log.d("Boctem", "Found Kitsu poster: $kitsuPosterUrl") }
             else { Log.w("Boctem", "Kitsu poster not found for '$title'") }
             kitsuPosterUrl
         } catch (e: Exception) { Log.e("Boctem", "Kitsu API Error for title '$title': ${e.message}"); null }
     }


    // --- Hàm getMainPage (Sửa tham số SearchResponse Helpers) ---
     override suspend fun getMainPage(page: Int, request : MainPageRequest): HomePageResponse? {
        val items = mutableListOf<HomePageList>()
        if (page > 1) return null
        try {
            val document = app.get(mainUrl).document
            // Carousel
            try {
                val carouselSection = document.select("div#halim-carousel-widget-3xx").firstOrNull()
                val carouselTitle = carouselSection?.selectFirst("h3.section-title span")?.text() ?: "Anime Vietsub Mới Nhất"
                val movieElements = carouselSection?.select("div#halim-carousel-widget-3 div.owl-item:not(.cloned) article.thumb")
                if (movieElements != null && movieElements.isNotEmpty()) {
                    val movies = movieElements.mapNotNull { element ->
                        val href = element.selectFirst("a.halim-thumb")?.attr("href")
                        val title = element.selectFirst("h2.entry-title")?.text()
                        val originalTitle = element.selectFirst("p.original_title")?.text()
                        val posterThumbnail = element.selectFirst("figure img.lazy")?.attr("data-src")
                        val boctemPoster = getOriginalImageUrl(posterThumbnail)
                        if (href.isNullOrBlank() || title.isNullOrBlank()) return@mapNotNull null
                        val tvType = if (href.contains("/hoat-hinh-trung-quoc/", ignoreCase = true)) TvType.Cartoon else TvType.Anime
                        val finalPoster = if (tvType == TvType.Anime) { getKitsuPoster(originalTitle ?: title) ?: boctemPoster } else { boctemPoster }
                        // **Sửa lỗi:** Bỏ this.name, truyền đúng TvType
                        newTvSeriesSearchResponse( title, href, tvType ) { this.posterUrl = finalPoster }
                    }
                    if (movies.isNotEmpty()) items.add(HomePageList(carouselTitle, movies))
                }
            } catch (e: Exception) { Log.e("Boctem", "Error parsing Carousel: ${e.message}") }
            // Grid
            try {
                val gridSection = document.select("section#halim-advanced-widget-2").firstOrNull()
                val gridTitle = gridSection?.selectFirst("span.h-text")?.text() ?: "Anime Vừa Cập Nhật"
                val movieElements = gridSection?.select("div#halim-advanced-widget-2-ajax-box article.thumb")
                if (movieElements != null && movieElements.isNotEmpty()) {
                     val movies = movieElements.mapNotNull { element ->
                        val href = element.selectFirst("a.halim-thumb")?.attr("href")
                        val title = element.selectFirst("h2.entry-title")?.text()
                        val posterThumbnail = element.selectFirst("figure img.lazy")?.attr("data-src")
                        val poster = getOriginalImageUrl(posterThumbnail)
                        if (href.isNullOrBlank() || title.isNullOrBlank()) return@mapNotNull null
                         val tvType = if (href.contains("/hoat-hinh-trung-quoc/", ignoreCase = true)) TvType.Cartoon else TvType.Anime
                         // **Sửa lỗi:** Bỏ this.name, truyền đúng TvType
                         newTvSeriesSearchResponse( title, href, tvType ) { this.posterUrl = poster }
                    }
                     if (movies.isNotEmpty()) items.add(HomePageList(gridTitle, movies))
                }
            } catch (e: Exception) { Log.e("Boctem", "Error parsing Grid: ${e.message}") }
             // Popular
             try {
                 val popularSection = document.select("div#halim_tab_popular_videos-widget-2").firstOrNull()
                 val popularItems = popularSection?.select("div.tab-pane.active div.item")
                 if (popularItems != null && popularItems.isNotEmpty()) {
                     val popularMovies = popularItems.mapNotNull { item ->
                         val href = item.selectFirst("a")?.attr("href")
                         val title = item.selectFirst("h3.title")?.text()
                         val posterThumbnail = item.selectFirst("img.lazy")?.let { it.attr("data-src").ifBlank { it.attr("src") } }
                         val poster = getOriginalImageUrl(posterThumbnail)
                         if (href.isNullOrBlank() || title.isNullOrBlank()) return@mapNotNull null
                          val tvType = if (href.contains("/hoat-hinh-trung-quoc/", ignoreCase = true)) TvType.Cartoon else TvType.Anime
                         // **Sửa lỗi:** Bỏ this.name, truyền đúng TvType
                         newTvSeriesSearchResponse( title, href, tvType ) { this.posterUrl = poster }
                     }
                     if (popularMovies.isNotEmpty()) {
                        val popularTitle = popularSection.selectFirst("ul.halim-popular-tab li.active a")?.text()?.let { "Nổi bật ($it)" } ?: "Nổi bật (Ngày)"
                        items.add(HomePageList(popularTitle, popularMovies))
                     }
                 }
             } catch (e: Exception) { Log.e("Boctem", "Error parsing Popular Section: ${e.message}") }

            if (items.isEmpty()) return null
            return newHomePageResponse(items) // Đã sửa
        } catch (e: Exception) { logError(e); return null }
    }

    // --- Hàm search (Sửa tham số SearchResponse Helpers) ---
     override suspend fun search(query: String): List<SearchResponse>? {
        val searchUrl = "$mainUrl/search/$query/"
        Log.d("Boctem", "Searching URL: $searchUrl")
        return try {
            val document = app.get(searchUrl).document
            val resultsContainer = document.select("div.halim_box").firstOrNull()
            resultsContainer?.select("article.thumb.grid-item")?.mapNotNull { element ->
                val href = element.selectFirst("a.halim-thumb")?.attr("href")
                val title = element.selectFirst("h2.entry-title")?.text()
                val posterThumbnail = element.selectFirst("figure img.lazy")?.attr("data-src")
                val poster = getOriginalImageUrl(posterThumbnail)
                if (href.isNullOrBlank() || title.isNullOrBlank()) return@mapNotNull null
                 val tvType = if (href.contains("/hoat-hinh-trung-quoc/", ignoreCase = true)) TvType.Cartoon else TvType.Anime
                 // **Sửa lỗi:** Bỏ this.name, truyền đúng TvType
                 newTvSeriesSearchResponse( title, href, tvType ) { this.posterUrl = poster }
            }
        } catch (e: Exception) { logError(e); null }
    }

    // --- Hàm load (Sửa tham số SearchResponse Helpers trong recommendations) ---
     override suspend fun load(url: String): LoadResponse? {
         Log.d("Boctem", "Loading URL: $url")
         return try {
            val document = app.get(url).document
            val title = document.selectFirst("div.movie-detail h1.entry-title")?.text() ?: ""
            val originalTitle = document.selectFirst("p.org_title")?.text()
            val posterThumbnail = document.selectFirst("div.movie-poster img.movie-thumb")?.let { it.attr("data-src").ifBlank { it.attr("src") } }
            val boctemPoster = getOriginalImageUrl(posterThumbnail)
            val plot = document.selectFirst("article.item-content")?.select("p")?.joinToString("\n\n") { it.text() }?.trim() ?: ""
            val year = document.selectFirst("p.released span.title-year a")?.text()?.toIntOrNull()
            val tags = document.select("p.category a")?.mapNotNull { it?.text() }
             val isDonghua = tags?.any { it.contains("Hoạt hình Trung Quốc", ignoreCase = true) } == true
             val primaryTvType = if (isDonghua) TvType.Cartoon else TvType.Anime
             val finalPosterUrl = if (primaryTvType == TvType.Anime) { getKitsuPoster(originalTitle?.takeIf { it.isNotBlank() } ?: title) ?: boctemPoster } else { boctemPoster }
            val episodeListElement = document.selectFirst("div#halim-list-server ul.halim-list-eps")

            // Parse recommendations (Sửa tham số SearchResponse Helpers)
            val recommendations = document.select("div#halim_related_movies-2 article.thumb").mapNotNull { recElement ->
                 val recLink = recElement.selectFirst("a.halim-thumb")?.attr("href")
                 val recTitle = recElement.selectFirst("h2.entry-title")?.text()
                 val recPosterThumbnail = recElement.selectFirst("figure img.lazy")?.attr("data-src")
                 val recPoster = getOriginalImageUrl(recPosterThumbnail)
                 if (recLink.isNullOrBlank() || recTitle.isNullOrBlank()) return@mapNotNull null
                 val recType = if (recLink.contains("/hoat-hinh-trung-quoc/", ignoreCase = true)) TvType.Cartoon else TvType.Anime
                 // **Sửa lỗi:** Bỏ this.name, truyền đúng TvType
                 newTvSeriesSearchResponse(recTitle, recLink, recType) { this.posterUrl = recPoster }
            }

            if (episodeListElement != null) { // Series
                val episodes = mutableListOf<Episode>()
                episodeListElement.select("li.halim-episode a")?.forEach { element ->
                    val epHref = element.attr("href"); val epName = element.selectFirst("span")?.text(); val epNum = epName?.toIntOrNull() ?: 1
                    if (epHref.isNotBlank()) {
                        // **Sửa lỗi:** Dùng newEpisode
                        episodes.add( newEpisode(data = "$epHref|$epNum") {
                            this.name = "Tập $epName"
                            this.episode = null
                        } )
                    }
                }
                newTvSeriesLoadResponse(title, url, primaryTvType, episodes) {
                    this.posterUrl = finalPosterUrl; this.year = year; this.plot = plot; this.tags = tags; this.recommendations = recommendations
                }
            } else { // Movie
                newMovieLoadResponse(title, url, primaryTvType, url) {
                    this.posterUrl = finalPosterUrl; this.year = year; this.plot = plot; this.tags = tags; this.recommendations = recommendations
                }
            }
         } catch (e: Exception) { logError(e); null }
     }

    // --- Hàm loadLinks (CHỈ DÙNG AJAX - Giữ nguyên) ---
    override suspend fun loadLinks(
        data: String, // Định dạng "URL|EpisodeNum"
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val parts = data.split('|')
        val watchUrl = parts[0]
        val episodeNum = parts.getOrNull(1)?.toIntOrNull() ?: 1
        Log.d("Boctem", "loadLinks called for watchUrl: $watchUrl, episode: $episodeNum (AJAX ONLY)")

        val document = try { app.get(watchUrl, referer = mainUrl).document }
                       catch (e: Exception) { Log.e("Boctem", "Failed to GET watch page $watchUrl: ${e.message}"); return false }

        var nonce: String? = null
        var postId: String? = null
        try {
            val nonceRegex = Regex("""ajax_player\s*=\s*\{\s*.*?\s*"nonce"\s*:\s*"([^"]+)"\s*.*?\}""", RegexOption.DOT_MATCHES_ALL)
            nonce = nonceRegex.find(document.html())?.groupValues?.getOrNull(1)
            postId = document.selectFirst("div#bookmark[data-id]")?.attr("data-id")
            if (postId.isNullOrBlank()) {
                 val postIdRegex = Regex("""halim_cfg\s*=\s*\{\s*.*?\s*"post_id"\s*:\s*(\d+)\s*.*?\}""", RegexOption.DOT_MATCHES_ALL)
                postId = postIdRegex.find(document.html())?.groupValues?.getOrNull(1)
            }
        } catch (e: Exception) { Log.e("Boctem", "Error extracting nonce/postId: ${e.message}") }

        val tikEpLink = "666" // Server TIK

        if (!nonce.isNullOrBlank() && !postId.isNullOrBlank()) {
            val ajaxUrl = "$mainUrl/wp-admin/admin-ajax.php"
            val postData = mapOf( "action" to "halim_ajax_player", "nonce" to nonce, "episode" to episodeNum.toString(), "server" to "1", "postid" to postId, "ep_link" to tikEpLink )
            val headers = mapOf( "Content-Type" to "application/x-www-form-urlencoded; charset=UTF-8", "X-Requested-With" to "XMLHttpRequest", "Referer" to watchUrl )
            Log.d("Boctem", "Attempting AJAX POST -> Tik Server (ep_link=$tikEpLink)")

            try {
                val ajaxResponseText = app.post(ajaxUrl, headers = headers, data = postData).text
                Log.d("Boctem", "AJAX Response (first 500 chars): ${ajaxResponseText.take(500)}")
                val scriptRegex = Regex("""playerInstance\.setup\(\s*\{.*?file\s*:\s*["']\s*(https?://[^"']+\.m3u8[^"']*)?\s*["'].*?\}\s*\);?""", RegexOption.DOT_MATCHES_ALL)
                val match = scriptRegex.find(ajaxResponseText)
                val m3u8Link = match?.groups?.get(1)?.value?.trim()

                if (!m3u8Link.isNullOrBlank()) {
                     Log.d("Boctem", "Extracted M3U8 link from AJAX response: $m3u8Link")
                     callback.invoke( newExtractorLink( source = this.name, name = "${this.name}", url = m3u8Link, type = ExtractorLinkType.M3U8 ) { this.referer = watchUrl } )
                     Log.d("Boctem", "M3U8 link (from AJAX TIK) submitted successfully.")
                     return true
                } else { Log.w("Boctem", "Could not find M3U8 link in AJAX TIK response.") }
            } catch (e: Exception) { Log.e("Boctem", "AJAX POST request for TIK server failed: ${e.message}"); e.printStackTrace() }
        } else { Log.e("Boctem", "Failed to extract nonce or postid for AJAX. Cannot fetch link.") }

        Log.e("Boctem", "Failed to get link via AJAX for $watchUrl (Ep: $episodeNum). No fallbacks.")
        return false
    }

} // Kết thúc class BoctemProvider
