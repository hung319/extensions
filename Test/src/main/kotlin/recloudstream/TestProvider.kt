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
        // println("HoatHinh3DProvider [load]: Extracted seriesPostIdFromCfg: $seriesPostIdFromCfg cho phim $title")

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

            if (finalSeriesPostId != null && !currentEpisodeSlug.isNullOrBlank()) { 
                val episodeDataJsonString = """
                    {
                        "seriesPostId": "$finalSeriesPostId",
                        "episodeSlug": "$currentEpisodeSlug",
                        "defaultSubSvid": "$defaultSubSvidForThisLink",
                        "episodePageUrl": "$epLink"
                    }
                """.trimIndent()
                // println("HoatHinh3DProvider [load]: EpisodeData for $epName: $episodeDataJsonString")

                newEpisode(episodeDataJsonString) {
                    this.name = epName
                }
            } else {
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

    // --- HÀM LOADLINKS ĐƯỢC CẬP NHẬT ĐỂ TRẢ VỀ LINK API PLAYER.PHP TRONG EXTRACTORLINK.URL ---
    override suspend fun loadLinks(
        data: String, 
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        // Bật log này để xem chính xác 'data' mà loadLinks nhận được
        // println("HoatHinh3DProvider: loadLinks called with data string: '$data'") 

        var episodeLinkInfo: EpisodeLinkData? = null
        var episodePageUrlForReferer: String? = null

        try {
            if (data.startsWith("{") && data.endsWith("}")) {
                episodeLinkInfo = AppUtils.parseJson<EpisodeLinkData>(data)
                if (episodeLinkInfo.seriesPostId.isBlank() || 
                    episodeLinkInfo.episodeSlug.isBlank() ||
                    episodeLinkInfo.episodePageUrl.isBlank()) { 
                    // println("HoatHinh3DProvider: Parsed EpisodeLinkData is missing critical fields: $episodeLinkInfo")    
                    return false
                }
                episodePageUrlForReferer = episodeLinkInfo.episodePageUrl
            } else { 
                // println("HoatHinh3DProvider: loadLinks received non-JSON data: '$data'")
                return false 
            }
        } catch (e: Exception) { 
            // println("HoatHinh3DProvider: Error parsing EpisodeLinkData JSON: ${e.message}")
            return false 
        }

        val currentEpisodeInfo = episodeLinkInfo ?: return false

        // Danh sách các cặp (subsv_id, Tên hiển thị cho server) để thử
        val targetSubServerIdsWithNames = listOf(
            ""    to "VIP 1 (Default/subsv_id rỗng)", // Thử subsv_id rỗng
            "1"   to "VIP 1 (subsv_id 1)",
            "2"   to "VIP 2 (Rumble)",
            "3"   to "VIP 3 (Cache)"
            // "4"   to "HX" // Nếu có server 4
        )
        
        var atLeastOneLinkGenerated = false // Cờ để theo dõi nếu ít nhất một link API được tạo
        val playerPhpUrlBase = "https://hoathinh3d.name/wp-content/themes/halimmovies/player.php"
        val fixedServerIdQueryParam = "1" 

        for ((currentSubSvid, serverDisplayName) in targetSubServerIdsWithNames) {
            // println("HoatHinh3DProvider: Preparing API link for subsv_id: '$currentSubSvid' (Name: $serverDisplayName)")

            val queryParams = try {
                listOf(
                    "episode_slug" to currentEpisodeInfo.episodeSlug,
                    "server_id" to fixedServerIdQueryParam, 
                    "subsv_id" to currentSubSvid,          
                    "post_id" to currentEpisodeInfo.seriesPostId 
                ).joinToString("&") { (key, value) -> 
                    // URLEncoder.encode cho value nếu value có thể chứa ký tự đặc biệt
                    // ví dụ: "$key=${URLEncoder.encode(value, "UTF-8")}"
                    // Với các ID và slug hiện tại, nối trực tiếp thường vẫn ổn.
                    "$key=$value"
                }
            } catch (e: Exception) { 
                // println("HoatHinh3DProvider: Error building query for subsv_id '$currentSubSvid': ${e.message}")
                continue 
            }

            val urlToFetch = "$playerPhpUrlBase?$queryParams"
            // println("HoatHinh3DProvider: Generated API URL for $serverDisplayName: $urlToFetch")

            // **THAY ĐỔI THEO YÊU CẦU:**
            // Thay vì fetch urlToFetch và lấy M3U8, chúng ta sẽ đặt urlToFetch vào ExtractorLink.url
            callback.invoke(
                ExtractorLink(
                    source = serverDisplayName, 
                    name = "$serverDisplayName - Kiểm tra Link API", // Tên để nhận biết đây là link API
                    url = urlToFetch, // << Đặt link API player.php vào đây
                    referer = episodePageUrlForReferer ?: mainUrl, 
                    quality = Qualities.Unknown.value, // Chất lượng không xác định vì đây là link API
                    type = ExtractorLinkType.VIDEO, // Loại chung, vì đây không phải link M3U8 trực tiếp
                                                    // Hoặc bạn có thể dùng một type khác nếu muốn xử lý đặc biệt sau này
                    headers = emptyMap() 
                )
            )
            atLeastOneLinkGenerated = true
            
            // Phần code fetch và parse JSON từ player.php sẽ được bỏ qua trong phiên bản này
            // để bạn có thể kiểm tra urlToFetch.
            // Nếu bạn muốn giữ lại logic fetch M3U8, hãy cho tôi biết.

        } // Kết thúc vòng lặp
        
        return atLeastOneLinkGenerated
    }
}
