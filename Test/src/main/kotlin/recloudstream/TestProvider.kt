package com.ctemplar.app // Hoặc package của bạn

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Element
import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.SearchQuality
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope // Cần để dùng `async`

class TxnhhProvider : MainAPI() {
    override var mainUrl = "https://www.txnhh.com"
    override var name = "Txnhh"
    override val hasMainPage = true
    override var lang = "en"
    override val hasChromecastSupport = true
    override val supportedTypes = setOf(
        TvType.NSFW
    )

    companion object {
        fun getQualityFromString(quality: String?): SearchQuality? {
            return when (quality?.trim()?.lowercase()) {
                "1080p" -> SearchQuality.HD
                "720p" -> SearchQuality.HD
                "480p" -> SearchQuality.SD
                "360p" -> SearchQuality.SD
                else -> null
            }
        }

        fun parseDuration(durationString: String?): Int? {
            if (durationString.isNullOrBlank()) return null
            var totalSeconds = 0
            Regex("""(\d+)\s*h""").find(durationString)?.groupValues?.get(1)?.toIntOrNull()?.let {
                totalSeconds += it * 3600
            }
            Regex("""(\d+)\s*min""").find(durationString)?.groupValues?.get(1)?.toIntOrNull()?.let {
                totalSeconds += it * 60
            }
            Regex("""(\d+)\s*s""").find(durationString)?.groupValues?.get(1)?.toIntOrNull()?.let {
                totalSeconds += it
            }
            return if (totalSeconds > 0) totalSeconds else null
        }
    }

    data class HomePageItem(
        @JsonProperty("i") val image: String?,
        @JsonProperty("u") val url: String?,
        @JsonProperty("t") val title: String?,
        @JsonProperty("tf") val titleFallback: String?,
        @JsonProperty("n") val count: String?,
        @JsonProperty("ty") val type: String?,
        @JsonProperty("no_rotate") val noRotate: Boolean? = null,
        @JsonProperty("tbk") val tbk: Boolean? = null,
        @JsonProperty("w") val weight: Int? = null
    )

    // Hàm mới để fetch video cho một section cụ thể
    private suspend fun fetchSectionVideos(sectionUrl: String, maxItems: Int = 10): List<SearchResponse> { // Giới hạn số item để tránh quá tải
        println("TxnhhProvider DEBUG: fetchSectionVideos called for URL = $sectionUrl")
        if (!sectionUrl.startsWith("http")) {
            // Đây có thể là lỗi nếu URL không hợp lệ
            println("TxnhhProvider WARNING: Invalid sectionUrl in fetchSectionVideos: $sectionUrl")
            return emptyList()
        }
        try {
            val document = app.get(sectionUrl).document
            val videoElements = document.select("div.mozaique div.thumb-block")
            println("TxnhhProvider DEBUG: Found ${videoElements.size} thumb-blocks in fetchSectionVideos for $sectionUrl")
            
            return videoElements.take(maxItems).mapNotNull { it.toSearchResponse() } // Chỉ lấy `maxItems` đầu tiên
        } catch (e: Exception) {
            System.err.println("TxnhhProvider ERROR: Failed to fetch/parse section $sectionUrl. Error: ${e.message}")
            e.printStackTrace()
            return emptyList()
        }
    }


    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        println("TxnhhProvider DEBUG: getMainPage called, page: $page")
        val document = app.get(mainUrl).document
        val homePageLists = ArrayList<HomePageList>() // Đổi tên biến

        val scriptElements = document.select("script:containsData(xv.cats.write_thumb_block_list)")
        if (scriptElements.isNotEmpty()) {
            val scriptContent = scriptElements.html()
            val regex = Regex("""xv\.cats\.write_thumb_block_list\s*\(\s*(\[(?:.|\n)*?\])\s*,\s*['"]home-cat-list['"]""")
            val matchResult = regex.find(scriptContent)
            
            if (matchResult != null && matchResult.groupValues.size > 1) {
                val arrayString = matchResult.groupValues[1].trim() 
                println("TxnhhProvider DEBUG: Raw arrayString from regex for getMainPage = ${arrayString.take(500)}...")

                if (arrayString.startsWith("[") && arrayString.endsWith("]")) {
                    try {
                        val homeItems = AppUtils.parseJson<List<HomePageItem>>(arrayString)
                        println("TxnhhProvider DEBUG: Parsed ${homeItems.size} HomePageItems.")
                        
                        // Sử dụng coroutineScope để thực hiện các yêu cầu mạng song song (nếu có thể)
                        // Tuy nhiên, việc này vẫn có thể làm chậm đáng kể nếu có nhiều sections
                        coroutineScope {
                            val deferredHomePageLists = homeItems.mapNotNull { item ->
                                val itemTitle = item.title ?: item.titleFallback ?: return@mapNotNull null
                                val itemUrl = if (item.url?.startsWith("/") == true) mainUrl + item.url else item.url
                                
                                if (itemUrl == null) return@mapNotNull null

                                val isGameOrStory = itemUrl.contains("nutaku.net") || itemUrl.contains("sexstories.com")
                                val isLikelyStaticLink = item.noRotate == true && item.count == "0" && item.tbk == false && (isGameOrStory || item.url == "/your-suggestions/straight" || item.url == "/tags")
                                
                                // Chỉ xử lý các mục được cho là chứa danh sách video
                                // Ví dụ: Các mục "cat", "search", hoặc "todays-selection"
                                if (!isGameOrStory && !isLikelyStaticLink && (item.type == "cat" || item.type == "search" || item.url == "/todays-selection" || item.url == "/best" || item.url == "/hits")) {
                                    // `async` để có thể fetch song song, nhưng vẫn nên cẩn thận với số lượng lớn
                                    async {
                                        val videosForSection = fetchSectionVideos(itemUrl)
                                        if (videosForSection.isNotEmpty()) {
                                            println("TxnhhProvider DEBUG: Successfully fetched ${videosForSection.size} videos for section '$itemTitle'")
                                            HomePageList(itemTitle, videosForSection)
                                        } else {
                                            println("TxnhhProvider WARNING: No videos fetched for section '$itemTitle' at $itemUrl")
                                            null // Không thêm HomePageList nếu không có video
                                        }
                                    }
                                } else {
                                    println("TxnhhProvider DEBUG: Filtered out (not a video section or unwanted): '$itemTitle', URL: $itemUrl, Type: ${item.type}")
                                    null // Không xử lý các mục đã lọc
                                }
                            }
                            // Đợi tất cả các tác vụ async hoàn thành và thêm vào list
                            deferredHomePageLists.forEach { deferred ->
                                deferred?.await()?.let { homePageLists.add(it) }
                            }
                        }
                        
                        if (homePageLists.isEmpty() && homeItems.any { item -> val url = if (item.url?.startsWith("/") == true) mainUrl + item.url else item.url; !(url?.contains("nutaku.net") == true || url?.contains("sexstories.com") == true) && !(item.noRotate == true && item.count == "0" && item.tbk == false) }) {
                             println("TxnhhProvider WARNING: All potential sections resulted in zero videos or were filtered out.")
                        }

                    } catch (e: Exception) {
                        System.err.println("TxnhhProvider ERROR: Failed to parse HomePage JSON in getMainPage. Error: ${e.message}")
                        e.printStackTrace()
                    }
                } else {
                    println("TxnhhProvider DEBUG: getMainPage - arrayString (value starts with: ${arrayString.take(50)}...) does not start/end with brackets as expected.")
                }
            } else {
                println("TxnhhProvider DEBUG: getMainPage - Regex did not match xv.cats.write_thumb_block_list or did not find the array group.")
            }
        } else {
            println("TxnhhProvider DEBUG: getMainPage - Script containing xv.cats.write_thumb_block_list not found.")
        }

        if (homePageLists.isEmpty()) {
            println("TxnhhProvider DEBUG: homePageList is empty after parsing, adding default sections as fallback.")
            // Mục mặc định này chỉ là link, không phải grid video
            homePageLists.add(HomePageList("Default Categories (Links)", listOf(
                newMovieSearchResponse(name = "Asian Woman", url = "$mainUrl/search/asian_woman", type = TvType.NSFW) {},
                newMovieSearchResponse(name = "Today's Selection", url = "$mainUrl/todays-selection", type = TvType.NSFW) {},
                newMovieSearchResponse(name = "Hardcore", url = "$mainUrl/search/hardcore?top", type = TvType.NSFW) {}
            )))
        }
        println("TxnhhProvider DEBUG: getMainPage returning ${homePageLists.size} HomePageList(s).")
        return HomePageResponse(homePageLists)
    }

    private fun Element.toSearchResponse(): SearchResponse? {
        val titleElement = this.selectFirst(".thumb-under p a")
        if (titleElement == null) {
            // println("TxnhhProvider DEBUG: titleElement not found in toSearchResponse() for element: ${this.html().take(100)}")
            return null
        }
        val title = titleElement.attr("title")
        val href = mainUrl + titleElement.attr("href") 
        
        val imgElement = this.selectFirst(".thumb img")
        val posterUrl = imgElement?.attr("data-src")?.let { if (it.startsWith("//")) "https:$it" else it }

        val metadataElement = this.selectFirst(".thumb-under p.metadata")
        val durationText = metadataElement?.ownText()?.trim()
        val qualityText = metadataElement?.selectFirst("span.video-hd")?.text()?.trim()
        
        return newMovieSearchResponse(
            name = title,
            url = href,
            type = TvType.NSFW
        ) {
            this.posterUrl = posterUrl
            this.quality = getQualityFromString(qualityText)
            // parseDuration(durationText)?.let { currentLength -> this.length = currentLength } // Tạm thời bỏ qua
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        println("TxnhhProvider DEBUG: search() called with query/URL = $query")
        val searchUrl = if (query.startsWith("http")) {
            query 
        } else {
            "$mainUrl/search/$query"
        }
        println("TxnhhProvider DEBUG: Constructed searchUrl = $searchUrl")

        return fetchSectionVideos(searchUrl, maxItems = 50) // Gọi hàm fetch chung, có thể tăng maxItems cho search
    }

    override suspend fun load(url: String): LoadResponse? {
        println("TxnhhProvider DEBUG: load() called with URL = $url")
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
            println("TxnhhProvider DEBUG: Extracted links - HLS: $hlsLink, Low: $lowQualityLink, High: $highQualityLink")
        } else {
            println("TxnhhProvider DEBUG: Script for html5player links not found on $url")
        }
        
        val videoData = mutableListOf<String>()
        hlsLink?.let { videoData.add("hls:$it") }
        lowQualityLink?.let { videoData.add("low:$it") }
        highQualityLink?.let { videoData.add("high:$it") }

        val relatedVideos = ArrayList<SearchResponse>()
        // Tạm thời bỏ qua related videos trong load() nếu nó không quan trọng bằng việc load chính
        // Bạn có thể thêm lại logic parse `var video_related` ở đây nếu cần

        var durationInSeconds: Int? = null
        document.selectFirst("meta[property=og:duration]")?.attr("content")?.let { durationMeta ->
            try {
                var tempDuration = 0
                Regex("""PT(?:(\d+)H)?(?:(\d+)M)?(?:(\d+)S)?""").find(durationMeta)?.let { match ->
                    match.groupValues.getOrNull(1)?.toIntOrNull()?.let { tempDuration += it * 3600 }
                    match.groupValues.getOrNull(2)?.toIntOrNull()?.let { tempDuration += it * 60 }
                    match.groupValues.getOrNull(3)?.toIntOrNull()?.let { tempDuration += it }
                }
                if (tempDuration > 0) durationInSeconds = tempDuration
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
        println("TxnhhProvider DEBUG: Loaded video '$title', Duration: $durationInSeconds seconds")

        return newMovieLoadResponse(
            name = title,
            url = url,
            type = TvType.NSFW,
            dataUrl = videoData.joinToString("||") 
        ) {
            this.posterUrl = poster
            this.plot = description
            this.tags = tags
            this.recommendations = relatedVideos // Sẽ rỗng nếu bạn bỏ qua logic parse ở trên
            this.duration = durationInSeconds
        }
    }
    // loadLinks vẫn comment
}
