package com.ctemplar.app // Hoặc package của bạn

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Element
import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.SearchQuality

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
        @JsonProperty("n") val count: String?, // count có thể là "0" hoặc "123,456"
        @JsonProperty("ty") val type: String?,
        // Thêm các trường có thể có trong các item đặc biệt như sexstories, games
        @JsonProperty("no_rotate") val noRotate: Boolean? = null,
        @JsonProperty("tbk") val tbk: Boolean? = null,
        @JsonProperty("w") val weight: Int? = null
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        println("TxnhhProvider DEBUG: getMainPage called")
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
                println("TxnhhProvider DEBUG: Raw arrayString from regex = $arrayString")

                if (arrayString.startsWith("[") && arrayString.endsWith("]")) {
                    try {
                        val items = AppUtils.parseJson<List<HomePageItem>>(arrayString)
                        println("TxnhhProvider DEBUG: Parsed ${items.size} items from HomePageItem JSON.")
                        
                        val sectionItems = ArrayList<SearchResponse>()
                        items.forEach { item ->
                            val itemTitle = item.title ?: item.titleFallback ?: "Unknown Section"
                            val itemUrl = if (item.url?.startsWith("/") == true) mainUrl + item.url else item.url
                            val itemPoster = if (item.image?.startsWith("//") == true) "https:${item.image}" else item.image

                            if (itemUrl != null) {
                                // Lọc các item không mong muốn
                                val isSpecialLink = item.noRotate == true && item.tbk == false && item.count == "0"
                                val isGameOrStory = itemUrl.contains("nutaku.net") || itemUrl.contains("sexstories.com")
                                
                                if (!isSpecialLink && !isGameOrStory) {
                                    sectionItems.add(
                                        newMovieSearchResponse(
                                            name = itemTitle,
                                            url = itemUrl,
                                            type = TvType.NSFW
                                        ) {
                                            this.posterUrl = itemPoster
                                        }
                                    )
                                } else {
                                     println("TxnhhProvider DEBUG: Filtered out special/game/story item: $itemTitle, URL: $itemUrl")
                                }
                            }
                        }
                        if (sectionItems.isNotEmpty()) {
                            homePageList.add(HomePageList("Browse Sections", sectionItems, true))
                            println("TxnhhProvider DEBUG: Added 'Browse Sections' with ${sectionItems.size} items.")
                        } else {
                            println("TxnhhProvider DEBUG: No valid section items found after filtering.")
                        }
                    } catch (e: Exception) {
                        System.err.println("TxnhhProvider ERROR: Failed to parse HomePage JSON. Error: ${e.message}")
                        e.printStackTrace()
                    }
                } else {
                    println("TxnhhProvider DEBUG: arrayString (value: $arrayString) does not start/end with brackets as expected.")
                }
            } else {
                println("TxnhhProvider DEBUG: Regex did not match xv.cats.write_thumb_block_list or did not find the array group.")
            }
        } else {
            println("TxnhhProvider DEBUG: Script containing xv.cats.write_thumb_block_list not found.")
        }

        if (homePageList.isEmpty()) {
            println("TxnhhProvider DEBUG: homePageList is empty, adding default sections.")
            homePageList.add(HomePageList("Default Sections (Example)", listOf(
                newMovieSearchResponse(name = "Asian Woman", url = "$mainUrl/search/asian_woman", type = TvType.NSFW) {},
                newMovieSearchResponse(name = "Today's Selection", url = "$mainUrl/todays-selection", type = TvType.NSFW) {},
                newMovieSearchResponse(name = "Hardcore", url = "$mainUrl/search/hardcore?top", type = TvType.NSFW) {}
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
        
        return newMovieSearchResponse(
            name = title,
            url = href,
            type = TvType.NSFW
        ) {
            this.posterUrl = posterUrl
            this.quality = getQualityFromString(qualityText)
            parseDuration(durationText)?.let {بحثطول -> // CloudStream thường dùng 'length' cho thời lượng trong SearchResponse
                 // Gán vào một trường có tên là length hoặc tương tự nếu builder hỗ trợ
                 // Ví dụ: this.length = بحثطول 
                 // Nếu không, bạn có thể cần tạo một SearchResponse tùy chỉnh hơn
            }
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        println("TxnhhProvider DEBUG: search() called with query/URL = $query")
        val searchUrl = if (query.startsWith("http")) {
            query 
        } else {
            "$mainUrl/search/$query"
        }

        val document = app.get(searchUrl).document
        val searchResults = ArrayList<SearchResponse>()
        val videoElements = document.select("div.mozaique div.thumb-block")
        println("TxnhhProvider DEBUG: Found ${videoElements.size} thumb-blocks on $searchUrl")


        videoElements.forEach { element ->
            try {
                 element.toSearchResponse()?.let { searchResults.add(it) }
            } catch (e: Exception) {
                System.err.println("TxnhhProvider ERROR: Failed to parse a search item from $searchUrl. Element HTML: ${element.html()}")
                e.printStackTrace()
            }
        }
        println("TxnhhProvider DEBUG: Returning ${searchResults.size} search results for $query")
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
                                // parseDuration(related.d)?.let { this.length = it }
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
}
