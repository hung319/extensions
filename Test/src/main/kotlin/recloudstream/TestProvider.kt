package com.lagradost.cloudstream3.vi.providers // Hoặc package của bạn

import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Element
import com.lagradost.cloudstream3.network.CloudflareKiller

class VietSubTvProvider : MainAPI() {
    override var mainUrl = "https://vietsubtv.us" // Thay đổi nếu URL chính của bạn khác
    override var name = "VietSubTV"
    override val hasMainPage = true
    override var lang = "vi"
    override val hasDownloadSupport = true // Giả sử có hỗ trợ tải
    override val supportedTypes = setOf(
        TvType.Movie,
        TvType.TvSeries
    )

    override val mainPage = mainPageOf(
        "$mainUrl/danh-sach/phim-moi.html" to "Phim Mới Cập Nhật",
        "$mainUrl/quoc-gia/han-quoc.html" to "Phim Hàn Quốc",
        "$mainUrl/quoc-gia/trung-quoc.html" to "Phim Trung Quốc",
        "$mainUrl/the-loai/hoat-hinh.html" to "Phim Hoạt Hình",
        "$mainUrl/danh-sach/phim-le.html" to "Phim Lẻ Mới",
        "$mainUrl/danh-sach/phim-bo.html" to "Phim Bộ Mới"
    )

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {
        // Nếu request.data là một URL đầy đủ (ví dụ từ redirect), không cần thêm mainUrl
        val url = if (request.data.startsWith("http")) request.data else "$mainUrl${request.data}"
        val document = app.get(url, interceptor = CloudflareKiller()).document
        val homePageList = mutableListOf<HomePageList>()
        val items = mutableListOf<SearchResponse>()

        document.select("div.slider__column li.splide__slide, ul.video-listing li.video-item, div.item-wrap")
            .forEach { element ->
                val titleElement = element.selectFirst("div.splide__item-title, div.video-item-name, div.item-title")
                val title = titleElement?.text()?.trim() ?: ""

                val linkElement = element.selectFirst("a")
                var link = linkElement?.attr("href") ?: ""
                if (link.isNotBlank() && !link.startsWith("http")) {
                    link = "$mainUrl$link"
                }

                var posterUrl = element.selectFirst("div.splide__img-wrap img, div.video-item-img img, div.item-img img")?.let { img ->
                    img.attr("data-src").ifBlank { img.attr("src") }
                }
                if (posterUrl?.startsWith("/") == true) {
                    posterUrl = "$mainUrl$posterUrl"
                }

                val quality = element.selectFirst("div.episodes, div.update-info-mask")?.text()?.trim()

                if (title.isNotBlank() && link.isNotBlank()) {
                    items.add(
                        newMovieSearchResponse(title, link) {
                            this.posterUrl = posterUrl
                            if (quality != null) {
                               this.quality = getQualityFromString(quality)
                            }
                        }
                    )
                }
            }
        if (items.isNotEmpty()) {
            homePageList.add(HomePageList(request.name, items))
        }

        if (homePageList.isEmpty() && page == 1 && mainPage.any { it.data == request.data }) { // Chỉ throw lỗi nếu là trang đầu tiên của 1 mục trong mainPage định nghĩa sẵn
             // Allow empty for subsequent pages or dynamic pages not in mainPage
        } else if (homePageList.isEmpty() && page == 1) {
            // It's okay for some dynamic pages (like genre pages not explicitly in mainPageOf) to be empty on page 1 if there are no items
            // Or if it's a subsequent page (page > 1) which is empty.
        }


        return HomePageResponse(homePageList)
    }


    override suspend fun search(query: String): List<SearchResponse> {
        val searchUrl = "$mainUrl/?search=$query"
        val document = app.get(searchUrl, interceptor = CloudflareKiller()).document
        val searchList = mutableListOf<SearchResponse>()

        document.select("div.item-wrap").forEach { element ->
            val title = element.selectFirst("div.item-title")?.text()?.trim() ?: ""
            var link = element.selectFirst("a.item-link")?.attr("href") ?: ""
            if (link.isNotBlank() && !link.startsWith("http")) {
                link = "$mainUrl$link"
            }
            var posterUrl = element.selectFirst("div.item-img img")?.attr("src")
            if (posterUrl?.startsWith("/") == true) {
                posterUrl = "$mainUrl$posterUrl"
            }
            val quality = element.selectFirst("div.update-info-mask")?.text()?.trim()

            if (title.isNotBlank() && link.isNotBlank()) {
                searchList.add(
                    newMovieSearchResponse(title, link) {
                        this.posterUrl = posterUrl
                        if (quality != null) {
                            this.quality = getQualityFromString(quality)
                        }
                    }
                )
            }
        }
        return searchList
    }

    override suspend fun load(url: String): LoadResponse? {
        val document = app.get(url, interceptor = CloudflareKiller()).document

        val title = document.selectFirst("div.info-detail h3.title span.intl-album-title-word-wrap")?.text()?.trim()
            ?: document.selectFirst("div.banner-content__title h1")?.text()?.trim()
            ?: document.selectFirst("meta[property=\"og:title\"]")?.attr("content")
                ?.replace("Xem phim ", "")?.replace(" VietSub Full HD", "")
                ?.replace(" Tập Full", "")?.substringBefore(" - ")
            ?: return null


        var posterUrl = document.selectFirst("div.col__right div.wrap-banner-img img")?.attr("src")
            ?: document.selectFirst("meta[property=\"og:image\"]")?.attr("content")

        if (posterUrl?.startsWith("/") == true) {
            posterUrl = "$mainUrl$posterUrl"
        }

        val plot = document.selectFirst("div.banner-content__desc")?.textNodes()?.joinToString("") { it.text().trim() }?.replace("Miêu tả:", "")?.trim()
            ?: document.selectFirst("meta[name=\"description\"]")?.attr("content")?.trim()

        val yearText = document.selectFirst("div.banner-content__infor div.year")?.text()?.trim()
            ?: document.select("div.intl-play-time span").find { it.text().matches(Regex("\\d{4}")) }?.text()
        val year = yearText?.toIntOrNull()

        val ratingText = document.selectFirst("div.banner-content__infor div.rate")?.text()?.trim()
        val rating = ratingText?.filter { it.isDigit() || it == '.' }?.toFloatOrNull()?.times(10)?.toInt()

        val tags = (document.select("div.intl-play-item-tags a span") + document.select("div.focus-info-tag.type a span.type-style"))
            .mapNotNull { it.text()?.trim() }.distinct().filterNot { it.equals("Vietsub", true) || it.equals("Thuyết Minh", true) || it.contains("Năm") }

        val actors = document.select("div.firm-desc:contains(Diễn viên) span.desc-content span.tt-at a, div.focus-info-tag:contains(Diễn viên) span a")
            .mapNotNull { element ->
                val name = element.text().trim()
                val image = element.selectFirst("img")?.attr("src")?.let { if (it.startsWith("/")) mainUrl + it else it }
                if (name.isNotBlank()) {
                     // Sửa ở đây: Tạo Actor rồi truyền vào ActorData
                    ActorData(actor = Actor(name, image))
                } else {
                    null
                }
            }

        val directors = document.select("div.firm-desc:contains(Đạo diễn) span.desc-content span.tt-at a, div.focus-info-tag:contains(Đạo diễn) span a")
            .mapNotNull { element ->
                val name = element.text().trim()
                if (name.isNotBlank()) {
                    ActorData(Actor(name)) // Sửa ở đây
                } else {
                    null
                }
            }


        val recommendations = mutableListOf<SearchResponse>()
        document.select("div.firm-propose li.splide__slide").forEach { recElement ->
            val recTitle = recElement.selectFirst("div.splide__item-title")?.text()?.trim()
            var recLink = recElement.selectFirst("a")?.attr("href")
            var recPoster = recElement.selectFirst("div.splide__img-wrap img")?.attr("src")

            if (recLink?.startsWith("/") == true) recLink = "$mainUrl$recLink"
            if (recPoster?.startsWith("/") == true) recPoster = "$mainUrl$recPoster"

            if (recTitle != null && recLink != null) {
                recommendations.add(newMovieSearchResponse(recTitle, recLink) {
                    this.posterUrl = recPoster
                })
            }
        }

        val episodes = mutableListOf<Episode>()
        val episodeSection = document.selectFirst("ul.AZList#episodes") // Chỉ lấy section chính chứa tập

        var currentSeasonInternal = 1 // Đổi tên để tránh trùng với biến bên ngoài nếu có

        // Lấy tên season từ thẻ h3 ngay trước ul#episodes nếu có
        val seasonNameFromH3 = episodeSection?.parent()?.select("div.w-full h3")
            ?.joinToString(" - ") { it.text().trim() } ?: "Mặc định"


        episodeSection?.select("li a")?.forEach { epElement ->
            val originalEpName = epElement.attr("title")?.ifBlank { null } ?: epElement.text().trim()
            var epUrl = epElement.attr("href")
            if (epUrl.isNotBlank() && !epUrl.startsWith("http")) {
                epUrl = "$mainUrl$epUrl"
            }
            val episodeNumber = epElement.text().toIntOrNull()

            // Sử dụng seasonNameFromH3 cho tất cả các tập trong #episodes này
            // Hoặc bạn có thể đặt tên season cụ thể hơn nếu cấu trúc HTML cho phép
            val currentEpisodeSeasonName = seasonNameFromH3

            episodes.add(
                Episode(
                    data = epUrl,
                    name = originalEpName, // Giữ tên tập gốc
                    episode = episodeNumber,
                    season = currentSeasonInternal, // Sử dụng season đã xác định
                    seasonName = currentEpisodeSeasonName, // Thêm tên season
                    posterUrl = posterUrl,
                    rating = rating
                )
            )
        }
        // Nếu không có thẻ h3 nào cho group, có thể không cần tăng currentSeasonInternal dựa trên group nữa,
        // mà dựa trên logic khác nếu phim có nhiều mùa thực sự (hiện tại đang coi mỗi ul là 1 season)


        val isTvSeries = episodes.isNotEmpty() || document.select("ul.AZList li a[title*='Tập']").isNotEmpty()
        val tvType = if (isTvSeries) TvType.TvSeries else TvType.Movie

        return if (tvType == TvType.TvSeries) {
            newTvSeriesLoadResponse(title, url, tvType, episodes.distinctBy { it.data }) {
                this.posterUrl = posterUrl
                this.year = year
                this.plot = plot
                this.tags = tags
                this.rating = rating
                this.actors = actors // Gán danh sách diễn viên đã parse
                // Gán đạo diễn nếu LoadResponse hỗ trợ (một số phiên bản có thể không có trường này trực tiếp)
                // this.recommendations = recommendations
            }
        } else {
            val movieEpisode = Episode(
                data = url,
                name = title, // Sử dụng title của phim cho phim lẻ
                posterUrl = posterUrl
                // Không cần year, plot, rating ở đây cho Episode của phim lẻ
            )
            newMovieLoadResponse(title, url, tvType, movieEpisode) {
                this.posterUrl = posterUrl
                this.year = year // Gán year ở đây
                this.plot = plot // Gán plot ở đây
                this.tags = tags
                this.rating = rating
                this.actors = actors // Gán danh sách diễn viên đã parse
                // this.recommendations = recommendations
            }
        }
    }
}
