package com.ctemplar.app // Bạn có thể thay đổi package name này

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.LoadResponse.Companion.newMovieLoadResponse
import com.lagradost.cloudstream3.LoadResponse.Companion.newTvSeriesLoadResponse
import org.jsoup.nodes.Element
import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.network.CloudflareKiller
import com.lagradost.cloudstream3.movieproviders.Vote // Or any other provider you want to import
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import com.lagradost.cloudstream3.utils.ExtractorLink

// Đặt tên cho Provider của bạn
class TxnhhProvider : MainAPI() { // Hoặc kế thừa từ HttpVidSrcAPIExtractor nếu trang web của bạn đơn giản hơn
    override var mainUrl = "https://www.txnhh.com"
    override var name = "Txnhh" // Tên sẽ hiển thị trong CloudStream
    override val hasMainPage = true
    override var lang = "en" // Ngôn ngữ mặc định, có thể thay đổi
    override val hasChromecastSupport = true
    override val supportedTypes = setOf(
        TvType.NSFW // Vì đây là nội dung người lớn
    )

    // Interceptor để xử lý Cloudflare nếu cần
    // override val mainPageInterceptors = listOf(CloudflareKiller())

    companion object {
        fun getType(t: String?): TvType {
            return if (t?.contains("series") == true) TvType.TvSeries else TvType.Movie
        }

        fun getQualityFromString(quality: String?): Int {
            return when (quality?.trim()?.lowercase()) {
                "1080p" -> Qualities.P1080.value
                "720p" -> Qualities.P720.value
                "480p" -> Qualities.P480.value
                "360p" -> Qualities.P360.value
                else -> Qualities.Unknown.value
            }
        }

        fun parseDuration(durationString: String?): Int? {
            if (durationString.isNullOrBlank()) return null
            return durationString.lowercase().filter { it.isDigit() }.toIntOrNull()?.let {
                if (durationString.contains("min")) it * 60 else it
            }
        }
    }

    // Dùng để parse JSON từ script trên trang chủ
    data class HomePageItem(
        @JsonProperty("i") val image: String?, // Image URL
        @JsonProperty("u") val url: String?,   // Relative URL
        @JsonProperty("t") val title: String?, // Title
        @JsonProperty("tf") val titleFallback: String?, // Fallback title
        @JsonProperty("n") val count: String?, // Video count (string "123,456" or "0")
        @JsonProperty("ty") val type: String? // "cat", "search", or null
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val document = app.get(mainUrl).document
        val homePageList = ArrayList<HomePageList>()

        // Trích xuất script chứa danh sách categories/items
        val scriptElements = document.select("script:containsData(xv.cats.write_thumb_block_list)")
        if (scriptElements.isNotEmpty()) {
            val scriptContent = scriptElements.html()
            // Regex để lấy phần array bên trong hàm write_thumb_block_list
            // Cần regex cẩn thận hơn nếu cấu trúc phức tạp
            val regex = Regex("""xv\.cats\.write_thumb_block_list\s*\((.+?),\s*['"]home-cat-list['"]\s*\)""")
            val matchResult = regex.find(scriptContent)
            
            if (matchResult != null && matchResult.groupValues.size > 1) {
                var arrayString = matchResult.groupValues[1]
                // Loại bỏ các hàm JavaScript hoặc đối tượng không phải JSON thuần túy nếu có
                // Đây là một bước đơn giản hóa, có thể cần xử lý phức tạp hơn
                arrayString = arrayString.replace(Regex("""\{\s*i:\s*[^,]+?\.png\s*,\s*u:\s*[^,]+?,\s*tf:\s*[^,]+?,\s*t:\s*[^,]+?,\s*n:\s*0,\s*w:\s*\d+,\s*no_rotate:\s*true,\s*tbk:\s*false\s*}"""), "")
                                      .replace(Regex(""",\s*\]"""), "]") // Dọn dẹp dấu phẩy thừa cuối array
                                      .trim()

                // Kiểm tra xem có phải là một JSON array hợp lệ không
                if (arrayString.startsWith("[") && arrayString.endsWith("]")) {
                    try {
                        val items = parseJson<List<HomePageItem>>(arrayString)
                        // Nhóm các items có type "cat" hoặc "search" thành một HomePageList "Categories"
                        // Và các items khác (ví dụ: "Today's selection") thành các HomePageList riêng nếu chúng trỏ đến trang danh sách video
                        
                        val categories = ArrayList<SearchResponse>()
                        val otherSections = mutableMapOf<String, ArrayList<SearchResponse>>()

                        items.forEach { item ->
                            val itemTitle = item.title ?: item.titleFallback ?: "Unknown Section"
                            val itemUrl = if (item.url?.startsWith("/") == true) mainUrl + item.url else item.url
                            val itemPoster = if (item.image?.startsWith("//") == true) "https:${item.image}" else item.image

                            if (itemUrl != null) {
                                // Nếu là category link (dựa trên phân tích home.html)
                                if (item.type == "cat" || item.type == "search") {
                                    categories.add(
                                        newMovieSearchResponse(
                                            itemTitle,
                                            itemUrl, // URL này sẽ được dùng trong search() hoặc loadPage()
                                            this.name
                                        ) {
                                            this.posterUrl = itemPoster
                                        }
                                    )
                                } else if (item.url == "/todays-selection") { // Ví dụ mục "Today's selection"
                                     // Mục này sẽ tải danh sách video trực tiếp, nên ta sẽ gọi search()
                                     // Hoặc tạo một list item đặc biệt để loadPage xử lý
                                     // Hiện tại, coi nó như một category để search() xử lý
                                     categories.add(
                                        newMovieSearchResponse(
                                            itemTitle,
                                            itemUrl,
                                            this.name
                                        ) {
                                            this.posterUrl = itemPoster
                                        }
                                    )
                                }
                                // Các mục khác có thể được xử lý tương tự hoặc tạo HomePageList riêng
                                // Dựa trên `hot.html` (Today's selection), có vẻ nó sẽ là 1 list video
                                // => HomePageList(itemTitle, videoList)
                                // Tuy nhiên, để đơn giản, ta có thể cho search() xử lý hết
                            }
                        }
                        if (categories.isNotEmpty()) {
                            homePageList.add(HomePageList("Categories", categories, true)) // true nếu có thể cuộn ngang
                        }
                        
                        // Ví dụ cho "Today's selection" nếu nó load video trực tiếp (dựa trên hot.html)
                        // Vì hot.html có cấu trúc giống asia.html (search), nên ta sẽ để hàm search xử lý URL của "Today's selection"
                        // Hoặc có thể fetch và parse hot.html ngay tại đây nếu muốn nó hiển thị video ngay trên homepage
                        // For now, keep it simple: "Today's selection" will be a link that opens with search()

                    } catch (e: Exception) {
                        logError(e) // Ghi log lỗi nếu parse JSON thất bại
                    }
                }
            }
        }
        // Nếu không có script hoặc parse lỗi, có thể trả về danh sách rỗng hoặc mặc định
        if (homePageList.isEmpty()) {
             // Thêm các category mặc định nếu cần
            homePageList.add(HomePageList("Popular Categories (Example)", listOf(
                newMovieSearchResponse("Asian Woman", "$mainUrl/search/asian_woman", this.name),
                newMovieSearchResponse("Today's Selection", "$mainUrl/todays-selection", this.name),
            )))
        }
        return HomePageResponse(homePageList)
    }

    private fun Element.toSearchResponse(): SearchResponse? {
        val titleElement = this.selectFirst(".thumb-under p a") ?: return null
        val title = titleElement.attr("title")
        val href = mainUrl + titleElement.attr("href")
        val posterUrl = this.selectFirst(".thumb img")?.attr("data-src")?.let { if (it.startsWith("//")) "https:$it" else it }

        val metadata = this.selectFirst(".thumb-under p.metadata")
        val durationText = metadata?.ownText()?.trim()
        val qualityText = metadata?.selectFirst("span.video-hd")?.text()?.trim()
        
        return newMovieSearchResponse(title, href, TvType.NSFW) { // Gán TvType.NSFW
            this.posterUrl = posterUrl
            this.quality = getQualityFromString(qualityText)
            this.duration = parseDuration(durationText)
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        // Kiểm tra xem query có phải là một URL đầy đủ không (từ getMainPage)
        val searchUrl = if (query.startsWith("http")) {
            query
        } else {
            // Nếu là từ khóa tìm kiếm
            "$mainUrl/search/$query"
        }

        val document = app.get(searchUrl).document
        val searchResults = ArrayList<SearchResponse>()

        document.select("div.mozaique div.thumb-block").forEach { element ->
            element.toSearchResponse()?.let { searchResults.add(it) }
        }
        return searchResults
    }
    
    // Hàm loadPage có thể được dùng nếu một mục trên homepage là danh sách video
    // và bạn muốn load nó mà không qua hàm search.
    // Hiện tại, cấu trúc getMainPage đang trỏ các mục category/selection vào search.
    // suspend fun loadPage(url: String): List<SearchResponse> {
    //     val document = app.get(url).document
    //     val results = ArrayList<SearchResponse>()
    //     document.select("div.mozaique div.thumb-block").forEach { element ->
    //         element.toSearchResponse()?.let { results.add(it) }
    //     }
    //     return results
    // }


    override suspend fun load(url: String): LoadResponse? {
        val document = app.get(url).document

        val title = document.selectFirst(".video-title strong")?.text() 
            ?: document.selectFirst("meta[property=og:title]")?.attr("content") 
            ?: "Unknown Title"
        
        val poster = document.selectFirst("meta[property=og:image]")?.attr("content")?.let { if (it.startsWith("//")) "https:$it" else it }
        val description = document.selectFirst("p.video-description")?.text()?.trim()
        
        val tags = document.select(".metadata-row.video-tags a:not(#suggestion)")
            .mapNotNull { it.text()?.trim() }
            .filter { it.isNotEmpty() }

        // Trích xuất thông tin video links (sẽ dùng cho loadLinks sau)
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
        
        // Lưu trữ các link này vào data để loadLinks có thể truy cập
        // Hoặc nếu loadLinks được gọi ngay sau load, có thể truyền trực tiếp
        // Hiện tại, ta sẽ tạo data string để loadLinks sau này parse.
        val videoData = mutableListOf<String>()
        hlsLink?.let { videoData.add("hls:$it") }
        lowQualityLink?.let { videoData.add("low:$it") }
        highQualityLink?.let { videoData.add("high:$it") }


        // Related videos
        val relatedVideos = ArrayList<SearchResponse>()
        val relatedScript = document.select("script:containsData(var video_related)")
        if (relatedScript.isNotEmpty()) {
            val scriptContent = relatedScript.html()
            val jsonRegex = Regex("""var video_related\s*=\s*(\[.*?\]);""")
            val match = jsonRegex.find(scriptContent)
            if (match != null && match.groupValues.size > 1) {
                val jsonArrayString = match.groupValues[1]
                try {
                    // Đây là cấu trúc JSON dự kiến cho related video items
                    data class RelatedItem(
                        @JsonProperty("u") val u: String?,
                        @JsonProperty("i") val i: String?,
                        @JsonProperty("tf") val tf: String?, // Title
                        @JsonProperty("d") val d: String?  // Duration
                        // Thêm các trường khác nếu cần: r (rating), n (views)
                    )
                    val relatedItems = parseJson<List<RelatedItem>>(jsonArrayString)
                    relatedItems.forEach { related ->
                        if (related.u != null && related.tf != null) {
                            relatedVideos.add(newMovieSearchResponse(
                                related.tf,
                                mainUrl + related.u,
                                TvType.NSFW // Gán TvType.NSFW
                            ) {
                                this.posterUrl = related.i?.let { if (it.startsWith("//")) "https:$it" else it }
                                this.duration = parseDuration(related.d)
                            })
                        }
                    }
                } catch (e: Exception) {
                    logError(e)
                }
            }
        }
        
        // Vì là nội dung NSFW và thường là các video đơn lẻ
        return newMovieLoadResponse(
            title,
            url,
            TvType.NSFW, // Quan trọng: Đặt đúng type
            url // Truyền data chứa các link video cho loadLinks, hoặc URL gốc để loadLinks tự fetch lại
            // Hoặc truyền videoData.joinToString(";") để loadLinks xử lý
        ) {
            this.posterUrl = poster
            this.plot = description
            this.tags = tags
            this.recommendations = relatedVideos
            // this.data = videoData.joinToString("||") // Lưu các link trích xuất được để loadLinks sử dụng
        }
    }

    // override suspend fun loadLinks(
    //     data: String, // data này sẽ là url hoặc chuỗi chứa các link đã trích xuất
    //     isCasting: Boolean,
    //     subtitleCallback: (SubtitleFile) -> Unit,
    //     callback: (ExtractorLink) -> Unit
    // ): Boolean {
    //     // TODO: Implement loadLinks when requested
    //     // If data contains pre-extracted links:
    //     // val links = data.split("||")
    //     // links.forEach { linkData ->
    //     //     val parts = linkData.split(":", limit = 2)
    //     //     val type = parts[0]
    //     //     val videoUrl = parts[1]
    //     //     when (type) {
    //     //         "hls" -> callback.invoke(ExtractorLink(this.name, "HLS", videoUrl, mainUrl, Qualities.Unknown.value, isM3u8 = true))
    //     //         "low" -> callback.invoke(ExtractorLink(this.name, "Low Quality", videoUrl, mainUrl, Qualities.P360.value))
    //     //         "high" -> callback.invoke(ExtractorLink(this.name, "High Quality", videoUrl, mainUrl, Qualities.P720.value)) // Hoặc P1080
    //     //     }
    //     // }
    //     // return true

    //     // If data is just the URL and loadLinks needs to re-fetch or parse:
    //     // val document = app.get(data).document (if needed)
    //     // ... extract HLS/MP4 links ...
    //     // callback.invoke(...)
    //     println("loadLinks function is not yet implemented as per request.")
    //     return false 
    // }
}
