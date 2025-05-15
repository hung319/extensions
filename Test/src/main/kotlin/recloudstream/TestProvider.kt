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
        // ... (companion object giữ nguyên)
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
        // ... (data class giữ nguyên)
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
        // ... (fetchSectionVideos giữ nguyên)
        println("TxnhhProvider DEBUG: fetchSectionVideos called for URL = $sectionUrl, maxItems = $maxItems")
        if (!sectionUrl.startsWith("http")) {
            println("TxnhhProvider WARNING: Invalid sectionUrl in fetchSectionVideos: $sectionUrl")
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
        // ... (getMainPage giữ nguyên, đảm bảo hasNextMainPage được tính đúng)
        println("TxnhhProvider DEBUG: getMainPage called, page: $page")
        val homePageListsResult = ArrayList<HomePageList>()
        var hasNextMainPage = false 

        if (page == 1) { 
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
                            }.distinctBy { it.second } 

                            val sectionsToDisplayThisPage = mutableListOf<Pair<String, String>>()
                            // "Today's Selection" chỉ ở trang 1
                            validSectionsSource.find { it.second.endsWith("/todays-selection") }?.let { 
                                sectionsToDisplayThisPage.add(it)
                            }
                            
                            val otherSections = validSectionsSource.filterNot { sectionsToDisplayThisPage.map { it.second }.contains(it.second) }.toMutableList()
                            
                            val itemsPerHomePage = 5
                            val randomItemsNeeded = itemsPerHomePage - sectionsToDisplayThisPage.size
                            
                            if (randomItemsNeeded > 0 && otherSections.isNotEmpty()) {
                                otherSections.shuffle(Random(System.currentTimeMillis())) // Xáo trộn mỗi lần cho sự đa dạng
                                sectionsToDisplayThisPage.addAll(otherSections.take(randomItemsNeeded))
                            }
                            
                            // Logic hasNextPage cho getMainPage (rất đơn giản: nếu có nhiều hơn 5 section hợp lệ, thì "có thể" có trang tiếp theo với lựa chọn ngẫu nhiên khác)
                            if (validSectionsSource.size > itemsPerHomePage && page < 3) { // Giới hạn 3 trang "ngẫu nhiên" cho homepage
                                hasNextMainPage = true
                            }

                            println("TxnhhProvider DEBUG: getMainPage (Page $page) - Final sections to display: ${sectionsToDisplayThisPage.size} -> ${sectionsToDisplayThisPage.map { it.first }}")

                            coroutineScope {
                                val deferredLists = sectionsToDisplayThisPage.map { (sectionTitle, sectionUrl) ->
                                    async {
                                        val videos = fetchSectionVideos(sectionUrl) // Lấy tất cả video
                                        if (videos.isNotEmpty()) HomePageList(sectionTitle, videos) else null
                                    }
                                }
                                deferredLists.forEach { it?.await()?.let { homePageListsResult.add(it) } }
                            }
                        } catch (e: Exception) { e.printStackTrace() }
                    }
                }
            }
        } else if (page > 1) { // Nếu không phải trang 1 và không có logic tải thêm section cụ thể cho page > 1
             println("TxnhhProvider DEBUG: getMainPage - No specific content for page $page, returning empty with hasNext=false")
             hasNextMainPage = false // Không có trang nào sau trang này nữa
        }


        if (homePageListsResult.isEmpty() && page == 1) {
            homePageListsResult.add(HomePageList("Default Links (Fallback)", listOf(
                newMovieSearchResponse(name = "Asian Woman", url = "$mainUrl/search/asian_woman", type = TvType.NSFW) {},
                newMovieSearchResponse(name = "Today's Selection", url = "$mainUrl/todays-selection", type = TvType.NSFW) {}
            )))
            // Nếu fallback được dùng, chắc chắn không có next page
            hasNextMainPage = false 
        }
        return newHomePageResponse(list = homePageListsResult, hasNext = hasNextMainPage)
    }

     private fun Element.toSearchResponse(): SearchResponse? {
        val titleElement = this.selectFirst(".thumb-under p a") ?: return null
        val title = titleElement.attr("title")
        var rawHref = titleElement.attr("href")

        val cleanHrefPath: String

        // Pattern 1: /video-IDCHU/IDSO/THUMBNUM/SLUG...
        val thumbNumPattern = Regex("""(/video-[^/]+)/(\d+/THUMBNUM/)(.+)""")
        val matchThumbNum = thumbNumPattern.find(rawHref)

        if (matchThumbNum != null && matchThumbNum.groupValues.size == 4) {
            cleanHrefPath = "${matchThumbNum.groupValues[1]}/${matchThumbNum.groupValues[3]}"
            // println("TxnhhProvider DEBUG: Cleaned THUMBNUM URL: $rawHref -> $cleanHrefPath")
        } else {
            // Pattern 2: /video-IDCHU/IDSO/INDEX/SLUG...
            val problematicUrlPattern = Regex("""(/video-[^/]+)/(\d+/\d+/)(.+)""")
            val matchProblematic = problematicUrlPattern.find(rawHref)
            if (matchProblematic != null && matchProblematic.groupValues.size == 4) {
                cleanHrefPath = "${matchProblematic.groupValues[1]}/${matchProblematic.groupValues[3]}"
                // println("TxnhhProvider DEBUG: Cleaned ID/INDEX URL: $rawHref -> $cleanHrefPath")
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
        // println("TxnhhProvider DEBUG: Constructed searchUrl for search() = $searchUrl")
        
        val (videoList, _) = fetchSectionVideos(searchUrl) // search chỉ lấy trang đầu
        if (videoList.isEmpty() && !query.startsWith("http")) { // Nếu là tìm kiếm từ khóa và không có kết quả
             println("TxnhhProvider DEBUG: search() returned no results for keyword query: $query")
             return null // Trả về null nếu là tìm kiếm từ khóa và không có kết quả
        }
        return videoList.ifEmpty { null } // Trả về null nếu list rỗng để CloudStream biết
    }


    override suspend fun load(url: String): LoadResponse? {
        // ... (Hàm load giữ nguyên)
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
