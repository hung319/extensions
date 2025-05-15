package com.ctemplar.app // Hoặc package của bạn (ví dụ: com.yourname.txnhhprovider)

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Element
import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.SearchQuality // Đảm bảo import này

// import com.lagradost.cloudstream3.network.CloudflareKiller // Bỏ comment nếu trang web dùng Cloudflare

class TxnhhProvider : MainAPI() {
    override var mainUrl = "https://www.txnhh.com"
    override var name = "Txnhh"
    override val hasMainPage = true
    override var lang = "en" // Bạn có thể thay đổi nếu trang web có nhiều ngôn ngữ
    override val hasChromecastSupport = true // Mặc định là true, cần test
    override val supportedTypes = setOf(
        TvType.NSFW // Vì nội dung của trang là dành cho người lớn
    )

    // override val mainPageInterceptors = listOf(CloudflareKiller()) // Bỏ comment nếu cần CloudflareKiller

    companion object {
        // Hàm này map chuỗi chất lượng từ trang web sang enum SearchQuality của CloudStream
        fun getQualityFromString(quality: String?): SearchQuality? {
            return when (quality?.trim()?.lowercase()) {
                "1080p" -> SearchQuality.HD // Hoặc SearchQuality.FourK nếu phù hợp và có sẵn
                "720p" -> SearchQuality.HD
                "480p" -> SearchQuality.SD
                "360p" -> SearchQuality.SD // Hoặc SearchQuality.Cam nếu chất lượng rất thấp
                else -> null // Không xác định được chất lượng
            }
        }

        // Hàm này chuyển đổi chuỗi thời lượng (ví dụ "13min", "1h 5min") sang tổng số giây
        fun parseDuration(durationString: String?): Int? {
            if (durationString.isNullOrBlank()) return null
            var totalSeconds = 0
            // Regex tìm "Xh" (giờ)
            Regex("""(\d+)\s*h""").find(durationString)?.groupValues?.get(1)?.toIntOrNull()?.let {
                totalSeconds += it * 3600
            }
            // Regex tìm "Xmin" (phút)
            Regex("""(\d+)\s*min""").find(durationString)?.groupValues?.get(1)?.toIntOrNull()?.let {
                totalSeconds += it * 60
            }
            // Regex tìm "Xs" (giây) nếu có
            Regex("""(\d+)\s*s""").find(durationString)?.groupValues?.get(1)?.toIntOrNull()?.let {
                totalSeconds += it
            }
            return if (totalSeconds > 0) totalSeconds else null
        }
    }

    // Data class để parse JSON từ script trên trang chủ
    data class HomePageItem(
        @JsonProperty("i") val image: String?,
        @JsonProperty("u") val url: String?,
        @JsonProperty("t") val title: String?,
        @JsonProperty("tf") val titleFallback: String?, // title thay thế
        @JsonProperty("n") val count: String?, // Số lượng video (có thể là "0" hoặc "123,456")
        @JsonProperty("ty") val type: String? // Loại item, ví dụ: "cat", "search"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val document = app.get(mainUrl).document // Tải HTML trang chủ
        val homePageList = ArrayList<HomePageList>()

        // Tìm script chứa dữ liệu cho các mục trên trang chủ
        val scriptElements = document.select("script:containsData(xv.cats.write_thumb_block_list)")
        if (scriptElements.isNotEmpty()) {
            val scriptContent = scriptElements.html()
            // Regex để trích xuất phần JSON array từ lời gọi hàm JavaScript
            val regex = Regex("""xv\.cats\.write_thumb_block_list\s*\((.*?),""") // Lấy tham số đầu tiên
            val matchResult = regex.find(scriptContent)
            
            if (matchResult != null && matchResult.groupValues.size > 1) {
                var arrayString = matchResult.groupValues[1].trim() // Lấy chuỗi JSON và loại bỏ khoảng trắng thừa

                // **Cách 2: Bỏ qua bước replace phức tạp, thử parse trực tiếp**
                // Nếu arrayString không phải là JSON chuẩn (ví dụ key không có ngoặc kép),
                // AppUtils.parseJson vẫn có thể xử lý được trong nhiều trường hợp.

                if (arrayString.startsWith("[") && arrayString.endsWith("]")) {
                    try {
                        val items = AppUtils.parseJson<List<HomePageItem>>(arrayString)
                        
                        // Nhóm các mục thành một list "Browse Sections"
                        // Các mục này khi click vào sẽ được xử lý bởi hàm search()
                        val sectionItems = ArrayList<SearchResponse>()
                        items.forEach { item ->
                            val itemTitle = item.title ?: item.titleFallback ?: "Unknown Section"
                            // Đảm bảo URL là tuyệt đối
                            val itemUrl = if (item.url?.startsWith("/") == true) mainUrl + item.url else item.url
                            // Đảm bảo URL ảnh là tuyệt đối
                            val itemPoster = if (item.image?.startsWith("//") == true) "https:${item.image}" else item.image

                            if (itemUrl != null) {
                                // Loại bỏ các mục không phải video/category chính (ví dụ: link game, stories)
                                // Dựa vào các pattern đã biết từ home.html (ví dụ: 'nutaku.net', 'sexstories.com')
                                if (!itemUrl.contains("nutaku.net") && !itemUrl.contains("sexstories.com")) {
                                    sectionItems.add(
                                        newMovieSearchResponse( // Sử dụng hàm helper của CloudStream
                                            name = itemTitle,
                                            url = itemUrl, // URL này sẽ được search() xử lý
                                            type = TvType.NSFW // Tất cả đều là NSFW
                                        ) {
                                            this.posterUrl = itemPoster
                                        }
                                    )
                                }
                            }
                        }
                        if (sectionItems.isNotEmpty()) {
                            homePageList.add(HomePageList("Browse Sections", sectionItems, true))
                        }
                    } catch (e: Exception) {
                        // Nếu parse lỗi, ghi log để debug
                        System.err.println("Failed to parse HomePage JSON: ${e.message}")
                        e.printStackTrace()
                    }
                }
            }
        }
        // Nếu không parse được gì từ script, có thể thêm các mục mặc định
        if (homePageList.isEmpty()) {
            homePageList.add(HomePageList("Default Sections (Example)", listOf(
                newMovieSearchResponse(name = "Asian Woman", url = "$mainUrl/search/asian_woman", type = TvType.NSFW) {},
                newMovieSearchResponse(name = "Today's Selection", url = "$mainUrl/todays-selection", type = TvType.NSFW) {},
                newMovieSearchResponse(name = "Hardcore", url = "$mainUrl/search/hardcore?top", type = TvType.NSFW) {}
            )))
        }
        return HomePageResponse(homePageList)
    }

    // Hàm private để chuyển đổi một Element (HTML) thành SearchResponse
    private fun Element.toSearchResponse(): SearchResponse? {
        val titleElement = this.selectFirst(".thumb-under p a") ?: return null
        val title = titleElement.attr("title")
        // Đảm bảo URL là tuyệt đối
        val href = mainUrl + titleElement.attr("href") 
        // Đảm bảo URL ảnh là tuyệt đối và lấy từ data-src
        val posterUrl = this.selectFirst(".thumb img")?.attr("data-src")?.let { if (it.startsWith("//")) "https:$it" else it }

        val metadata = this.selectFirst(".thumb-under p.metadata")
        val durationText = metadata?.ownText()?.trim() // Phần text chứa thời lượng, ví dụ "13min"
        val qualityText = metadata?.selectFirst("span.video-hd")?.text()?.trim() // Phần text chứa chất lượng, ví dụ "1080p"
        
        // Tạo SearchResponse
        return newMovieSearchResponse(
            name = title,
            url = href,
            type = TvType.NSFW // Tất cả video trên trang này là NSFW
        ) {
            this.posterUrl = posterUrl
            this.quality = getQualityFromString(qualityText) // Gán chất lượng đã parse
            // Tạm thời bỏ qua 'length' ở đây nếu nó gây lỗi biên dịch hoặc không quan trọng bằng ở trang load
            // parseDuration(durationText)?.let { this.length = it }
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        // Xây dựng URL dựa trên query: có thể là từ khóa hoặc URL đầy đủ từ getMainPage
        val searchUrl = if (query.startsWith("http")) {
            query 
        } else {
            "$mainUrl/search/$query" // URL cho tìm kiếm từ khóa
        }

        val document = app.get(searchUrl).document // Tải HTML của trang tìm kiếm/danh mục
        val searchResults = ArrayList<SearchResponse>()

        // Chọn tất cả các video item và chuyển đổi chúng
        document.select("div.mozaique div.thumb-block").forEach { element ->
            element.toSearchResponse()?.let { searchResults.add(it) }
        }
        
        // TODO: Xử lý phân trang (pagination) nếu có
        // Ví dụ: tìm thẻ `div.pagination a.next` hoặc các link số trang

        return searchResults
    }

    override suspend fun load(url: String): LoadResponse? {
        val document = app.get(url).document // Tải HTML của trang chi tiết video

        // Lấy tiêu đề video
        val title = document.selectFirst(".video-title strong")?.text() 
            ?: document.selectFirst("meta[property=og:title]")?.attr("content") 
            ?: "Unknown Title"
        
        // Lấy ảnh poster
        val poster = document.selectFirst("meta[property=og:image]")?.attr("content")?.let { if (it.startsWith("//")) "https:$it" else it }
        
        // Lấy mô tả video
        val description = document.selectFirst("p.video-description")?.text()?.trim()
        
        // Lấy tags
        val tags = document.select(".metadata-row.video-tags a:not(#suggestion)")
            .mapNotNull { it.text()?.trim() }
            .filter { it.isNotEmpty() }

        // Trích xuất các link video trực tiếp từ script
        val scriptElements = document.select("script:containsData(html5player.setVideoUrlLow)")
        var hlsLink: String? = null
        var lowQualityLink: String? = null
        var highQualityLink: String? = null

        if (scriptElements.isNotEmpty()) {
            val scriptContent = scriptElements.html()
            hlsLink = Regex("""html5player\.setVideoHLS\s*\(\s*['"](.*?)['"]\s*\)""").find(scriptContent)?.groupValues?.get(1)
            lowQualityLink = Regex("""html5player\.setVideoUrlLow\s*\(\s*['"](.*?)['"]\s*\)""").find(scriptContent)?.groupValues?.get(1)
            highQualityLink = Regex("""html5player\.setVideoUrlHigh\s*\(\s*['"](.*?)['"]\s*\)""").find(scriptContent)?.groupValues?.get(1)
        }
        
        // Chuẩn bị dữ liệu video_data để truyền cho loadLinks
        val videoData = mutableListOf<String>()
        hlsLink?.let { videoData.add("hls:$it") }
        lowQualityLink?.let { videoData.add("low:$it") }
        highQualityLink?.let { videoData.add("high:$it") }

        // Parse video liên quan
        val relatedVideos = ArrayList<SearchResponse>()
        val relatedScript = document.select("script:containsData(var video_related)")
        if (relatedScript.isNotEmpty()) {
            val scriptContentRelated = relatedScript.html()
            val jsonRegexRelated = Regex("""var video_related\s*=\s*(\[.*?\]);""")
            val matchRelated = jsonRegexRelated.find(scriptContentRelated)
            if (matchRelated != null && matchRelated.groupValues.size > 1) {
                val jsonArrayStringRelated = matchRelated.groupValues[1]
                try {
                    // Data class cho item video liên quan
                    data class RelatedItem(
                        @JsonProperty("u") val u: String?, // URL tương đối
                        @JsonProperty("i") val i: String?, // Ảnh
                        @JsonProperty("tf") val tf: String?, // Tiêu đề
                        @JsonProperty("d") val d: String?  // Thời lượng (chuỗi)
                    )
                    val relatedItems = AppUtils.parseJson<List<RelatedItem>>(jsonArrayStringRelated)
                    relatedItems.forEach { related ->
                        if (related.u != null && related.tf != null) {
                            relatedVideos.add(newMovieSearchResponse(
                                name = related.tf,
                                url = mainUrl + related.u, // Tạo URL tuyệt đối
                                type = TvType.NSFW
                            ) {
                                this.posterUrl = related.i?.let { if (it.startsWith("//")) "https:$it" else it }
                                // Tạm thời bỏ qua length cho related items nếu gây lỗi
                                // parseDuration(related.d)?.let { this.length = it } 
                            })
                        }
                    }
                } catch (e: Exception) {
                     e.printStackTrace() // Log lỗi nếu parse JSON video liên quan thất bại
                }
            }
        }
        
        // Parse thời lượng từ thẻ meta og:duration nếu có
        var durationInSeconds: Int? = null
        document.selectFirst("meta[property=og:duration]")?.attr("content")?.let { durationMeta ->
            // Format của og:duration là ISO 8601 duration format, ví dụ "PT2H25M23S"
            try {
                var tempDuration = 0
                Regex("""PT(?:(\d+)H)?(?:(\d+)M)?(?:(\d+)S)?""").find(durationMeta)?.let { match ->
                    match.groupValues.getOrNull(1)?.toIntOrNull()?.let { tempDuration += it * 3600 } // Giờ
                    match.groupValues.getOrNull(2)?.toIntOrNull()?.let { tempDuration += it * 60 }  // Phút
                    match.groupValues.getOrNull(3)?.toIntOrNull()?.let { tempDuration += it }       // Giây
                }
                if (tempDuration > 0) durationInSeconds = tempDuration
            } catch (e: Exception) {
                e.printStackTrace() // Log lỗi nếu parse duration thất bại
            }
        }

        // Trả về MovieLoadResponse
        return newMovieLoadResponse(
            name = title,
            url = url, // URL gốc của video
            type = TvType.NSFW, // Loại nội dung
            dataUrl = videoData.joinToString("||") // Truyền các link video đã trích xuất cho loadLinks
        ) {
            this.posterUrl = poster
            this.plot = description
            this.tags = tags
            this.recommendations = relatedVideos
            this.duration = durationInSeconds // Gán thời lượng đã parse (nếu có)
            // this.year = ... // Gán năm sản xuất nếu có thể lấy được
        }
    }

    // Hàm loadLinks sẽ được implement sau theo yêu cầu
    // override suspend fun loadLinks(
    //     data: String, // data này sẽ là chuỗi videoData.joinToString("||") từ hàm load
    //     isCasting: Boolean,
    //     subtitleCallback: (SubtitleFile) -> Unit,
    //     callback: (ExtractorLink) -> Unit
    // ): Boolean {
    //     val links = data.split("||")
    //     var hasLinks = false
    //     links.forEach { linkData ->
    //         if (linkData.isNotBlank()) {
    //             val parts = linkData.split(":", limit = 2)
    //             if (parts.size == 2) {
    //                 val type = parts[0]
    //                 val videoUrl = parts[1]
    //                 hasLinks = true
    //                 when (type) {
    //                     "hls" -> callback.invoke(ExtractorLink(this.name, "HLS", videoUrl, mainUrl, Qualities.Unknown.value, isM3u8 = true))
    //                     "low" -> callback.invoke(ExtractorLink(this.name, "Low Quality", videoUrl, mainUrl, Qualities.P360.value)) // Hoặc SD
    //                     "high" -> callback.invoke(ExtractorLink(this.name, "High Quality", videoUrl, mainUrl, Qualities.P720.value)) // Hoặc HD
    //                 }
    //             }
    //         }
    //     }
    //     return hasLinks
    // }
}
