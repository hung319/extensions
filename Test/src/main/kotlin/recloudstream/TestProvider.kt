package com.heovl // Bạn có thể thay đổi package này

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Element

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
        if (!href.startsWith("http")) return null

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

        val featuredCategoriesHeading = document.select("h3.featured-list__desktop__heading").find { it.text().trim() == "Thể loại" }
        featuredCategoriesHeading?.parent()?.select("li.featured-list__desktop__list__item")?.let { categoryElements ->
            val categories = categoryElements.mapNotNull { catElement ->
                val title = catElement.selectFirst("h3.featured-list__desktop__list__item__title")?.text()?.trim() ?: return@mapNotNull null
                var href = catElement.selectFirst("a.featured-list__desktop__list__item__body")?.attr("href") ?: return@mapNotNull null
                if (href.startsWith("/")) href = mainUrl + href
                if (title.equals("Tất cả thể loại", ignoreCase = true) && href.endsWith("/trang/the-loai")) return@mapNotNull null

                val poster = catElement.selectFirst("img")?.attr("src")
                val absolutePosterUrl = poster?.let { if (it.startsWith("/")) mainUrl + it else it }
                
                newMovieSearchResponse(title, href, TvType.NSFW) { 
                    this.posterUrl = absolutePosterUrl
                }
            }
            if (categories.isNotEmpty()) {
                homePageList.add(HomePageList("Thể Loại Nổi Bật", categories))
            }
        }
        
        if (homePageList.isEmpty()) {
            document.select("nav#navbar div.hidden.md\\:flex a.navbar__link[href*=categories]").forEach { navLink ->
                 val title = navLink.attr("title")
                 homePageList.add(HomePageList(title, emptyList()))
            }
        }

        if (homePageList.isEmpty() && page > 1) { 
            return null
        }
        // FIX: Match the parameter name 'hasNext' from the error message.
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
        
        val pageRecommendations = document.select("div[x-data*=list\\(\\'\\/ajax\\/suggestions\\/").firstOrNull()?.parent()
            ?.select("div.video-box")?.mapNotNull { it.toSearchResponse() }

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
