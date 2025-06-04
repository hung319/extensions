package com.example.HoatHinh3DProvider

import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Element
import java.net.URLEncoder

// Data class này dùng để chứa thông tin parse được từ halim_cfg bên trong loadLinks.
private data class ExtractedEpisodeInfo(
    val seriesPostId: String,
    val episodeSlug: String
)

private data class PlayerApiResponse(
    @JsonProperty("status") val status: Boolean?,
    @JsonProperty("file") val file: String?,    // Video URL M3U8
    @JsonProperty("label") val label: String?,  // Ví dụ: "1080"
    @JsonProperty("type") val type: String?,     // Ví dụ: "hls"
    @JsonProperty("server_used") val serverUsed: String? 
)

class HoatHinh3DProvider : MainAPI() {
    override var mainUrl = "https://hoathinh3d.name" // mainUrl đã bao gồm https://
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
            this.rating = rating
            this.recommendations = partsListAsRecommendations 
        }
    }

    // --- HÀM LOADLINKS HOÀN CHỈNH - LẤY LINK M3U8 ---
    override suspend fun loadLinks(
        data: String, // data giờ là URL của trang xem tập phim (epLink)
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val episodePageUrl = data 
        // println("HoatHinh3DProvider: [loadLinks] Called with episodePageUrl: '$episodePageUrl'") 

        if (episodePageUrl.isBlank()) {
            // println("HoatHinh3DProvider: [loadLinks] episodePageUrl is blank.")
            return false
        }

        var extractedSeriesPostId: String? = null
        var extractedEpisodeSlug: String? = null
        var availableServersFromPage: List<Pair<String, String>> = emptyList()

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

            availableServersFromPage = episodePageDocument.select("div#halim-ajax-list-server span.get-eps").mapNotNull { serverElement ->
                val subSvid = serverElement.attr("data-subsv-id")
                val serverName = serverElement.text().trim()
                if (subSvid.isNotBlank() && serverName.isNotBlank()) {
                    subSvid to serverName
                } else {
                    null
                }
            }
        } catch (e: Exception) { 
            // println("HoatHinh3DProvider: [loadLinks] Error fetching/parsing episode page: ${e.message}")
            return false 
        }
        
        val desiredServerNames = setOf("VIP 1", "VIP 2", "VIP 3")
        val serversToTry = availableServersFromPage.filter { desiredServerNames.contains(it.second) }

        if (serversToTry.isEmpty()) {
            // println("HoatHinh3DProvider: [loadLinks] No desired servers (VIP 1, VIP 2, VIP 3) found on the page.")
            return false
        }
        
        var atLeastOneLinkFound = false 
        // --- THAY ĐỔI CÁCH ĐỊNH NGHĨA playerPhpUrlBase ---
        val playerPhpUrlBase = "$mainUrl/wp-content/themes/halimmovies/player.php"
        // --- KẾT THÚC THAY ĐỔI ---
        val fixedServerIdQueryParam = "1" 

        for ((currentSubSvid, serverDisplayNameFromHtml) in serversToTry) {
            val queryParams = try {
                // Đảm bảo extractedEpisodeSlug và extractedSeriesPostId không null ở đây
                // bằng cách sử dụng !! (nếu chắc chắn) hoặc kiểm tra lại
                val slug = extractedEpisodeSlug ?: return@loadLinks false // Thoát sớm nếu null
                val postId = extractedSeriesPostId ?: return@loadLinks false // Thoát sớm nếu null

                listOf(
                    "episode_slug" to slug,
                    "server_id" to fixedServerIdQueryParam, 
                    "subsv_id" to currentSubSvid,          
                    "post_id" to postId 
                ).joinToString("&") { (key, value) -> "$key=$value" }
            } catch (e: Exception) { 
                // println("HoatHinh3DProvider: [loadLinks] Error building query for subsv_id '$currentSubSvid': ${e.message}")
                continue 
            }

            val urlToFetchApi = "$playerPhpUrlBase?$queryParams"
            // println("HoatHinh3DProvider: [loadLinks] Fetching API URL for $serverDisplayNameFromHtml: $urlToFetchApi")

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
                    
                    val finalServerName = playerApiResponse.serverUsed?.replace("_", " ")?.replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() } ?: serverDisplayNameFromHtml

                    val qualityInt = qualityLabel.replace("p", "", ignoreCase = true).toIntOrNull() 
                                     ?: Qualities.Unknown.value

                    val linkType = if (videoType == "hls" || videoUrl.contains(".m3u8")) {
                        ExtractorLinkType.M3U8
                    } else if (videoType == "mp4") {
                        ExtractorLinkType.VIDEO 
                    } else {
                        ExtractorLinkType.VIDEO 
                    }

                    callback.invoke(
                        ExtractorLink(
                            source = finalServerName, 
                            name = "$qualityLabel - $finalServerName", 
                            url = videoUrl, 
                            referer = episodePageUrl, 
                            quality = qualityInt,
                            type = linkType, 
                            headers = emptyMap() 
                        )
                    )
                    atLeastOneLinkFound = true 
                } else {
                    // println("HoatHinh3DProvider: [loadLinks] API response for $serverDisplayNameFromHtml status not true or file link missing. Status: ${playerApiResponse.status}, File: ${playerApiResponse.file}")
                }
            } catch (e: Exception) {
                // println("HoatHinh3DProvider: [loadLinks] Exception during GET request to API $urlToFetchApi or subsequent processing: ${e.message}")
            }
        } 
        return atLeastOneLinkFound
    }
}
