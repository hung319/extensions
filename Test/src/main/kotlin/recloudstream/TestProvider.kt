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

    private fun Element.toSearchResponse(): SearchResponse? {
        val title = this.selectFirst("h3.video-box__heading")?.text()?.trim() ?: return null
        var href = this.selectFirst("a.video-box__thumbnail__link")?.attr("href") ?: return null
        
        href = fixUrl(href)
        // Đảm bảo href là URL hợp lệ trước khi trả về
        if (!href.startsWith("http")) return null


        var poster = this.selectFirst("a.video-box__thumbnail__link img")?.attr("src")
        poster = poster?.let { fixUrl(it) }
        if (poster?.startsWith("http") == false) poster = null

        return newMovieSearchResponse(title, href, TvType.NSFW) {
            this.posterUrl = poster
        }
    }

    private fun fixUrl(url: String): String {
        if (url.startsWith("//")) {
            return "https:$url"
        }
        if (url.startsWith("/")) {
            return mainUrl + url
        }
        return url
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse? {
        if (page > 1) return null

        val document = app.get(mainUrl).document
        val homePageList = mutableListOf<HomePageList>()

        document.select("div.lg\\:col-span-3 > a[title^=Xem thêm]").forEach { sectionAnchor ->
            sectionAnchor.selectFirst("h2.heading-2")?.text()?.trim()?.let { sectionTitle ->
                val videos = sectionAnchor.nextElementSibling()
                    ?.select("div.videos__box-wrapper div.video-box")
                    ?.mapNotNull { it.toSearchResponse() } 
                    ?: emptyList()
                if (videos.isNotEmpty()) {
                    homePageList.add(HomePageList(sectionTitle, videos))
                }
            }
        }
        
        if (homePageList.isEmpty()) {
            document.select("nav#navbar div.hidden.md\\:flex a.navbar__link[href*=categories]").forEach { navLink ->
                 navLink.attr("title").let { title ->
                     homePageList.add(HomePageList(title, emptyList()))
                 }
            }
        }
        return newHomePageResponse(list = homePageList, hasNext = false) 
    }

    override suspend fun search(query: String): List<SearchResponse>? {
        val searchOrCategoryUrl = if (query.startsWith("http")) query else "$mainUrl/search?q=$query"
        
        return app.get(searchOrCategoryUrl).document
            .select("div.videos div.videos__box-wrapper div.video-box")
            .mapNotNull { it.toSearchResponse() }
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
        
        val episode = Episode(data = url, name = title)
        
        var pageRecommendations: List<SearchResponse>? = null
        try {
            url.substringAfterLast("/videos/").substringBefore("?").takeIf { it.isNotBlank() }?.let { videoSlug ->
                val recommendationsAjaxUrl = "$mainUrl/ajax/suggestions/$videoSlug"
                val recommendationsJsonText = app.get(recommendationsAjaxUrl).text
                
                // Lỗi 131: Sửa lại cách parse JSON bằng app.parseJson
                val recommendationResponse = app.parseJson<RecommendationResponse>(recommendationsJsonText) // SỬA Ở ĐÂY
                
                pageRecommendations = recommendationResponse.data?.mapNotNull { item -> // item bây giờ nên được suy luận kiểu RecommendationItem
                    val itemTitle = item.title // Lỗi 135
                    val itemUrl = item.url     // Lỗi 136
                    val itemThumbnail = item.thumbnailFileUrl // Lỗi 137

                    if (itemTitle == null || itemUrl == null) return@mapNotNull null

                    // Lỗi 141, 142: startsWith và trimStart sẽ hoạt động nếu itemUrl là String
                    val absoluteUrl = if (itemUrl.startsWith("http")) itemUrl else mainUrl + itemUrl.trimStart('/') 
                    val absolutePoster = itemThumbnail?.let { thumb -> 
                        if (thumb.startsWith("http")) thumb else mainUrl + thumb.trimStart('/') 
                    }
                    // Lỗi 146
                    newMovieSearchResponse(itemTitle, absoluteUrl, TvType.NSFW) {
                        this.posterUrl = absolutePoster
                    }
                }
            }
        } catch (e: Exception) {
            // Lỗi khi lấy recommendations không làm dừng hàm load
        }

        return newMovieLoadResponse(name = title, url = url, type = TvType.NSFW, dataUrl = episode.data) {
            this.posterUrl = pagePosterUrl
            this.plot = pageDescription
            this.tags = combinedTags
            this.recommendations = pageRecommendations 
            this.year = null 
        }
    }

    private fun findDirectVideoLink(text: String): Pair<String?, ExtractorLinkType?> {
        val m3u8Pattern = Pattern.compile("""["'](https?://[^"']+\.m3u8[^"']*)["']""")
        var matcher = m3u8Pattern.matcher(text)
        if (matcher.find()) {
            return Pair(matcher.group(1), ExtractorLinkType.M3U8)
        }

        val videoPattern = Pattern.compile("""["'](https?://[^"']+\.mp4[^"']*)["']""")
        matcher = videoPattern.matcher(text)
        if (matcher.find()) {
            return Pair(matcher.group(1), ExtractorLinkType.VIDEO)
        }
        return Pair(null, null)
    }

    override suspend fun loadLinks(
        data: String, 
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
                if (embedUrl.contains("streamqq.com")) {
                    val embedDocText = app.get(embedUrl, referer = data).text
                    val videoDataRegex = Regex("""window\.videoData\s*=\s*(\{[\s\S]+?\});""")
                    matchRegexResult(videoDataRegex, embedDocText) { videoDataJson ->
                        try {
                            // Lỗi 214: Sửa lại cách parse JSON bằng app.parseJson
                            val videoData = app.parseJson<StreamQQVideoData>(videoDataJson) // SỬA Ở ĐÂY
                            
                            // Lỗi 215, 216, 217: Các lỗi này là do videoData không được parse đúng kiểu
                            videoData.sources?.firstOrNull { sourceItem -> // Đặt tên biến khác 'it' để rõ ràng
                                sourceItem.type?.contains("hls", true) == true || sourceItem.type?.contains("mpegurl", true) == true 
                            }?.file?.let { m3u8RelativePath -> // file là thuộc tính của sourceItem
                                val domain = if (embedUrl.startsWith("http")) embedUrl.substringBefore("/videos/") else "https://e.streamqq.com"
                                val absoluteM3u8Url = fixUrl(domain + m3u8RelativePath)
                                
                                // Lỗi 194, 205, 206, 208, 210, 211, 212, 229, 231, 234, 236, 269:
                                // Các lỗi argument type mismatch này thường là hệ quả của các lỗi type inference trước đó.
                                // Khi kiểu dữ liệu của các biến là đúng, các hàm gọi sau đó sẽ nhận đúng kiểu tham số.
                                callback(
                                    newExtractorLink(source = serverName, name = "$serverName (M3U8)", url = absoluteM3u8Url, type = ExtractorLinkType.M3U8) {
                                        this.referer = embedUrl; this.quality = Qualities.Unknown.value
                                    }
                                )
                                foundAnyDirectLinks = true
                            }
                        } catch (e: Exception) { /* Error parsing StreamQQ JSON */ }
                    }
                } else { 
                    val embedPageContent = app.get(embedUrl, referer = data).text
                    val (directLink, linkType) = findDirectVideoLink(embedPageContent)

                    if (directLink != null && linkType != null) {
                        callback(
                            newExtractorLink(source = serverName, name = "$serverName (${linkType.name})", url = directLink, type = linkType) {
                                this.referer = embedUrl; this.quality = Qualities.Unknown.value
                            }
                        )
                        foundAnyDirectLinks = true
                    }
                }
            } catch (e: Exception) { /* Error processing individual embedUrl */ }
        }
        return foundAnyDirectLinks
    }

    private inline fun matchRegexResult(regex: Regex, content: String, R:(String) -> Unit ) {
        regex.find(content)?.groupValues?.get(1)?.let { R(it) }
    }
}
