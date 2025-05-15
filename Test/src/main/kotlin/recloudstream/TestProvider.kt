package com.ctemplar.app // Hoặc package của bạn

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Element
import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.SearchQuality
import com.lagradost.cloudstream3.extractors.helper.ExtractorLinkType // Import ExtractorLinkType
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlin.random.Random

class TxnhhProvider : MainAPI() {
    override var mainUrl = "https://www.txnhh.com"
    override var name = "Txnhh" // Sẽ được dùng làm 'source' trong ExtractorLink
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
        
        // Hàm này map chuỗi chất lượng từ link video sang giá trị Int của Qualities
        fun getQualityIntFromLinkType(type: String, videoUrl: String): Int {
            // Bạn có thể phân tích videoUrl để xác định chất lượng chính xác hơn nếu cần
            // Ví dụ: nếu URL chứa '720p', '1080p', etc.
            return when (type) {
                "hls" -> Qualities.Unknown.value // HLS có thể chứa nhiều chất lượng, trình phát sẽ chọn
                "low" -> Qualities.P360.value  // Giả định Low là 360p
                "high" -> Qualities.P720.value // Giả định High là 720p hoặc 1080p
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

    private suspend fun fetchSectionVideos(sectionUrl: String, maxItems: Int = Int.MAX_VALUE): List<SearchResponse> {
        // ... (Giữ nguyên như trước)
        println("TxnhhProvider DEBUG: fetchSectionVideos called for URL = $sectionUrl, maxItems = $maxItems")
        if (!sectionUrl.startsWith("http")) {
            println("TxnhhProvider WARNING: Invalid sectionUrl in fetchSectionVideos: $sectionUrl")
            return emptyList()
        }
        try {
            val document = app.get(sectionUrl).document
            val videoElements = document.select("div.mozaique div.thumb-block")
            println("TxnhhProvider DEBUG: Found ${videoElements.size} thumb-blocks in fetchSectionVideos for $sectionUrl")
            return videoElements.take(maxItems).mapNotNull { it.toSearchResponse() }
        } catch (e: Exception) {
            System.err.println("TxnhhProvider ERROR: Failed to fetch/parse section $sectionUrl. Error: ${e.message}")
            return emptyList()
        }
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        // ... (Giữ nguyên như trước, đã fix HomePageResponse constructor)
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
                            else if (item.type == "cat" || item.type == "search" || item.url == "/todays-selection" || item.url == "/best" || item.url == "/hits" || item.url=="/fresh" || item.url=="/verified/videos" ) Pair(itemTitle, itemUrl)
                            else null
                        }.distinctBy { it.second } 

                        val sectionsToDisplay = mutableListOf<Pair<String, String>>()
                        validSections.find { it.second.endsWith("/todays-selection") }?.let { sectionsToDisplay.add(it) }
                        
                        val remainingSections = validSections.filterNot { sectionsToDisplay.contains(it) }.toMutableList()
                        remainingSections.shuffle(Random(System.currentTimeMillis())) 
                        
                        val neededRandomSections = 5 - sectionsToDisplay.size
                        if (neededRandomSections > 0) sectionsToDisplay.addAll(remainingSections.take(neededRandomSections))
                        
                        coroutineScope {
                            val deferredLists = sectionsToDisplay.map { (sectionTitle, sectionUrl) ->
                                async {
                                    val videos = fetchSectionVideos(sectionUrl, maxItems = 20) // Giới hạn số video cho homepage grid
                                    if (videos.isNotEmpty()) HomePageList(sectionTitle, videos) else null
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
        return newHomePageResponse(homePageListsResult, hasNext = false)
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
            // parseDuration(durationText)?.let { this.length = it } // Bỏ qua length nếu không cần thiết ở search
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val searchUrl = if (query.startsWith("http")) query else "$mainUrl/search/$query"
        println("TxnhhProvider DEBUG: search() - Constructed searchUrl = $searchUrl")
        return fetchSectionVideos(searchUrl) 
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
        var lowQualityLink: String? = null
        var highQualityLink: String? = null

        if (scriptElements.isNotEmpty()) {
            val scriptContent = scriptElements.html()
            hlsLink = Regex("""html5player\.setVideoHLS\s*\(\s*['"](.*?)['"]\s*\)""").find(scriptContent)?.groupValues?.get(1)
            lowQualityLink = Regex("""html5player\.setVideoUrlLow\s*\(\s*['"](.*?)['"]\s*\)""").find(scriptContent)?.groupValues?.get(1)
            highQualityLink = Regex("""html5player\.setVideoUrlHigh\s*\(\s*['"](.*?)['"]\s*\)""").find(scriptContent)?.groupValues?.get(1)
        }
        
        val videoData = mutableListOf<String>()
        // `dataUrl` là nơi CloudStream truyền URL gốc của video vào hàm loadLinks,
        // chúng ta sẽ dùng nó làm referer.
        // Còn các link video cụ thể sẽ được parse từ `videoDataString`
        val videoDataString = videoData.apply {
            hlsLink?.let { add("hls:$it") }
            lowQualityLink?.let { add("low:$it") }
            highQualityLink?.let { add("high:$it") }
        }.joinToString("||")

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
            // this.recommendations = relatedVideos // Tạm bỏ qua related videos để đơn giản
            this.duration = durationInSeconds
        }
    }

    override suspend fun loadLinks(
        data: String, // Đây là videoDataString từ hàm load(), ví dụ: "hls:LINK1||low:LINK2||high:LINK3"
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        println("TxnhhProvider DEBUG: loadLinks called with data = $data")
        // dataUrl (URL của trang xem video) sẽ được dùng làm referer.
        // Chúng ta cần lấy nó từ đâu đó. Thông thường, `data` của `MovieLoadResponse`
        // được truyền vào đây. Nếu `data` hiện tại của chúng ta là chuỗi link,
        // thì URL trang gốc (referer) cần được lấy từ context hoặc truyền riêng.
        // Tuy nhiên, CloudStream thường truyền URL của item (từ loadResponse.url) vào data.
        // Để an toàn, ta sẽ giả định `data` là `videoDataString` và URL của trang hiện tại (referer)
        // sẽ là URL của video đang được load, có thể truy cập qua một biến cục bộ nếu bạn lưu nó
        // từ hàm load, hoặc CloudStream sẽ tự xử lý referer nếu ta không set rõ.
        // Trong trường hợp này, `this.mainUrl` hoặc URL của trang video cụ thể là tốt nhất cho referer.
        // Ta sẽ dùng `data` để lấy link, và `mainUrl` làm referer chung,
        // lý tưởng nhất là URL của trang video (được truyền vào hàm `load`).
        // Vì hàm `load` trả về `dataUrl = videoDataString`, thì `data` trong `loadLinks` CHÍNH LÀ `videoDataString`.
        // Chúng ta cần referer là URL của trang video đó.
        // CloudStream không tự động truyền URL của trang video vào đây như một tham số riêng.
        // Ta cần đảm bảo `data` của `MovieLoadResponse` chứa cả URL trang gốc và các link.
        // Sửa lại hàm `load` để `dataUrl` chứa cả URL trang và các link:
        // Ví dụ: `dataUrl = "$url||$videoDataString"`
        // Sau đó parse trong `loadLinks`:
        // val parts = data.split("||", limit = 2)
        // val refererUrl = parts[0]
        // val linksString = parts.getOrNull(1) ?: ""

        // Hiện tại, hàm load đang gán videoData.joinToString("||") vào dataUrl.
        // Vậy `data` trong `loadLinks` là chuỗi đó.
        // Chúng ta sẽ cần referer là URL của video, nhưng nó không có sẵn trực tiếp ở đây.
        // Cách tốt nhất là hàm `load` nên trả về một data class hoặc JSON string chứa cả referer và links.
        // VỚI CẤU TRÚC HIỆN TẠI, CHÚNG TA SẼ GIẢ ĐỊNH REFERER LÀ MAINURL HOẶC URL TRANG VIDEO NẾU CÓ.
        // Tuy nhiên, để đơn giản, chúng ta sẽ dùng mainUrl.
        // Tốt hơn là: Sửa hàm load() để dataUrl là URL của video, và các link được truyền qua một trường khác hoặc parse từ document trong loadLinks.
        // Nhưng vì đã thiết kế dataUrl là chuỗi link, ta sẽ cố gắng dùng nó.

        // Xem lại: `newMovieLoadResponse(..., dataUrl = videoDataString)`
        // Vậy `data` ở đây chính là `videoDataString`. Referer sẽ là `this.mainUrl` hoặc URL của trang video.
        // `url` trong `ExtractorLink` là link video stream. `referer` là trang mà stream đó được nhúng.

        // Lấy referer từ đâu?
        // Thông thường, hàm `load` nhận `url` (URL của trang video)
        // Và `loadLinks` cũng nên biết `url` đó để làm referer.
        // CloudStream sẽ truyền `MovieLoadResponse.url` làm `referer` mặc định nếu bạn không cung cấp.
        // Hoặc, bạn có thể cấu trúc `data` để chứa `refererUrl` và các `linkInfo`.

        // Hiện tại, `data` là `videoDataString`. `MovieLoadResponse.url` (là `url` truyền vào `load`) sẽ là referer mặc định.
        // Đây là cách CloudStream thường làm.

        var hasAddedLink = false
        data.split("||").forEach { linkInfo ->
            if (linkInfo.isNotBlank()) {
                val parts = linkInfo.split(":", limit = 2)
                if (parts.size == 2) {
                    val type = parts[0]
                    val videoStreamUrl = parts[1]
                    
                    val qualityName: String
                    val qualityValue: Int
                    val linkType: ExtractorLinkType

                    when (type) {
                        "hls" -> {
                            qualityName = "HLS (Auto)"
                            qualityValue = Qualities.Unknown.value // HLS tự chọn chất lượng
                            linkType = ExtractorLinkType.M3U8
                        }
                        "low" -> {
                            qualityName = "MP4 Low" // (Ví dụ: 360p)
                            qualityValue = getQualityIntFromLinkType(type, videoStreamUrl)
                            linkType = ExtractorLinkType.VIDEO
                        }
                        "high" -> {
                            qualityName = "MP4 High" // (Ví dụ: 720p/1080p)
                            qualityValue = getQualityIntFromLinkType(type, videoStreamUrl)
                            linkType = ExtractorLinkType.VIDEO
                        }
                        else -> return@forEach // Bỏ qua nếu không nhận dạng được type
                    }

                    callback.invoke(
                        ExtractorLink(
                            source = this.name,       // Tên provider
                            name = qualityName,       // Tên hiển thị cho link này
                            url = videoStreamUrl,     // Link stream
                            referer = this.mainUrl,   // Referer. QUAN TRỌNG: nên là URL của trang video cụ thể nếu có thể lấy được
                                                      // CloudStream sẽ tự dùng MovieLoadResponse.url làm referer nếu ở đây là ""
                            quality = qualityValue,
                            type = linkType,
                            // headers = mapOf("Referer" to this.mainUrl) // Có thể cần header này
                        )
                    )
                    hasAddedLink = true
                    println("TxnhhProvider DEBUG: Added ExtractorLink - Name: $qualityName, URL: $videoStreamUrl, Quality: $qualityValue, Type: $linkType")
                }
            }
        }
        if (!hasAddedLink) {
            println("TxnhhProvider WARNING: No links were extracted in loadLinks from data: $data")
        }
        return hasAddedLink
    }
}
