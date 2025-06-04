package com.example.HoatHinh3DProvider

// Không cần @JsonProperty nữa vì không dùng JSON cho EpisodeLinkData trực tiếp trong data string
// import com.fasterxml.jackson.annotation.JsonProperty 
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Element
import java.net.URLEncoder

// Bỏ data class EpisodeLinkData vì sẽ parse thủ công từ string
// private data class EpisodeLinkData(...)

// Data class cho JSON response từ player.php (vẫn cần thiết)
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

    // Ký tự phân cách cho Episode.data
    private val episodeDataDelimiter = "Ψ★Ψ"

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

        var seriesPostIdFromCfg: String? = null
        document.select("script").forEach { script ->
            val scriptData = script.data()
            if (scriptData.contains("var halim_cfg")) {
                val postIdRegex = Regex("""["']post_id["']\s*:\s*["']?(\d+)["']?""")
                seriesPostIdFromCfg = postIdRegex.find(scriptData)?.groupValues?.get(1)
                if (seriesPostIdFromCfg != null) return@forEach 
            }
        }
        // println("HoatHinh3DProvider [load]: Extracted seriesPostIdFromCfg for '$title': $seriesPostIdFromCfg")

        val episodeLinkRegex = Regex("""/([^/]+)-sv(\d+)\.html$""")

        val episodes = document.select("div#halim-list-server ul.halim-list-eps li.halim-episode").mapNotNull { epElement ->
            val epA = epElement.selectFirst("a") ?: return@mapNotNull null
            var originalHref = epA.attr("href") ?: ""
            var epLink = fixUrl(originalHref) 
            epLink = epLink.replace(" ", "") 
            
            val epNameCandidate = epA.attr("title")?.takeIf { it.isNotBlank() } 
                ?: epA.selectFirst("span")?.text()?.trim()
                ?: "Tập ?"
            
            var epName = if (epNameCandidate.startsWith("Tập ", ignoreCase = true) || epNameCandidate.matches(Regex("^\\d+$"))) {
                if (epNameCandidate.matches(Regex("^\\d+$"))) "Tập $epNameCandidate" else epNameCandidate
            } else {
                "Tập $epNameCandidate"
            }

            val spanInsideA = epA.selectFirst("span.halim-btn")
            
            var currentEpisodeSlug = spanInsideA?.attr("data-episode-slug")
            var defaultSubSvidForThisLink = spanInsideA?.attr("data-server")
            val episodeSpecificPostId = spanInsideA?.attr("data-post-id")

            if (currentEpisodeSlug.isNullOrBlank() || defaultSubSvidForThisLink.isNullOrBlank()) {
                val matchResult = episodeLinkRegex.find(epLink) 
                if (matchResult != null) {
                    if (currentEpisodeSlug.isNullOrBlank()) {
                        currentEpisodeSlug = matchResult.groupValues.getOrNull(1)
                    }
                    if (defaultSubSvidForThisLink.isNullOrBlank()) {
                        defaultSubSvidForThisLink = matchResult.groupValues.getOrNull(2)
                    }
                }
            }
            
            if (defaultSubSvidForThisLink == null) { 
                 defaultSubSvidForThisLink = ""
            }

            val finalSeriesPostId = seriesPostIdFromCfg ?: episodeSpecificPostId

            val slugForDebug = currentEpisodeSlug ?: "NULL_SLUG"
            val subSvidForDebug = defaultSubSvidForThisLink 
            val epLinkForDebug = epLink ?: "NULL_EPLINK"
            val finalPostIdForDebug = finalSeriesPostId ?: "NULL_POSTID"

            if (finalSeriesPostId != null && !currentEpisodeSlug.isNullOrBlank() && epLink.isNotBlank()) { 
                // THAY ĐỔI: Tạo chuỗi data bằng ký tự phân cách
                val episodeDataString = listOfNotNull(
                    finalSeriesPostId,
                    currentEpisodeSlug,
                    defaultSubSvidForThisLink, // Có thể rỗng
                    epLink // episodePageUrl
                ).joinToString(episodeDataDelimiter)
                
                // println("HoatHinh3DProvider [load]: Generated EpisodeData String for $epName: $episodeDataString")
                
                epName = "$epName || sPID:$finalPostIdForDebug || epSLUG:$slugForDebug || defSUBVID:$subSvidForDebug || epURL:$epLinkForDebug"

                newEpisode(episodeDataString) { // Truyền chuỗi đã được join
                    this.name = epName 
                }
            } else {
                epName = "$epName || SKIPPED_DATA || sPID:$finalPostIdForDebug || epSLUG:$slugForDebug"
                newEpisode("") { // Data rỗng nếu thiếu thông tin quan trọng
                    this.name = epName
                }
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

    // --- HÀM LOADLINKS ĐƯỢC CẬP NHẬT ĐỂ PARSE CHUỖI DATA MỚI ---
    override suspend fun loadLinks(
        data: String, 
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        // println("HoatHinh3DProvider: [loadLinks] Called with data string: '$data'") 

        // Tách chuỗi data bằng ký tự phân cách
        val parts = data.split(episodeDataDelimiter)
        if (parts.size < 4) { // Cần ít nhất 4 phần: seriesPostId, episodeSlug, defaultSubSvid, episodePageUrl
            // println("HoatHinh3DProvider: [loadLinks] Invalid data string format. Expected 4 parts, got ${parts.size}. Data: '$data'")
            return false
        }

        val seriesPostId = parts[0]
        val episodeSlug = parts[1]
        val defaultSubSvid = parts[2] // Có thể rỗng
        val episodePageUrl = parts[3]

        // println("HoatHinh3DProvider: [loadLinks] Parsed data parts: sPID='$seriesPostId', epSLUG='$episodeSlug', defSUBVID='$defaultSubSvid', epURL='$episodePageUrl'")

        // Kiểm tra các trường quan trọng không được rỗng (defaultSubSvid được phép rỗng)
        if (seriesPostId.isBlank() || episodeSlug.isBlank() || episodePageUrl.isBlank()) {
            // println("HoatHinh3DProvider: [loadLinks] Critical parsed parts are blank. sPID: '$seriesPostId', epSLUG: '$episodeSlug', epURL: '$episodePageUrl'")
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

        // println("HoatHinh3DProvider: [loadLinks] Proceeding to loop through servers...")

        for ((currentSubSvid, serverDisplayName) in targetSubServerIdsWithNames) {
            // println("HoatHinh3DProvider: [loadLinks] Preparing API link for subsv_id: '$currentSubSvid' (Name: $serverDisplayName)")

            val queryParams = try {
                listOf(
                    "episode_slug" to episodeSlug, // Từ `parts`
                    "server_id" to fixedServerIdQueryParam, 
                    "subsv_id" to currentSubSvid,          
                    "post_id" to seriesPostId // Từ `parts`
                ).joinToString("&") { (key, value) -> "$key=$value" }
            } catch (e: Exception) { 
                // println("HoatHinh3DProvider: [loadLinks] Error building query for subsv_id '$currentSubSvid': ${e.message}")
                continue 
            }

            val urlToFetch = "$playerPhpUrlBase?$queryParams"
            // println("HoatHinh3DProvider: [loadLinks] Generated API URL for $serverDisplayName: $urlToFetch")

            // Vẫn đặt link API vào ExtractorLink.url để bạn kiểm tra
            callback.invoke(
                ExtractorLink(
                    source = serverDisplayName, 
                    name = "$serverDisplayName - Kiểm tra Link API", 
                    url = urlToFetch, 
                    referer = episodePageUrl, // Từ `parts`
                    quality = Qualities.Unknown.value, 
                    type = ExtractorLinkType.VIDEO, 
                    headers = emptyMap() 
                )
            )
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
