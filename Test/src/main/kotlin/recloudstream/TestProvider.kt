package com.ctemplar.app // Hoặc package của bạn

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Element
import com.fasterxml.jackson.annotation.JsonProperty
// import com.lagradost.cloudstream3.network.CloudflareKiller // Bỏ comment nếu cần

// Đảm bảo các import này là đúng với phiên bản CloudStream bạn đang dùng
// import com.lagradost.cloudstream3.LoadResponse.Companion.newMovieLoadResponse // Có thể không cần nữa
// import com.lagradost.cloudstream3.LoadResponse.Companion.newTvSeriesLoadResponse // Có thể không cần nữa


class TxnhhProvider : MainAPI() {
    override var mainUrl = "https://www.txnhh.com"
    override var name = "Txnhh"
    override val hasMainPage = true
    override var lang = "en"
    override val hasChromecastSupport = true // Mặc định là true, có thể test lại
    override val supportedTypes = setOf(
        TvType.NSFW
    )

    // override val mainPageInterceptors = listOf(CloudflareKiller()) // Bỏ comment nếu dùng CloudflareKiller

    companion object {
        // getType không được sử dụng trong code hiện tại, có thể bỏ nếu không cần
        // fun getType(t: String?): TvType {
        //     return if (t?.contains("series") == true) TvType.TvSeries else TvType.Movie
        // }

        fun getQualityFromString(quality: String?): SearchQuality? { // Sửa kiểu trả về
            return when (quality?.trim()?.lowercase()) {
                "1080p" -> SearchQuality.P1080
                "720p" -> SearchQuality.P720
                "480p" -> SearchQuality.P480
                "360p" -> SearchQuality.P360
                else -> null 
            }
        }

        fun parseDuration(durationString: String?): Int? {
            if (durationString.isNullOrBlank()) return null
            // Ví dụ: "13min", "2h 30min"
            var totalSeconds = 0
            val minMatch = Regex("""(\d+)\s*min""").find(durationString)
            minMatch?.groupValues?.get(1)?.toIntOrNull()?.let { totalSeconds += it * 60 }
            
            val hMatch = Regex("""(\d+)\s*h""").find(durationString)
            hMatch?.groupValues?.get(1)?.toIntOrNull()?.let { totalSeconds += it * 3600 }

            return if (totalSeconds > 0) totalSeconds else null
        }
    }

    data class HomePageItem(
        @JsonProperty("i") val image: String?,
        @JsonProperty("u") val url: String?,
        @JsonProperty("t") val title: String?,
        @JsonProperty("tf") val titleFallback: String?,
        @JsonProperty("n") val count: String?,
        @JsonProperty("ty") val type: String?
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val document = app.get(mainUrl).document
        val homePageList = ArrayList<HomePageList>()

        val scriptElements = document.select("script:containsData(xv.cats.write_thumb_block_list)")
        if (scriptElements.isNotEmpty()) {
            val scriptContent = scriptElements.html()
            val regex = Regex("""xv\.cats\.write_thumb_block_list\s*\((.+?),\s*['"]home-cat-list['"]\s*\)""")
            val matchResult = regex.find(scriptContent)
            
            if (matchResult != null && matchResult.groupValues.size > 1) {
                var arrayString = matchResult.groupValues[1]
                // Đơn giản hóa việc dọn dẹp, có thể cần tinh chỉnh
                arrayString = arrayString.replace(Regex("""\{\s*i:\s*[^,]+?\.png\s*,\s*u:\s*[^,]+?,\s*tf:\s*[^,]+?,\s*t:\s*[^,]+?,\s*n:\s*0,\s*w:\s*\d+,\s*no_rotate:\s*true,\s*tbk:\s*false\s*}"""), "")
                                      .replace(Regex(""",\s*\]"""), "]")
                                      .trim()

                if (arrayString.startsWith("[") && arrayString.endsWith("]")) {
                    try {
                        val items = AppUtils.parseJson<List<HomePageItem>>(arrayString)
                        val categories = ArrayList<SearchResponse>()
                        // val specialSections = mutableMapOf<String, ArrayList<SearchResponse>>()

                        items.forEach { item ->
                            val itemTitle = item.title ?: item.titleFallback ?: "Unknown Section"
                            val itemUrl = if (item.url?.startsWith("/") == true) mainUrl + item.url else item.url
                            val itemPoster = if (item.image?.startsWith("//") == true) "https:${item.image}" else item.image

                            if (itemUrl != null) {
                                // Tất cả các mục từ trang chủ sẽ được coi là category/link để search() xử lý
                                categories.add(
                                    newMovieSearchResponse( // Đảm bảo đây là hàm đúng
                                        name = itemTitle,
                                        url = itemUrl, // URL này sẽ được dùng trong search()
                                        apiName = this.name,
                                        type = TvType.NSFW // Cung cấp TvType
                                    ) {
                                        this.posterUrl = itemPoster
                                    }
                                )
                            }
                        }
                        if (categories.isNotEmpty()) {
                            homePageList.add(HomePageList("Browse Sections", categories, true))
                        }
                        
                    } catch (e: Exception) {
                        // Sử dụng cơ chế log lỗi của CloudStream hoặc printStackTrace
                        // MainAPIKt.logError(this, e) // Thử cách này
                        e.printStackTrace()
                    }
                }
            }
        }
        if (homePageList.isEmpty()) {
            homePageList.add(HomePageList("Default Sections (Example)", listOf(
                newMovieSearchResponse("Asian Woman", "$mainUrl/search/asian_woman", this.name, type = TvType.NSFW),
                newMovieSearchResponse("Today's Selection", "$mainUrl/todays-selection", this.name, type = TvType.NSFW),
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
        
        val durationSeconds = parseDuration(durationText)

        return newMovieSearchResponse(title, href, TvType.NSFW) { // TvType được cung cấp
            this.posterUrl = posterUrl
            this.quality = getQualityFromString(qualityText) // Đã sửa hàm này
            if (durationSeconds != null) {
                this.length = durationSeconds // Giả sử trường là 'length'
            }
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val searchUrl = if (query.startsWith("http")) {
            query
        } else {
            "$mainUrl/search/$query"
        }

        val document = app.get(searchUrl).document
        val searchResults = ArrayList<SearchResponse>()

        document.select("div.mozaique div.thumb-block").forEach { element ->
            element.toSearchResponse()?.let { searchResults.add(it) }
        }
        return searchResults
    }

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
        
        val videoData = mutableListOf<String>()
        hlsLink?.let { videoData.add("hls:$it") }
        lowQualityLink?.let { videoData.add("low:$it") }
        highQualityLink?.let { videoData.add("high:$it") }

        val relatedVideos = ArrayList<SearchResponse>()
        val relatedScript = document.select("script:containsData(var video_related)")
        if (relatedScript.isNotEmpty()) {
            val scriptContent = relatedScript.html()
            val jsonRegex = Regex("""var video_related\s*=\s*(\[.*?\]);""")
            val match = jsonRegex.find(scriptContent)
            if (match != null && match.groupValues.size > 1) {
                val jsonArrayString = match.groupValues[1]
                try {
                    data class RelatedItem(
                        @JsonProperty("u") val u: String?,
                        @JsonProperty("i") val i: String?,
                        @JsonProperty("tf") val tf: String?,
                        @JsonProperty("d") val d: String?
                    )
                    val relatedItems = AppUtils.parseJson<List<RelatedItem>>(jsonArrayString)
                    relatedItems.forEach { related ->
                        if (related.u != null && related.tf != null) {
                            val relatedDuration = parseDuration(related.d)
                            relatedVideos.add(newMovieSearchResponse(
                                name = related.tf, // Đảm bảo tên tham số đúng
                                url = mainUrl + related.u,
                                apiName = this.name, // Đảm bảo tên tham số đúng
                                type = TvType.NSFW // Cung cấp TvType
                            ) {
                                this.posterUrl = related.i?.let { if (it.startsWith("//")) "https:$it" else it }
                                if (relatedDuration != null) {
                                    this.length = relatedDuration // Gán vào trường 'length'
                                }
                            })
                        }
                    }
                } catch (e: Exception) {
                    // MainAPIKt.logError(this, e) // Thử cách này
                     e.printStackTrace()
                }
            }
        }
        
        return newMovieLoadResponse( // Đảm bảo hàm này tồn tại và đúng tham số
            name = title,
            url = url,
            type = TvType.NSFW,
            dataUrl = videoData.joinToString("||") // Truyền dữ liệu link cho loadLinks
        ) {
            this.posterUrl = poster
            this.plot = description
            this.tags = tags
            this.recommendations = relatedVideos
        }
    }

    // loadLinks vẫn được comment
    // override suspend fun loadLinks(...): Boolean { ... }
}
