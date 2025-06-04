package com.example.HoatHinh3DProvider // Bạn có thể thay đổi package name này

import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Element

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

    // Helper function để parse các item phim/series
    private fun Element.toSearchResponse(): SearchResponse? {
        val thumbLink = this.selectFirst("a.halim-thumb") ?: return null
        val href = fixUrl(thumbLink.attr("href"))
        val title = this.selectFirst("div.halim-post-title h2.entry-title")?.text()?.trim()
            ?: thumbLink.attr("title")?.trim()
            ?: return null
        val posterUrl = fixUrlNull(this.selectFirst("figure img.img-responsive")?.attr("src"))
        val latestEpisodeText = this.selectFirst("span.episode")?.text()?.trim()

        return newAnimeSearchResponse(title, href, TvType.Cartoon) {
            this.posterUrl = posterUrl
            if (latestEpisodeText != null) {
                // Không có link riêng cho tập mới nhất ở đây, nên chỉ hiển thị text
                 addMeta(latestEpisodeText)
            }
        }
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse? {
        // Trang chủ có vẻ là mainUrl, các trang tiếp theo là /page/{page_number}
        val url = if (page == 1) mainUrl else "$mainUrl/page/$page/"
        val document = app.get(url).document

        val homePageList = ArrayList<HomePageList>()

        // Lấy mục "Mới Cập Nhật" (dựa trên main.html)
        // Trong main.html, mục "Mới Cập Nhật" nằm trong div có id="-ajax-box" sau đó là div.halim_box
        val newUpdateSection = document.selectFirst("div#sticky-sidebar div#-ajax-box div.halim_box") 
                                ?: document.selectFirst("div#-ajax-box div.halim_box") // Fallback selector
        
        newUpdateSection?.let { section ->
            val sectionTitle = section.selectFirst("div.section-bar h3.section-title span")?.text()?.trim() ?: "Mới Cập Nhật"
            val movies = section.select("article.thumb.grid-item").mapNotNull {
                it.toSearchResponse()
            }
            if (movies.isNotEmpty()) {
                homePageList.add(HomePageList(sectionTitle, movies))
            }
        }
        
        // Thêm các section khác nếu muốn, ví dụ "Đang thịnh hành"
        val trendingSection = document.selectFirst("div.halim-trending-slider")
        trendingSection?.let { section ->
            val sectionTitle = section.selectFirst("div.section-bar h3.section-title span")?.text()?.trim() ?: "Đang Thịnh Hành"
            val movies = section.select("div.halim-trending-card").mapNotNull { card ->
                val linkTag = card.selectFirst("a.halim-trending-link")
                val href = fixUrlNull(linkTag?.attr("href"))
                val title = card.selectFirst("h3.halim-trending-title-text")?.text()?.trim()
                val posterUrl = fixUrlNull(card.selectFirst("img.halim-trending-poster-image")?.attr("src"))
                
                if (href != null && title != null) {
                    newAnimeSearchResponse(title, href, TvType.Cartoon) {
                        this.posterUrl = posterUrl
                    }
                } else {
                    null
                }
            }
            if (movies.isNotEmpty()) {
                homePageList.add(HomePageList(sectionTitle, movies, true)) // isHorizontal = true for slider
            }
        }


        if (homePageList.isEmpty()) {
            return null
        }
        return HomePageResponse(homePageList)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        // URL tìm kiếm: https://hoathinh3d.name/search/{query} (dựa trên search.html và script ở main.html)
        val searchUrl = "$mainUrl/search/$query"
        val document = app.get(searchUrl).document

        // Kết quả tìm kiếm nằm trong main#main-contents div.halim_box article.thumb.grid-item (dựa trên search.html)
        return document.select("main#main-contents div.halim_box article.thumb.grid-item").mapNotNull {
            it.toSearchResponse()
        }
    }

    override suspend fun load(url: String): LoadResponse? {
        val document = app.get(url).document // url này là link đến trang chi tiết phim (load.html)

        val movieWrapper = document.selectFirst("div.halim-movie-wrapper.tpl-2.info-movie") ?: return null

        val title = movieWrapper.selectFirst("div.head h1.movie_name")?.text()?.trim() ?: return null
        val poster = fixUrlNull(movieWrapper.selectFirst("div.head div.first img")?.attr("src"))
        
        // Năm sản xuất (dựa trên load.html)
        val yearText = movieWrapper.selectFirst("div.last span.released a")?.text()?.trim()
        val year = yearText?.toIntOrNull()
        
        // Mô tả (dựa trên load.html, ưu tiên og:description)
        val description = document.selectFirst("meta[property=og:description]")?.attr("content")?.trim()
            ?: movieWrapper.selectFirst("div.entry-content div.wp-content")?.text()?.trim()

        // Thể loại (dựa trên load.html)
        val tags = movieWrapper.select("div.last div.list_cate a").mapNotNull { it.text()?.trim() }
        
        // Đánh giá (dựa trên load.html)
        val ratingScoreText = movieWrapper.selectFirst("div.last span.halim-rating-score")?.text()?.trim()
        val rating = ratingScoreText?.let {
            (it.toFloatOrNull()?.times(1000))?.toInt() // CloudStream rating is 0-10000
        }

        // Danh sách tập phim (dựa trên load.html)
        // Các tập nằm trong div#halim-list-server ul.halim-list-eps li.halim-episode
        val episodes = document.select("div#halim-list-server ul.halim-list-eps li.halim-episode").mapNotNull { epElement ->
            val epA = epElement.selectFirst("a") ?: return@mapNotNull null
            val epLink = fixUrl(epA.attr("href"))
            
            // Tên tập có thể là title của thẻ a hoặc text của span bên trong
            val epNameCandidate = epA.attr("title")?.takeIf { it.isNotBlank() } 
                ?: epA.selectFirst("span")?.text()?.trim()
                ?: "Tập ?" // Tên mặc định nếu không tìm thấy
            
            // Đảm bảo tên tập có chữ "Tập"
            val epName = if (epNameCandidate.startsWith("Tập ", ignoreCase = true) || epNameCandidate.matches(Regex("^\\d+$"))) {
                if (epNameCandidate.matches(Regex("^\\d+$"))) "Tập $epNameCandidate" else epNameCandidate
            } else {
                "Tập $epNameCandidate"
            }


            // Lấy data cho loadLinks từ các thuộc tính data-* của span bên trong thẻ a
            val spanInsideA = epA.selectFirst("span.halim-btn")
            val postId = spanInsideA?.attr("data-post-id")
            val serverId = spanInsideA?.attr("data-server")
            val episodeSlug = spanInsideA?.attr("data-episode-slug")

            // Lưu trữ thông tin cần thiết để loadLinks dưới dạng JSON string
            val episodeData = if (postId != null && serverId != null && episodeSlug != null) {
                """{"postId":"$postId", "slug":"$episodeSlug", "server":"$serverId", "episodePageUrl":"$epLink"}"""
            } else {
                // Fallback nếu không có data attributes, loadLinks sẽ cần fetch trang tập phim để lấy thông tin
                epLink 
            }
            
            Episode(
                data = episodeData, // Sẽ được dùng bởi loadLinks
                name = epName,
                url = epLink, // URL của trang xem tập đó (dùng để tham khảo hoặc fallback)
                // episode = epName.replace(Regex("[^0-9]"), "").toIntOrNull() // Lấy số tập nếu có
            )
        }.reversed() // Thường danh sách trên web là từ mới đến cũ, CloudStream muốn từ cũ đến mới

        // Xác định đây là TvSeries vì có danh sách tập
        return newTvSeriesLoadResponse(title, url, TvType.Cartoon, episodes) {
            this.posterUrl = poster
            this.year = year
            this.plot = description
            this.tags = tags
            this.rating = rating
            // recommendations = recommendations // Thêm logic lấy phim đề xuất nếu có
        }
    }

    override suspend fun loadLinks(
        data: String, // data từ Episode object (JSON string hoặc URL)
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        // Hàm này sẽ được triển khai sau theo yêu cầu của bạn.
        // Hiện tại, nó sẽ không làm gì cả và trả về false.
        println("HoatHinh3DProvider: loadLinks function called with data: $data - NOT IMPLEMENTED YET")
        // Ví dụ cơ bản về cách parse data JSON nếu bạn đã chuẩn bị nó trong `load()`
        // try {
        //     if (data.startsWith("{") && data.endsWith("}")) {
        //         val json = MainActivity.getMapper().readValue(data, Map::class.java) as Map<String, String>
        //         val postId = json["postId"]
        //         val episodeSlug = json["slug"]
        //         val serverId = json["server"]
        //         val episodePageUrl = json["episodePageUrl"] 
        //         // TODO: Thực hiện AJAX request tại đây với thông tin trên
        //         // val ajaxUrl = "$mainUrl/wp-content/themes/halimmovies/halim-ajax.php"
        //         // ...
        //     } else {
        //          // Data là URL, cần fetch trang episodePageUrl để lấy config
        //     }
        // } catch (e: Exception) {
        //     e.printStackTrace()
        // }
        return false // Trả về false vì chưa có link nào được gọi qua callback
    }
}
