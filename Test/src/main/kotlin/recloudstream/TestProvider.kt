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
    override var lang = "en"
    override val hasChromecastSupport = true
    override val supportedTypes = setOf(
        TvType.NSFW
    )

    // override val mainPageInterceptors = listOf(CloudflareKiller())

    companion object {
        fun getQualityFromString(quality: String?): SearchQuality? { 
            return when (quality?.trim()?.lowercase()) {
                "1080p" -> SearchQuality.HD // Hoặc FourK nếu API hỗ trợ và phù hợp
                "720p" -> SearchQuality.HD
                "480p" -> SearchQuality.SD
                "360p" -> SearchQuality.SD // Hoặc Cam
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

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        println("TxnhhProvider DEBUG: getMainPage called, page: $page")
        val document = app.get(mainUrl).document
        val homePageList = ArrayList<HomePageList>()

        val scriptElements = document.select("script:containsData(xv.cats.write_thumb_block_list)")
        if (scriptElements.isNotEmpty()) {
            val scriptContent = scriptElements.html()
            // Regex chính xác hơn để lấy array là tham số đầu tiên
            val regex = Regex("""xv\.cats\.write_thumb_block_list\s*\(\s*(\[(?:.|\n)*?\])\s*,\s*['"]home-cat-list['"]""")
            val matchResult = regex.find(scriptContent)
            
            if (matchResult != null && matchResult.groupValues.size > 1) {
                val arrayString = matchResult.groupValues[1].trim() 
                println("TxnhhProvider DEBUG: Raw arrayString from regex = ${arrayString.take(500)}...") // Log một phần để tránh quá dài

                if (arrayString.startsWith("[") && arrayString.endsWith("]")) {
                    try {
                        val items = AppUtils.parseJson<List<HomePageItem>>(arrayString)
                        println("TxnhhProvider DEBUG: Parsed ${items.size} items from HomePageItem JSON.")
                        
                        items.forEach { item ->
                            val itemTitle = item.title ?: item.titleFallback ?: "Unknown Section"
                            val itemUrl = if (item.url?.startsWith("/") == true) mainUrl + item.url else item.url
                            val itemPoster = if (item.image?.startsWith("//") == true) "https:${item.image}" else item.image

                            if (itemUrl != null) {
                                val isGameOrStory = itemUrl.contains("nutaku.net") || itemUrl.contains("sexstories.com")
                                // Các mục "Today's selection", "Sex Stories", "Porn Games" từ home.html gốc có n=0, no_rotate=true, tbk=false
                                // Chúng ta chỉ muốn lấy các mục là category video thực sự hoặc các section video
                                val isLikelyStaticLink = item.noRotate == true && item.count == "0" && item.tbk == false && (isGameOrStory || item.url == "/your-suggestions/straight" || item.url == "/tags")


                                if (!isGameOrStory && !isLikelyStaticLink) {
                                    // Mỗi item hợp lệ sẽ tạo ra một HomePageList riêng.
                                    // Bên trong HomePageList này sẽ là một SearchResponse duy nhất trỏ đến URL của mục đó.
                                    // Khi người dùng bấm vào, CloudStream sẽ gọi search(url_cua_muc_do).
                                    homePageList.add(HomePageList(itemTitle, listOf(
                                        newMovieSearchResponse(
                                            name = "Browse $itemTitle", // Hoặc chỉ itemTitle để ngắn gọn
                                            url = itemUrl,
                                            type = TvType.NSFW
                                        ) {
                                            this.posterUrl = itemPoster // Poster cho mục này
                                        }
                                    )))
                                    println("TxnhhProvider DEBUG: Added HomePageList for section: '$itemTitle' with URL: $itemUrl")
                                } else {
                                     println("TxnhhProvider DEBUG: Filtered out item: '$itemTitle', URL: $itemUrl, Type: ${item.type}, NoRotate: ${item.noRotate}, Count: ${item.count}")
                                }
                            }
                        }
                        if (homePageList.isEmpty() && items.isNotEmpty()) {
                            // Nếu tất cả item bị filter, có thể logic filter quá chặt
                            println("TxnhhProvider WARNING: All items were filtered out from HomePage.")
                        }
                        
                    } catch (e: Exception) {
                        System.err.println("TxnhhProvider ERROR: Failed to parse HomePage JSON. Error: ${e.message}")
                        e.printStackTrace()
                    }
                } else {
                    println("TxnhhProvider DEBUG: arrayString (value starts with: ${arrayString.take(50)}...) does not start/end with brackets as expected.")
                }
            } else {
                println("TxnhhProvider DEBUG: Regex did not match xv.cats.write_thumb_block_list or did not find the array group.")
            }
        } else {
            println("TxnhhProvider DEBUG: Script containing xv.cats.write_thumb_block_list not found.")
        }

        if (homePageList.isEmpty()) {
            println("TxnhhProvider DEBUG: homePageList is empty after parsing, adding default sections.")
            homePageList.add(HomePageList("Default Sections (Example)", listOf(
                newMovieSearchResponse(name = "Asian Woman", url = "$mainUrl/search/asian_woman", type = TvType.NSFW) {},
                newMovieSearchResponse(name = "Today's Selection", url = "$mainUrl/todays-selection", type = TvType.NSFW) {},
                newMovieSearchResponse(name = "Hardcore", url = "$mainUrl/search/hardcore?top", type = TvType.NSFW) {}
            )))
        }
        println("TxnhhProvider DEBUG: getMainPage returning ${homePageList.size} HomePageList(s).")
        return HomePageResponse(homePageList)
    }

    private fun Element.toSearchResponse(): SearchResponse? {
        // println("TxnhhProvider DEBUG: toSearchResponse() called for element: ${this.html().take(150)}...") 
        val titleElement = this.selectFirst(".thumb-under p a")
        if (titleElement == null) {
            // println("TxnhhProvider DEBUG: titleElement not found in toSearchResponse() for element: ${this.html().take(100)}")
            return null
        }
        val title = titleElement.attr("title")
        val href = mainUrl + titleElement.attr("href") 
        
        val imgElement = this.selectFirst(".thumb img")
        val posterUrl = imgElement?.attr("data-src")?.let { if (it.startsWith("//")) "https:$it" else it }
        // if (imgElement == null) println("TxnhhProvider DEBUG: imgElement (for poster) not found for title: $title")
        // else if (posterUrl == null) println("TxnhhProvider DEBUG: data-src not found on imgElement for title: $title")

        val metadataElement = this.selectFirst(".thumb-under p.metadata")
        val durationText = metadataElement?.ownText()?.trim()
        val qualityText = metadataElement?.selectFirst("span.video-hd")?.text()?.trim()
        // if (metadataElement == null) println("TxnhhProvider DEBUG: metadataElement not found for title: $title")
        
        // println("TxnhhProvider DEBUG: Extracted for search - Title: $title, Href: $href, Poster: $posterUrl, DurationStr: $durationText, QualityStr: $qualityText")

        return newMovieSearchResponse(
            name = title,
            url = href,
            type = TvType.NSFW
        ) {
            this.posterUrl = posterUrl
            this.quality = getQualityFromString(qualityText)
            // Tạm thời comment lại việc gán length ở đây để tránh lỗi nếu API không hỗ trợ
            // parseDuration(durationText)?.let { currentLength -> this.length = currentLength }
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

        val document = app.get(searchUrl).document
        val searchResults = ArrayList<SearchResponse>()
        val videoElements = document.select("div.mozaique div.thumb-block") // Selector cho các item video
        println("TxnhhProvider DEBUG: Found ${videoElements.size} thumb-blocks on $searchUrl")

        videoElements.forEachIndexed { index, element ->
            // println("TxnhhProvider DEBUG: Processing search item ${index + 1}/${videoElements.size}")
            try {
                 element.toSearchResponse()?.let { 
                    searchResults.add(it)
                    // println("TxnhhProvider DEBUG: Added to searchResults: ${it.name}")
                 } // else {
                    // println("TxnhhProvider DEBUG: toSearchResponse returned null for item ${index + 1}")
                // }
            } catch (e: Exception) {
                System.err.println("TxnhhProvider ERROR: Failed to parse a search item from $searchUrl. Index: $index. Element HTML snippet: ${element.html().take(100)}")
                e.printStackTrace()
            }
        }
        println("TxnhhProvider DEBUG: search() returning ${searchResults.size} results for query '$query'")
        return searchResults
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
        val relatedScript = document.select("script:containsData(var video_related)")
        if (relatedScript.isNotEmpty()) {
            val scriptContentRelated = relatedScript.html()
            val jsonRegexRelated = Regex("""var video_related\s*=\s*(\[.*?\]);""")
            val matchRelated = jsonRegexRelated.find(scriptContentRelated)
            if (matchRelated != null && matchRelated.groupValues.size > 1) {
                val jsonArrayStringRelated = matchRelated.groupValues[1]
                try {
                    data class RelatedItem(
                        @JsonProperty("u") val u: String?,
                        @JsonProperty("i") val i: String?,
                        @JsonProperty("tf") val tf: String?, 
                        @JsonProperty("d") val d: String?
                    )
                    val relatedItems = AppUtils.parseJson<List<RelatedItem>>(jsonArrayStringRelated)
                    relatedItems.forEach { related ->
                        if (related.u != null && related.tf != null) {
                            relatedVideos.add(newMovieSearchResponse(
                                name = related.tf,
                                url = mainUrl + related.u,
                                type = TvType.NSFW
                            ) {
                                this.posterUrl = related.i?.let { if (it.startsWith("//")) "https:$it" else it }
                                // parseDuration(related.d)?.let { this.length = it } // Tạm thời bỏ qua length
                            })
                        }
                    }
                } catch (e: Exception) {
                     System.err.println("TxnhhProvider ERROR: Failed to parse related videos JSON. Error: ${e.message}")
                     e.printStackTrace()
                }
            }
        }
        
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
            this.recommendations = relatedVideos
            this.duration = durationInSeconds
        }
    }

    // Hàm loadLinks vẫn được comment, sẽ implement khi bạn sẵn sàng
    // override suspend fun loadLinks(
    //     data: String, 
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
    //                     "low" -> callback.invoke(ExtractorLink(this.name, "Low Quality MP4", videoUrl, mainUrl, Qualities.P360.value))
    //                     "high" -> callback.invoke(ExtractorLink(this.name, "High Quality MP4", videoUrl, mainUrl, Qualities.P720.value))
    //                 }
    //             }
    //         }
    //     }
    //     return hasLinks
    // }
}
