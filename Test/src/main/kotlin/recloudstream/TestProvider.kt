package com.example.HoatHinh3DProvider // Bạn có thể thay đổi package name này

import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Element
import java.net.URLEncoder // Import cho trường hợp cần encode URL

// Data class cho JSON được lưu trong Episode.data
private data class EpisodeDataInput(
    @JsonProperty("postId") val postId: String,
    @JsonProperty("slug") val episodeSlug: String,
    @JsonProperty("server") val initialServerIdOrSubSvid: String, // Giá trị này có thể là server_id ban đầu hoặc subsv_id ban đầu
    @JsonProperty("episodePageUrl") val episodePageUrl: String
)

// Data class cho JSON response từ player.php
private data class PlayerApiResponse(
    @JsonProperty("status") val status: Boolean?,
    @JsonProperty("file") val file: String?,    // Video URL
    @JsonProperty("label") val label: String?,  // Ví dụ: "1080"
    @JsonProperty("type") val type: String?,     // Ví dụ: "hls"
    @JsonProperty("server_used") val serverUsed: String? // Ví dụ: "tiktok1_cache", "rum"
)

class HoatHinh3DProvider : MainAPI() {
    override var mainUrl = "https://hoathinh3d.name"
    override var name = "HoatHinh3D" // Tên provider
    override val supportedTypes = setOf(TvType.Cartoon)
    override var lang = "vi"
    override val hasMainPage = true
    override val hasChromecastSupport = true
    override val hasDownloadSupport = true

    // Helper function (giữ nguyên)
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

    // hàm getMainPage (giữ nguyên)
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

    // hàm search (giữ nguyên)
    override suspend fun search(query: String): List<SearchResponse> {
        val searchUrl = "$mainUrl/search/$query"
        val document = app.get(searchUrl).document

        return document.select("main#main-contents div.halim_box article.thumb.grid-item").mapNotNull {
            it.toSearchResponse()
        }
    }

    // hàm load (giữ nguyên)
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
            val postId = spanInsideA?.attr("data-post-id")
            val serverId = spanInsideA?.attr("data-server") 
            val episodeSlug = spanInsideA?.attr("data-episode-slug")

            val episodeData = if (postId != null && serverId != null && episodeSlug != null) {
                """{"postId":"$postId", "slug":"$episodeSlug", "server":"$serverId", "episodePageUrl":"$epLink"}"""
            } else {
                epLink 
            }
            
            newEpisode(episodeData) {
                this.name = epName
                this.episode = epName.replace(Regex("[^0-9]"), "").toIntOrNull()
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
                } catch (_: Exception) {
                }
            }

            if (partUrl.isNotBlank() && !partTitle.isNullOrEmpty()) {
                newAnimeSearchResponse(partTitle, partUrl, TvType.Cartoon) {
                    this.posterUrl = partSpecificPoster ?: mainFilmPoster 
                }
            } else {
                null
            }
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

    // --- HÀM LOADLINKS ĐƯỢC CẬP NHẬT ĐỂ LẤY NHIỀU SERVER DỰA TRÊN subsv_id ---
    override suspend fun loadLinks(
        data: String, 
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        var episodeInfo: EpisodeDataInput? = null
        var episodePageUrlForReferer: String? = null

        try {
            if (data.startsWith("{") && data.endsWith("}")) {
                episodeInfo = AppUtils.parseJson<EpisodeDataInput>(data)
                episodePageUrlForReferer = episodeInfo.episodePageUrl
            } else {
                return false
            }
        } catch (e: Exception) {
            return false
        }

        if (episodeInfo == null) {
            return false
        }

        // Danh sách các sub-server ID bạn muốn thử
        // Dựa trên ví dụ của bạn: VIP 1 (subsv_id= để trống hoặc 1), VIP 2 (subsv_id=2), VIP 3 (subsv_id=3)
        // Chúng ta sẽ thử subsv_id = 1, 2, 3. Server VIP 1 có thể dùng subsv_id="" hoặc subsv_id="1".
        // Dựa trên các button trên trang: data-subsv-id="1", "2", "3"
        val targetSubServerIds = listOf("1", "2", "3") 
        var atLeastOneLinkFound = false

        // Ánh xạ subsv_id sang tên hiển thị (dựa trên HTML của trang)
        val serverDisplayNames = mapOf(
            "1" to "VIP 1", // Hoặc tên server thực tế từ JSON response nếu có (response.server_used)
            "2" to "VIP 2 (Rumble)",
            "3" to "VIP 3 (Cache)" 
        )

        val playerPhpUrlBase = "https://hoathinh3d.name/wp-content/themes/halimmovies/player.php"
        // server_id dường như cố định là "1" dựa trên các URL bạn cung cấp
        val fixedServerId = "1" 

        for (currentSubSvid in targetSubServerIds) {
            // println("HoatHinh3DProvider: Attempting to fetch link for subsv_id: $currentSubSvid with server_id: $fixedServerId")
            
            val queryParams = try {
                listOf(
                    "episode_slug" to episodeInfo.episodeSlug,
                    "server_id" to fixedServerId, // Giữ server_id=1
                    "subsv_id" to currentSubSvid, // Thay đổi subsv_id
                    "post_id" to episodeInfo.postId
                ).joinToString("&") { (key, value) ->
                    // Nên encode value nếu nó có thể chứa ký tự đặc biệt
                    // Ví dụ: "$key=${URLEncoder.encode(value, "UTF-8")}"
                    // Tuy nhiên, với các ID và slug này, nối trực tiếp thường vẫn ổn.
                    "$key=$value"
                }
            } catch (e: Exception) {
                // println("HoatHinh3DProvider: Error building query parameters for subsv_id $currentSubSvid: ${e.message}")
                continue 
            }

            val urlToFetch = "$playerPhpUrlBase?$queryParams"
            // println("HoatHinh3DProvider: Constructed URL for subsv_id $currentSubSvid: $urlToFetch")

            try {
                val responseText = app.get(
                    urlToFetch,
                    referer = episodePageUrlForReferer ?: mainUrl
                ).text
                // println("HoatHinh3DProvider: Raw response for subsv_id $currentSubSvid: '$responseText'")

                if (responseText.isBlank()) {
                    // println("HoatHinh3DProvider: Raw response from player.php for subsv_id $currentSubSvid is blank.")
                    continue 
                }

                val response = try {
                    AppUtils.parseJson<PlayerApiResponse>(responseText)
                } catch (e: Exception) {
                    // println("HoatHinh3DProvider: Failed to parse JSON for subsv_id $currentSubSvid: ${e.message}")
                    continue 
                }
                
                // println("HoatHinh3DProvider: Parsed PlayerApiResponse for subsv_id $currentSubSvid: $response")

                if (response.status == true && !response.file.isNullOrBlank()) {
                    val videoUrl = response.file
                    val qualityLabel = response.label ?: "Chất lượng" 
                    val videoType = response.type?.lowercase() ?: ""
                    // Sử dụng tên server từ map, hoặc tên từ JSON response (response.serverUsed), hoặc tên mặc định
                    val serverDisplayName = serverDisplayNames[currentSubSvid] ?: response.serverUsed ?: "Server $currentSubSvid"


                    val qualityInt = qualityLabel.replace("p", "", ignoreCase = true).toIntOrNull() 
                                     ?: Qualities.Unknown.value

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
                    // println("HoatHinh3DProvider: Link for subsv_id $currentSubSvid ($serverDisplayName) added successfully.")
                    atLeastOneLinkFound = true 
                } else {
                    // println("HoatHinh3DProvider: subsv_id $currentSubSvid response status not true or file link missing.")
                }
            } catch (e: Exception) {
                // println("HoatHinh3DProvider: Exception for subsv_id $currentSubSvid: ${e.message}")
            }
        } 
        return atLeastOneLinkFound
    }
}
