package com.example.HoatHinh3DProvider

import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Element
import java.net.URLEncoder

private data class EpisodeLinkData(
    @JsonProperty("seriesPostId") val seriesPostId: String,
    @JsonProperty("episodeSlug") val episodeSlug: String,
    @JsonProperty("defaultSubSvid") val defaultSubSvid: String,
    @JsonProperty("episodePageUrl") val episodePageUrl: String
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
        val href = fixUrl(thumbLink.attr("href"))
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
                val href = fixUrlNull(linkTag?.attr("href"))
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

    // --- HÀM LOAD ĐƯỢC CẬP NHẬT LOGIC LẤY postId, episodeSlug, defaultSubSvid ---
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
        // println("HoatHinh3DProvider [load]: Extracted seriesPostIdFromCfg: $seriesPostIdFromCfg")

        // Regex để parse slug và server từ href của tập phim
        val episodeLinkRegex = Regex("""/([^/]+)-sv(\d+)\.html$""")

        val episodes = document.select("div#halim-list-server ul.halim-list-eps li.halim-episode").mapNotNull { epElement ->
            val epA = epElement.selectFirst("a") ?: return@mapNotNull null
            val epLink = fixUrl(epA.attr("href")) 
            
            val epNameCandidate = epA.attr("title")?.takeIf { it.isNotBlank() } 
                ?: epA.selectFirst("span")?.text()?.trim()
                ?: "Tập ?"
            
            val epName = if (epNameCandidate.startsWith("Tập ", ignoreCase = true) || epNameCandidate.matches(Regex("^\\d+$"))) {
                if (epNameCandidate.matches(Regex("^\\d+$"))) "Tập $epNameCandidate" else epNameCandidate
            } else {
                "Tập $epNameCandidate"
            }

            val spanInsideA = epA.selectFirst("span.halim-btn")
            
            // Ưu tiên lấy từ data attributes
            var currentEpisodeSlug = spanInsideA?.attr("data-episode-slug")
            var defaultSubSvidForThisLink = spanInsideA?.attr("data-server")
            val episodeSpecificPostId = spanInsideA?.attr("data-post-id") // postId trên từng tập (dùng làm fallback nếu seriesPostIdFromCfg null)

            // Fallback: Parse từ epLink nếu data attributes rỗng hoặc thiếu
            if (currentEpisodeSlug.isNullOrBlank() || defaultSubSvidForThisLink.isNullOrBlank()) {
                val matchResult = episodeLinkRegex.find(epLink)
                if (matchResult != null) {
                    if (currentEpisodeSlug.isNullOrBlank()) {
                        currentEpisodeSlug = matchResult.groupValues[1]
                        // println("HoatHinh3DProvider [load]: Fallback - Parsed slug from href: $currentEpisodeSlug for $epLink")
                    }
                    if (defaultSubSvidForThisLink.isNullOrBlank()) {
                        defaultSubSvidForThisLink = matchResult.groupValues[2]
                        // println("HoatHinh3DProvider [load]: Fallback - Parsed subsvid from href: $defaultSubSvidForThisLink for $epLink")
                    }
                }
            }
            
            // Đảm bảo defaultSubSvid không null, nếu vẫn null sau các bước trên thì gán rỗng
            if (defaultSubSvidForThisLink == null) {
                defaultSubSvidForThisLink = ""
            }

            val finalSeriesPostId = seriesPostIdFromCfg ?: episodeSpecificPostId

            if (finalSeriesPostId != null && !currentEpisodeSlug.isNullOrBlank()) { // defaultSubSvidForThisLink có thể rỗng
                val episodeDataJsonString = """
                    {
                        "seriesPostId": "$finalSeriesPostId",
                        "episodeSlug": "$currentEpisodeSlug",
                        "defaultSubSvid": "$defaultSubSvidForThisLink",
                        "episodePageUrl": "$epLink"
                    }
                """.trimIndent()

                newEpisode(episodeDataJsonString) {
                    this.name = epName
                }
            } else {
                // println("HoatHinh3DProvider [load]: Missing critical data for episode: $epName. FinalPostId: $finalSeriesPostId, Slug: $currentEpisodeSlug, DefaultSubSvid: $defaultSubSvidForThisLink")
                null
            }
        }.reversed()
        
        val partLinkElements = document.select("ul#list-movies-part li.movies-part a")
        val partsListAsRecommendations = partLinkElements.mapNotNull { linkElement ->
            val partUrl = fixUrl(linkElement.attr("href"))
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

    override suspend fun loadLinks(
        data: String, 
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        var episodeLinkInfo: EpisodeLinkData? = null
        var episodePageUrlForReferer: String? = null

        try {
            if (data.startsWith("{") && data.endsWith("}")) {
                episodeLinkInfo = AppUtils.parseJson<EpisodeLinkData>(data)
                if (episodeLinkInfo.seriesPostId.isBlank() || 
                    episodeLinkInfo.episodeSlug.isBlank() ||
                    // defaultSubSvid được phép rỗng, không kiểm tra isBlank()
                    episodeLinkInfo.episodePageUrl.isBlank()) {
                    return false
                }
                episodePageUrlForReferer = episodeLinkInfo.episodePageUrl
            } else { return false }
        } catch (e: Exception) { return false }

        val currentEpisodeInfo = episodeLinkInfo ?: return false


        val targetSubServerIds = listOf("1", "2", "3") 
        // Cân nhắc thêm "" vào list này nếu server mặc định yêu cầu subsv_id rỗng
        // Hoặc sử dụng currentEpisodeInfo.defaultSubSvid làm một trong các server để thử đầu tiên.
        // Ví dụ: val subIdsToTry = (listOf(currentEpisodeInfo.defaultSubSvid) + targetSubServerIds).distinct().filterNotNull()

        var atLeastOneLinkFound = false
        val serverDisplayNames = mapOf(
            "" to "VIP 1 (Mặc định)", // Dành cho subsv_id rỗng
            "1" to "VIP 1", 
            "2" to "VIP 2 (Rumble)", 
            "3" to "VIP 3 (Cache)"
        )
        val playerPhpUrlBase = "https://hoathinh3d.name/wp-content/themes/halimmovies/player.php"
        val fixedServerIdQueryParam = "1" 

        for (currentSubSvid in targetSubServerIds) {
            val queryParams = try {
                listOf(
                    "episode_slug" to currentEpisodeInfo.episodeSlug,
                    "server_id" to fixedServerIdQueryParam, 
                    "subsv_id" to currentSubSvid,          
                    "post_id" to currentEpisodeInfo.seriesPostId 
                ).joinToString("&") { (key, value) -> "$key=$value" }
            } catch (e: Exception) { continue }

            val urlToFetch = "$playerPhpUrlBase?$queryParams"

            try {
                val responseText = app.get(urlToFetch, referer = episodePageUrlForReferer ?: mainUrl).text
                if (responseText.isBlank()) { continue }

                val response = try { AppUtils.parseJson<PlayerApiResponse>(responseText) } catch (e: Exception) { continue }
                
                if (response.status == true && !response.file.isNullOrBlank()) {
                    val videoUrl = response.file
                    val qualityLabel = response.label ?: "Chất lượng" 
                    val videoType = response.type?.lowercase() ?: ""
                    val serverDisplayName = serverDisplayNames[currentSubSvid] ?: response.serverUsed ?: "Server $currentSubSvid"
                    val qualityInt = qualityLabel.replace("p", "", ignoreCase = true).toIntOrNull() ?: Qualities.Unknown.value
                    val linkType = when (videoType) {
                        "hls", "m3u8" -> ExtractorLinkType.M3U8
                        "mp4" -> ExtractorLinkType.VIDEO 
                        else -> ExtractorLinkType.VIDEO 
                    }

                    callback.invoke(
                        ExtractorLink(
                            source = serverDisplayName, 
                            name = "$qualityLabel - $serverDisplayName", 
                            url = videoUrl,
                            referer = episodePageUrlForReferer ?: mainUrl, 
                            quality = qualityInt,
                            type = linkType, 
                            headers = emptyMap() 
                        )
                    )
                    atLeastOneLinkFound = true 
                }
            } catch (_: Exception) { }
        } 
        return atLeastOneLinkFound
    }
}
