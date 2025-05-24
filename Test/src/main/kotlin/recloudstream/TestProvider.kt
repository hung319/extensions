package com.heovl // Bạn có thể thay đổi package này

import com.lagradost.cloudstream3.*
// Đảm bảo AppUtils được import đúng cách, thường thì utils.* sẽ bao gồm nó
import com.lagradost.cloudstream3.utils.* // Hoặc import cụ thể nếu cần: import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import com.fasterxml.jackson.annotation.JsonProperty
import org.jsoup.nodes.Element

// Data class cho item trong suggestions
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

        val sourceUrls = document.select("button.set-player-source").mapNotNull { button ->
            button.attr("data-source").ifBlank { null }
        }
        
        if (sourceUrls.isEmpty()) return null
        val primarySourceUrl = sourceUrls.first()
        
        var pageRecommendations: List<SearchResponse>? = null
        try {
            val videoSlug = url.substringAfterLast("/videos/").substringBefore("?")
            if (videoSlug.isNotBlank()) {
                val recommendationsAjaxUrl = "$mainUrl/ajax/suggestions/$videoSlug"
                val recommendationsJsonText = app.get(recommendationsAjaxUrl).text
                
                // Sửa lại cách parse JSON sử dụng AppUtils.parseJson
                // Cần import com.lagradost.cloudstream3.utils.AppUtils.parseJson hoặc đảm bảo nó trong scope
                val recommendationResponse = parseJson<RecommendationResponse>(recommendationsJsonText) //
                
                pageRecommendations = recommendationResponse.data?.mapNotNull { item -> // Không cần chỉ định kiểu item: RecommendationItem nữa nếu parseJson hoạt động đúng
                    val itemTitle = item.title 
                    val itemUrl = item.url
                    val itemThumbnail = item.thumbnailFileUrl

                    if (itemTitle == null || itemUrl == null) return@mapNotNull null

                    val absoluteUrl = if (itemUrl.startsWith("http")) itemUrl else mainUrl + itemUrl.trimStart('/')
                    val absolutePoster = itemThumbnail?.let { if (it.startsWith("http")) it else mainUrl + it.trimStart('/') }
                    
                    newMovieSearchResponse(itemTitle, absoluteUrl, TvType.NSFW) {
                        this.posterUrl = absolutePoster
                    }
                }
            }
        } catch (e: Exception) {
            println("Error fetching or parsing recommendations: ${e.message}")
            // e.printStackTrace() // Bỏ comment nếu muốn xem stack trace chi tiết khi debug
        }

        return newMovieLoadResponse(
            name = title,
            url = url, 
            type = TvType.NSFW,
            dataUrl = primarySourceUrl 
        ) {
            this.posterUrl = pagePosterUrl
            this.plot = pageDescription
            this.tags = combinedTags
            this.recommendations = pageRecommendations 
            this.year = null 
        }
    }
}
