package recloudstream

import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Element
import java.net.URLEncoder
import com.lagradost.cloudstream3.CommonActivity.showToast
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import android.widget.Toast
// Thêm import cho newExtractorLink
import com.lagradost.cloudstream3.utils.newExtractorLink
// Thêm import cho HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

// Data class chứa thông tin trích xuất từ halim_cfg bên trong loadLinks.
private data class ExtractedEpisodeInfo(
    val seriesPostId: String,
    val episodeSlug: String
)

// Data class cho JSON response từ player.php
private data class PlayerApiResponse(
    @JsonProperty("status") val status: Boolean?,
    @JsonProperty("file") val file: String?,    // Video URL M3U8 hoặc MP4
    @JsonProperty("label") val label: String?,  // Ví dụ: "1080"
    @JsonProperty("type") val type: String?,    // Ví dụ: "hls", "mp4"
    @JsonProperty("server_used") val serverUsed: String? // Tên server thực tế được sử dụng
)

class HoatHinh3DProvider : MainAPI() {
    // URL BITLY SẼ ĐƯỢC RESOLVE
    private val redirectorUrl = "https://bit.ly/hh3d"
    // DOMAIN DỰ PHÒNG NẾU RESOLVE LỖI
    private val fallbackDomain = "https://hoathinh3d.name"
    // Cờ để đảm bảo chỉ resolve 1 lần
    @Volatile // Đảm bảo an toàn cho đa luồng
    private var isDomainResolved = false

    // 'mainUrl' sẽ bắt đầu bằng domain dự phòng và được CẬP NHẬT sau khi resolve
    override var mainUrl = fallbackDomain 
    override var name = "HoatHinh3D"
    override val supportedTypes = setOf(TvType.Cartoon)
    override var lang = "vi"
    override val hasMainPage = true
    override val hasChromecastSupport = true
    override val hasDownloadSupport = true

    /**
     * Hàm này thực hiện resolve redirect từ 'redirectorUrl'
     * và cập nhật 'mainUrl' của class.
     * Nó được gọi ở đầu mỗi hàm public suspend.
     *
     * CẬP NHẬT: Đã loại bỏ throw và toast theo yêu cầu.
     */
    private suspend fun getBaseUrl() {
        if (isDomainResolved) return // Chỉ chạy một lần

        try {
            // Dùng HEAD request cho hiệu quả, app.head tự động follow redirect
            val response = app.head(redirectorUrl, allowRedirects = true)
            val finalUrl = response.url
            
            // Lấy scheme + host từ URL cuối cùng
            val resolved = finalUrl.toHttpUrlOrNull()?.let {
                "${it.scheme}://${it.host}"
            } // 'throw' đã bị xoá

            if (!resolved.isNullOrBlank() && resolved != redirectorUrl) {
                // Success
                mainUrl = resolved // Cập nhật 'mainUrl' của class
                isDomainResolved = true
            } else {
                // Logic failure (parse/redirect failed)
                // mainUrl vẫn là 'fallbackDomain'
                isDomainResolved = true // Đặt cờ để không thử lại
            }
        } catch (e: Exception) {
            // Network failure
            // Nếu lỗi, mainUrl vẫn là 'fallbackDomain'
            // 'Toast' đã bị xoá
            
            // Đặt cờ này để không thử lại (vì đã fallback)
            isDomainResolved = true 
        }
    }

    private fun Element.toSearchResponse(): SearchResponse? {
        val thumbLink = this.selectFirst("a.halim-thumb") ?: return null
        // fixUrl sẽ tự động sử dụng 'mainUrl' (đã được resolve)
        var href = fixUrl(thumbLink.attr("href"))
        href = href.replace(" ", "") 
        val title = this.selectFirst("div.halim-post-title h2.entry-title")?.text()?.trim()
            ?: thumbLink.attr("title")?.trim()
            ?: return null
        
        var posterUrl = fixUrlNull(this.selectFirst("figure img.img-responsive")?.attr("data-src"))
        if (posterUrl == null) {
            posterUrl = fixUrlNull(this.selectFirst("figure img.img-responsive")?.attr("src"))
        }
        val latestEpisodeText = this.selectFirst("span.episode")?.text()?.trim()

        return newAnimeSearchResponse(title, href, TvType.Cartoon) {
            this.posterUrl = posterUrl
            if (latestEpisodeText != null) {
                addQuality(latestEpisodeText)
            }
        }
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse? {
        getBaseUrl() // <-- GỌI HÀM RESOLVE DOMAIN

        withContext(Dispatchers.Main) {
            CommonActivity.activity?.let { activity ->
                showToast(activity, "Free Repo From H4RS", Toast.LENGTH_LONG)
            }
        }
        // 'mainUrl' ở đây đã là domain đã được resolve
        val url = if (page == 1) mainUrl else "$mainUrl/page/$page/"
        val effectiveUrl = if (page == 1 && !url.endsWith("/")) "$url/" else url

        val document = app.get(effectiveUrl).document
        val homePageList = ArrayList<HomePageList>()

        val newUpdateSection = document.selectFirst("div#sticky-sidebar div#-ajax-box div.halim_box")
                                ?: document.selectFirst("div#-ajax-box div.halim_box") 
        
        newUpdateSection?.let { section ->
            val sectionTitle = section.selectFirst("div.section-bar h3.section-title span")?.text()?.trim() ?: "Mới Cập Nhật"
            val movies = section.select("article.thumb.grid-item").mapNotNull {
                it.toSearchResponse()
            }
            if (movies.isNotEmpty()) {
                homePageList.add(HomePageList(sectionTitle, movies))
            }
        }
        
        val trendingSection = document.selectFirst("div.halim-trending-slider")
        trendingSection?.let { section ->
            val sectionTitle = section.selectFirst("div.section-bar h3.section-title span")?.text()?.trim() ?: "Đang Thịnh Hành"
            val movies = section.select("div.halim-trending-card").mapNotNull { card ->
                val linkTag = card.selectFirst("a.halim-trending-link")
                var href = fixUrlNull(linkTag?.attr("href"))
                href = href?.replace(" ", "") 
                val title = card.selectFirst("h3.halim-trending-title-text")?.text()?.trim()
                var posterUrl = fixUrlNull(card.selectFirst("img.halim-trending-poster-image")?.attr("data-src"))
                 if (posterUrl == null) {
                     posterUrl = fixUrlNull(card.selectFirst("img.halim-trending-poster-image")?.attr("src"))
                 }
                
                if (href != null && title != null) {
                    newAnimeSearchResponse(title, href, TvType.Cartoon) {
                        this.posterUrl = posterUrl
                    }
                } else {
                    null
                }
            }
            if (movies.isNotEmpty()) {
                homePageList.add(HomePageList(sectionTitle, movies)) 
            }
        }

        if (homePageList.isEmpty()) {
            return null
        }
        val hasNextPage = homePageList.any { it.list.isNotEmpty() }
        return newHomePageResponse(homePageList, hasNextPage)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        getBaseUrl() // <-- GỌI HÀM RESOLVE DOMAIN

        val searchUrl = "$mainUrl/search/$query" // 'mainUrl' đã được resolve
        val document = app.get(searchUrl).document

        return document.select("main#main-contents div.halim_box article.thumb.grid-item").mapNotNull {
            it.toSearchResponse()
        }
    }

    override suspend fun load(url: String): LoadResponse? {
        getBaseUrl() // <-- GỌI HÀM RESOLVE DOMAIN

        val document = app.get(url).document 

        val movieWrapper = document.selectFirst("div.halim-movie-wrapper.tpl-2.info-movie") ?: return null
        val title = movieWrapper.selectFirst("div.head h1.movie_name")?.text()?.trim() ?: return null
        
        var mainFilmPoster = fixUrlNull(movieWrapper.selectFirst("div.head div.first img")?.attr("data-src"))
        if (mainFilmPoster == null) {
             mainFilmPoster = fixUrlNull(movieWrapper.selectFirst("div.head div.first img")?.attr("src"))
        }
        
        val yearText = movieWrapper.selectFirst("div.last span.released a")?.text()?.trim()
        val year = yearText?.toIntOrNull()
        
        val description = document.selectFirst("meta[property=og:description]")?.attr("content")?.trim()
            ?: movieWrapper.selectFirst("div.entry-content div.wp-content")?.text()?.trim()
            ?: document.selectFirst("div.film-content div#info div.wp-content")?.text()?.trim()

        val tags = movieWrapper.select("div.last div.list_cate a").mapNotNull { it.text()?.trim() }
        
        val ratingScoreText = movieWrapper.selectFirst("div.last span.halim-rating-score")?.text()?.trim()
        val rating = ratingScoreText?.let {
            (it.toFloatOrNull())
        }

        val episodes = document.select("div#halim-list-server ul.halim-list-eps li.halim-episode").mapNotNull { epElement ->
            val epA = epElement.selectFirst("a") ?: return@mapNotNull null
            var epLink = fixUrl(epA.attr("href")) 
            epLink = epLink.replace(" ", "") 
            
            val epNameCandidate = epA.attr("title")?.takeIf { it.isNotBlank() } 
                ?: epA.selectFirst("span")?.text()?.trim()
                ?: "Tập ?"
            
            val epName = if (epNameCandidate.startsWith("Tập ", ignoreCase = true) || epNameCandidate.matches(Regex("^\\d+$"))) {
                if (epNameCandidate.matches(Regex("^\\d+$"))) "Tập $epNameCandidate" else epNameCandidate
            } else {
                "Tập $epNameCandidate"
            }
            
            if (epLink.isNotBlank()) {
                newEpisode(epLink) { 
                    this.name = epName
                }
            } else {
                null
            }
        }.reversed()
        
        val partLinkElements = document.select("ul#list-movies-part li.movies-part a")
        val partsListAsRecommendations = partLinkElements.mapNotNull { linkElement ->
            var partUrl = fixUrl(linkElement.attr("href"))
            partUrl = partUrl.replace(" ", "")
            var partTitle = linkElement.attr("title")?.trim()
            if (partTitle.isNullOrEmpty()) {
                partTitle = linkElement.text()?.trim()
            }

            var partSpecificPoster: String? = null
            if (partUrl.isNotBlank() && partUrl != url) { 
                try {
                    val partDocument = app.get(partUrl).document
                    val partMovieWrapper = partDocument.selectFirst("div.halim-movie-wrapper.tpl-2.info-movie")
                    partSpecificPoster = fixUrlNull(partMovieWrapper?.selectFirst("div.head div.first img")?.attr("data-src"))
                    if (partSpecificPoster == null) {
                        partSpecificPoster = fixUrlNull(partMovieWrapper?.selectFirst("div.head div.first img")?.attr("src"))
                    }
                } catch (_: Exception) { }
            }

            if (partUrl.isNotBlank() && !partTitle.isNullOrEmpty()) {
                newAnimeSearchResponse(partTitle, partUrl, TvType.Cartoon) {
                    this.posterUrl = partSpecificPoster ?: mainFilmPoster 
                }
            } else { null }
        }.take(10) 

        return newTvSeriesLoadResponse(title, url, TvType.Cartoon, episodes) {
            this.posterUrl = mainFilmPoster 
            this.year = year
            this.plot = description
            this.tags = tags
            this.score = rating?.let { Score.from10(it) }
            this.recommendations = partsListAsRecommendations 
        }
    }

    // --- HÀM LOADLINKS ĐƯỢC CẬP NHẬT VỚI DANH SÁCH SERVER ĐỊNH NGHĨA SẴN ---
    override suspend fun loadLinks(
        data: String, 
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        getBaseUrl() // <-- GỌI HÀM RESOLVE DOMAIN

        val episodePageUrl = data 
        // println("HoatHinh3DProvider: [loadLinks] Called with episodePageUrl: '$episodePageUrl'") 

        if (episodePageUrl.isBlank()) {
            // println("HoatHinh3DProvider: [loadLinks] episodePageUrl is blank.")
            return false
        }

        var extractedSeriesPostId: String? = null
        var extractedEpisodeSlug: String? = null

        try {
            val episodePageDocument = app.get(episodePageUrl).document
            episodePageDocument.select("script").forEach { script ->
                val scriptData = script.data()
                if (scriptData.contains("var halim_cfg")) {
                    extractedSeriesPostId = Regex("""["']post_id["']\s*:\s*["']?(\d+)["']?""").find(scriptData)?.groupValues?.get(1)
                    extractedEpisodeSlug = Regex("""["']episode_slug["']\s*:\s*["']?([^"']+)["']?""").find(scriptData)?.groupValues?.get(1)
                    if (extractedSeriesPostId != null && extractedEpisodeSlug != null) return@forEach
                }
            }
            
            if (extractedSeriesPostId.isNullOrBlank() || extractedEpisodeSlug.isNullOrBlank()) {
                // println("HoatHinh3DProvider: [loadLinks] Failed to extract critical info (seriesPostId or episodeSlug) from halim_cfg on episode page.")
                return false
            }
        } catch (e: Exception) { 
            // println("HoatHinh3DProvider: [loadLinks] Error fetching/parsing episode page: ${e.message}")
            return false 
        }
        
        // Danh sách các server (subsv_id và tên hiển thị) mà chúng ta muốn thử
        val serversToProcess = listOf(
            "" to "Default",    // Server "Default" với subsv_id rỗng
            "1" to "VIP 1",
            "2" to "VIP 2",
            "3" to "VIP 3"
        )
        
        var atLeastOneLinkFound = false 
        // 'mainUrl' ở đây đã là domain đã được resolve
        val playerPhpUrlBase = "$mainUrl/wp-content/themes/halimmovies/player.php"
        val fixedServerIdQueryParam = "1" // server_id trong URL của player.php luôn là "1"

        for ((currentSubSvid, serverDisplayName) in serversToProcess) {
            val queryParams = try {
                listOf(
                    "episode_slug" to extractedEpisodeSlug!!,
                    "server_id" to fixedServerIdQueryParam, 
                    "subsv_id" to currentSubSvid,       
                    "post_id" to extractedSeriesPostId!! 
                ).joinToString("&") { (key, value) -> "$key=$value" }
            } catch (e: Exception) { 
                // println("HoatHinh3DProvider: [loadLinks] Error building query for subsv_id '$currentSubSvid': ${e.message}")
                continue 
            }

            val urlToFetchApi = "$playerPhpUrlBase?$queryParams"
            // println("HoatHinh3DProvider: [loadLinks] Fetching API URL for $serverDisplayName: $urlToFetchApi")

            try {
                val apiResponseText = app.get(urlToFetchApi, referer = episodePageUrl).text
                if (apiResponseText.isBlank()) {
                    // println("HoatHinh3DProvider: [loadLinks] Blank response from API $urlToFetchApi")
                    continue 
                }

                val playerApiResponse = try { 
                    AppUtils.parseJson<PlayerApiResponse>(apiResponseText) 
                } catch (e: Exception) { 
                    // println("HoatHinh3DProvider: [loadLinks] JSON parse error for API response from $urlToFetchApi: ${e.message}")
                    // println("HoatHinh3DProvider: [loadLinks] Raw API response: $apiResponseText")
                    continue 
                }
                
                if (playerApiResponse.status == true && !playerApiResponse.file.isNullOrBlank()) {
                    val videoUrl = playerApiResponse.file 
                    val qualityLabel = playerApiResponse.label ?: "Chất lượng" 
                    val videoType = playerApiResponse.type?.lowercase() ?: ""
                    
                    // Sử dụng serverDisplayName từ list serversToProcess, 
                    // hoặc có thể dùng playerApiResponse.serverUsed nếu muốn tên server thực tế từ API
                    val finalServerName = serverDisplayName 

                    val qualityInt = qualityLabel.replace("p", "", ignoreCase = true).toIntOrNull() 
                                         ?: Qualities.Unknown.value

                    val linkType = if (videoType == "hls" || videoUrl.contains(".m3u8")) {
                        ExtractorLinkType.M3U8
                    } else if (videoType == "mp4") {
                        ExtractorLinkType.VIDEO 
                    } else {
                        ExtractorLinkType.VIDEO 
                    }

                    // =========================================================================
                    // FIX: (Line 315) Sử dụng newExtractorLink thay vì constructor cũ
                    // =========================================================================
                    val link = newExtractorLink(
                        source = finalServerName,
                        name = "$qualityLabel - $finalServerName",
                        url = videoUrl,
                        type = linkType
                    ) {
                        this.referer = episodePageUrl
                        this.quality = qualityInt
                        // headers không cần gán (mặc định là empty)
                    }
                    callback.invoke(link)

                    atLeastOneLinkFound = true 
                } else {
                    // println("HoatHinh3DProvider: [loadLinks] API for $serverDisplayName status not true or file link missing.")
                }
            } catch (e: Exception) {
                // println("HoatHinh3DProvider: [loadLinks] Exception for $serverDisplayName API call: ${e.message}")
            }
        } 
        return atLeastOneLinkFound
    }
}
