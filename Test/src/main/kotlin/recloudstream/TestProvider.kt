package com.heovl // You can change this package

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Element

class HeoVLProvider : MainAPI() {
    // Error 1 Fix: mainPageUrl should be 'val' not 'var' when overriding
    override val mainPageUrl = "https://heovl.fit"
    override var name = "HeoVL" // Name can be var if you intend to change it, but usually val
    override val supportedTypes = setOf(TvType.NSFW)
    override var lang = "vi" // Lang can be var, but usually val
    override val hasMainPage = true
    // Removed hasChromecastSupport as it's not a standard overrideable MainAPI property
    // It's usually handled by the player capabilities.

    private fun Element.toSearchResponse(): SearchResponse? {
        val titleElement = this.selectFirst("h3.video-box__heading")
        val title = titleElement?.text()?.trim() ?: return null
        
        // Ensure href is a full URL or correctly prefixed
        var href = this.selectFirst("a.video-box__thumbnail__link")?.attr("href") ?: return null
        if (href.startsWith("//")) {
            href = "https:$href"
        } else if (href.startsWith("/")) {
            href = mainPageUrl + href
        }
        if (!href.startsWith("http")) return null


        val posterUrl = this.selectFirst("a.video-box__thumbnail__link img")?.attr("src")
        var absolutePosterUrl = posterUrl?.let {
            if (it.startsWith("//")) "https:$it"
            else if (it.startsWith("/")) mainPageUrl + it 
            else it 
        }
        // Ensure poster URL is valid
        if (absolutePosterUrl?.startsWith("http") == false) {
            absolutePosterUrl = null
        }


        return newMovieSearchResponse(title, href, TvType.NSFW) {
            this.posterUrl = absolutePosterUrl
        }
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse? {
        val document = app.get(mainPageUrl).document
        val homePageList = mutableListOf<HomePageList>()

        document.select("div.lg\\:col-span-3 > a[title^=Xem thêm]").forEach { sectionAnchor ->
            val sectionTitle = sectionAnchor.selectFirst("h2.heading-2")?.text()?.trim()
            var sectionUrl = sectionAnchor.attr("href") // This is the "see more" link for the category
            if (sectionUrl.startsWith("/")) sectionUrl = mainPageUrl + sectionUrl

            if (sectionTitle != null && sectionUrl.isNotEmpty()) {
                val videoElements = sectionAnchor.nextElementSibling()?.select("div.videos__box-wrapper")
                val videos = videoElements?.mapNotNull { it.selectFirst("div.video-box")?.toSearchResponse() } ?: emptyList()
                if (videos.isNotEmpty()) {
                    // Error 2 & 3 Fix: Use named argument 'nextKey' for the sectionUrl
                    homePageList.add(HomePageList(sectionTitle, videos, nextKey = sectionUrl))
                }
            }
        }

        val featuredCategoriesHeading = document.select("h3.featured-list__desktop__heading").find { it.text().trim() == "Thể loại" }
        featuredCategoriesHeading?.parent()?.select("li.featured-list__desktop__list__item")?.let { categoryElements ->
            val categories = categoryElements.mapNotNull { catElement ->
                val title = catElement.selectFirst("h3.featured-list__desktop__list__item__title")?.text()?.trim() ?: return@mapNotNull null
                var href = catElement.selectFirst("a.featured-list__desktop__list__item__body")?.attr("href") ?: return@mapNotNull null
                if (href.startsWith("/")) href = mainPageUrl + href

                if (title.equals("Tất cả thể loại", ignoreCase = true) && href.endsWith("/trang/the-loai")) return@mapNotNull null


                val poster = catElement.selectFirst("img")?.attr("src")
                val absolutePosterUrl = poster?.let { if (it.startsWith("/")) mainPageUrl + it else it }

                // For categories, the href itself is the data
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
                 var href = navLink.attr("href")
                 if (href.startsWith("/")) href = mainPageUrl + href
                 // Error 2 & 3 Fix: Use named argument 'nextKey'
                 homePageList.add(HomePageList(title, emptyList(), nextKey = href ))
            }
        }

        return HomePageResponse(homePageList)
    }

    override suspend fun search(query: String): List<SearchResponse>? {
        // CloudStream calls search with the `nextKey` from HomePageList if it exists, 
        // or with the user's search query.
        // The `query` variable here can be a category URL or a search term.
        
        val searchOrCategoryUrl = if (query.startsWith("http")) {
            query // It's already a full URL (likely a category link from getMainPage)
        } else {
            "$mainPageUrl/search?q=$query" // It's a search term
        }
        // CloudStream handles adding &page= for pagination automatically if the initial URL doesn't have it.

        val document = app.get(searchOrCategoryUrl).document
        return document.select("div.videos div.videos__box-wrapper").mapNotNull {
            it.selectFirst("div.video-box")?.toSearchResponse()
        }
    }

    override suspend fun load(url: String): LoadResponse? {
        val document = app.get(url).document

        val title = document.selectFirst("div#detail-page h1.heading-1")?.text()?.trim() ?: return null
        val posterUrl = document.selectFirst("meta[property=og:image]")?.attr("content")
        val description = document.selectFirst("article.detail-page__information__content")?.text()?.trim()
        
        val genres = document.select("div.featured-list__desktop__list__item a[href*=categories]").mapNotNull { it.text() }.distinct()
        val tags = document.select("div.featured-list__desktop__list__item a[href*=tag]").mapNotNull { it.text() }.distinct()

        val extractorLinks = mutableListOf<ExtractorLink>()

        document.select("button.set-player-source").forEach { button ->
            val sourceUrl = button.attr("data-source")
            val serverName = button.text().trim()
            if (sourceUrl.isNotBlank()) {
                // Error 4 Fix: Use newExtractorLink
                extractorLinks.add(
                    newExtractorLink(
                        source = this.name, // Provider name
                        name = serverName,  // Server name (e.g., "Server 1")
                        url = sourceUrl,
                        referer = url,      // Referer is the current video page URL
                        quality = Qualities.Unknown.value,
                        isM3u8 = sourceUrl.contains(".m3u8", ignoreCase = true)
                    )
                )
            }
        }
        
        if (extractorLinks.isEmpty()) return null

        // For movies, create a single episode. The links will be associated with this episode.
        // The `data` for the episode itself can be the first source or the page URL.
        // Since we're providing extractorLinks directly to AnimeLoadResponse, Episode.data might be less critical here.
        val episodes = listOf(
            Episode(
                data = extractorLinks.first().url, // Use the first link as primary data for the episode
                name = title, // Or "Xem Phim" / "Play"
                // posterUrl = posterUrl, // Episode specific poster if different
                // description = description // Episode specific description if different
            )
        )
        
        val recommendations = document.select("div[x-data*=list\\(\\'\\/ajax\\/suggestions\\/").firstOrNull()?.parent()
            ?.select("div.video-box")?.mapNotNull { it.toSearchResponse() }

        // Error 5 Fix: Use newAnimeLoadResponse as it supports `extractorLinks` parameter directly.
        // MovieLoadResponse might be a typealias or simpler version.
        return newAnimeLoadResponse(
            name = title,
            url = url,
            type = TvType.NSFW,
            subEpisodes = episodes, // List of Episode objects
            posterUrl = posterUrl,
            plot = description,
            tags = tags,
            recommendations = recommendations,
            extractorLinks = extractorLinks // Pass the list of ExtractorLink here
        ) {
            // Additional properties for AnimeLoadResponse can be set here if needed
            this.year = null // Parse year if available
            // this.actors = actors // Parse actors if available
             this.genres = genres // Set genres parsed from the page
        }
    }
}
