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
    @JsonProperty("server") val serverId: String,
    @JsonProperty("episodePageUrl") val episodePageUrl: String
)

// Data class cho JSON response từ player.php
private data class PlayerApiResponse(
    @JsonProperty("status") val status: Boolean?,
    @JsonProperty("file") val file: String?,    // Video URL
    @JsonProperty("label") val label: String?,  // Ví dụ: "1080"
    @JsonProperty("type") val type: String?     // Ví dụ: "hls"
    // Các trường khác như title, poster, skip_time có thể thêm nếu cần
)

class HoatHinh3DProvider : MainAPI() {
    override var mainUrl = "https://hoathinh3d.name"
    override var name = "HoatHinh3D"
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
                    // Bỏ qua lỗi khi lấy poster phụ, sẽ dùng poster chính
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

    // --- HÀM LOADLINKS ĐƯỢC CẬP NHẬT ---
    override suspend fun loadLinks(
        data: String, 
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        // println("HoatHinh3DProvider: loadLinks called with data string: '$data'") 

        var episodeInfo: EpisodeDataInput? = null
        var episodePageUrlForReferer: String? = null

        try {
            if (data.startsWith("{") && data.endsWith("}")) {
                episodeInfo = AppUtils.parseJson<EpisodeDataInput>(data)
                episodePageUrlForReferer = episodeInfo.episodePageUrl
                // println("HoatHinh3DProvider: Parsed EpisodeDataInput: $episodeInfo")
            } else {
                // println("HoatHinh3DProvider: loadLinks received non-JSON data: '$data'. This is unexpected.")
                return false
            }
        } catch (e: Exception) {
            // println("HoatHinh3DProvider: Error parsing Episode.data JSON: ${e.message}")
            // e.printStackTrace()
            return false
        }

        if (episodeInfo == null) {
            // println("HoatHinh3DProvider: Failed to get episode info from data (episodeInfo is null).")
            return false
        }

        val playerPhpUrlBase = "https://hoathinh3d.name/wp-content/themes/halimmovies/player.php"
        
        // Xây dựng query parameters theo đúng thứ tự và bao gồm subsv_id=""
        val queryParams = try {
            // Sử dụng map để dễ dàng nối chuỗi, sau đó joinToString sẽ giữ thứ tự
            // Hoặc bạn có thể nối chuỗi trực tiếp nếu muốn kiểm soát hoàn toàn thứ tự.
            // Trong ví dụ này, List sẽ đảm bảo thứ tự khi join.
            listOf(
                "episode_slug" to episodeInfo.episodeSlug,
                "server_id" to episodeInfo.serverId,
                "subsv_id" to "", // Quan trọng: thêm subsv_id rỗng
                "post_id" to episodeInfo.postId
            ).joinToString("&") { (key, value) ->
                // Mã hóa giá trị để đảm bảo URL hợp lệ, đặc biệt nếu slug có thể chứa ký tự đặc biệt
                // tuy nhiên, slug thường đã an toàn. ID thì chắc chắn an toàn.
                // Để cẩn thận, có thể encode:
                // "$key=${URLEncoder.encode(value, "UTF-8")}"
                // Nhưng với các giá trị hiện tại, nối trực tiếp có thể vẫn ổn.
                "$key=$value"
            }
        } catch (e: Exception) {
            // println("HoatHinh3DProvider: Error building query parameters: ${e.message}")
            // e.printStackTrace()
            return false
        }

        val urlToFetch = "$playerPhpUrlBase?$queryParams"
        // println("HoatHinh3DProvider: Constructed URL to fetch player data: $urlToFetch")
        // println("HoatHinh3DProvider: Referer for request will be: $episodePageUrlForReferer")

        try {
            val responseText = app.get(
                urlToFetch,
                referer = episodePageUrlForReferer ?: mainUrl
            ).text
            // println("HoatHinh3DProvider: Raw response from player.php: '$responseText'")

            if (responseText.isBlank()) {
                // println("HoatHinh3DProvider: Raw response from player.php is blank.")
                return false
            }

            val response = try {
                AppUtils.parseJson<PlayerApiResponse>(responseText)
            } catch (e: Exception) {
                // println("HoatHinh3DProvider: Failed to parse JSON from player.php response: ${e.message}")
                // println("HoatHinh3DProvider: JSON was: $responseText")
                // e.printStackTrace()
                return false
            }
            
            // println("HoatHinh3DProvider: Parsed PlayerApiResponse: $response")

            if (response.status == true && !response.file.isNullOrBlank()) {
                val videoUrl = response.file
                val qualityLabel = response.label ?: "Chất lượng" 
                val videoType = response.type?.lowercase() ?: ""

                // println("HoatHinh3DProvider: Video URL found: $videoUrl, Label: $qualityLabel, Type: $videoType")

                val qualityInt = qualityLabel.replace("p", "", ignoreCase = true).toIntOrNull() 
                                 ?: Qualities.Unknown.value

                val linkType = when (videoType) {
                    "hls", "m3u8" -> ExtractorLinkType.M3U8
                    "mp4" -> ExtractorLinkType.VIDEO 
                    else -> ExtractorLinkType.VIDEO 
                }
                // println("HoatHinh3DProvider: Determined QualityInt: $qualityInt, LinkType: $linkType")

                callback.invoke(
                    ExtractorLink(
                        source = "$name Server ${episodeInfo.serverId}",
                        name = "$name - $qualityLabel", 
                        url = videoUrl,
                        referer = episodePageUrlForReferer ?: mainUrl, 
                        quality = qualityInt,
                        type = linkType, 
                        headers = emptyMap() 
                    )
                )
                // println("HoatHinh3DProvider: ExtractorLink callback invoked successfully.")
                return true 
            } else {
                // println("HoatHinh3DProvider: player.php response status is not true or file link is missing.")
                // println("HoatHinh3DProvider: Response details -> Status: ${response.status}, File: ${response.file}, Type: ${response.type}, Label: ${response.label}")
                return false
            }
        } catch (e: Exception) {
            // println("HoatHinh3DProvider: Exception during HTTP GET request to player.php or subsequent processing: ${e.message}")
            // e.printStackTrace()
            return false
        }
    }
}
