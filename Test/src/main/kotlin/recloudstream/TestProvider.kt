package com.ctemplar.app // Hoặc package của bạn

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Element
import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.SearchQuality
import com.lagradost.cloudstream3.subtitles.SubtitleFile // Import cho SubtitleFile
import com.lagradost.cloudstream3.utils.ExtractorLink // Import cho ExtractorLink
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlin.random.Random

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

    private suspend fun fetchSectionVideos(sectionUrl: String, maxItems: Int = Int.MAX_VALUE): List<SearchResponse> {
        println("TxnhhProvider DEBUG: fetchSectionVideos called for URL = $sectionUrl, maxItems = $maxItems")
        if (!sectionUrl.startsWith("http")) {
            println("TxnhhProvider WARNING: Invalid sectionUrl in fetchSectionVideos: $sectionUrl")
            return emptyList()
        }
        try {
            val document = app.get(sectionUrl).document
            val videoElements = document.select("div.mozaique div.thumb-block")
            // println("TxnhhProvider DEBUG: Found ${videoElements.size} thumb-blocks in fetchSectionVideos for $sectionUrl")
            
            return videoElements.take(maxItems).mapNotNull { it.toSearchResponse() }
        } catch (e: Exception) {
            System.err.println("TxnhhProvider ERROR: Failed to fetch/parse section $sectionUrl. Error: ${e.message}")
            return emptyList()
        }
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        println("TxnhhProvider DEBUG: getMainPage called, page: $page")
        val homePageListsResult = ArrayList<HomePageList>()

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
                        // println("TxnhhProvider DEBUG: Parsed ${allHomePageItems.size} total HomePageItems.")
                        
                        val validSections = allHomePageItems.mapNotNull { item ->
                            val itemTitle = item.title ?: item.titleFallback
                            val itemUrlPart = item.url
                            
                            if (itemTitle == null || itemUrlPart == null) return@mapNotNull null
                            val itemUrl = if (itemUrlPart.startsWith("/")) mainUrl + itemUrlPart else itemUrlPart

                            val isGameOrStory = itemUrl.contains("nutaku.net") || itemUrl.contains("sexstories.com")
                            val isLikelyStaticLink = item.noRotate == true && item.count == "0" && item.tbk == false && 
                                                     (isGameOrStory || item.url == "/your-suggestions/straight" || item.url == "/tags" || item.url == "/pornstars")

                            if (isGameOrStory || isLikelyStaticLink) {
                                null
                            } else {
                                if (item.type == "cat" || item.type == "search" || item.url == "/todays-selection" || item.url == "/best" || item.url == "/hits" || item.url=="/fresh" || item.url=="/verified/videos" ) {
                                    Pair(itemTitle, itemUrl)
                                } else {
                                    null
                                }
                            }
                        }.distinctBy { it.second } 

                        // println("TxnhhProvider DEBUG: Found ${validSections.size} valid sections after initial filtering.")

                        val sectionsToDisplay = mutableListOf<Pair<String, String>>()
                        val todaysSelectionUrlPart = "/todays-selection"

                        val todaysSelection = validSections.find { it.second.endsWith(todaysSelectionUrlPart) }
                        if (todaysSelection != null) {
                            sectionsToDisplay.add(todaysSelection)
                        }

                        val remainingSections = validSections.filter { it != todaysSelection }.toMutableList()
                        remainingSections.shuffle(Random(System.currentTimeMillis())) 
                        
                        val neededRandomSections = 5 - sectionsToDisplay.size
                        if (neededRandomSections > 0 && remainingSections.isNotEmpty()) {
                            sectionsToDisplay.addAll(remainingSections.take(neededRandomSections))
                        }
                        
                        println("TxnhhProvider DEBUG: Final sections to display on homepage: ${sectionsToDisplay.map { it.first }}")

                        coroutineScope {
                            val deferredLists = sectionsToDisplay.map { (sectionTitle, sectionUrl) ->
                                async {
                                    // Giới hạn số lượng video cho mỗi grid trên trang chủ để tránh tải quá lâu
                                    val videos = fetchSectionVideos(sectionUrl, maxItems = 12) 
                                    if (videos.isNotEmpty()) {
                                        // println("TxnhhProvider DEBUG: Successfully fetched ${videos.size} videos for homepage section '$sectionTitle'")
                                        HomePageList(sectionTitle, videos)
                                    } else {
                                        // println("TxnhhProvider WARNING: No videos fetched for homepage section '$sectionTitle' ($sectionUrl)")
                                        null
                                    }
                                }
                            }
                            deferredLists.forEach { deferred ->
                                deferred?.await()?.let { homePageListsResult.add(it) }
                            }
                        }
                    } catch (e: Exception) { /* Log */ e.printStackTrace() }
                }
            } 
        } 

        if (homePageListsResult.isEmpty()) {
            println("TxnhhProvider DEBUG: homePageList is empty after parsing, adding default link sections as fallback.")
            homePageListsResult.add(HomePageList("Default Links (Fallback)", listOf(
                newMovieSearchResponse(name = "Asian Woman", url = "$mainUrl/search/asian_woman", type = TvType.NSFW) {},
                newMovieSearchResponse(name = "Today's Selection", url = "$mainUrl/todays-selection", type = TvType.NSFW) {}
            )))
        }
        // println("TxnhhProvider DEBUG: getMainPage returning ${homePageListsResult.size} HomePageList(s).")
        return newHomePageResponse(homePageListsResult, hasNext = page < 2) // Giả sử trang chủ có thể có nhiều "trang" nếu logic phức tạp hơn
    }

     private fun Element.toSearchResponse(): SearchResponse? {
        val titleElement = this.selectFirst(".thumb-under p a") ?: return null
        val title = titleElement.attr("title")
        val href = mainUrl + titleElement.attr("href") 
        val posterUrl = this.selectFirst(".thumb img")?.attr("data-src")?.let { if (it.startsWith("//")) "https:$it" else it }
        val metadataElement = this.selectFirst(".thumb-under p.metadata")
        val durationText = metadataElement?.ownText()?.trim()
        val qualityText = metadataElement?.selectFirst("span.video-hd")?.text()?.trim()
        
        return newMovieSearchResponse(name = title, url = href, type = TvType.NSFW) {
            this.posterUrl = posterUrl
            this.quality = getQualityFromString(qualityText)
            // parseDuration(durationText)?.let { this.length = it } // Tạm thời bỏ qua
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        println("TxnhhProvider DEBUG: search() called with query/URL = $query")
        val searchUrl = if (query.startsWith("http")) query else "$mainUrl/search/$query"
        println("TxnhhProvider DEBUG: Constructed searchUrl for search() = $searchUrl")
        return fetchSectionVideos(searchUrl, maxItems = 50) // Lấy nhiều hơn cho trang search
    }

    override suspend fun load(url: String): LoadResponse? {
        println("TxnhhProvider DEBUG: load() called with URL = $url")
        val document = app.get(url).document
        val title = document.selectFirst(".video-title strong")?.text() 
            ?: document.selectFirst("meta[property=og:title]")?.attr("content") 
            ?: "Unknown Title"
        val poster = document.selectFirst("meta[property=og:image]")?.attr("content")?.let { if (it.startsWith("//")) "https:$it" else it }
        val description = document.selectFirst("p.video-description")?.text()?.trim()
        val tags = document.select(".metadata-row.video-tags a:not(#suggestion)").mapNotNull { it.text()?.trim() }.filter { it.isNotEmpty() }

        var hlsLink: String? = null
        var lowQualityLink: String? = null
        var highQualityLink: String? = null
        document.select("script:containsData(html5player.setVideoUrlLow)").firstOrNull()?.html()?.let { scriptContent ->
            hlsLink = Regex("""html5player\.setVideoHLS\s*\(\s*['"](.*?)['"]\s*\)""").find(scriptContent)?.groupValues?.get(1)
            lowQualityLink = Regex("""html5player\.setVideoUrlLow\s*\(\s*['"](.*?)['"]\s*\)""").find(scriptContent)?.groupValues?.get(1)
            highQualityLink = Regex("""html5player\.setVideoUrlHigh\s*\(\s*['"](.*?)['"]\s*\)""").find(scriptContent)?.groupValues?.get(1)
        }
        // println("TxnhhProvider DEBUG: Extracted links from load() - HLS: $hlsLink, Low: $lowQualityLink, High: $highQualityLink")
        
        val videoData = mutableListOf<String>()
        hlsLink?.let { videoData.add("hls:$it") }
        lowQualityLink?.let { videoData.add("low:$it") }
        highQualityLink?.let { videoData.add("high:$it") }

        val relatedVideos = ArrayList<SearchResponse>()
        document.select("script:containsData(var video_related)").firstOrNull()?.html()?.let { scriptContentRelated ->
            Regex("""var video_related\s*=\s*(\[.*?\]);""").find(scriptContentRelated)?.groupValues?.get(1)?.let { jsonArrayStringRelated ->
                try {
                    data class RelatedItem(@JsonProperty("u") val u: String?, @JsonProperty("i") val i: String?, @JsonProperty("tf") val tf: String?, @JsonProperty("d") val d: String?)
                    AppUtils.parseJson<List<RelatedItem>>(jsonArrayStringRelated).forEach { related ->
                        if (related.u != null && related.tf != null) {
                            relatedVideos.add(newMovieSearchResponse(name = related.tf, url = mainUrl + related.u, type = TvType.NSFW) {
                                this.posterUrl = related.i?.let { if (it.startsWith("//")) "https:$it" else it }
                            })
                        }
                    }
                } catch (e: Exception) { e.printStackTrace() }
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
            } catch (e: Exception) { e.printStackTrace() }
        }
        // println("TxnhhProvider DEBUG: Loaded video details for '$title', Duration: $durationInSeconds seconds")

        return newMovieLoadResponse(name = title, url = url, type = TvType.NSFW, dataUrl = videoData.joinToString("||")) {
            this.posterUrl = poster; this.plot = description; this.tags = tags; this.recommendations = relatedVideos; this.duration = durationInSeconds
        }
    }

    override suspend fun loadLinks(
        data: String, // data này là chuỗi videoData.joinToString("||") từ hàm load
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        println("TxnhhProvider DEBUG: loadLinks called with data = $data")
        val links = data.split("||")
        var foundLinks = false

        links.forEach { linkData ->
            if (linkData.isNotBlank()) {
                val parts = linkData.split(":", limit = 2)
                if (parts.size == 2) {
                    val type = parts[0]
                    val videoUrl = parts[1]
                    // Đảm bảo URL không bị lỗi (ví dụ: có dấu cách không mong muốn)
                    val cleanVideoUrl = videoUrl.trim() 

                    println("TxnhhProvider DEBUG: Processing link - Type: $type, URL: $cleanVideoUrl")
                    foundLinks = true
                    try {
                        when (type) {
                            "hls" -> callback.invoke(ExtractorLink(
                                source = this.name, // Hoặc một tên cụ thể cho nguồn HLS
                                name = "${this.name} HLS",
                                url = cleanVideoUrl,
                                referer = mainUrl, // Referer quan trọng
                                quality = Qualities.Unknown.value, // HLS thường tự chọn chất lượng
                                isM3u8 = true
                            ))
                            "low" -> callback.invoke(ExtractorLink(
                                source = this.name,
                                name = "${this.name} Low MP4",
                                url = cleanVideoUrl,
                                referer = mainUrl,
                                quality = Qualities.P360.value // Hoặc Qualities.SD.value
                            ))
                            "high" -> callback.invoke(ExtractorLink(
                                source = this.name,
                                name = "${this.name} High MP4",
                                url = cleanVideoUrl,
                                referer = mainUrl,
                                quality = Qualities.P720.value // Hoặc Qualities.HD.value
                            ))
                            else -> println("TxnhhProvider WARNING: Unknown link type in loadLinks: $type")
                        }
                    } catch (e: Exception) {
                        System.err.println("TxnhhProvider ERROR: Failed to invoke callback for link $cleanVideoUrl. Error: ${e.message}")
                        e.printStackTrace()
                    }
                }
            }
        }
        if (!foundLinks) {
            println("TxnhhProvider WARNING: No valid links found in loadLinks data: $data")
        }
        return foundLinks
    }
}
