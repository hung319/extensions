package com.heovl

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.fasterxml.jackson.annotation.JsonProperty
import org.jsoup.nodes.Element
import java.util.regex.Pattern

// Data class (giữ nguyên)
data class StreamQQVideoData(
    @JsonProperty("sources") val sources: List<StreamQQSource>?
)

data class StreamQQSource(
    @JsonProperty("file") val file: String?,
    @JsonProperty("type") val type: String?
)

data class RecommendationItem(
    @JsonProperty("title") val title: String?,
    @JsonProperty("url") val url: String?,
    @JsonProperty("thumbnail_file_url") val thumbnailFileUrl: String?
)

data class RecommendationResponse(
    @JsonProperty("data") val data: List<RecommendationItem>?
)

class HeoVLProvider : MainAPI() {
    override var mainUrl = "https://heovl.fit"
    override var name = "HeoVL"
    override val supportedTypes = setOf(TvType.NSFW)
    override var lang = "vi"
    override val hasMainPage = true

    // ... (các hàm toSearchResponse, getMainPage, search, load giữ nguyên như trước) ...
    private fun Element.toSearchResponse(): SearchResponse? {
        val titleElement = this.selectFirst("h3.video-box__heading")
        val title = titleElement?.text()?.trim() ?: return null
        
        var href = this.selectFirst("a.video-box__thumbnail__link")?.attr("href") ?: return null
        if (href.startsWith("//")) {
            href = "https:$href"
        } else if (href.startsWith("/")) {
            href = mainUrl + href
        }
        if (!href.startsWith("http")) {
            return null
        }

        val posterUrl = this.selectFirst("a.video-box__thumbnail__link img")?.attr("src")
        var absolutePosterUrl = posterUrl?.let {
            if (it.startsWith("//")) "https:$it"
            else if (it.startsWith("/")) mainUrl + it 
            else it 
        }
        if (absolutePosterUrl?.startsWith("http") == false) {
            absolutePosterUrl = null
        }

        return newMovieSearchResponse(title, href, TvType.NSFW) {
            this.posterUrl = absolutePosterUrl
        }
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse? {
        if (page > 1) return null

        val document = app.get(mainUrl).document
        val homePageList = mutableListOf<HomePageList>()

        document.select("div.lg\\:col-span-3 > a[title^=Xem thêm]").forEach { sectionAnchor ->
            val sectionTitle = sectionAnchor.selectFirst("h2.heading-2")?.text()?.trim()
            if (sectionTitle != null) {
                val videoElements = sectionAnchor.nextElementSibling()?.select("div.videos__box-wrapper")
                val videos = videoElements?.mapNotNull { it.selectFirst("div.video-box")?.toSearchResponse() } ?: emptyList()
                if (videos.isNotEmpty()) {
                    homePageList.add(HomePageList(sectionTitle, videos))
                }
            }
        }
        
        if (homePageList.isEmpty()) {
            document.select("nav#navbar div.hidden.md\\:flex a.navbar__link[href*=categories]").forEach { navLink ->
                 val title = navLink.attr("title")
                 homePageList.add(HomePageList(title, emptyList()))
            }
        }

        return newHomePageResponse(list = homePageList, hasNext = false) 
    }

    override suspend fun search(query: String): List<SearchResponse>? {
        val searchOrCategoryUrl = if (query.startsWith("http")) {
            query 
        } else {
            "$mainUrl/search?q=$query"
        }
        val document = app.get(searchOrCategoryUrl).document 
        return document.select("div.videos div.videos__box-wrapper").mapNotNull {
            it.selectFirst("div.video-box")?.toSearchResponse()
        }
    }

    override suspend fun load(url: String): LoadResponse? {
        val document = app.get(url).document

        val title = document.selectFirst("div#detail-page h1.heading-1")?.text()?.trim() ?: return null
        val pagePosterUrl = document.selectFirst("meta[property=og:image]")?.attr("content")
        val pageDescription = document.selectFirst("article.detail-page__information__content")?.text()?.trim()
        
        val combinedTags = (
            document.select("div.featured-list__desktop__list__item a[href*=categories]").mapNotNull { it.text() } +
            document.select("div.featured-list__desktop__list__item a[href*=tag]").mapNotNull { it.text() }
        ).distinct()
        
        val episode = Episode(
            data = url, 
            name = title
        )
        
        var pageRecommendations: List<SearchResponse>? = null
        try {
            val videoSlug = url.substringAfterLast("/videos/").substringBefore("?")
            if (videoSlug.isNotBlank()) {
                val recommendationsAjaxUrl = "$mainUrl/ajax/suggestions/$videoSlug"
                val recommendationResponse = app.get(recommendationsAjaxUrl).parsed<RecommendationResponse>()
                
                pageRecommendations = recommendationResponse.data?.mapNotNull { item ->
                    val itemTitle = item.title 
                    val itemUrl = item.url
                    val itemThumbnail = item.thumbnailFileUrl

                    if (itemTitle == null || itemUrl == null) return@mapNotNull null

                    val absoluteUrl = if (itemUrl.startsWith("http")) itemUrl else mainUrl + itemUrl.trimStart('/')
                    val absolutePoster = itemThumbnail?.let { thumb -> 
                        if (thumb.startsWith("http")) thumb else mainUrl + thumb.trimStart('/') 
                    }
                    
                    newMovieSearchResponse(itemTitle, absoluteUrl, TvType.NSFW) {
                        this.posterUrl = absolutePoster
                    }
                }
            }
        } catch (e: Exception) {
            println("Error fetching or parsing recommendations: ${e.message}")
        }

        return newMovieLoadResponse(
            name = title,
            url = url, 
            type = TvType.NSFW,
            dataUrl = episode.data 
        ) {
            this.posterUrl = pagePosterUrl
            this.plot = pageDescription
            this.tags = combinedTags
            this.recommendations = pageRecommendations 
            this.year = null 
        }
    }

    // Hàm helper để tìm link .m3u8 hoặc .mp4 từ một đoạn text (HTML/script)
    // Sẽ trả về Pair<String?, ExtractorLinkType?>: link và type của nó
    private fun findDirectVideoLink(text: String): Pair<String?, ExtractorLinkType?> {
        // Ưu tiên M3U8
        val m3u8Pattern = Pattern.compile("""["'](https?://[^"']+\.m3u8[^"']*)["']""")
        var matcher = m3u8Pattern.matcher(text)
        if (matcher.find()) {
            return Pair(matcher.group(1), ExtractorLinkType.M3U8)
        }

        // Nếu không có M3U8, tìm MP4
        val mp4Pattern = Pattern.compile("""["'](https?://[^"']+\.mp4[^"']*)["']""")
        matcher = mp4Pattern.matcher(text)
        if (matcher.find()) {
            return Pair(matcher.group(1), ExtractorLinkType.MP4)
        }
        return Pair(null, null)
    }

    override suspend fun loadLinks(
        data: String, // URL của trang video HeoVL
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val heovlPageDocument = app.get(data).document 
        var foundAnyDirectLinks = false

        val embedSources = heovlPageDocument.select("button.set-player-source").mapNotNull { button ->
            val embedUrl = button.attr("data-source")
            val serverName = button.text().trim().ifBlank { name }
            if (embedUrl.isNotBlank()) Pair(serverName, embedUrl) else null
        }

        for ((serverName, embedUrl) in embedSources) {
            try {
                println("Processing server: $serverName, embed URL: $embedUrl")
                if (embedUrl.contains("streamqq.com")) {
                    val embedDocText = app.get(embedUrl, referer = data).text
                    val videoDataRegex = Regex("""window\.videoData\s*=\s*(\{[\s\S]+?\});""")
                    val matchResult = videoDataRegex.find(embedDocText)
                    
                    if (matchResult != null) {
                        val videoDataJson = matchResult.groupValues[1]
                        try {
                            val videoData = parseJson<StreamQQVideoData>(videoDataJson)
                            videoData.sources?.firstOrNull { 
                                it.type?.contains("hls", true) == true || it.type?.contains("mpegurl", true) == true 
                            }?.file?.let { m3u8RelativePath ->
                                val domain = if (embedUrl.startsWith("http")) embedUrl.substringBefore("/videos/") else "https://e.streamqq.com"
                                val absoluteM3u8Url = if (m3u8RelativePath.startsWith("http")) m3u8RelativePath else domain + m3u8RelativePath.trimStart('/')
                                
                                callback(
                                    newExtractorLink(
                                        source = serverName,
                                        name = "$serverName (M3U8)",
                                        url = absoluteM3u8Url,
                                        type = ExtractorLinkType.M3U8
                                    ) {
                                        this.referer = embedUrl
                                        this.quality = Qualities.Unknown.value
                                    }
                                )
                                foundAnyDirectLinks = true
                                println("Found M3U8 for StreamQQ: $absoluteM3u8Url")
                            } ?: println("No M3U8 source found in StreamQQ videoData for $serverName")
                        } catch (e: Exception) {
                             println("Error parsing StreamQQ videoData JSON: ${e.message} for $embedUrl")
                        }
                    } else {
                         println("Could not find window.videoData for StreamQQ: $embedUrl")
                    }
                } else if (embedUrl.contains("playheovl.xyz") || embedUrl.contains("vnstream.net")) {
                    println("PlayHeoVL/VNStream Embed: $embedUrl. Requires JS deobfuscation and API call logic.")
                    // **PHẦN NÀY VẪN CẦN LOGIC GIẢI MÃ JAVASCRIPT VÀ GỌI API PHỨC TẠP**
                    // Hiện tại, chúng ta không thể cung cấp link M3U8/MP4 trực tiếp mà không có logic đó.
                    // Vì không có ExtractorLinkType.EMBED, chúng ta sẽ không gọi callback cho server này
                    // trừ khi bạn hoàn thành logic bóc tách JS.
                    // Để ví dụ, nếu bạn *đã* có logic đó và lấy được linkM3U8:
                    // val directM3u8FromPlayHeoVL = yourCustomJsExtractionFunction(embedUrl, data)
                    // if (directM3u8FromPlayHeoVL != null) {
                    //    callback(newExtractorLink(serverName, "$serverName (M3U8 - API)", directM3u8FromPlayHeoVL, ExtractorLinkType.M3U8) { ... })
                    //    foundAnyDirectLinks = true
                    // }
                    println("Skipping PlayHeoVL/VNStream for now as direct link extraction is not implemented.")

                } else {
                    // Thử tìm link M3U8/MP4 chung cho các server không xác định
                    println("Unknown server type, attempting generic M3U8/MP4 extraction for: $embedUrl")
                    val embedPageContent = app.get(embedUrl, referer = data).text
                    val (directLink, linkType) = findDirectVideoLink(embedPageContent)

                    if (directLink != null && linkType != null) {
                        callback(
                            newExtractorLink(
                                source = serverName,
                                name = "$serverName (${linkType.name})",
                                url = directLink,
                                type = linkType
                            ) {
                                this.referer = embedUrl
                                this.quality = Qualities.Unknown.value
                            }
                        )
                        foundAnyDirectLinks = true
                        println("Found generic direct link: $directLink")
                    } else {
                        println("No generic M3U8/MP4 found for $embedUrl")
                    }
                }
            } catch (e: Exception) {
                println("Error in loadLinks for $embedUrl: ${e.message}")
            }
        }
        return foundAnyDirectLinks
    }
}
