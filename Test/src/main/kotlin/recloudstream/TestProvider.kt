package com.example.vietsubtvprovider // Thay thế bằng package name của bạn

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.AppUtils.parseJson // Để parse JSON từ data-wrap_data
import com.lagradost.cloudstream3.utils.ExtractorLink // Sẽ dùng trong loadLinks
import com.lagradost.cloudstream3.utils.Qualities // Sẽ dùng trong loadLinks
import org.jsoup.nodes.Element // Để làm việc với các phần tử HTML

// Data class để parse JSON từ thuộc tính data-wrap_data trên trang chủ
data class SplideItemData(
    val rate: String?,
    val type: String?,
    val year: String?,
    val desc: String?,
    val img_url: String?,
    val title: String?,
    val linkF: String?, // Link của phim
    val firm_cate: String? // Thể loại chính
)

class VietSubTvProvider : MainAPI() {
    override var name = "VietSubTV"
    override var mainUrl = "https://vietsubtv.us" // URL chính của trang web
    override var lang = "vi" // Ngôn ngữ chính của provider

    override val hasMainPage = true // Provider này có trang chủ
    override val hasDownloadSupport = true // Giả sử có thể lấy link trực tiếp để tải

    // Các loại nội dung được hỗ trợ
    override val supportedTypes = setOf(
        TvType.Movie,
        TvType.TvSeries,
        TvType.Anime // Dựa trên các danh mục đã thấy
    )

    // Hàm tiện ích để sửa các URL tương đối thành URL tuyệt đối
    private fun fixUrl(url: String?): String? {
        if (url.isNullOrBlank()) return null
        return when {
            url.startsWith("http") -> url
            url.startsWith("//") -> "https:$url"
            url.startsWith("/") -> "$mainUrl$url"
            else -> "$mainUrl/$url"
        }
    }

    // Hàm lấy dữ liệu cho trang chủ
    override suspend fun getMainPage(
        page: Int, // Số trang, thường là 1 cho trang chủ
        request: MainPageRequest // Chứa tên và url của section nếu là một danh sách cụ thể trên trang chủ
    ): HomePageResponse? {
        val document = app.get(mainUrl).document // Tải HTML của trang chủ

        val homePageLists = mutableListOf<HomePageList>()

        // 1. Slider chính (Phim nổi bật/mới)
        // Tham khảo: home.txt, source 2073-2325
        try {
            val mainSliderItems = document.select("section#slider div#splide01 ul.splide__list li.splide__slide")
                .mapNotNull { slide ->
                    val titleElement = slide.selectFirst("div.crs-content__title h2")
                    val title = titleElement?.text()?.trim() ?: return@mapNotNull null
                    val href = fixUrl(slide.selectFirst("a")?.attr("href")) ?: return@mapNotNull null
                    var posterUrl = fixUrl(slide.selectFirst("a img")?.attr("src"))
                    if (posterUrl == null || posterUrl.endsWith("Slider")) { // Đôi khi ảnh slider không phải poster
                         posterUrl = fixUrl(slide.selectFirst("div.crs-content a img")?.attr("src")) // Thử lấy ảnh khác nếu có
                    }


                    val episodeNumberText = slide.selectFirst("div.episode_number")?.text()?.trim()
                    val updateSetText = slide.selectFirst("span.update-set")?.text()?.trim()
                    val isTvSeries = episodeNumberText?.contains("Phim bộ", ignoreCase = true) == true ||
                                     updateSetText?.contains("Tập", ignoreCase = true) == true

                    val year = slide.selectFirst("div.year.after-item")?.text()?.trim()?.toIntOrNull()

                    if (isTvSeries) {
                        TvSeriesSearchResponse(
                            name = title,
                            url = href,
                            apiName = this.name,
                            type = TvType.TvSeries,
                            posterUrl = posterUrl,
                            year = year,
                            quality = if (!updateSetText.isNullOrBlank()) SearchQuality.Custom(updateSetText) else null
                        )
                    } else {
                        MovieSearchResponse(
                            name = title,
                            url = href,
                            apiName = this.name,
                            type = TvType.Movie,
                            posterUrl = posterUrl,
                            year = year,
                            quality = if (!updateSetText.isNullOrBlank()) SearchQuality.Custom(updateSetText) else null
                        )
                    }
                }
            if (mainSliderItems.isNotEmpty()) {
                homePageLists.add(HomePageList("Phim Đề Cử (Slider)", mainSliderItems))
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }


        // 2. Các section phim theo danh mục/quốc gia
        // Tham khảo: home.txt, source 2326-3184
        val sections = document.select("section.firm-by-category")
        sections.forEach { section ->
            try {
                val sectionTitle = section.selectFirst("h2.title-category")?.text()?.trim() ?: "Phim Mới"
                val itemsInSection = section.select("div.splide__item").mapNotNull { item ->
                    try {
                        val dataWrap = item.attr("data-wrap_data").trim()
                        if (dataWrap.isBlank()) return@mapNotNull null

                        // Đôi khi JSON trong data-wrap_data có thể không chuẩn, cần xử lý
                        val correctedJson = dataWrap
                            .replace(Regex("""(\w+)\s*:\s*"""), "\"$1\":") // Thêm dấu ngoặc kép cho key
                            .replace(Regex(""",\s*}"""), "}") // Xóa dấu phẩy thừa cuối JSON object
                            .replace(Regex(""",\s*]"""), "]") // Xóa dấu phẩy thừa cuối JSON array

                        val splideData = parseJson<SplideItemData>(correctedJson)

                        val title = splideData.title ?: return@mapNotNull null
                        val href = fixUrl(splideData.linkF) ?: return@mapNotNull null
                        val posterUrl = fixUrl(splideData.img_url ?: item.selectFirst("img.splide__img")?.attr("src"))

                        val episodeText = item.selectFirst("div.episodes")?.text()?.trim()
                        val year = splideData.year?.filter { it.isDigit() }?.toIntOrNull() // Lọc chỉ lấy số cho năm


                        val isTvSeries = splideData.type?.contains("Phim Bộ", ignoreCase = true) == true ||
                                         episodeText?.contains("Tập", ignoreCase = true) == true ||
                                         splideData.type?.contains("series", ignoreCase = true) == true

                        if (isTvSeries) {
                            TvSeriesSearchResponse(
                                name = title,
                                url = href,
                                apiName = this.name,
                                type = TvType.TvSeries,
                                posterUrl = posterUrl,
                                year = year,
                                quality = if (!episodeText.isNullOrBlank()) SearchQuality.Custom(episodeText) else null
                            )
                        } else {
                            MovieSearchResponse(
                                name = title,
                                url = href,
                                apiName = this.name,
                                type = TvType.Movie,
                                posterUrl = posterUrl,
                                year = year,
                                quality = if (!episodeText.isNullOrBlank()) SearchQuality.Custom(episodeText) else null
                            )
                        }
                    } catch (e: Exception) {
                        e.printStackTrace() // In lỗi ra logcat để debug
                        null
                    }
                }
                if (itemsInSection.isNotEmpty()) {
                    homePageLists.add(HomePageList(sectionTitle, itemsInSection))
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
        if (homePageLists.isEmpty()) return null
        return HomePageResponse(homePageLists)
    }

    // Hàm tìm kiếm phim
    override suspend fun search(query: String): List<SearchResponse>? {
        // Tham khảo: search.txt
        // URL tìm kiếm: $mainUrl/?search={query} (source 1356 từ search.txt)
        val searchUrl = "$mainUrl/?search=$query"
        val document = app.get(searchUrl).document

        // Kết quả nằm trong các thẻ div.item-wrap (source 1626, 1631, 1635 từ search.txt)
        return document.select("div.item-wrap").mapNotNull { element ->
            try {
                val linkElement = element.selectFirst("a.item-link")
                val href = fixUrl(linkElement?.attr("href")) ?: return@mapNotNull null
                var title = linkElement.selectFirst("div.item-title")?.text()?.trim()
                if (title.isNullOrBlank()) { // Fallback nếu không có div.item-title
                    title = element.selectFirst("img.desc-img")?.attr("alt")?.trim()
                }
                if (title.isNullOrBlank()) return@mapNotNull null


                val posterUrl = fixUrl(element.selectFirst("img.desc-img")?.attr("src"))
                val qualityEpisodeText = element.selectFirst("div.update-info-mask")?.text()?.trim()

                // Xác định TvType dựa vào qualityEpisodeText hoặc title
                val isTvSeries = qualityEpisodeText?.contains("Tập", ignoreCase = true) == true ||
                                 qualityEpisodeText?.matches(Regex("""Full \d+/\d+.*""", RegexOption.IGNORE_CASE)) == true ||
                                 title.contains(Regex("""(Phần|Season)\s*\d+""", RegexOption.IGNORE_CASE)) ||
                                 (qualityEpisodeText != null && Regex("""\d+\s*/\s*\d+\s*(Tập|VietSub|Thuyết Minh)""").containsMatchIn(qualityEpisodeText))


                if (isTvSeries) {
                    TvSeriesSearchResponse(
                        name = title,
                        url = href,
                        apiName = this.name,
                        type = TvType.TvSeries,
                        posterUrl = posterUrl,
                        quality = if (!qualityEpisodeText.isNullOrBlank()) SearchQuality.Custom(qualityEpisodeText) else null
                    )
                } else {
                    MovieSearchResponse(
                        name = title,
                        url = href,
                        apiName = this.name,
                        type = TvType.Movie,
                        posterUrl = posterUrl,
                        quality = if (!qualityEpisodeText.isNullOrBlank()) SearchQuality.Custom(qualityEpisodeText) else null
                    )
                }
            } catch (e: Exception) {
                e.printStackTrace()
                null
            }
        }
    }

    // Hàm tải thông tin chi tiết phim/series và danh sách tập
    override suspend fun load(url: String): LoadResponse? {
        val document = app.get(url).document
        // Tham khảo: info.txt

        // Tiêu đề phim (source 959)
        val title = document.selectFirst("div.banner-content__title h1")?.text()?.trim()
            ?: document.selectFirst("meta[property=\"og:title\"]")?.attr("content")?.substringBefore(" - ") // Lấy phần trước dấu " - "
            ?: return null

        // Poster (source 981-982 hoặc meta tag)
        val posterUrl = fixUrl(
            document.selectFirst("div.col__right img.banner-img")?.attr("src")
                ?: document.selectFirst("meta[property=\"og:image\"]")?.attr("content")
        )

        // Năm sản xuất (source 963)
        val year = document.selectFirst("div.banner-content__infor div.year")?.text()?.trim()?.toIntOrNull()

        // Mô tả (source 974-976)
        val plot = document.selectFirst("div.banner-content__desc")?.ownText()?.trim()
            ?: document.selectFirst("meta[name=description]")?.attr("content") // Fallback
            ?: document.selectFirst("meta[property=\"og:description\"]")?.attr("content") // Fallback

        // Thể loại (source 966-967)
        val tags = document.select("div.focus-info-tag.type a span.type-style")
            .mapNotNull { it.text()?.trim() }
            .filter { it.lowercase() != "vietsub" && it.lowercase() != "thuyết minh" } // Loại bỏ các tag không phải thể loại

        // Diễn viên (source 970-971)
        val actors = document.select("div.focus-info-tag:contains(Diễn viên) span a").mapNotNull {
            ActorData(Actor(it.text().trim()))
        }
        
        // Đạo diễn (source 967-969)
         val directorsElements = document.select("div.focus-info-tag:contains(Đạo diễn) span a")
         val directors = directorsElements.map { it.text().trim() }


        // Phim đề xuất (source 993-1076)
        val recommendations = document.select("div#pills-propose div.video-item").mapNotNull { recElement ->
            try {
                val recTitle = recElement.selectFirst("div.video-item-name")?.text()?.trim() ?: return@mapNotNull null
                val recHref = fixUrl(recElement.attr("href")) ?: return@mapNotNull null
                val recPoster = fixUrl(recElement.selectFirst("img.desc-img")?.attr("src"))
                // Cố gắng xác định TvType cho recommendations nếu có thể
                val recEpisodeText = recElement.selectFirst("div.update-info-mask")?.text()?.trim()
                val recIsTvSeries = recEpisodeText?.contains("Tập", ignoreCase = true) == true ||
                                    recEpisodeText?.matches(Regex("""Full \d+/\d+.*""", RegexOption.IGNORE_CASE)) == true ||
                                    recTitle.contains(Regex("""(Phần|Season)\s*\d+""", RegexOption.IGNORE_CASE))

                if (recIsTvSeries) {
                    TvSeriesSearchResponse(recTitle, recHref, this.name, TvType.TvSeries, recPoster)
                } else {
                    MovieSearchResponse(recTitle, recHref, this.name, TvType.Movie, recPoster)
                }
            } catch (e: Exception) {
                null
            }
        }

        // Xác định là phim bộ hay phim lẻ (source 965, 989-992, 365-369)
        val isTvSeries = document.selectFirst("div.episode_number:contains(Phim bộ)") != null ||
                         document.select("ul#pills-tab li.nav-item button[id^=pills-firm-tab]").isNotEmpty() ||
                         (document.selectFirst("ul.AZList")?.select("li a")?.size ?: 0) > 1 && !url.contains("/tap-") // Kiểm tra có nhiều hơn 1 tập và không phải đang ở trang xem tập


        if (isTvSeries) {
            val episodesList = mutableListOf<Episode>()
            // Lấy danh sách server từ các tab (source 987-992)
            val serverTabs = document.select("ul#pills-tab li.nav-item button[id^=pills-firm-tab]")

            if (serverTabs.isNotEmpty()) {
                serverTabs.forEachIndexed { serverIndex, serverButton ->
                    val serverName = serverButton.selectFirst("span")?.text()?.trim() ?: "Server ${serverIndex + 1}"
                    val tabPaneId = serverButton.attr("data-bs-target") // Ví dụ: "#pills-firm-1"
                    // Danh sách tập trong mỗi server (source 1077-1091)
                    val episodeElements = document.select("$tabPaneId div.video-list-wrapper a.video-item, $tabPaneId ul.AZList li a")


                    episodeElements.forEach { epElement ->
                        val epHref = fixUrl(epElement.attr("href")) ?: return@forEach
                        val epNameOriginal = epElement.text().trim().ifBlank {
                            epElement.selectFirst("div.video-item-name")?.text()?.trim() ?: "Tập ${episodesList.size + 1}"
                        }
                        // Cố gắng chuẩn hóa tên tập: "Phim - Tập X" -> "Tập X"
                        val epName = epNameOriginal.substringAfterLast("-").trim().ifBlank { epNameOriginal }


                        episodesList.add(
                            Episode(
                                data = epHref, // URL này sẽ được truyền cho loadLinks
                                name = epName,
                                posterUrl = fixUrl(epElement.selectFirst("img.desc-img")?.attr("src")) ?: posterUrl, // Ảnh của tập hoặc poster phim
                                // season = -1, // Không có thông tin season rõ ràng
                                // episode = // Có thể thử parse từ epName
                            )
                        )
                    }
                }
            } else {
                // Trường hợp chỉ có 1 server, danh sách tập nằm trực tiếp trong ul.AZList
                // (source 365-369 từ watch.txt, nhưng logic tương tự cho info.txt nếu chỉ có 1 server)
                 document.select("ul.AZList#episodes li a, div#pills-firm-1 ul.AZList li a").forEach { epElement ->
                     val epHref = fixUrl(epElement.attr("href")) ?: return@forEach
                     val epName = epElement.text().trim().ifBlank { "Tập ${episodesList.size + 1}" }
                     val episodePoster = fixUrl(epElement.selectFirst("img")?.attr("src")) ?: posterUrl
                     episodesList.add(
                         Episode(
                             data = epHref,
                             name = epName,
                             posterUrl = episodePoster
                         )
                     )
                 }
            }


            return TvSeriesLoadResponse(
                name = title,
                url = url,
                apiName = this.name,
                type = TvType.TvSeries,
                episodes = episodesList.distinctBy { it.data }, // Loại bỏ các tập trùng link
                posterUrl = posterUrl,
                year = year,
                plot = plot,
                tags = tags,
                actors = actors,
                recommendations = recommendations,
                // rating = document.selectFirst("div.rate")?.text()?.trim()?.removePrefix("★")?.trim()?.toRatingInt(),
                // duration = document.select("div.focus-info-tag:contains(Thời lượng:) span")?.lastOrNull()?.text()?.trim(),
                // showStatus = // Có thể lấy từ "Hoàn thành", "Đang chiếu" (source 960)
            )
        } else {
            // Đây là phim lẻ
            return MovieLoadResponse(
                name = title,
                url = url, // URL của trang thông tin phim
                apiName = this.name,
                type = TvType.Movie,
                dataUrl = url, // URL này sẽ được truyền cho loadLinks để lấy server phát
                posterUrl = posterUrl,
                year = year,
                plot = plot,
                tags = tags,
                actors = actors,
                recommendations = recommendations,
                // rating = document.selectFirst("div.rate")?.text()?.trim()?.removePrefix("★")?.trim()?.toRatingInt(),
                // duration = document.select("div.focus-info-tag:contains(Thời lượng:) span")?.lastOrNull()?.text()?.trim()
            )
        }
    }

    // Hàm tải link video trực tiếp (sẽ được làm sau)
    override suspend fun loadLinks(
        data: String, // Đây là URL của trang xem phim (epHref từ hàm load) hoặc URL trang info (cho phim lẻ)
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        // TODO: Implement link extraction logic here based on watch.txt
        // 1. Tải HTML của trang `data` (trang xem phim).
        // 2. Tìm các nút server (a.streaming-server).
        // 3. Với mỗi server:
        //    - Lấy `data-type` và `data-link`.
        //    - Nếu `data-type` là `m3u8` hoặc `mp4`, gọi `callback` với `ExtractorLink`.
        //    - Nếu `data-type` là `embed`, dùng `app.get(data-link).document` để tải trang embed,
        //      rồi tìm link video bên trong trang đó (thường là trong thẻ iframe hoặc script).
        //      Hoặc sử dụng các extractor có sẵn của CloudStream3 nếu tên miền được hỗ trợ.
        //      Ví dụ: apis.forEach { api -> api.getSafeUrl(link, referer)?.forEach(callback) }
        //      Hoặc: invokeExtractor(link, data, subtitleCallback, callback)

        println("loadLinks called with data: $data") // Log để debug
        return false // Placeholder, trả về true nếu tìm được link
    }
}
