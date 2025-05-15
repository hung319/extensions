package com.ctemplar.app // Hoặc package của bạn

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Element
import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.SearchQuality
// import com.lagradost.cloudstream3.utils.ExtractorLinkType // Đã có trong utils.*
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
        
        fun getQualityIntFromLinkType(type: String): Int {
            return when (type) {
                "hls" -> Qualities.Unknown.value 
                else -> Qualities.Unknown.value
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

    // Data class cho item video liên quan (để parse JSON từ script)
    private data class RelatedItem(
        @JsonProperty("u") val u: String?,     // URL (relative)
        @JsonProperty("i") val i: String?,     // Image URL
        @JsonProperty("tf") val tf: String?,   // Title Fallback (thường là title)
        @JsonProperty("d") val d: String?      // Duration string (ví dụ "10min")
        // Các trường khác có thể có: "id", "eid", "r" (rating), "n" (views)
    )

    private suspend fun fetchSectionVideos(sectionUrl: String, maxItems: Int = Int.MAX_VALUE): List<SearchResponse> {
        println("TxnhhProvider DEBUG: fetchSectionVideos called for URL = $sectionUrl, maxItems = $maxItems")
        if (!sectionUrl.startsWith("http")) {
            // Nếu URL không bắt đầu bằng http, đó có thể là một path tương đối từ mainUrl
            // Nhưng hiện tại, logic getMainPage và search đều tạo URL đầy đủ.
            println("TxnhhProvider WARNING: Invalid sectionUrl (not absolute) in fetchSectionVideos: $sectionUrl")
            return emptyList()
        }
        val videoList = mutableListOf<SearchResponse>()
        try {
            val document = app.get(sectionUrl).document 
            val videoElements = document.select("div.mozaique div.thumb-block")
            println("TxnhhProvider DEBUG: Found ${videoElements.size} thumb-blocks in fetchSectionVideos for $sectionUrl")
            
            videoElements.take(maxItems).mapNotNullTo(videoList) { it.toSearchResponse() }
        } catch (e: Exception) {
            System.err.println("TxnhhProvider ERROR: Failed to fetch/parse section $sectionUrl. Error: ${e.message}")
        }
        return videoList
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        println("TxnhhProvider DEBUG: getMainPage called, page: $page")
        val homePageListsResult = ArrayList<HomePageList>()
        var hasNextMainPage = false 

        // Chỉ tải script từ trang chủ gốc một lần, bất kể `page`
        // Logic chọn section ngẫu nhiên sẽ dựa trên `page`
        val document = app.get(mainUrl).document 
        val scriptElements = document.select("script:containsData(xv.cats.write_thumb_block_list)")

        if (scriptElements.isNotEmpty()) {
            val scriptContent = scriptElements.html()
            val regex = Regex("""xv\.cats\.write_thumb_block_list\s*\(\s*(\[(?:.|\n)*?\])\s*,\s*['"]home-cat-list['"]""")
            val matchResult = regex.find(scriptContent)
            
            if (matchResult != null && matchResult.groupValues.size > 1) {
                val arrayString = matchResult.groupValues[1].trim() 
                if (arrayString.startsWith("[") && arrayString.endsWith("]")) {
                    try {
                        val allHomePageItems = AppUtils.parseJson<List<HomePageItem>>(arrayString)
                        val validSectionsSource = allHomePageItems.mapNotNull { item ->
                            val itemTitle = item.title ?: item.titleFallback
                            val itemUrlPart = item.url
                            if (itemTitle == null || itemUrlPart == null) return@mapNotNull null
                            val itemUrl = if (itemUrlPart.startsWith("/")) mainUrl + itemUrlPart else itemUrlPart
                            val isGameOrStory = itemUrl.contains("nutaku.net") || itemUrl.contains("sexstories.com")
                            val isLikelyStaticLink = item.noRotate == true && item.count == "0" && item.tbk == false && 
                                                     (isGameOrStory || item.url == "/your-suggestions/straight" || item.url == "/tags" || item.url == "/pornstars")
                            if (isGameOrStory || isLikelyStaticLink) null
                            else if (item.type == "cat" || item.type == "search" || item.url == "/todays-selection" || item.url == "/best" || item.url == "/hits" || item.url == "/fresh" || item.url == "/verified/videos" ) Pair(itemTitle, itemUrl)
                            else null
                        }.distinctBy { it.second } // Loại bỏ các section có URL trùng lặp

                        val sectionsToDisplayThisPage = mutableListOf<Pair<String, String>>()
                        val todaySelectionUrlPart = "/todays-selection"
                        
                        var hasPrioritizedTodaysSelectionThisPage = false
                        if (page == 1) { // "Today's Selection" chỉ ở trang 1
                            validSectionsSource.find { it.second.endsWith(todaySelectionUrlPart) }?.let { 
                                sectionsToDisplayThisPage.add(it)
                                hasPrioritizedTodaysSelectionThisPage = true
                            }
                        }
                        
                        val otherSectionsPool = validSectionsSource.filterNot { it.second.endsWith(todaySelectionUrlPart) }.toMutableList()
                        
                        val itemsPerHomePage = 5 // Tổng số grid muốn hiển thị
                        // Số lượng item ngẫu nhiên cần lấy cho trang này
                        val randomItemsNeededForThisPage = itemsPerHomePage - sectionsToDisplayThisPage.size
                        
                        if (randomItemsNeededForThisPage > 0 && otherSectionsPool.isNotEmpty()) {
                            // Để đảm bảo các trang khác nhau có các mục ngẫu nhiên khác nhau (ở một mức độ nào đó)
                            // chúng ta sẽ xáo trộn toàn bộ otherSectionsPool với một seed phụ thuộc vào page
                            // sau đó lấy một "slice" dựa trên page.
                            val random = Random(page.toLong()) // Seed dựa trên page
                            val shuffledOtherSections = otherSectionsPool.shuffled(random)

                            // Tính toán vị trí bắt đầu và kết thúc cho slice
                            // Ví dụ: page 1 lấy 0 -> needed-1, page 2 lấy needed -> 2*needed-1
                            // Điều này sẽ không hoàn toàn ngẫu nhiên nếu người dùng nhảy trang, nhưng sẽ khác nhau giữa các trang tuần tự.
                            val startIndex = (page - 1) * randomItemsNeededForThisPage 
                            val endIndex = startIndex + randomItemsNeededForThisPage

                            if (startIndex < shuffledOtherSections.size) {
                                sectionsToDisplayThisPage.addAll(shuffledOtherSections.subList(startIndex, minOf(endIndex, shuffledOtherSections.size)))
                            }
                        }
                        
                        // Kiểm tra hasNextPage: nếu còn section trong otherSectionsPool để hiển thị ở trang tiếp theo
                        val nextRandomItemsNeeded = itemsPerHomePage - (if(page + 1 == 1 && validSectionsSource.any{it.second.endsWith(todaySelectionUrlPart)}) 1 else 0)
                        val nextStartIndexForRandom = page * nextRandomItemsNeeded // Vì page hiện tại là `page`, trang tiếp theo là `page+1`, (page+1-1)*needed = page*needed
                        if (nextStartIndexForRandom < otherSectionsPool.size && sectionsToDisplayThisPage.isNotEmpty()) {
                             hasNextMainPage = true
                        }
                        // Giới hạn số trang ảo để tránh lặp vô hạn nếu logic chọn không hoàn hảo
                        if (page >= 5) hasNextMainPage = false


                        println("TxnhhProvider DEBUG: getMainPage (Page $page) - Final sections to display: ${sectionsToDisplayThisPage.size} -> ${sectionsToDisplayThisPage.map { it.first }}. HasNext: $hasNextMainPage")

                        if (sectionsToDisplayThisPage.isNotEmpty()) {
                            coroutineScope {
                                val deferredLists = sectionsToDisplayThisPage.map { (sectionTitle, sectionUrl) ->
                                    async {
                                        val videos = fetchSectionVideos(sectionUrl) // Lấy tất cả video
                                        if (videos.isNotEmpty()) HomePageList(sectionTitle, videos) else null
                                    }
                                }
                                deferredLists.forEach { it?.await()?.let { homePageListsResult.add(it) } }
                            }
                        }
                    } catch (e: Exception) { e.printStackTrace() }
                }
            }
        }

        if (homePageListsResult.isEmpty() && page == 1) {
            homePageListsResult.add(HomePageList("Default Links (Fallback)", listOf(
                newMovieSearchResponse(name = "Asian Woman", url = "$mainUrl/search/asian_woman", type = TvType.NSFW) {},
                newMovieSearchResponse(name = "Today's Selection", url = "$mainUrl/todays-selection", type = TvType.NSFW) {}
            )))
            hasNextMainPage = false 
        }
        return newHomePageResponse(list = homePageListsResult, hasNext = hasNextMainPage)
    }

     private fun Element.toSearchResponse(): SearchResponse? {
        val titleElement = this.selectFirst(".thumb-under p a") ?: return null
        val title = titleElement.attr("title")
        var rawHref = titleElement.attr("href")

        val cleanHrefPath: String
        val thumbNumPattern = Regex("""(/video-[^/]+)/(\d+/THUMBNUM/)(.+)""")
        val matchThumbNum = thumbNumPattern.find(rawHref)

        if (matchThumbNum != null && matchThumbNum.groupValues.size == 4) {
            cleanHrefPath = "${matchThumbNum.groupValues[1]}/${matchThumbNum.groupValues[3]}"
        } else {
            val problematicUrlPattern = Regex("""(/video-[^/]+)/(\d+/\d+/)(.+)""")
            val matchProblematic = problematicUrlPattern.find(rawHref)
            if (matchProblematic != null && matchProblematic.groupValues.size == 4) {
                cleanHrefPath = "${matchProblematic.groupValues[1]}/${matchProblematic.groupValues[3]}"
            } else {
                cleanHrefPath = rawHref
            }
        }
        
        val finalHref = mainUrl + cleanHrefPath
        val posterUrl = this.selectFirst(".thumb img")?.attr("data-src")?.let { if (it.startsWith("//")) "https:$it" else it }
        val metadataElement = this.selectFirst(".thumb-under p.metadata")
        val qualityText = metadataElement?.selectFirst("span.video-hd")?.text()?.trim()
        
        return newMovieSearchResponse(name = title, url = finalHref, type = TvType.NSFW) {
            this.posterUrl = posterUrl
            this.quality = getQualityFromString(qualityText)
        }
    }

    override suspend fun search(query: String): List<SearchResponse>? { 
        println("TxnhhProvider DEBUG: search() called with query/URL = $query")
        val searchUrl = if (query.startsWith("http")) query else "$mainUrl/search/$query"
        
        val videoList = fetchSectionVideos(searchUrl) 
        
        return if (videoList.isEmpty()) {
            println("TxnhhProvider DEBUG: search() returning null as videoList is empty for $searchUrl (query: $query)")
            null 
        } else {
            videoList
        }
    }

    override suspend fun load(url: String): LoadResponse? {
        println("TxnhhProvider DEBUG: load() called with URL = $url")
        val document = app.get(url).document
        val title = document.selectFirst(".video-title strong")?.text() ?: document.selectFirst("meta[property=og:title]")?.attr("content") ?: "Unknown Title"
        val poster = document.selectFirst("meta[property=og:image]")?.attr("content")?.let { if (it.startsWith("//")) "https:$it" else it }
        val description = document.selectFirst("p.video-description")?.text()?.trim()
        val tags = document.select(".metadata-row.video-tags a:not(#suggestion)").mapNotNull { it.text()?.trim() }.filter { it.isNotEmpty() }

        val scriptElements = document.select("script:containsData(html5player.setVideoHLS)")
        var hlsLink: String? = null

        if (scriptElements.isNotEmpty()) {
            val scriptContent = scriptElements.html()
            hlsLink = Regex("""html5player\.setVideoHLS\s*\(\s*['"](.*?)['"]\s*\)""").find(scriptContent)?.groupValues?.get(1)
        }
        
        val videoDataString = hlsLink?.let { "hls:$it" } ?: ""

        // Khôi phục Recommendations
        val relatedVideos = ArrayList<SearchResponse>()
        val relatedScript = document.select("script:containsData(var video_related)")
        if (relatedScript.isNotEmpty()) {
            val scriptContentRelated = relatedScript.html()
            val jsonRegexRelated = Regex("""var video_related\s*=\s*(\[(?:.|\n)*?\])\s*;""") 
            val matchRelated = jsonRegexRelated.find(scriptContentRelated)
            if (matchRelated != null && matchRelated.groupValues.size > 1) {
                val jsonArrayStringRelated = matchRelated.groupValues[1]
                try {
                    val relatedItems = AppUtils.parseJson<List<RelatedItem>>(jsonArrayStringRelated)
                    relatedItems.forEach { related ->
                        if (related.u != null && related.tf != null) {
                            var cleanRelatedHrefPath = related.u
                            val relThumbNumPattern = Regex("""(/video-[^/]+)/(\d+/THUMBNUM/)(.+)""")
                            val relProblematicPattern = Regex("""(/video-[^/]+)/(\d+/\d+/)(.+)""")

                            var relMatch = relThumbNumPattern.find(related.u)
                            if (relMatch != null && relMatch.groupValues.size == 4) {
                                cleanRelatedHrefPath = "${relMatch.groupValues[1]}/${relMatch.groupValues[3]}"
                            } else {
                                relMatch = relProblematicPattern.find(related.u)
                                if (relMatch != null && relMatch.groupValues.size == 4) {
                                    cleanRelatedHrefPath = "${relMatch.groupValues[1]}/${relMatch.groupValues[3]}"
                                }
                            }
                            val finalRelatedUrl = mainUrl + cleanRelatedHrefPath

                            relatedVideos.add(newMovieSearchResponse(
                                name = related.tf,
                                url = finalRelatedUrl,
                                type = TvType.NSFW
                            ) {
                                this.posterUrl = related.i?.let { if (it.startsWith("//")) "https:$it" else it }
                                // this.length = parseDuration(related.d) // Có thể thêm nếu cần
                            })
                        }
                    }
                    println("TxnhhProvider DEBUG: Found ${relatedVideos.size} related videos.")
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
            } catch (_: Exception) {}
        }

        return newMovieLoadResponse(name = title, url = url, type = TvType.NSFW, dataUrl = videoDataString) {
            this.posterUrl = poster
            this.plot = description
            this.tags = tags
            this.recommendations = relatedVideos // Đã khôi phục
            this.duration = durationInSeconds
        }
    }

    override suspend fun loadLinks(
        data: String, 
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        var hasAddedLink = false
        if (data.startsWith("hls:")) {
            val videoStreamUrl = data.substringAfter("hls:")
            if (videoStreamUrl.isNotBlank()) {
                callback.invoke(
                    ExtractorLink(
                        source = this.name,
                        name = "HLS (Auto)",
                        url = videoStreamUrl,
                        referer = "", 
                        quality = getQualityIntFromLinkType("hls"),
                        type = ExtractorLinkType.M3U8, 
                    )
                )
                hasAddedLink = true
            }
        }
        return hasAddedLink
    }
}
