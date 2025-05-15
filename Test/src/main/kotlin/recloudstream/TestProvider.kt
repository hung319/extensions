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
    // Bỏ: override val supportsSearchPage = true 

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

    // fetchSectionVideos sẽ không cần xử lý page nữa vì search không có page
    private suspend fun fetchSectionVideos(sectionUrl: String, maxItems: Int = Int.MAX_VALUE): List<SearchResponse> {
        println("TxnhhProvider DEBUG: fetchSectionVideos called for URL = $sectionUrl, maxItems = $maxItems")
        if (!sectionUrl.startsWith("http")) {
            println("TxnhhProvider WARNING: Invalid sectionUrl in fetchSectionVideos: $sectionUrl")
            return emptyList()
        }
        val videoList = mutableListOf<SearchResponse>()
        try {
            // Nếu sectionUrl có dạng /page/number, Jsoup sẽ tải nó.
            // Tuy nhiên, hàm search sẽ không tự động tạo các URL /page/number này.
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

        if (page == 1) { // Chỉ tải nội dung cho trang đầu tiên của getMainPage
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
                            validSectionsSource.find { it.second.endsWith("/todays-selection") }?.let { sectionsToDisplayThisPage.add(it) }
                            
                            val remainingSections = validSectionsSource.filterNot { sectionsToDisplayThisPage.contains(it) }.toMutableList()
                            remainingSections.shuffle(Random(System.currentTimeMillis())) // Ngẫu nhiên cho trang đầu
                            
                            val neededRandomSections = 5 - sectionsToDisplayThisPage.size
                            if (neededRandomSections > 0) sectionsToDisplayThisPage.addAll(remainingSections.take(neededRandomSections))
                            
                            println("TxnhhProvider DEBUG: getMainPage (Page 1) - Final sections to display: ${sectionsToDisplayThisPage.map { it.first }}")

                            coroutineScope {
                                val deferredLists = sectionsToDisplayThisPage.map { (sectionTitle, sectionUrl) ->
                                    async {
                                        val videos = fetchSectionVideos(sectionUrl) // Lấy tất cả video cho grid
                                        if (videos.isNotEmpty()) HomePageList(sectionTitle, videos) else null
                                    }
                                }
                                deferredLists.forEach { it?.await()?.let { homePageListsResult.add(it) } }
                            }
                            // Giả sử không có pagination cho các grid này trên trang chủ
                            // hasNextMainPage = false (vì chúng ta chỉ làm 1 trang duy nhất cho getMainPage theo kiểu này)
                        } catch (e: Exception) { e.printStackTrace() }
                    }
                }
            }
        }

        if (homePageListsResult.isEmpty() && page == 1) {
            homePageListsResult.add(HomePageList("Default Links (Fallback)", listOf(
                newMovieSearchResponse(name = "Asian Woman", url = "$mainUrl/search/asian_woman", type = TvType.NSFW) {},
                newMovieSearchResponse(name = "Today's Selection", url = "$mainUrl/todays-selection", type = TvType.NSFW) {}
            )))
        }
        // Trang chủ của chúng ta không phân trang theo cách truyền thống (tải thêm section)
        // Mà là hiển thị một bộ section cố định (hoặc ngẫu nhiên).
        // Nếu bạn muốn `hasNext` cho `getMainPage` để CloudStream có thể gọi `getMainPage(2, ...)`
        // thì logic chọn section phải khác nhau cho mỗi `page`.
        // Hiện tại, ta sẽ coi `getMainPage` chỉ có 1 trang.
        return newHomePageResponse(list = homePageListsResult, hasNext = false)
    }

     private fun Element.toSearchResponse(): SearchResponse? {
        val titleElement = this.selectFirst(".thumb-under p a") ?: return null
        val title = titleElement.attr("title")
        var rawHref = titleElement.attr("href")

        val problematicUrlPattern = Regex("""(/video-[^/]+)/(\d+/\d+/)(.+)""")
        val match = problematicUrlPattern.find(rawHref)
        val cleanHrefPath = if (match != null && match.groupValues.size == 4) {
            "${match.groupValues[1]}/${match.groupValues[3]}"
        } else {
            rawHref
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

    // Sửa lại chữ ký hàm search để khớp với MainAPI
    // Sẽ không có pagination tự động từ CloudStream cho hàm này
    override suspend fun search(query: String): List<SearchResponse>? { 
        println("TxnhhProvider DEBUG: search() called with query/URL = $query")
        val searchUrl = if (query.startsWith("http")) {
             // Nếu query là URL (ví dụ từ homepage item), nó có thể đã có sẵn page number
             // ví dụ: https://www.txnhh.com/search/asian_woman/1 (cho trang 2)
             query
        } else {
            "$mainUrl/search/$query" // Mặc định lấy trang đầu cho từ khóa tìm kiếm
        }
        println("TxnhhProvider DEBUG: Constructed searchUrl for search() = $searchUrl")
        
        // fetchSectionVideos sẽ tải trang đầu tiên hoặc trang được chỉ định trong searchUrl
        val videoList = fetchSectionVideos(searchUrl) 
        if (videoList.isEmpty()) {
            println("TxnhhProvider DEBUG: search() returned no results for $searchUrl")
            return null // Hoặc emptyList() tùy theo yêu cầu của MainAPI
        }
        return videoList
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
            // this.recommendations = ... // Bạn có thể thêm lại logic parse video liên quan nếu muốn
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
                        name = "HLS (Auto)", // Hoặc chỉ this.name
                        url = videoStreamUrl,
                        referer = "", // Để CloudStream tự xử lý referer (sẽ dùng URL của trang load)
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
