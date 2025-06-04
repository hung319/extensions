package com.example.HoatHinh3DProvider // Bạn có thể thay đổi package name này

import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Element
// Dòng "import com.lagradost.cloudstream3.network.र्ज़" đã được XÓA BỎ

// Data class cho response của AJAX call (sẽ dùng trong loadLinks)
// Bạn có thể cần điều chỉnh các trường này dựa trên response thực tế
data class VideoSourceResponse(
    @JsonProperty("status") val status: Boolean?,
    @JsonProperty("type") val type: String?,
    @JsonProperty("link") val link: String?,
    @JsonProperty("subs") val subs: List<SubtitleInfo>?
)

data class SubtitleInfo(
    @JsonProperty("label") val label: String?,
    @JsonProperty("file") val file: String
)

class HoatHinh3DProvider : MainAPI() {
    override var mainUrl = "https://hoathinh3d.name"
    override var name = "HoatHinh3D"
    override val supportedTypes = setOf(TvType.Cartoon)
    override var lang = "vi"
    override val hasMainPage = true
    override val hasChromecastSupport = true // Có thể cần xác minh lại khi loadLinks hoạt động
    override val hasDownloadSupport = true // Có thể cần xác minh lại khi loadLinks hoạt động

    // Helper function để parse các item phim/series
    private fun Element.toSearchResponse(): SearchResponse? {
        val thumbLink = this.selectFirst("a.halim-thumb") ?: return null
        val href = fixUrl(thumbLink.attr("href"))
        val title = this.selectFirst("div.halim-post-title h2.entry-title")?.text()?.trim()
            ?: thumbLink.attr("title")?.trim()
            ?: return null
        // Thử lấy poster từ data-src trước, sau đó mới đến src
        var posterUrl = fixUrlNull(this.selectFirst("figure img.img-responsive")?.attr("data-src"))
        if (posterUrl == null) {
            posterUrl = fixUrlNull(this.selectFirst("figure img.img-responsive")?.attr("src"))
        }
        val latestEpisodeText = this.selectFirst("span.episode")?.text()?.trim()

        return newAnimeSearchResponse(title, href, TvType.Cartoon) {
            this.posterUrl = posterUrl
            if (latestEpisodeText != null) {
                addQuality(latestEpisodeText) // SỬA LỖI: Sử dụng addQuality thay vì addMeta
            }
        }
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse? {
        val url = if (page == 1) mainUrl else "$mainUrl/page/$page/"
        // Thêm trailing slash nếu chưa có cho mainUrl khi page là 1
        val effectiveUrl = if (page == 1 && !url.endsWith("/")) "$url/" else url

        val document = app.get(effectiveUrl).document

        val homePageList = ArrayList<HomePageList>()

        // Lấy mục "Mới Cập Nhật" (dựa trên main.html)
        val newUpdateSection = document.selectFirst("div#sticky-sidebar div#-ajax-box div.halim_box")
                                ?: document.selectFirst("div#-ajax-box div.halim_box") // Fallback
        
        newUpdateSection?.let { section ->
            val sectionTitle = section.selectFirst("div.section-bar h3.section-title span")?.text()?.trim() ?: "Mới Cập Nhật"
            val movies = section.select("article.thumb.grid-item").mapNotNull {
                it.toSearchResponse()
            }
            if (movies.isNotEmpty()) {
                homePageList.add(HomePageList(sectionTitle, movies))
            }
        }
        
        // Thêm section "Đang thịnh hành" (dựa trên main.html)
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
                        // Có thể thêm original_title nếu muốn hiển thị
                        // val originalTitle = card.selectFirst("p.halim-trending-original-title")?.text()?.trim()
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
        return HomePageResponse(homePageList)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val searchUrl = "$mainUrl/search/$query" // Đã xác nhận từ search.html và script
        val document = app.get(searchUrl).document

        return document.select("main#main-contents div.halim_box article.thumb.grid-item").mapNotNull {
            it.toSearchResponse()
        }
    }

    override suspend fun load(url: String): LoadResponse? {
        val document = app.get(url).document

        val movieWrapper = document.selectFirst("div.halim-movie-wrapper.tpl-2.info-movie") ?: return null

        val title = movieWrapper.selectFirst("div.head h1.movie_name")?.text()?.trim() ?: return null
        
        var poster = fixUrlNull(movieWrapper.selectFirst("div.head div.first img")?.attr("data-src"))
        if (poster == null) {
             poster = fixUrlNull(movieWrapper.selectFirst("div.head div.first img")?.attr("src"))
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
        val recommendations = ArrayList<SearchResponse>() // Có thể thêm logic sau

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
                // Fallback: Nếu không có data-attributes, dùng chính epLink làm data.
                // loadLinks sẽ cần fetch trang epLink này để tìm thông tin (ví dụ: halim_cfg).
                epLink 
            }
            
            Episode( // SỬA LỖI: Bỏ tham số 'url' không hợp lệ
                data = episodeData,
                name = epName
                // episode = epName.replace(Regex("[^0-9]"), "").toIntOrNull(), // Có thể parse số tập
                // posterUrl = poster // Poster của tập phim (nếu có)
            )
        }.reversed()

        return newTvSeriesLoadResponse(title, url, TvType.Cartoon, episodes) {
            this.posterUrl = poster
            this.year = year
            this.plot = description
            this.tags = tags
            this.rating = rating
            this.recommendations = recommendations
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        // Hàm này sẽ được triển khai sau theo yêu cầu của bạn.
        println("HoatHinh3DProvider: loadLinks function called with data: $data - NOT IMPLEMENTED YET")
        
        // // Ví dụ cơ bản về cách parse data JSON nếu bạn đã chuẩn bị nó trong `load()`
        // try {
        //     if (data.startsWith("{") && data.endsWith("}")) {
        //         val json = AppUtils.parseJson<Map<String, String>>(data)
        //         val postId = json["postId"]
        //         val episodeSlug = json["slug"]
        //         val serverId = json["server"]
        //         val episodePageUrl = json["episodePageUrl"] // URL của trang xem tập phim

        //         // TODO: Thực hiện AJAX request tại đây với thông tin trên
        //         // val ajaxUrl = "$mainUrl/wp-content/themes/halimmovies/halim-ajax.php"
        //         // val postData = mapOf(
        //         //     "action" to "halim_player_ajax", // Cần xác minh action này
        //         //     "post_id" to postId,
        //         //     "episode_slug" to episodeSlug,
        //         //     "server" to serverId
        //         // )
        //         // val headers = mapOf("X-Requested-With" to "XMLHttpRequest", "Referer" to episodePageUrl)
        //         // val response = app.post(ajaxUrl, data = postData, headers = headers).parsedSafe<VideoSourceResponse>()

        //         // if (response?.status == true && response.link != null) {
        //         //     val videoUrl = response.link
        //         //     val sourceName = "HoatHinh3D Server $serverId"
        //         //     if (response.type == "iframe" && videoUrl.isNotBlank()) {
        //         //         // Cần xử lý iframe, có thể cần gọi hàm Unshorten.unshorten(videoUrl) hoặc loadExtractor
        //         //         // Hoặc nếu iframe là của một host video đã biết (vd: Dailymotion, Facebook), dùng loadExtractor
        //         //         ExtractorApi.loadExtractor(videoUrl, episodePageUrl, subtitleCallback, callback)
        //         //     } else if (videoUrl.isNotBlank()) {
        //         //         callback.invoke(
        //         //             ExtractorLink(
        //         //                 source = sourceName,
        //         //                 name = sourceName,
        //         //                 url = videoUrl,
        //         //                 referer = episodePageUrl, // Quan trọng: referer thường là trang chứa player
        //         //                 quality = Qualities.Unknown.value,
        //         //                 isM3u8 = videoUrl.contains(".m3u8")
        //         //             )
        //         //         )
        //         //     }
        //         //     response.subs?.forEach { sub ->
        //         //         if (sub.file.isNotBlank()) {
        //         //             subtitleCallback(SubtitleFile(sub.label ?: "Phụ đề", sub.file))
        //         //         }
        //         //     }
        //         //     return true
        //         // }
        //     } else {
        //          // Data là URL (fallback), cần fetch trang `data` (chính là episodePageUrl)
        //          // và tìm biến `halim_cfg` hoặc các thông tin tương tự để thực hiện AJAX call
        //          // val doc = app.get(data).document
        //          // val scriptContent = doc.select("script").firstOrNull { it.data().contains("var halim_cfg") }?.data()
        //          // // Parse scriptContent để lấy postId, episodeSlug, serverId rồi làm tương tự như trên
        //     }
        // } catch (e: Exception) {
        //     e.printStackTrace()
        // }
        return false // Trả về false vì chưa có link nào được gọi qua callback
    }
}
