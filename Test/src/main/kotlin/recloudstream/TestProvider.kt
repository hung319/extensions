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
        // Đảm bảo href là URL hợp lệ trước khi trả về
        if (!href.startsWith("http")) {
            // Log hoặc xử lý trường hợp href không hợp lệ nếu cần
            // println("Invalid href found: $href")
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
        // Trang chủ của heovl.fit dường như không có phân trang cho các mục lớn,
        // chỉ tải các mục category chính một lần.
        // Nếu page > 1, trả về null vì không có trang tiếp theo cho chính getMainPage.
        if (page > 1) return null

        val document = app.get(mainUrl).document
        val homePageList = mutableListOf<HomePageList>()

        // Parse các mục video chính từ trang chủ (Việt Nam, Vietsub, etc.)
        document.select("div.lg\\:col-span-3 > a[title^=Xem thêm]").forEach { sectionAnchor ->
            val sectionTitle = sectionAnchor.selectFirst("h2.heading-2")?.text()?.trim()
            if (sectionTitle != null) {
                // URL để "Xem thêm" cho category này
                val categoryUrl = sectionAnchor.attr("href")?.let { if (it.startsWith("/")) mainUrl + it else it }

                val videoElements = sectionAnchor.nextElementSibling()?.select("div.videos__box-wrapper")
                val videos = videoElements?.mapNotNull { it.selectFirst("div.video-box")?.toSearchResponse() } ?: emptyList()
                
                if (videos.isNotEmpty()) {
                    // Thêm HomePageList với categoryUrl làm key để CloudStream có thể gọi search/loadPage
                    // khi người dùng muốn xem thêm từ mục này (nếu UI hỗ trợ).
                    // Hiện tại, HomePageList không có trường `key` hoặc `url` rõ ràng cho mục đích này
                    // mà thường dựa vào việc các item là SearchResponse trỏ đến category,
                    // hoặc provider implement loadPage và HomePageList có hasNext=true.
                    // Vì chúng ta đã có video, chỉ hiển thị chúng.
                    homePageList.add(HomePageList(sectionTitle, videos))
                }
            }
        }
        
        // Đã bỏ mục "Thể loại nổi bật" theo yêu cầu

        if (homePageList.isEmpty()) {
            // Fallback nếu không parse được section nào, thử lấy từ navbar
            document.select("nav#navbar div.hidden.md\\:flex a.navbar__link[href*=categories]").forEach { navLink ->
                 val title = navLink.attr("title")
                 // Tạo HomePageList rỗng, người dùng sẽ phải tự tìm kiếm category này
                 homePageList.add(HomePageList(title, emptyList())) 
            }
        }

        // `hasNext` cho getMainPage: Vì trang chủ không có phân trang cho các mục lớn,
        // nên sau khi tải trang 1, sẽ không có trang tiếp theo.
        return newHomePageResponse(list = homePageList, hasNext = false) 
    }

    override suspend fun search(query: String): List<SearchResponse>? {
        val searchOrCategoryUrl = if (query.startsWith("http")) {
            query 
        } else {
            "$mainUrl/search?q=$query"
        }
        // CloudStream sẽ tự động thêm &page= vào URL nếu cần phân trang
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
            // Trích xuất slug video từ URL hiện tại để xây dựng URL AJAX recommendations
            val videoSlug = url.substringAfterLast("/videos/").substringBefore("?")
            if (videoSlug.isNotBlank()) {
                val recommendationsAjaxUrl = "$mainUrl/ajax/suggestions/$videoSlug"
                // Thực hiện request thứ hai để lấy HTML/JSON của recommendations
                val recommendationsDoc = app.get(recommendationsAjaxUrl).document
                // Parse các video items từ response AJAX này.
                // Giả sử response là HTML chứa các div.video-box tương tự.
                pageRecommendations = recommendationsDoc.select("div.video-box")?.mapNotNull {
                    // Cần đảm bảo toSearchResponse xử lý đúng URL tương đối nếu có từ AJAX response
                    // Nếu link trong AJAX response là tương đối, cần mainUrl
                    // Tuy nhiên, toSearchResponse đã có logic prefix mainUrl nếu href bắt đầu bằng /
                    it.toSearchResponse()
                }
            }
        } catch (e: Exception) {
            // Log lỗi nếu không lấy được recommendations, nhưng không làm dừng toàn bộ hàm load
            println("Error fetching recommendations: ${e.message}")
            e.printStackTrace()
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
            this.recommendations = pageRecommendations // Gán recommendations đã parse (có thể null)
            this.year = null 
        }
    }
}
