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
        // Bước 1: Truy cập trang thông tin phim ban đầu (url)
        val initialDocument = app.get(url, interceptor = CloudflareKiller()).document

        // Bước 2: Tìm nút "Xem Phim" và lấy href của nó
        val watchPageLink = initialDocument.selectFirst("a.btn-item.btn-s[href*=tap-]")?.attr("href")?.let {
            if (it.startsWith("/")) mainUrl + it else it
        } ?: url // Nếu không tìm thấy nút "Xem Phim", vẫn dùng URL gốc làm fallback (cho phim lẻ hoặc cấu trúc khác)

        // Bước 3: Truy cập URL trang xem phim (watchPageLink)
        // Thông tin phim chính vẫn có thể lấy từ initialDocument hoặc watchPageDocument tùy thuộc vào độ tin cậy
        val watchPageDocument = app.get(watchPageLink, interceptor = CloudflareKiller()).document

        // Ưu tiên lấy thông tin từ trang xem phim nếu nó đầy đủ hơn, hoặc từ trang ban đầu
        val title = watchPageDocument.selectFirst("div.info-detail h3.title span.intl-album-title-word-wrap")?.text()?.trim()
            ?: initialDocument.selectFirst("div.banner-content__title h1")?.text()?.trim()
            ?: watchPageDocument.selectFirst("meta[property=\"og:title\"]")?.attr("content")
                ?.replace("Xem phim ", "")?.replace(" VietSub Full HD", "")
                ?.replace(" Tập Full", "")?.substringBefore(" - ")
            ?: initialDocument.selectFirst("meta[property=\"og:title\"]")?.attr("content")
                ?.replace("Xem phim ", "")?.replace(" VietSub Full HD", "")
                ?.replace(" Tập Full", "")?.substringBefore(" - ")
            ?: return null

        var posterUrl = watchPageDocument.selectFirst("meta[property=\"og:image\"]")?.attr("content") // Ưu tiên OG image từ trang xem
            ?: initialDocument.selectFirst("div.col__right div.wrap-banner-img img")?.attr("src")
            ?: initialDocument.selectFirst("meta[property=\"og:image\"]")?.attr("content")

        if (posterUrl?.startsWith("/") == true) {
            posterUrl = "$mainUrl$posterUrl"
        }
        // Gộp plot từ cả 2 trang nếu cần, hoặc ưu tiên 1 trang
        val plot = watchPageDocument.selectFirst("div.banner-content__desc")?.textNodes()?.joinToString("") { it.text().trim() }?.replace("Miêu tả:", "")?.trim()
            ?: initialDocument.selectFirst("div.banner-content__desc")?.textNodes()?.joinToString("") { it.text().trim() }?.replace("Miêu tả:", "")?.trim()
            ?: watchPageDocument.selectFirst("meta[name=\"description\"]")?.attr("content")?.trim()
            ?: initialDocument.selectFirst("meta[name=\"description\"]")?.attr("content")?.trim()

        val yearText = watchPageDocument.select("div.intl-play-time span").find { it.text().matches(Regex("\\d{4}")) }?.text()
            ?: initialDocument.selectFirst("div.banner-content__infor div.year")?.text()?.trim()
        val year = yearText?.toIntOrNull()

        val ratingText = watchPageDocument.selectFirst("div.intl-play-time span.focus-item-label-original:contains(Điểm)")?.nextElementSibling()?.text()?.trim() // Ví dụ nếu rating ở trang xem
            ?: initialDocument.selectFirst("div.banner-content__infor div.rate")?.text()?.trim()
        val rating = ratingText?.filter { it.isDigit() || it == '.' }?.toFloatOrNull()?.times(10)?.toInt()

        val tags = (watchPageDocument.select("div.intl-play-item-tags a span") + initialDocument.select("div.focus-info-tag.type a span.type-style"))
            .mapNotNull { it.text()?.trim() }.distinct().filterNot { it.equals("Vietsub", true) || it.equals("Thuyết Minh", true) || it.contains("Năm") }

        // Lấy actors và directors từ trang nào có vẻ đầy đủ hơn (ví dụ initialDocument từ info.txt)
        val actors = initialDocument.select("div.focus-info-tag:contains(Diễn viên) span a, div.firm-desc:contains(Diễn viên) span.desc-content span.tt-at a")
            .mapNotNull { element ->
                val name = element.text().trim()
                val image = element.selectFirst("img")?.attr("src")?.let { if (it.startsWith("/")) mainUrl + it else it }
                if (name.isNotBlank()) {
                    ActorData(Actor(name, image))
                } else {
                    null
                }
            }

        val directors = initialDocument.select("div.focus-info-tag:contains(Đạo diễn) span a, div.firm-desc:contains(Đạo diễn) span.desc-content span.tt-at a")
            .mapNotNull { element ->
                val name = element.text().trim()
                if (name.isNotBlank()) {
                    ActorData(Actor(name))
                } else {
                    null
                }
            }

        val recommendations = mutableListOf<SearchResponse>()
        // Ưu tiên lấy recommendations từ watchPageDocument nếu có, nếu không thì từ initialDocument
        (watchPageDocument.select("div.firm-propose li.splide__slide").ifEmpty { initialDocument.select("div.firm-propose li.splide__slide") })
            .forEach { recElement ->
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

        // Bước 4: Lấy danh sách tập từ watchPageDocument
        val episodes = mutableListOf<Episode>()
        val episodeContainer = watchPageDocument.selectFirst("div.intl-play-right div.MainContainer section.SeasonBx ul.AZList#episodes")
        // Log.d("VietSubTvProvider", "Watch Page URL for episodes: $watchPageLink")
        // Log.d("VietSubTvProvider", "Episode Container HTML: ${episodeContainer?.outerHtml()}")

        if (episodeContainer != null) {
            var currentSeasonNumberForEpisodes = 1 // Biến đếm season/group riêng cho việc parse tập
            var lastGroupName = ""

            episodeContainer.children().forEach { child ->
                if (child.tagName() == "div" && child.hasClass("w-full")) {
                    val groupName = child.selectFirst("h3")?.text()?.trim() ?: "Nhóm $currentSeasonNumberForEpisodes"
                    // Chỉ tăng season nếu tên nhóm thay đổi thực sự, hoặc nếu đây là h3 đầu tiên
                    if (groupName != lastGroupName || episodes.isEmpty()) { // Điều kiện này có thể cần tinh chỉnh
                        if (episodes.isNotEmpty() && groupName != lastGroupName) currentSeasonNumberForEpisodes++ // Tăng season cho nhóm mới thực sự
                    }
                    lastGroupName = groupName
                } else if (child.tagName() == "li") {
                    val epElement = child.selectFirst("a")
                    if (epElement != null) {
                        val originalEpName = epElement.attr("title")?.ifBlank { null } ?: epElement.text().trim()
                        var epUrl = epElement.attr("href")
                        if (epUrl.isNotBlank() && !epUrl.startsWith("http")) {
                            epUrl = "$mainUrl$epUrl"
                        }
                        val episodeNumber = epElement.text().toIntOrNull()

                        // Dùng lastGroupName (tên nhóm/server đã được cập nhật ở trên)
                        val finalEpName = if (lastGroupName.isNotBlank() && lastGroupName != "Mặc định" && !originalEpName.contains(lastGroupName, ignoreCase = true)) {
                            "$lastGroupName: $originalEpName"
                        } else {
                            originalEpName
                        }

                        episodes.add(
                            Episode(
                                data = epUrl,
                                name = finalEpName,
                                episode = episodeNumber,
                                season = currentSeasonNumberForEpisodes,
                                posterUrl = posterUrl,
                                rating = rating
                            )
                        )
                    }
                }
            }
        } else {
            Log.d("VietSubTvProvider", "Không tìm thấy container chứa tập phim trên trang: $watchPageLink")
        }


        val isTvSeries = episodes.isNotEmpty()
        val tvType = if (isTvSeries) TvType.TvSeries else TvType.Movie

        return if (tvType == TvType.TvSeries) {
            newTvSeriesLoadResponse(title, url, tvType, episodes.distinctBy { it.data }) {
                this.posterUrl = posterUrl
                this.year = year
                this.plot = plot
                this.tags = tags
                this.rating = rating
                this.actors = actors
                this.recommendations = recommendations
            }
        } else {
            // Phim lẻ: data là link xem phim luôn (watchPageLink)
            val movieEpisode = Episode(
                data = watchPageLink, // Sử dụng watchPageLink vì đây là link thực tế để loadLinks
                name = title,
                posterUrl = posterUrl
            )
            newMovieLoadResponse(title, url, tvType, movieEpisode) { // url vẫn là url gốc của trang info
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
