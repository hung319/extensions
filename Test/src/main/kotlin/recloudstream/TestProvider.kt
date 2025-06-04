package com.example.HoatHinh3DProvider

import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Element
import java.net.URLEncoder

// Data class cho JSON được lưu trong Episode.data (đã đổi tên và trường)
private data class EpisodeLinkData(
    @JsonProperty("seriesPostId") val seriesPostId: String,
    @JsonProperty("episodeSlug") val episodeSlug: String,
    @JsonProperty("defaultSubSvid") val defaultSubSvid: String,
    @JsonProperty("episodePageUrl") val episodePageUrl: String
)

// Data class cho JSON response từ player.php
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

    // --- HÀM LOAD ĐƯỢC CẬP NHẬT LOGIC LẤY postId ---
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

        // Trích xuất seriesPostId từ halim_cfg trên trang chi tiết phim
        var seriesPostIdFromCfg: String? = null
        document.select("script").forEach { script ->
            val scriptData = script.data()
            if (scriptData.contains("var halim_cfg")) {
                val postIdRegex = Regex("""["']post_id["']\s*:\s*["']?(\d+)["']?""")
                seriesPostIdFromCfg = postIdRegex.find(scriptData)?.groupValues?.get(1)
                if (seriesPostIdFromCfg != null) return@forEach // Thoát sớm nếu tìm thấy
            }
        }
        // println("HoatHinh3DProvider [load]: Extracted seriesPostIdFromCfg: $seriesPostIdFromCfg")


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
            val episodeSpecificPostId = spanInsideA?.attr("data-post-id") // postId trên từng tập
            val defaultSubSvidForThisLink = spanInsideA?.attr("data-server") // data-server trên từng tập, là subsv_id mặc định
            val currentEpisodeSlug = spanInsideA?.attr("data-episode-slug")

            // Ưu tiên postId từ halim_cfg của trang, nếu không có thì mới dùng postId từ thuộc tính của tập
            val finalSeriesPostId = seriesPostIdFromCfg ?: episodeSpecificPostId

            if (finalSeriesPostId != null && defaultSubSvidForThisLink != null && currentEpisodeSlug != null) {
                val episodeDataJson = EpisodeLinkData(
                    seriesPostId = finalSeriesPostId,
                    episodeSlug = currentEpisodeSlug,
                    defaultSubSvid = defaultSubSvidForThisLink,
                    episodePageUrl = epLink
                ).toJson() // Chuyển thành JSON string

                newEpisode(episodeDataJson) {
                    this.name = epName
                    this.episode = epName.replace(Regex("[^0-9]"), "").toIntOrNull()
                }
            } else {
                // println("HoatHinh3DProvider [load]: Missing data for episode: $epName. FinalPostId: $finalSeriesPostId, DefaultSubSvid: $defaultSubSvidForThisLink, Slug: $currentEpisodeSlug")
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
        var episodeLinkInfo: EpisodeLinkData? = null // Sử dụng data class đã đổi tên
        var episodePageUrlForReferer: String? = null

        try {
            if (data.startsWith("{") && data.endsWith("}")) {
                episodeLinkInfo = AppUtils.parseJson<EpisodeLinkData>(data)
                episodePageUrlForReferer = episodeLinkInfo.episodePageUrl
            } else { return false }
        } catch (e: Exception) { return false }

        if (episodeLinkInfo == null) { return false }

        val targetSubServerIds = listOf("1", "2", "3") 
        var atLeastOneLinkFound = false
        val serverDisplayNames = mapOf("1" to "VIP 1", "2" to "VIP 2 (Rumble)", "3" to "VIP 3 (Cache)")
        val playerPhpUrlBase = "https://hoathinh3d.name/wp-content/themes/halimmovies/player.php"
        val fixedServerIdQueryParam = "1" // server_id trong URL của player.php luôn là "1"

        for (currentSubSvid in targetSubServerIds) {
            val queryParams = try {
                listOf(
                    "episode_slug" to episodeLinkInfo.episodeSlug,
                    "server_id" to fixedServerIdQueryParam, // Luôn dùng server_id="1"
                    "subsv_id" to currentSubSvid,           // Thay đổi subsv_id
                    "post_id" to episodeLinkInfo.seriesPostId // Dùng seriesPostId đã lấy chính xác
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
