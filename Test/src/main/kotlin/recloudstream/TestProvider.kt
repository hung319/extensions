package com.example.HoatHinh3DProvider

import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Element
import java.net.URLEncoder

// Data class này không còn dùng để parse Episode.data nữa, 
// vì Episode.data giờ chỉ là URL string.
// Nó sẽ được dùng để chứa thông tin parse được từ halim_cfg bên trong loadLinks.
private data class ExtractedEpisodeInfo(
    val seriesPostId: String,
    val episodeSlug: String,
    val defaultServerForPage: String // server (subsv_id) mặc định của trang xem phim
)

private data class PlayerApiResponse(
    @JsonProperty("status") val status: Boolean?,
    @JsonProperty("file") val file: String?,
    @JsonProperty("label") val label: String?,
    @JsonProperty("type") val type: String?,
    @JsonProperty("server_used") val serverUsed: String?
)

class HoatHinh3DProvider : MainAPI() {
    override var mainUrl = "https://hoathinh3d.name"
    override var name = "HoatHinh3D"
    override val supportedTypes = setOf(TvType.Cartoon)
    override var lang = "vi"
    override val hasMainPage = true
    override val hasChromecastSupport = true
    override val hasDownloadSupport = true

    private fun Element.toSearchResponse(): SearchResponse? {
        val thumbLink = this.selectFirst("a.halim-thumb") ?: return null
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
        val searchUrl = "$mainUrl/search/$query"
        val document = app.get(searchUrl).document

        return document.select("main#main-contents div.halim_box article.thumb.grid-item").mapNotNull {
            it.toSearchResponse()
        }
    }

    // --- HÀM LOAD ĐƯỢC ĐƠN GIẢN HÓA ---
    override suspend fun load(url: String): LoadResponse? {
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
            (it.toFloatOrNull()?.times(1000))?.toInt()
        }

        val episodes = document.select("div#halim-list-server ul.halim-list-eps li.halim-episode").mapNotNull { epElement ->
            val epA = epElement.selectFirst("a") ?: return@mapNotNull null
            var epLink = fixUrl(epA.attr("href")) 
            epLink = epLink.replace(" ", "") // Làm sạch khoảng trắng khỏi epLink
            
            val epNameCandidate = epA.attr("title")?.takeIf { it.isNotBlank() } 
                ?: epA.selectFirst("span")?.text()?.trim()
                ?: "Tập ?"
            
            val epName = if (epNameCandidate.startsWith("Tập ", ignoreCase = true) || epNameCandidate.matches(Regex("^\\d+$"))) {
                if (epNameCandidate.matches(Regex("^\\d+$"))) "Tập $epNameCandidate" else epNameCandidate
            } else {
                "Tập $epNameCandidate"
            }

            // Giờ đây, Episode.data sẽ chỉ là epLink
            if (epLink.isNotBlank()) {
                newEpisode(epLink) { // data giờ là URL của trang xem tập phim
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
            this.rating = rating
            this.recommendations = partsListAsRecommendations 
        }
    }

    // --- HÀM LOADLINKS ĐƯỢC CẬP NHẬT ĐỂ LẤY THÔNG TIN TỪ TRANG XEM PHIM ---
    override suspend fun loadLinks(
        data: String, // data giờ là URL của trang xem tập phim (epLink)
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val episodePageUrl = data // data chính là episodePageUrl
        // println("HoatHinh3DProvider: [loadLinks] Called with episodePageUrl: '$episodePageUrl'") 

        if (episodePageUrl.isBlank()) {
            // println("HoatHinh3DProvider: [loadLinks] episodePageUrl is blank.")
            return false
        }

        var extractedInfo: ExtractedEpisodeInfo? = null
        try {
            val episodePageDocument = app.get(episodePageUrl).document
            var seriesPostId: String? = null
            var currentEpisodeSlug: String? = null
            var defaultServer: String? = null // server (subsv_id) mặc định của trang

            episodePageDocument.select("script").forEach { script ->
                val scriptData = script.data()
                if (scriptData.contains("var halim_cfg")) {
                    // println("HoatHinh3DProvider: [loadLinks] Found halim_cfg on episode page.")
                    seriesPostId = Regex("""["']post_id["']\s*:\s*["']?(\d+)["']?""").find(scriptData)?.groupValues?.get(1)
                    currentEpisodeSlug = Regex("""["']episode_slug["']\s*:\s*["']?([^"']+)["']?""").find(scriptData)?.groupValues?.get(1)
                    defaultServer = Regex("""["']server["']\s*:\s*["']?(\d*?)["']?""").find(scriptData)?.groupValues?.get(1) // Cho phép server rỗng
                    // Thoát sớm nếu đã tìm thấy đủ thông tin
                    if (seriesPostId != null && currentEpisodeSlug != null && defaultServer != null) return@forEach
                }
            }
            
            // println("HoatHinh3DProvider: [loadLinks] From halim_cfg on episode page - seriesPostId: $seriesPostId, episodeSlug: $currentEpisodeSlug, defaultServer: $defaultServer")

            if (seriesPostId.isNullOrBlank() || currentEpisodeSlug.isNullOrBlank() || defaultServer == null) { // defaultServer có thể rỗng ""
                // println("HoatHinh3DProvider: [loadLinks] Failed to extract critical info from halim_cfg on episode page.")
                return false
            }
            extractedInfo = ExtractedEpisodeInfo(seriesPostId, currentEpisodeSlug, defaultServer)

        } catch (e: Exception) { 
            // println("HoatHinh3DProvider: [loadLinks] Error fetching/parsing episode page or its halim_cfg: ${e.message}")
            // e.printStackTrace()
            return false 
        }

        if (extractedInfo == null) {
            // println("HoatHinh3DProvider: [loadLinks] extractedInfo is null.")
            return false
        }

        val targetSubServerIdsWithNames = listOf(
            ""    to "VIP 1 (Default/subsv_id rỗng)", 
            "1"   to "VIP 1 (subsv_id 1)",
            "2"   to "VIP 2 (Rumble)",
            "3"   to "VIP 3 (Cache)"
        )
        
        var atLeastOneLinkGenerated = false 
        val playerPhpUrlBase = "https://hoathinh3d.name/wp-content/themes/halimmovies/player.php"
        val fixedServerIdQueryParam = "1" 

        // println("HoatHinh3DProvider: [loadLinks] Proceeding to loop through servers with extractedInfo: $extractedInfo")

        for ((currentSubSvid, serverDisplayName) in targetSubServerIdsWithNames) {
            // println("HoatHinh3DProvider: [loadLinks] Preparing API link for subsv_id: '$currentSubSvid'")

            val queryParams = try {
                listOf(
                    "episode_slug" to extractedInfo.episodeSlug,
                    "server_id" to fixedServerIdQueryParam, 
                    "subsv_id" to currentSubSvid,          
                    "post_id" to extractedInfo.seriesPostId 
                ).joinToString("&") { (key, value) -> "$key=$value" }
            } catch (e: Exception) { 
                // println("HoatHinh3DProvider: [loadLinks] Error building query for subsv_id '$currentSubSvid': ${e.message}")
                continue 
            }

            val urlToFetch = "$playerPhpUrlBase?$queryParams"
            // println("HoatHinh3DProvider: [loadLinks] Generated API URL for $serverDisplayName: $urlToFetch")

            // Vẫn đang ở chế độ trả về link API để bạn kiểm tra
            callback.invoke(
                ExtractorLink(
                    source = serverDisplayName, 
                    name = "$serverDisplayName - Kiểm tra Link API", 
                    url = urlToFetch, 
                    referer = episodePageUrl, // Dùng episodePageUrl (data đầu vào) làm referer
                    quality = Qualities.Unknown.value, 
                    type = ExtractorLinkType.VIDEO, 
                    headers = emptyMap() 
                )
            )
            // println("HoatHinh3DProvider: [loadLinks] callback.invoke called for $serverDisplayName")
            atLeastOneLinkGenerated = true
        } 
        
        // if (!atLeastOneLinkGenerated) {
        //     println("HoatHinh3DProvider: [loadLinks] Loop completed but no API links were generated/callbacked.")
        // } else {
        //     println("HoatHinh3DProvider: [loadLinks] Finished. atLeastOneLinkGenerated is true.")
        // }
        return atLeastOneLinkGenerated
    }
}
