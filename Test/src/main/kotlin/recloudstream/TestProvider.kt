package com.heovl

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.fasterxml.jackson.annotation.JsonProperty
import org.jsoup.nodes.Element
import java.util.regex.Pattern // Để dùng regex

// Data class cho streamqq
data class StreamQQVideoData(
    @JsonProperty("sources") val sources: List<StreamQQSource>?
)

data class StreamQQSource(
    @JsonProperty("file") val file: String?,
    @JsonProperty("type") val type: String?
)

// Data class cho item trong suggestions (giữ nguyên)
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

    // ... (các hàm toSearchResponse và getMainPage, search, load giữ nguyên như trước) ...
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


    // Hàm loadLinks được cập nhật
    override suspend fun loadLinks(
        data: String, // URL của trang video HeoVL
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val heovlPageDocument = app.get(data).document 
        var foundLinks = false

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
                    // Regex để tìm object window.videoData
                    val videoDataRegex = Regex("""window\.videoData\s*=\s*(\{.+?\});""")
                    val matchResult = videoDataRegex.find(embedDocText)
                    
                    if (matchResult != null) {
                        val videoDataJson = matchResult.groupValues[1]
                        val videoData = parseJson<StreamQQVideoData>(videoDataJson)
                        videoData.sources?.firstOrNull { it.type?.contains("hls", true) == true || it.type?.contains("mpegurl", true) == true }?.file?.let { m3u8RelativePath ->
                            // Cần domain của streamqq, ví dụ: "https://e.streamqq.com"
                            // Chúng ta có thể lấy từ embedUrl
                            val domain = embedUrl.substringBefore("/videos/")
                            val absoluteM3u8Url = if (m3u8RelativePath.startsWith("http")) m3u8RelativePath else domain + m3u8RelativePath.trimStart('/')
                            
                            callback(
                                newExtractorLink(
                                    source = serverName,
                                    name = "$serverName (M3U8)",
                                    url = absoluteM3u8Url,
                                    referer = embedUrl,
                                    quality = Qualities.Unknown.value,
                                    isM3u8 = true
                                )
                            )
                            foundLinks = true
                            println("Found M3U8 for StreamQQ: $absoluteM3u8Url")
                        }
                    } else {
                         println("Could not find window.videoData for StreamQQ: $embedUrl")
                         // Fallback to embed if direct link extraction fails
                        callback(newExtractorLink(serverName, "$serverName (Embed)", embedUrl, data, Qualities.Unknown.value, type = ExtractorLinkType.EMBED))
                        foundLinks = true 
                    }
                } else if (embedUrl.contains("playheovl.xyz") || embedUrl.contains("vnstream.net")) { // playheovl.html có script từ vnstream.net
                    // Logic cho playheovl.xyz / vnstream.net
                    // Đây là phần phức tạp nhất vì có mã hóa và gọi API
                    // Cần phân tích file playheovl.html kỹ hơn để tái tạo logic gọi API lấy link m3u8
                    // Ví dụ: Tìm idfile_enc, idUser_enc, DOMAIN_API từ HTML/script của embedUrl
                    // rồi giải mã và gọi API.
                    // Đây là một placeholder, bạn cần thay thế bằng logic thực tế.
                    
                    val embedPageText = app.get(embedUrl, referer = data).text

                    // Thử tìm các biến mã hóa và API endpoint từ script
                    val idFileEncRegex = Regex("""const idfile_enc\s*=\s*["']([^"']+)["'];""")
                    val domainApiRegex = Regex("""const DOMAIN_API\s*=\s*['"](https?://[^'"]+)['"];""")

                    val idFileEnc = idFileEncRegex.find(embedPageText)?.groupValues?.get(1)
                    val domainApi = domainApiRegex.find(embedPageText)?.groupValues?.get(1)
                    // Tương tự cho idUser_enc và các key giải mã nếu có

                    println("PlayHeoVL/VNStream Embed: idFileEnc=$idFileEnc, domainApi=$domainApi")

                    if (idFileEnc != null && domainApi != null) {
                        // **PHẦN NÀY CẦN LOGIC GIẢI MÃ VÀ GỌI API THỰC TẾ**
                        // Đây chỉ là ví dụ, không phải code chạy được ngay cho việc giải mã.
                        // Bạn cần nghiên cứu file playheovl.html và các file JS nó load
                        // để hiểu cách f0001, f0002, LoadPlay hoạt động.
                        // Ví dụ (giả định, cần thay thế):
                        // val decryptedIdFile = someDecryptionFunction(idFileEnc, someKey)
                        // val apiPayload = buildApiPayload(decryptedIdFile, ...)
                        // val apiResponse = app.post(domainApi, requestBody = apiPayload.toRequestBody()).parsed<SomeApiResponse>()
                        // val m3u8Link = apiResponse.m3u8link
                        // if (m3u8Link != null) {
                        //    callback(newExtractorLink(serverName, "$serverName (M3U8 - API)", m3u8Link, embedUrl, Qualities.Unknown.value, isM3u8 = true))
                        //    foundLinks = true
                        // } else {
                        //    // Fallback
                        //    callback(newExtractorLink(serverName, "$serverName (Embed)", embedUrl, data, Qualities.Unknown.value, type = ExtractorLinkType.EMBED))
                        //    foundLinks = true
                        // }
                         println("PlayHeoVL extraction logic is complex and needs deobfuscation of its JS.")
                         println("Falling back to embed for: $serverName - $embedUrl")
                         callback(newExtractorLink(serverName, "$serverName (Embed)", embedUrl, data, Qualities.Unknown.value, type = ExtractorLinkType.EMBED))
                         foundLinks = true // Cho CloudStream thử xử lý embed
                    } else {
                        println("Could not find necessary JS variables for PlayHeoVL: $embedUrl")
                        callback(newExtractorLink(serverName, "$serverName (Embed)", embedUrl, data, Qualities.Unknown.value, type = ExtractorLinkType.EMBED))
                        foundLinks = true 
                    }
                } else {
                    // Server không xác định, thử gửi link embed
                    println("Unknown server, trying embed URL: $embedUrl for server $serverName")
                    callback(
                        newExtractorLink(
                            source = serverName,
                            name = "$serverName (Embed)",
                            url = embedUrl,
                            referer = data, 
                            quality = Qualities.Unknown.value,
                            type = ExtractorLinkType.EMBED
                        )
                    )
                    foundLinks = true // Cho CloudStream thử xử lý
                }
            } catch (e: Exception) {
                println("Error in loadLinks for $embedUrl: ${e.message}")
                // e.printStackTrace()
            }
        }
        return foundLinks
    }
}
