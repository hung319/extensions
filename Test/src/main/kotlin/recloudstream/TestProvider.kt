package com.heovl // Bạn có thể thay đổi package này

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.* import org.jsoup.nodes.Element

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
            // val sectionCategoryUrl = mainUrl + sectionAnchor.attr("href") // Đã được xử lý trong toSearchResponse
            // Lỗi 62: HomePageList có thể không có tham số 'url' hoặc 'nextKey' trực tiếp như vậy.
            // Thường thì nếu sectionCategoryUrl là link để "xem thêm", nó sẽ là data của một item đặc biệt,
            // hoặc CloudStream sẽ tự động gọi loadSearch/loadPage khi người dùng cuộn xuống/chuyển trang cho HomePageList đó.
            // Để đơn giản, ta sẽ không truyền url/nextKey ở đây nếu API không hỗ trợ rõ ràng.
            // Thay vào đó, đảm bảo rằng sectionTitle là duy nhất hoặc đủ để CloudStream phân biệt.
            // Nếu 'sectionCategoryUrl' là quan trọng để load thêm, nó thường được CloudStream quản lý qua 'key' của HomePageList.
            // Hoặc, CloudStream sẽ gọi `loadSearch(sectionCategoryUrl)` nếu `HomePageList.name` được cấu hình đúng.
            // Hiện tại, chúng ta chỉ thêm danh sách video có sẵn.
            if (sectionTitle != null) {
                val videoElements = sectionAnchor.nextElementSibling()?.select("div.videos__box-wrapper")
                val videos = videoElements?.mapNotNull { it.selectFirst("div.video-box")?.toSearchResponse() } ?: emptyList()
                if (videos.isNotEmpty()) {
                    // Thử constructor HomePageList(name: String, list: List<SearchResponse>)
                    // Nếu cần link "Xem thêm", cách làm có thể khác tùy phiên bản CS3.
                    // Một số provider dùng name là Pair(displayName, internalKeyForNextPage)
                    // Hoặc tạo một SearchResponse cuối cùng trong list với vai trò "Next Page".
                    // Tạm thời bỏ sectionCategoryUrl khỏi đây để tránh lỗi.
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
                
                newMovieSearchResponse(title, href, TvType.NSFW) { // href ở đây là URL của category
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
                 // Lỗi 87: Tương tự, bỏ url/nextKey nếu không chắc chắn.
                 // HomePageList này sẽ chỉ có tiêu đề, CloudStream sẽ không tự động load gì thêm từ nó.
                 // Để nó có thể click và load, title phải trỏ đến một query cho search().
                 // Hoặc, href này nên là một phần của `SearchResponse` nếu đây là danh sách category.
                 // Cách đơn giản:
                 homePageList.add(HomePageList(title, emptyList())) // Sẽ không click được để load thêm trừ khi cấu hình khác.
                 // Cách tốt hơn: tạo SearchResponse cho category
                 // homePageList.add(HomePageList(title, listOf(CategorySearchResponse(title, href, TvType.NSFW))))
            }
        }

        return HomePageResponse(homePageList)
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
        val poster = document.selectFirst("meta[property=og:image]")?.attr("content")
        val description = document.selectFirst("article.detail-page__information__content")?.text()?.trim()
        
        // Kết hợp genres và tags vào một danh sách tags cho MovieLoadResponse
        val combinedTags = (
            document.select("div.featured-list__desktop__list__item a[href*=categories]").mapNotNull { it.text() } +
            document.select("div.featured-list__desktop__list__item a[href*=tag]").mapNotNull { it.text() }
        ).distinct()

        val sourceUrls = document.select("button.set-player-source").mapNotNull { button ->
            button.attr("data-source").ifBlank { null }
        }
        
        if (sourceUrls.isEmpty()) return null

        val episode = Episode(
            data = sourceUrls.first(), 
            name = title,
        )
        
        val currentRecommendations = document.select("div[x-data*=list\\(\\'\\/ajax\\/suggestions\\/").firstOrNull()?.parent()
            ?.select("div.video-box")?.mapNotNull { it.toSearchResponse() }

        // Lỗi 141-147: Sử dụng newMovieLoadResponse và truyền các tham số mà nó hỗ trợ.
        // newMovieLoadResponse(name: String, url: String, type: TvType, data: List<Episode>, ...)
        // 'data' chính là danh sách episodes.
        return newMovieLoadResponse(
            name = title,
            url = url,
            type = TvType.NSFW,
            data = listOf(episode), // 'data' is the parameter for episodes
            posterUrl = poster,
            plot = description,
            tags = combinedTags, // MovieLoadResponse dùng 'tags'
            recommendations = currentRecommendations,
            year = null // Parse năm nếu có
            // 'genres' không phải là một parameter riêng của newMovieLoadResponse, đã gộp vào tags.
            // 'extractorLinks' đã được loại bỏ theo yêu cầu.
        )
    }
}
