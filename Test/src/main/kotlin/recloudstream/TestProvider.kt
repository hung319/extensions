package com.heovl // Bạn có thể thay đổi package này

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Element

class HeoVLProvider : MainAPI() {
    // Cập nhật khai báo thuộc tính theo yêu cầu
    override var mainUrl = "https://heovl.fit" // Đổi từ mainPageUrl và thành var
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
            // Sử dụng mainUrl đã được khai báo ở trên
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
        val document = app.get(mainUrl).document // Sử dụng mainUrl
        val homePageList = mutableListOf<HomePageList>()

        document.select("div.lg\\:col-span-3 > a[title^=Xem thêm]").forEach { sectionAnchor ->
            val sectionTitle = sectionAnchor.selectFirst("h2.heading-2")?.text()?.trim()
            var sectionCategoryUrl = sectionAnchor.attr("href") 
            if (sectionCategoryUrl.startsWith("/")) sectionCategoryUrl = mainUrl + sectionCategoryUrl

            if (sectionTitle != null && sectionCategoryUrl.isNotEmpty()) {
                val videoElements = sectionAnchor.nextElementSibling()?.select("div.videos__box-wrapper")
                val videos = videoElements?.mapNotNull { it.selectFirst("div.video-box")?.toSearchResponse() } ?: emptyList()
                if (videos.isNotEmpty()) {
                    // HomePageList constructor thường là (name, list, urlForNextPage)
                    homePageList.add(HomePageList(sectionTitle, videos, url = sectionCategoryUrl))
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
                 var href = navLink.attr("href")
                 if (href.startsWith("/")) href = mainUrl + href
                 homePageList.add(HomePageList(title, emptyList(), url = href ))
            }
        }

        return HomePageResponse(homePageList)
    }

    override suspend fun search(query: String): List<SearchResponse>? {
        val searchOrCategoryUrl = if (query.startsWith("http")) {
            query 
        } else {
            "$mainUrl/search?q=$query" // Sử dụng mainUrl
        }
        val document = app.get(searchOrCategoryUrl).document
        return document.select("div.videos div.videos__box-wrapper").mapNotNull {
            it.selectFirst("div.video-box")?.toSearchResponse()
        }
    }

    override suspend fun load(url: String): LoadResponse? {
        val document = app.get(url).document

        val title = document.selectFirst("div#detail-page h1.heading-1")?.text()?.trim() ?: return null
        val poster = document.selectFirst("meta[property=og:image]")?.attr("content")
        val description = document.selectFirst("article.detail-page__information__content")?.text()?.trim()
        
        val parsedGenres = document.select("div.featured-list__desktop__list__item a[href*=categories]").mapNotNull { it.text() }.distinct()
        val parsedTags = document.select("div.featured-list__desktop__list__item a[href*=tag]").mapNotNull { it.text() }.distinct()

        // Lấy danh sách các URL nguồn từ các nút server
        val sourceUrls = document.select("button.set-player-source").mapNotNull { button ->
            button.attr("data-source").ifBlank { null }
        }
        
        // Nếu không có source URL nào, không thể phát video
        if (sourceUrls.isEmpty()) return null

        // Với phim lẻ, chỉ có một Episode.
        // Chúng ta sẽ đặt URL nguồn đầu tiên vào `Episode.data`.
        // CloudStream sẽ sử dụng URL này để gọi `loadLinks` (nội bộ hoặc của extractor khác nếu khớp).
        val episode = Episode(
            data = sourceUrls.first(), // URL này sẽ được CloudStream xử lý tiếp
            name = title, // Hoặc một tên chung như "Xem Phim"
            // Các thông tin khác của episode nếu có
        )
        
        val currentRecommendations = document.select("div[x-data*=list\\(\\'\\/ajax\\/suggestions\\/").firstOrNull()?.parent()
            ?.select("div.video-box")?.mapNotNull { it.toSearchResponse() }

        // Loại bỏ extractorLinks khỏi đây
        return newAnimeLoadResponse( // Hoặc newMovieLoadResponse nếu nó phù hợp hơn và có API tương tự
            name = title,
            url = url,
            type = TvType.NSFW,
            episodes = listOf(episode), 
            posterUrl = poster,
            plot = description,
            tags = parsedTags,
            recommendations = currentRecommendations,
            year = null, 
            genres = parsedGenres 
        )
    }
}
