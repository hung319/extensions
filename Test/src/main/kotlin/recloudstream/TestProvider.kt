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
                    ActorData(Actor(name, image))
                } else {
                    null
                }
            }

        val directors = document.select("div.firm-desc:contains(Đạo diễn) span.desc-content span.tt-at a, div.focus-info-tag:contains(Đạo diễn) span a")
            .mapNotNull { element ->
                val name = element.text().trim()
                if (name.isNotBlank()) {
                    ActorData(Actor(name))
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
        // Mục tiêu: Lấy tất cả các thẻ <li> bên trong <ul class="AZList" id="episodes">
        // và xác định tên nhóm (server/loại) cho từng cụm tập.

        val episodeContainer = document.selectFirst("ul.AZList#episodes")
        if (episodeContainer != null) {
            var currentGroupName = "Mặc định" // Tên nhóm mặc định
            var currentSeasonNumber = 1     // Số mùa/nhóm

            // Lặp qua tất cả các con trực tiếp của episodeContainer
            episodeContainer.children().forEach { child ->
                if (child.tagName() == "div" && child.hasClass("w-full")) {
                    // Nếu là div chứa tên nhóm (ví dụ: <h3>Vietsub #1</h3>)
                    currentGroupName = child.selectFirst("h3")?.text()?.trim() ?: "Nhóm $currentSeasonNumber"
                    // Nếu có thẻ h3 mới, có thể coi đây là một "season" mới (hoặc server mới)
                    // Nếu logic season của bạn phức tạp hơn, bạn cần điều chỉnh ở đây
                    // Ví dụ: nếu tên nhóm thay đổi, tăng currentSeasonNumber
                    // Tuy nhiên, để đơn giản, chúng ta có thể dùng một seasonNumber cố định cho tất cả các tập
                    // hoặc tăng seasonNumber mỗi khi gặp một div.w-full mới.
                    // Hiện tại, tôi sẽ thử logic tăng seasonNumber cho mỗi div.w-full mới.
                    // Nếu đây là lần đầu gặp h3 hoặc h3 khác với h3 trước đó, thì tăng season.
                    // Điều này cần một biến để lưu tên nhóm trước đó.
                    // Để đơn giản hơn, chúng ta sẽ gán season dựa trên một biến đếm `seasonCountForEpisodes`
                    // và sẽ tăng biến này mỗi khi gặp `div.w-full`.
                } else if (child.tagName() == "li") {
                    // Nếu là thẻ <li> chứa tập phim
                    val epElement = child.selectFirst("a")
                    if (epElement != null) {
                        val originalEpName = epElement.attr("title")?.ifBlank { null } ?: epElement.text().trim()
                        var epUrl = epElement.attr("href")
                        if (epUrl.isNotBlank() && !epUrl.startsWith("http")) {
                            epUrl = "$mainUrl$epUrl"
                        }
                        val episodeNumber = epElement.text().toIntOrNull()

                        // Kết hợp tên nhóm vào tên tập để dễ phân biệt
                        val finalEpName = if (currentGroupName != "Mặc định" && !originalEpName.contains(currentGroupName, ignoreCase = true)) {
                            "$currentGroupName: $originalEpName"
                        } else {
                            originalEpName
                        }

                        episodes.add(
                            Episode(
                                data = epUrl,
                                name = finalEpName,
                                episode = episodeNumber,
                                season = currentSeasonNumber, // Sử dụng số season hiện tại cho nhóm này
                                posterUrl = posterUrl,
                                rating = rating
                            )
                        )
                    }
                }
                // Nếu gặp div.w-full, nghĩa là bắt đầu một nhóm mới, tăng season number cho nhóm tiếp theo
                if (child.tagName() == "div" && child.hasClass("w-full")) {
                    currentSeasonNumber++
                }
            }
        }


        val isTvSeries = episodes.isNotEmpty()
        val tvType = if (isTvSeries) TvType.TvSeries else TvType.Movie

        return if (tvType == TvType.TvSeries) {
            newTvSeriesLoadResponse(title, url, tvType, episodes.distinctBy { it.data }) { // Đảm bảo episode data là unique
                this.posterUrl = posterUrl
                this.year = year
                this.plot = plot
                this.tags = tags
                this.rating = rating
                this.actors = actors
                this.recommendations = recommendations
            }
        } else {
            val movieEpisode = Episode(
                data = url,
                name = title,
                posterUrl = posterUrl
            )
            newMovieLoadResponse(title, url, tvType, movieEpisode) {
                this.posterUrl = posterUrl
                this.year = year
                this.plot = plot
                this.tags = tags
                this.rating = rating
                this.actors = actors
                this.recommendations = recommendations
            }
        }
    }
}
