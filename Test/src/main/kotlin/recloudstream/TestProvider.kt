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
    // override val supportsSearchPage = true // Đã xóa

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

    private suspend fun fetchSectionVideos(sectionUrl: String, pageForPagination: Int? = null): Pair<List<SearchResponse>, String?> {
        var currentUrl = sectionUrl
        var actualPageForUrl = pageForPagination ?: 1 // Trang bắt đầu từ 1 cho logic người dùng

        if (actualPageForUrl > 1) {
            val pageNumberInUrl = actualPageForUrl - 1 // XNXX dùng /0, /1, /2... cho trang 2, 3, 4...
            val pageSuffix = "/$pageNumberInUrl"
            
            // Chỉ thêm suffix nếu URL chưa có phần số ở cuối cùng hoặc là base URL
            if (!currentUrl.matches(Regex(".*/\\d+$"))) {
                 currentUrl = "$currentUrl$pageSuffix"
            } else {
                // Nếu URL đã có dạng /search/term/number, thay thế number đó
                currentUrl = currentUrl.replace(Regex("/\\d+$"), pageSuffix)
            }
        }
        println("TxnhhProvider DEBUG: fetchSectionVideos called for URL = $currentUrl (original: $sectionUrl, pageForApp: $actualPageForUrl)")

        val videoList = mutableListOf<SearchResponse>()
        var nextPageUrl: String? = null

        try {
            val document = app.get(currentUrl).document
            val videoElements = document.select("div.mozaique div.thumb-block")
            println("TxnhhProvider DEBUG: Found ${videoElements.size} thumb-blocks in fetchSectionVideos for $currentUrl")
            
            videoElements.mapNotNullTo(videoList) { it.toSearchResponse() }

            val pagination = document.selectFirst("div.pagination ul")
            if (pagination != null) {
                val nextElement = pagination.select("li a.next, li a:not(.active):not(.disabled)").lastOrNull { 
                    // Lấy link trang kế tiếp không phải là "..." và có href
                    it.text().toIntOrNull() != null || it.hasClass("next") || it.selectFirst("span.icon-f.icf-chevron-right") != null
                }

                if (nextElement != null && nextElement.hasAttr("href")) {
                    val href = nextElement.attr("href")
                    if (href.isNotBlank() && !nextElement.hasClass("active") && !nextElement.hasClass("disabled")) { // Đảm bảo nó không phải là trang hiện tại hoặc bị vô hiệu hóa
                        // Kiểm tra xem href có phải là một trang hợp lệ không (ví dụ, không phải là #)
                        if(href != "#") {
                            nextPageUrl = mainUrl + href
                             println("TxnhhProvider DEBUG: Found nextPageUrl for fetchSectionVideos: $nextPageUrl")
                        } else {
                            println("TxnhhProvider DEBUG: Next page link is '#', considering no next page.")
                        }
                    }
                } else {
                    println("TxnhhProvider DEBUG: No valid 'Next' page link found or it's the last page for $currentUrl.")
                }
            } else {
                 println("TxnhhProvider DEBUG: Pagination block not found for $currentUrl")
            }
        } catch (e: Exception) {
            System.err.println("TxnhhProvider ERROR: Failed to fetch/parse section $currentUrl. Error: ${e.message}")
        }
        return Pair(videoList, nextPageUrl)
    }


    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        println("TxnhhProvider DEBUG: getMainPage called, page: $page")
        val homePageListsResult = ArrayList<HomePageList>()

        val document = app.get(mainUrl).document // Chỉ get 1 lần cho trang chủ
        val scriptElements = document.select("script:containsData(xv.cats.write_thumb_block_list)")

        var hasNextMainPage = false // Mặc định là không có trang chủ tiếp theo

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
                        val todaySelectionUrlPart = "/todays-selection"
                        
                        // "Today's Selection" chỉ hiển thị ở trang 1
                        if (page == 1) {
                            validSectionsSource.find { it.second.endsWith(todaySelectionUrlPart) }?.let { 
                                sectionsToDisplayThisPage.add(it)
                            }
                        }
                        
                        val otherSections = validSectionsSource.filterNot { it.second.endsWith(todaySelectionUrlPart) }.toMutableList()
                        
                        val itemsPerHomePage = 5 // Tổng số grid muốn hiển thị
                        val randomItemsNeeded = itemsPerHomePage - sectionsToDisplayThisPage.size
                        
                        if (randomItemsNeeded > 0 && otherSections.isNotEmpty()) {
                            // Để việc chọn ngẫu nhiên có tính "phân trang", ta cần một seed ổn định cho mỗi page
                            // Hoặc đơn giản là lấy các phần tử dựa trên chỉ số `page`
                            val randomSeedForPage = page.toLong() // Sử dụng page làm seed cơ bản
                            otherSections.shuffle(Random(randomSeedForPage)) // Xáo trộn với seed dựa trên trang
                            
                            // Tính toán để lấy các mục khác nhau cho mỗi trang (đơn giản hóa)
                            // Ví dụ: trang 1 lấy 0->N-1, trang 2 lấy N->2N-1 (nếu có đủ)
                            // Điều này không hoàn toàn ngẫu nhiên giữa các trang nhưng đảm bảo không lặp lại nếu số lượng section lớn
                            val startIndexForRandom = (page - 1) * randomItemsNeeded 
                            if (startIndexForRandom < otherSections.size) {
                                sectionsToDisplayThisPage.addAll(otherSections.drop(startIndexForRandom).take(randomItemsNeeded))
                            }
                        }
                        
                        // Kiểm tra xem còn section nào để hiển thị cho trang tiếp theo không
                        val nextStartIndexForRandom = page * randomItemsNeeded
                        if (otherSections.drop(nextStartIndexForRandom).take(randomItemsNeeded).isNotEmpty() && sectionsToDisplayThisPage.size >= (itemsPerHomePage - (if(page==1 && todaysSelection!=null) 1 else 0) )) {
                             hasNextMainPage = true
                        }


                        println("TxnhhProvider DEBUG: getMainPage (Page $page) - Final sections to display: ${sectionsToDisplayThisPage.map { it.first }}")

                        coroutineScope {
                            val deferredLists = sectionsToDisplayThisPage.map { (sectionTitle, sectionUrl) ->
                                async {
                                    val (videos, _) = fetchSectionVideos(sectionUrl) // Lấy tất cả video
                                    if (videos.isNotEmpty()) {
                                        HomePageList(sectionTitle, videos)
                                    } else { null }
                                }
                            }
                            deferredLists.forEach { it?.await()?.let { homePageListsResult.add(it) } }
                        }
                    } catch (e: Exception) { e.printStackTrace() }
                }
            }
        }

        if (homePageListsResult.isEmpty() && page == 1) { // Chỉ thêm default nếu là trang 1 và không có gì
            homePageListsResult.add(HomePageList("Default Links (Fallback)", listOf(
                newMovieSearchResponse(name = "Asian Woman", url = "$mainUrl/search/asian_woman", type = TvType.NSFW) {},
                newMovieSearchResponse(name = "Today's Selection", url = "$mainUrl/todays-selection", type = TvType.NSFW) {}
            )))
        }
        return newHomePageResponse(list = homePageListsResult, hasNext = hasNextMainPage)
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

    override suspend fun search(query: String, page: Int): SearchPageResponse { 
        println("TxnhhProvider DEBUG: search() called with query/URL = $query, page = $page")
        
        val baseUrlForSearch = if (query.startsWith("http")) {
             query.substringBeforeLast('/').let { if(it.substringAfterLast('/').toIntOrNull() != null && it.count{c -> c == '/'} >=3) it.substringBeforeLast('/') else it}
                 .let { if (it.endsWith("search")) "$it/$query" else it } // Handle case where query is actually search term for a base url
        } else {
            "$mainUrl/search/$query"
        }
        // Loại bỏ /page nếu query là url từ homepage đã có phân trang
        val cleanBaseUrl = baseUrlForSearch.replace(Regex("/\\d+$"), "")


        val (videoList, nextPageUrl) = fetchSectionVideos(cleanBaseUrl, page) // Truyền page
        println("TxnhhProvider DEBUG: search() - Fetched ${videoList.size} videos. Next page URL for app: $nextPageUrl. Current app page: $page")
        // Nếu nextPageUrl không null, nghĩa là có trang tiếp theo
        return newSearchPageResponse(title = query, list = videoList, hasNextPage = nextPageUrl != null)
    }


    override suspend fun load(url: String): LoadResponse? {
        // ... (Hàm load giữ nguyên như trước)
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
                        referer = "", // Để CloudStream tự xử lý
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
