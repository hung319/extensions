package com.ctemplar.app // Hoặc package của bạn

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Element
import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.SearchQuality
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlin.random.Random // Import để lấy ngẫu nhiên

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

    // Sửa hàm fetchSectionVideos: bỏ giới hạn maxItems mặc định hoặc đặt rất cao
    private suspend fun fetchSectionVideos(sectionUrl: String, maxItems: Int = Int.MAX_VALUE): List<SearchResponse> {
        println("TxnhhProvider DEBUG: fetchSectionVideos called for URL = $sectionUrl, maxItems = $maxItems (Int.MAX_VALUE means no limit)")
        if (!sectionUrl.startsWith("http")) {
            println("TxnhhProvider WARNING: Invalid sectionUrl in fetchSectionVideos: $sectionUrl")
            return emptyList()
        }
        try {
            val document = app.get(sectionUrl).document
            val videoElements = document.select("div.mozaique div.thumb-block")
            println("TxnhhProvider DEBUG: Found ${videoElements.size} thumb-blocks in fetchSectionVideos for $sectionUrl")
            
            // Nếu maxItems vẫn được truyền vào (ví dụ từ getMainPage), thì tôn trọng nó
            // Nếu không (ví dụ từ search), thì lấy hết
            return videoElements.take(maxItems).mapNotNull { it.toSearchResponse() }
        } catch (e: Exception) {
            System.err.println("TxnhhProvider ERROR: Failed to fetch/parse section $sectionUrl. Error: ${e.message}")
            return emptyList()
        }
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        println("TxnhhProvider DEBUG: getMainPage called, page: $page")
        val homePageListsResult = ArrayList<HomePageList>() // Đổi tên biến để tránh nhầm lẫn

        val document = app.get(mainUrl).document
        val scriptElements = document.select("script:containsData(xv.cats.write_thumb_block_list)")

        if (scriptElements.isNotEmpty()) {
            val scriptContent = scriptElements.html()
            val regex = Regex("""xv\.cats\.write_thumb_block_list\s*\(\s*(\[(?:.|\n)*?\])\s*,\s*['"]home-cat-list['"]""")
            val matchResult = regex.find(scriptContent)
            
            if (matchResult != null && matchResult.groupValues.size > 1) {
                val arrayString = matchResult.groupValues[1].trim() 
                // println("TxnhhProvider DEBUG: Raw arrayString for getMainPage = ${arrayString.take(200)}...")

                if (arrayString.startsWith("[") && arrayString.endsWith("]")) {
                    try {
                        val allHomePageItems = AppUtils.parseJson<List<HomePageItem>>(arrayString)
                        println("TxnhhProvider DEBUG: Parsed ${allHomePageItems.size} total HomePageItems.")
                        
                        val validSections = allHomePageItems.mapNotNull { item ->
                            val itemTitle = item.title ?: item.titleFallback
                            val itemUrlPart = item.url
                            
                            if (itemTitle == null || itemUrlPart == null) return@mapNotNull null
                            val itemUrl = if (itemUrlPart.startsWith("/")) mainUrl + itemUrlPart else itemUrlPart

                            val isGameOrStory = itemUrl.contains("nutaku.net") || itemUrl.contains("sexstories.com")
                            val isLikelyStaticLink = item.noRotate == true && item.count == "0" && item.tbk == false && 
                                                     (isGameOrStory || item.url == "/your-suggestions/straight" || item.url == "/tags" || item.url == "/pornstars")

                            if (isGameOrStory || isLikelyStaticLink) {
                                // println("TxnhhProvider DEBUG: Filtering out static/game/story item: '$itemTitle', URL: $itemUrl")
                                null
                            } else {
                                if (item.type == "cat" || item.type == "search" || item.url == "/todays-selection" || item.url == "/best" || item.url == "/hits" || item.url=="/fresh" || item.url=="/verified/videos" ) {
                                    Pair(itemTitle, itemUrl)
                                } else {
                                    // println("TxnhhProvider DEBUG: Filtering out item not matching section criteria: '$itemTitle', URL: $itemUrl, Type: ${item.type}")
                                    null
                                }
                            }
                        }.distinctBy { it.second } 

                        println("TxnhhProvider DEBUG: Found ${validSections.size} valid sections after initial filtering.")

                        val sectionsToDisplay = mutableListOf<Pair<String, String>>()
                        val todaysSelectionUrlPart = "/todays-selection" // URL part cho "Today's Selection"

                        val todaysSelection = validSections.find { it.second.endsWith(todaysSelectionUrlPart) }
                        if (todaysSelection != null) {
                            sectionsToDisplay.add(todaysSelection)
                            println("TxnhhProvider DEBUG: Prioritized 'Today's Selection' for display.")
                        }

                        val remainingSections = validSections.filter { it != todaysSelection }.toMutableList()
                        remainingSections.shuffle(Random(System.currentTimeMillis())) 
                        
                        val neededRandomSections = 5 - sectionsToDisplay.size
                        if (neededRandomSections > 0 && remainingSections.isNotEmpty()) {
                            sectionsToDisplay.addAll(remainingSections.take(neededRandomSections))
                            println("TxnhhProvider DEBUG: Added ${remainingSections.take(neededRandomSections).size} random sections. Total to display: ${sectionsToDisplay.size}")
                        }
                        
                        println("TxnhhProvider DEBUG: Final sections to display on homepage: ${sectionsToDisplay.map { it.first }}")

                        coroutineScope {
                            val deferredLists = sectionsToDisplay.map { (sectionTitle, sectionUrl) ->
                                async {
                                    // Gọi fetchSectionVideos KHÔNG giới hạn maxItems cho trang chủ
                                    val videos = fetchSectionVideos(sectionUrl, maxItems = 20) // Giới hạn lại chút để không quá chậm
                                    if (videos.isNotEmpty()) {
                                        println("TxnhhProvider DEBUG: Successfully fetched ${videos.size} videos for homepage section '$sectionTitle'")
                                        HomePageList(sectionTitle, videos)
                                    } else {
                                        println("TxnhhProvider WARNING: No videos fetched for homepage section '$sectionTitle' ($sectionUrl)")
                                        null
                                    }
                                }
                            }
                            deferredLists.forEach { deferred ->
                                deferred?.await()?.let { homePageListsResult.add(it) }
                            }
                        }
                    } catch (e: Exception) {
                        System.err.println("TxnhhProvider ERROR: Failed to parse HomePage JSON in getMainPage. Error: ${e.message}")
                        e.printStackTrace()
                    }
                } else { /* Log */ }
            } else { /* Log */ }
        } else { /* Log */ }

        if (homePageListsResult.isEmpty()) {
            println("TxnhhProvider DEBUG: homePageList is empty after parsing, adding default link sections as fallback.")
            // Fallback này chỉ là link, không phải grid video
            homePageListsResult.add(HomePageList("Default Links (Fallback)", listOf(
                newMovieSearchResponse(name = "Asian Woman", url = "$mainUrl/search/asian_woman", type = TvType.NSFW) {},
                newMovieSearchResponse(name = "Today's Selection", url = "$mainUrl/todays-selection", type = TvType.NSFW) {}
            )))
        }
        println("TxnhhProvider DEBUG: getMainPage returning ${homePageListsResult.size} HomePageList(s).")
        // Sử dụng newHomePageResponse theo khuyến nghị
        return newHomePageResponse(homePageListsResult, hasNext = false) // Giả sử không có trang tiếp theo cho trang chủ
    }

    private fun Element.toSearchResponse(): SearchResponse? {
        val titleElement = this.selectFirst(".thumb-under p a")
        if (titleElement == null) {
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
            // parseDuration(durationText)?.let { currentLength -> this.length = currentLength } // Vẫn tạm comment
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        println("TxnhhProvider DEBUG: search() called with query/URL = $query")
        val searchUrl = if (query.startsWith("http")) {
            query 
        } else {
            "$mainUrl/search/$query"
        }
        println("TxnhhProvider DEBUG: Constructed searchUrl for search() = $searchUrl")
        // Gọi fetchSectionVideos không giới hạn số lượng item cho trang search/category
        return fetchSectionVideos(searchUrl) 
    }

    override suspend fun load(url: String): LoadResponse? {
        // ... (Hàm load giữ nguyên như trước vì bạn nói nó đã hoạt động tốt)
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
            println("TxnhhProvider DEBUG: Extracted links from load() - HLS: $hlsLink, Low: $lowQualityLink, High: $highQualityLink")
        } else {
            println("TxnhhProvider DEBUG: Script for html5player links not found on load page: $url")
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
                     // e.printStackTrace()
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
                // e.printStackTrace()
            }
        }
        println("TxnhhProvider DEBUG: Loaded video details for '$title', Duration: $durationInSeconds seconds")

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
    // loadLinks vẫn được comment
}
