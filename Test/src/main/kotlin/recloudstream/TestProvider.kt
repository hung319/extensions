package com.example.HoatHinh3DProvider // Bạn có thể thay đổi package name này

import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.* // AppUtils cũng nằm trong đây
import org.jsoup.nodes.Element

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

    // Helper function để parse các item phim/series (giữ nguyên)
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

    // hàm load (giữ nguyên với các chỉnh sửa trước đó)
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
                // this.duration = null // Đã bỏ do lỗi unresolved reference
            }
        }.reversed()
        
        val partLinkElements = document.select("ul#list-movies-part li.movies-part a")
        // println("HoatHinh3DProvider: Found ${partLinkElements.size} part links for recommendations section.")

        val partsListAsRecommendations = partLinkElements.mapNotNull { linkElement ->
            val partUrl = fixUrl(linkElement.attr("href"))
            var partTitle = linkElement.attr("title")?.trim()
            if (partTitle.isNullOrEmpty()) {
                partTitle = linkElement.text()?.trim()
            }

            var partSpecificPoster: String? = null
            if (partUrl.isNotBlank() && partUrl != url) { 
                try {
                    // println("HoatHinh3DProvider: Fetching sub-page for poster: $partUrl")
                    val partDocument = app.get(partUrl).document
                    val partMovieWrapper = partDocument.selectFirst("div.halim-movie-wrapper.tpl-2.info-movie")
                    partSpecificPoster = fixUrlNull(partMovieWrapper?.selectFirst("div.head div.first img")?.attr("data-src"))
                    if (partSpecificPoster == null) {
                        partSpecificPoster = fixUrlNull(partMovieWrapper?.selectFirst("div.head div.first img")?.attr("src"))
                    }
                } catch (e: Exception) {
                    // println("HoatHinh3DProvider: Error fetching poster for $partUrl - ${e.message}")
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

    // --- HÀM LOADLINKS ĐƯỢC TRIỂN KHAI ---
    override suspend fun loadLinks(
        data: String, // JSON string từ Episode.data
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        var episodeInfo: EpisodeDataInput? = null
        var episodePageUrlForReferer: String? = null // Biến để lưu URL trang tập phim làm referer

        try {
            // Parse JSON string từ Episode.data
            if (data.startsWith("{") && data.endsWith("}")) {
                episodeInfo = AppUtils.parseJson<EpisodeDataInput>(data)
                episodePageUrlForReferer = episodeInfo.episodePageUrl
            } else {
                // Trường hợp data là URL trực tiếp (fallback từ hàm load, nếu có)
                // Tuy nhiên, với cách chúng ta xây dựng Episode.data, nó luôn là JSON.
                // Nếu data là URL, chúng ta có thể cần fetch trang đó để lấy postId, slug, serverId
                // nhưng điều này sẽ phức tạp hơn và ít khả năng xảy ra với logic hiện tại.
                // For now, we assume data is always the JSON string.
                // episodePageUrlForReferer = data // Nếu data là URL của trang tập phim
                println("HoatHinh3DProvider: loadLinks received non-JSON data: $data. This is unexpected.")
                return false
            }
        } catch (e: Exception) {
            println("HoatHinh3DProvider: Error parsing Episode.data JSON: ${e.message}")
            e.printStackTrace()
            return false
        }

        if (episodeInfo == null) {
            println("HoatHinh3DProvider: Failed to get episode info from data.")
            return false
        }

        // Xây dựng URL cho player.php
        val playerPhpUrl = "https://hoathinh3d.name/wp-content/themes/halimmovies/player.php"
        val urlToFetch = try {
            buildUrl(
                playerPhpUrl,
                mapOf(
                    "post_id" to episodeInfo.postId,
                    "episode_slug" to episodeInfo.episodeSlug,
                    "server_id" to episodeInfo.serverId,
                    "subsv_id" to "" // Giữ nguyên subsv_id rỗng như trong ví dụ bạn tìm thấy
                )
            )
        } catch (e: Exception) {
            println("HoatHinh3DProvider: Error building URL for player.php: ${e.message}")
            e.printStackTrace()
            return false
        }
        
        println("HoatHinh3DProvider: Fetching player data from: $urlToFetch")

        try {
            val response = app.get(
                urlToFetch,
                referer = episodePageUrlForReferer ?: mainUrl // Dùng episodePageUrl làm referer
            ).parsedSafe<PlayerApiResponse>() // Parse JSON response trực tiếp

            if (response?.status == true && !response.file.isNullOrBlank()) {
                val videoUrl = response.file
                val qualityLabel = response.label ?: "Chất lượng" // Ví dụ "1080"
                val videoType = response.type?.lowercase() ?: ""

                // Chuyển đổi label chất lượng (vd: "1080p", "720") thành Int
                val qualityInt = qualityLabel.replace("p", "", ignoreCase = true).toIntOrNull() 
                                 ?: Qualities.Unknown.value // Giá trị mặc định nếu không parse được

                val linkType = when (videoType) {
                    "hls", "m3u8" -> ExtractorLinkType.M3U8
                    "mp4" -> ExtractorLinkType.MP4Download
                    // Thêm các case khác nếu trang web có thể trả về các type khác
                    else -> ExtractorLinkType.VIDEO // Hoặc một type mặc định khác
                }

                callback.invoke(
                    ExtractorLink(
                        source = "$name Server ${episodeInfo.serverId}", // Tên nguồn (ví dụ: "HoatHinh3D Server 1")
                        name = "$name - $qualityLabel", // Tên của link (ví dụ: "HoatHinh3D - 1080p")
                        url = videoUrl,
                        referer = episodePageUrlForReferer ?: mainUrl, // Referer quan trọng
                        quality = qualityInt,
                        type = linkType, // Loại link (M3U8, MP4, etc.)
                        headers = emptyMap() // Thêm header nếu cần
                    )
                )
                return true // Báo hiệu đã tìm thấy và gọi callback thành công
            } else {
                println("HoatHinh3DProvider: player.php response status is not true or file link is missing.")
                println("HoatHinh3DProvider: Response status: ${response?.status}, file: ${response?.file}")
                return false
            }
        } catch (e: Exception) {
            println("HoatHinh3DProvider: Exception during loadLinks GET request or JSON parsing: ${e.message}")
            e.printStackTrace()
            return false
        }
    }
}
