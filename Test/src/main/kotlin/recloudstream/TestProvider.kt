package com.heovl // Bạn có thể thay đổi package này

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Element

class HeoVLProvider : MainAPI() { // Hoặc tên provider bạn muốn
    override var mainPageUrl = "https://heovl.fit"
    override var name = "HeoVL"
    override val supportedTypes = setOf(TvType.NSFW)
    override var lang = "vi"
    override val hasMainPage = true
    override val hasChromecastSupport = true // Giả định

    // Hàm helper để parse một item video
    private fun Element.toSearchResponse(): SearchResponse? {
        val titleElement = this.selectFirst("h3.video-box__heading")
        val title = titleElement?.text()?.trim() ?: return null
        val href = this.selectFirst("a.video-box__thumbnail__link")?.attr("href") ?: return null
        if (!href.startsWith(mainPageUrl)) return null // Đảm bảo href là link của trang

        val posterUrl = this.selectFirst("a.video-box__thumbnail__link img")?.attr("src")
        // Đảm bảo posterUrl là URL đầy đủ
        val absolutePosterUrl = posterUrl?.let { if (it.startsWith("/")) mainPageUrl + it else it }

        return newMovieSearchResponse(title, href, TvType.NSFW) {
            this.posterUrl = absolutePosterUrl
            // Các thông tin khác có thể thêm ở đây nếu có, ví dụ: quality, year
        }
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse? {
        val document = app.get(mainPageUrl).document
        val homePageList = mutableListOf<HomePageList>()

        // 1. Parse các mục video chính từ trang chủ (Việt Nam, Vietsub, etc.)
        // Mỗi mục có dạng: <a href="{category_url}" title="Xem thêm {category_name}"> <h2 class="heading-2 mb-3">{category_name}</h2> </a> <div class="videos">...</div>
        document.select("div.lg\\:col-span-3 > a[title^=Xem thêm]").forEach { sectionAnchor ->
            val sectionTitle = sectionAnchor.selectFirst("h2.heading-2")?.text()?.trim()
            val sectionUrl = sectionAnchor.attr("href")
            if (sectionTitle != null && sectionUrl.isNotEmpty()) {
                val videoElements = sectionAnchor.nextElementSibling()?.select("div.videos__box-wrapper")
                val videos = videoElements?.mapNotNull { it.selectFirst("div.video-box")?.toSearchResponse() } ?: emptyList()
                if (videos.isNotEmpty()) {
                    homePageList.add(HomePageList(sectionTitle, videos, sectionUrl))
                }
            }
        }

        // 2. Parse mục "Thể loại" nổi bật
        val featuredCategoriesHeading = document.select("h3.featured-list__desktop__heading").find { it.text().trim() == "Thể loại" }
        featuredCategoriesHeading?.parent()?.select("li.featured-list__desktop__list__item")?.let { categoryElements ->
            val categories = categoryElements.mapNotNull { catElement ->
                val title = catElement.selectFirst("h3.featured-list__desktop__list__item__title")?.text()?.trim() ?: return@mapNotNull null
                val href = catElement.selectFirst("a.featured-list__desktop__list__item__body")?.attr("href") ?: return@mapNotNull null
                // Bỏ qua "Tất cả thể loại" nếu nó không phải link category thực sự hoặc xử lý riêng
                if (href == "/trang/the-loai") return@mapNotNull null

                val poster = catElement.selectFirst("img")?.attr("src")
                val absolutePosterUrl = poster?.let { if (it.startsWith("/")) mainPageUrl + it else it }

                newMovieSearchResponse(title, href, TvType.NSFW) {
                    this.posterUrl = absolutePosterUrl
                }
            }
            if (categories.isNotEmpty()) {
                homePageList.add(HomePageList("Thể Loại Nổi Bật", categories))
            }
        }
        
        // 3. Parse các mục Top từ Sidebar (Đang HOT, Top ngày, Top tuần) - Yêu cầu gọi AJAX
        // Đây là ví dụ cơ bản, cần kiểm tra định dạng JSON/HTML của các endpoint AJAX này
        // Ví dụ cho "Đang HOT"
        // try {
        //     val hotVideosDoc = app.get("$mainPageUrl/ajax/top/1h").document // Giả sử trả về HTML, nếu JSON thì .text và parse JSON
        //     // Cần selector đúng cho các item video từ response của /ajax/top/1h
        //     // Ví dụ: hotVideosDoc.select("div.video-box") hoặc parse JSON
        //     val hotVideos = hotVideosDoc.select("div.video-box").mapNotNull { it.toSearchResponse() } // Cần điều chỉnh selector
        //     if (hotVideos.isNotEmpty()) {
        //         homePageList.add(HomePageList("Đang HOT (Sidebar)", hotVideos))
        //     }
        // } catch (e: Exception) {
        //     e.printStackTrace()
        // }
        // Tương tự cho Top Ngày, Top Tuần


        if (homePageList.isEmpty()) {
            // Fallback nếu không parse được gì, có thể lấy các mục categories từ navbar
            document.select("nav#navbar div.hidden.md\\:flex a.navbar__link[href*=categories]").forEach { navLink ->
                 val title = navLink.attr("title")
                 val href = mainPageUrl + navLink.attr("href") // Đảm bảo href là URL đầy đủ
                 // Tạo một HomePageList rỗng, CloudStream sẽ tự động gọi search(href) khi người dùng click vào
                 homePageList.add(HomePageList(title, emptyList(), href))
            }
        }


        return HomePageResponse(homePageList)
    }

    override suspend fun search(query: String): List<SearchResponse>? {
        // Trang tìm kiếm có thể có URL dạng /search?q=keyword hoặc /search/keyword
        // Ở đây dùng ?q= cho thống nhất và dễ xử lý page
        // https://heovl.fit/search?q=keyword&page=2
        // Tuy nhiên, search.html được cung cấp có URL /search/viet-nam?page=2
        // Nếu query là một slug (như "viet-nam") thì nó có thể là link category, nếu là từ khóa thì là ?q=
        // CloudStream có thể gọi search cho cả link category, cần phân biệt
        
        val searchUrl = if (query.startsWith("$mainPageUrl/categories/") || query.startsWith("$mainPageUrl/tag/")) {
             query // Đây là URL category/tag đầy đủ, không cần thêm page
        } else {
            "$mainPageUrl/search?q=$query" // Đây là tìm kiếm từ khóa
        }
        // Việc xử lý page cho searchUrl cần cẩn thận. CloudStream sẽ tự thêm &page= cho query.
        // Nếu query là URL đầy đủ thì CloudStream sẽ không thêm.

        val document = app.get(searchUrl).document // CloudStream tự xử lý &page=
        val results = document.select("div.videos div.videos__box-wrapper").mapNotNull {
            it.selectFirst("div.video-box")?.toSearchResponse()
        }
        return results
    }

    override suspend fun load(url: String): LoadResponse? {
        val document = app.get(url).document

        val title = document.selectFirst("div#detail-page h1.heading-1")?.text()?.trim() ?: return null //
        val posterUrl = document.selectFirst("meta[property=og:image]")?.attr("content") //
        val description = document.selectFirst("article.detail-page__information__content")?.text()?.trim() //
        
        // Parse thể loại và tags từ trang load nếu cần
        val genres = document.select("div.featured-list__desktop__list__item a[href*=categories]").mapNotNull { it.text() }.distinct()
        val tags = document.select("div.featured-list__desktop__list__item a[href*=tag]").mapNotNull { it.text() }.distinct()


        val episodes = mutableListOf<Episode>()
        val sources = mutableListOf<ExtractorLink>()

        // Lấy các nguồn video từ các nút server
        document.select("button.set-player-source").forEach { button -> //
            val sourceUrl = button.attr("data-source") //
            val serverName = button.text().trim() // Ví dụ: "Server 1" //
            if (sourceUrl.isNotBlank()) {
                // CloudStream sẽ cố gắng dùng các extractor có sẵn cho các URL này
                // Nếu là link trực tiếp (mp4, m3u8) thì cũng sẽ hoạt động
                 sources.add(
                    ExtractorLink(
                        this.name, // Nguồn gốc của link này (tên provider)
                        serverName, // Tên của server (ví dụ: StreamQQ, PlayHQ)
                        sourceUrl,
                        url, // Referer là URL của trang video
                        Qualities.Unknown.value, // Chất lượng chưa rõ, CloudStream sẽ cố xác định
                        isM3u8 = sourceUrl.contains(".m3u8", ignoreCase = true),
                        // isDash = sourceUrl.contains(".mpd", ignoreCase = true) // Nếu có DASH
                    )
                )
            }
        }
        
        // Vì đây là phim lẻ, chỉ có 1 episode
        if (sources.isNotEmpty()) {
            episodes.add(Episode(
                data = sources.joinToString(separator = "\n") { it.url }, // Lưu trữ các URL nguồn, hoặc chỉ 1 URL chính nếu dùng addলোডLinks
                name = title, // Hoặc "Xem phim"
                episode = 1,
                season = 1,
                posterUrl = posterUrl,
                description = description,
                // quan trọng: thêm các extractor links vào đây để CloudStream xử lý
                extractorLinks = sources 
            ))
        } else {
            // Xử lý trường hợp không tìm thấy nguồn video
            // Có thể throw lỗi hoặc trả về null tùy theo logic mong muốn
            return null
        }
        

        // Lấy video đề xuất (related)
        val recommendations = document.select("div[x-data*=list\\(\\'\\/ajax\\/suggestions\\/").firstOrNull()?.parent()
            ?.select("div.video-box")?.mapNotNull { it.toSearchResponse() }


        return newMovieLoadResponse(title, url, TvType.NSFW, episodes) {
            this.posterUrl = posterUrl
            this.plot = description
            this.tags = tags
            // this.year = year // Nếu có thể parse năm
            this.recommendations = recommendations
            // Thêm các thông tin khác nếu có thể parse được
        }
    }
}
