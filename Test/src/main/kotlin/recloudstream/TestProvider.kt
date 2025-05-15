package com.ctemplar.app // Hoặc package của bạn

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.* // Đảm bảo có utils.* để có ExtractorLinkType
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

    // Cho phép search hỗ trợ phân trang
    override val supportsSearchPage = true 

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

    // Hàm fetchSectionVideos giờ sẽ xử lý cả pagination cho hàm search
    // Trả về Pair<List<SearchResponse>, nextPageUrl: String?>
    private suspend fun fetchSectionVideos(sectionUrl: String, pageForPagination: Int? = null): Pair<List<SearchResponse>, String?> {
        var currentUrl = sectionUrl
        // Nếu có pageForPagination (từ hàm search), xây dựng URL cho trang đó
        // Ví dụ: https://www.txnhh.com/search/asian_woman/2 (trang 3)
        // Trang web này dùng số trang bắt đầu từ 0 trong URL cho trang 2 trở đi (page 1 là không có số)
        if (pageForPagination != null && pageForPagination > 1) {
            // Kiểm tra xem sectionUrl đã có dạng phân trang chưa
            val pageSuffix = "/${pageForPagination - 1}"
            if (sectionUrl.contains("/search/")) { // Chỉ áp dụng cho search URLs
                 val baseUrlWithoutPage = sectionUrl.substringBeforeLast('/')
                 if (baseUrlWithoutPage != sectionUrl && baseUrlWithoutPage.substringAfterLast('/').toIntOrNull() != null) {
                    // URL đã có dạng /search/term/page_number
                    currentUrl = "$baseUrlWithoutPage$pageSuffix"
                 } else if (!sectionUrl.endsWith(pageSuffix.substring(1))) { // Tránh thêm /page nếu đã có nhưng khác
                    currentUrl = "$sectionUrl$pageSuffix"
                 }
            }
            // Các loại URL khác (ví dụ /todays-selection) cũng có thể có phân trang tương tự
            // ví dụ: /todays-selection/1, /todays-selection/2
            else if (!sectionUrl.contains("?") && !sectionUrl.endsWith(pageSuffix.substring(1)) && sectionUrl.count { it == '/' } < 4) { // Đơn giản hóa để tránh lỗi URL
                 currentUrl = "$sectionUrl$pageSuffix"
            }

        }
        println("TxnhhProvider DEBUG: fetchSectionVideos called for URL = $currentUrl (original: $sectionUrl, pageForPagination: $pageForPagination)")

        val videoList = mutableListOf<SearchResponse>()
        var nextPageUrl: String? = null

        try {
            val document = app.get(currentUrl).document
            val videoElements = document.select("div.mozaique div.thumb-block")
            println("TxnhhProvider DEBUG: Found ${videoElements.size} thumb-blocks in fetchSectionVideos for $currentUrl")
            
            videoElements.mapNotNullTo(videoList) { it.toSearchResponse() }

            // Xử lý pagination: Tìm link "Next"
            val pagination = document.selectFirst("div.pagination ul")
            if (pagination != null) {
                val currentPageElement = pagination.selectFirst("li a.active")
                val nextElement = currentPageElement?.parent()?.nextElementSibling()?.selectFirst("a")
                
                if (nextElement != null && nextElement.hasAttr("href") && !nextElement.hasClass("no-page")) {
                    val href = nextElement.attr("href")
                    if (href.isNotBlank()) {
                        nextPageUrl = mainUrl + href // URL của trang tiếp theo
                        println("TxnhhProvider DEBUG: Found nextPageUrl: $nextPageUrl")
                    }
                } else {
                    println("TxnhhProvider DEBUG: No 'Next' page link found or it's the last page.")
                }
            } else {
                 println("TxnhhProvider DEBUG: Pagination block not found for $currentUrl")
            }

        } catch (e: Exception) {
            System.err.println("TxnhhProvider ERROR: Failed to fetch/parse section $currentUrl. Error: ${e.message}")
            // e.printStackTrace()
        }
        return Pair(videoList, nextPageUrl)
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        // ... (Phần đầu giữ nguyên)
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
                if (arrayString.startsWith("[") && arrayString.endsWith("]")) {
                    try {
                        val allHomePageItems = AppUtils.parseJson<List<HomePageItem>>(arrayString)
                        val validSections = allHomePageItems.mapNotNull { item ->
                            val itemTitle = item.title ?: item.titleFallback
                            val itemUrlPart = item.url
                            if (itemTitle == null || itemUrlPart == null) return@mapNotNull null
                            val itemUrl = if (itemUrlPart.startsWith("/")) mainUrl + itemUrlPart else itemUrlPart
                            val isGameOrStory = itemUrl.contains("nutaku.net") || itemUrl.contains("sexstories.com")
                            val isLikelyStaticLink = item.noRotate == true && item.count == "0" && item.tbk == false &&
                                (isGameOrStory || item.url == "/your-suggestions/straight" || item.url == "/tags" || item.url == "/pornstars")
                            if (isGameOrStory || isLikelyStaticLink) null
                            else if (item.type == "cat" || item.type == "search" || item.url == "/todays-selection" || item.url == "/best" || item.url == "/hits" || item.url == "/fresh" || item.url == "/verified/videos") Pair(itemTitle, itemUrl)
                            else null
                        }.distinctBy { it.second }

                        val sectionsToDisplay = mutableListOf<Pair<String, String>>()
                        validSections.find { it.second.endsWith("/todays-selection") }?.let { sectionsToDisplay.add(it) }
                        
                        val remainingSections = validSections.filterNot { sectionsToDisplay.contains(it) }.toMutableList()
                        remainingSections.shuffle(Random(System.currentTimeMillis()))
                        
                        val neededRandomSections = 5 - sectionsToDisplay.size
                        if (neededRandomSections > 0) sectionsToDisplay.addAll(remainingSections.take(neededRandomSections))
                        
                        println("TxnhhProvider DEBUG: Final sections to display on homepage: ${sectionsToDisplay.map { it.first }}")

                        coroutineScope {
                            val deferredLists = sectionsToDisplay.map { (sectionTitle, sectionUrl) ->
                                async {
                                    // Gọi fetchSectionVideos KHÔNG giới hạn video cho trang chủ
                                    val (videos, _) = fetchSectionVideos(sectionUrl) // Không cần nextPageUrl ở đây
                                    if (videos.isNotEmpty()) {
                                        println("TxnhhProvider DEBUG: Successfully fetched ${videos.size} videos for homepage section '$sectionTitle'")
                                        HomePageList(sectionTitle, videos)
                                    } else {
                                        println("TxnhhProvider WARNING: No videos fetched for homepage section '$sectionTitle' ($sectionUrl)")
                                        null
                                    }
                                }
                            }
                            deferredLists.forEach { it?.await()?.let { homePageListsResult.add(it) } }
                        }
                    } catch (e: Exception) { e.printStackTrace() }
                }
            }
        }

        if (homePageListsResult.isEmpty()) {
            homePageListsResult.add(HomePageList("Default Links (Fallback)", listOf(
                newMovieSearchResponse(name = "Asian Woman", url = "$mainUrl/search/asian_woman", type = TvType.NSFW) {},
                newMovieSearchResponse(name = "Today's Selection", url = "$mainUrl/todays-selection", type = TvType.NSFW) {}
            )))
        }
        // Sửa constructor HomePageResponse
        return newHomePageResponse(homePageListsResult, hasNextPage = false)
    }

     private fun Element.toSearchResponse(): SearchResponse? {
        val titleElement = this.selectFirst(".thumb-under p a") ?: return null
        val title = titleElement.attr("title")
        val href = mainUrl + titleElement.attr("href") 
        val posterUrl = this.selectFirst(".thumb img")?.attr("data-src")?.let { if (it.startsWith("//")) "https:$it" else it }
        val metadataElement = this.selectFirst(".thumb-under p.metadata")
        // val durationText = metadataElement?.ownText()?.trim() // Tạm thời bỏ nếu không cần ở search
        val qualityText = metadataElement?.selectFirst("span.video-hd")?.text()?.trim()
        
        return newMovieSearchResponse(name = title, url = href, type = TvType.NSFW) {
            this.posterUrl = posterUrl
            this.quality = getQualityFromString(qualityText)
        }
    }

    // Sửa hàm search để sử dụng page và trả về SearchPageResponse
    override suspend fun search(query: String, page: Int): SearchPageResponse { // Thêm tham số page
        println("TxnhhProvider DEBUG: search() called with query/URL = $query, page = $page")
        
        // query ở đây có thể là từ khóa tìm kiếm hoặc URL của một category từ getMainPage
        val baseUrlForSearch = if (query.startsWith("http")) {
             // Nếu query là URL đầy đủ từ homepage (ví dụ: https://www.txnhh.com/search/asian_woman)
             // thì nó đã là base URL cho trang 1 của category đó.
             // Loại bỏ phần số trang nếu có để tạo base URL đúng
             query.substringBeforeLast('/').let { if(it.substringAfterLast('/').toIntOrNull() != null) it.substringBeforeLast('/') else it}
        } else {
            "$mainUrl/search/$query" // Nếu query là từ khóa
        }

        val (videoList, nextPageUrl) = fetchSectionVideos(baseUrlForSearch, page)
        println("TxnhhProvider DEBUG: search() - Fetched ${videoList.size} videos. Next page URL: $nextPageUrl")

        // Trả về SearchPageResponse để hỗ trợ pagination
        return newSearchPageResponse(query, videoList, nextPageUrl != null)
    }


    override suspend fun load(url: String): LoadResponse? {
        // ... (Hàm load giữ nguyên như trước)
        println("TxnhhProvider DEBUG: load() called with URL = $url")
        val document = app.get(url).document
        val title = document.selectFirst(".video-title strong")?.text() ?: document.selectFirst("meta[property=og:title]")?.attr("content") ?: "Unknown Title"
        val poster = document.selectFirst("meta[property=og:image]")?.attr("content")?.let { if (it.startsWith("//")) "https:$it" else it }
        val description = document.selectFirst("p.video-description")?.text()?.trim()
        val tags = document.select(".metadata-row.video-tags a:not(#suggestion)").mapNotNull { it.text()?.trim() }.filter { it.isNotEmpty() }

        val scriptElements = document.select("script:containsData(html5player.setVideoUrlLow)")
        var hlsLink: String? = null
        // Loại bỏ lowQualityLink và highQualityLink vì chỉ giữ HLS
        // var lowQualityLink: String? = null 
        // var highQualityLink: String? = null

        if (scriptElements.isNotEmpty()) {
            val scriptContent = scriptElements.html()
            hlsLink = Regex("""html5player\.setVideoHLS\s*\(\s*['"](.*?)['"]\s*\)""").find(scriptContent)?.groupValues?.get(1)
            // lowQualityLink = Regex("""html5player\.setVideoUrlLow\s*\(\s*['"](.*?)['"]\s*\)""").find(scriptContent)?.groupValues?.get(1)
            // highQualityLink = Regex("""html5player\.setVideoUrlHigh\s*\(\s*['"](.*?)['"]\s*\)""").find(scriptContent)?.groupValues?.get(1)
            println("TxnhhProvider DEBUG: Extracted HLS link from load(): $hlsLink")
        } else {
            println("TxnhhProvider DEBUG: Script for html5player links not found on load page: $url")
        }
        
        val videoDataString = hlsLink?.let { "hls:$it" } ?: "" // Chỉ chứa link HLS

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
            // this.recommendations = relatedVideos // Tạm bỏ qua
            this.duration = durationInSeconds
        }
    }

    override suspend fun loadLinks(
        data: String, // Đây là videoDataString từ hàm load(), ví dụ: "hls:LINK_HLS"
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        println("TxnhhProvider DEBUG: loadLinks called with data = $data")
        
        var hasAddedLink = false
        // data bây giờ chỉ chứa thông tin cho link HLS
        if (data.startsWith("hls:")) {
            val videoStreamUrl = data.substringAfter("hls:")
            if (videoStreamUrl.isNotBlank()) {
                callback.invoke(
                    ExtractorLink(
                        source = this.name,
                        name = "HLS (Auto)", // Tên cho link HLS
                        url = videoStreamUrl,
                        referer = "", // Để CloudStream tự dùng URL của trang video làm referer
                        quality = getQualityIntFromLinkType("hls"),
                        type = ExtractorLinkType.M3U8, // Đã sửa
                    )
                )
                hasAddedLink = true
                println("TxnhhProvider DEBUG: Added HLS ExtractorLink - URL: $videoStreamUrl")
            }
        }

        if (!hasAddedLink) {
            println("TxnhhProvider WARNING: No HLS link was extracted in loadLinks from data: $data")
        }
        return hasAddedLink
    }
}
