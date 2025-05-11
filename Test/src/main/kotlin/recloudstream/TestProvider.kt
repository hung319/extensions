package com.example.vietsubtv // Hoặc package của bạn

import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.* // Quan trọng cho AppUtils và các hàm helper khác
import org.jsoup.nodes.Element // Quan trọng cho Jsoup

class VietSubTVProvider : MainAPI() {
    override var mainUrl = "https://vietsubtv.us" // Đã CẬP NHẬT URL
    override var name = "VietSubTV"
    override val hasMainPage = true
    override var lang = "vi"
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(
        TvType.Movie,
        TvType.TvSeries
    )

    data class DataWrap(
        @JsonProperty("title") val title: String?,
        @JsonProperty("linkF") val linkF: String?,
        @JsonProperty("img_url") val imgUrl: String?,
        @JsonProperty("type") val type: String?,
        @JsonProperty("year") val year: String?,
    )

    private fun Element.toSearchResponseFromSplideItem(): SearchResponse? {
        val json = this.attr("data-wrap_data")
        if (json.isNotBlank()) {
            try {
                val data = AppUtils.parseJson<DataWrap>(json)
                val title = data.title?.trim() ?: return null
                val href = data.linkF ?: return null
                val poster = data.imgUrl?.trim()?.ifEmpty { null } ?: this.selectFirst("div.splide__img-wrap > img")?.attr("src")
                val typeString = data.type?.trim()?.lowercase()
                val tvType = if (typeString?.contains("bộ") == true) TvType.TvSeries else TvType.Movie

                return newAnimeSearchResponse(title, href, tvType) {
                    this.posterUrl = fixUrlNull(poster)
                    val qualityText = this@toSearchResponseFromSplideItem.selectFirst("div.episodes")?.text()?.trim()
                    if (!qualityText.isNullOrEmpty()) {
                        addQuality(qualityText)
                    }
                }
            } catch (e: Exception) {
                System.err.println("Failed to parse JSON for item: $json. Error: ${e.message}")
            }
        }
        val title = this.selectFirst("div.splide__item-title")?.text()?.trim() ?: return null
        val href = this.parent()?.attr("href") ?: return null
        val poster = this.selectFirst("div.splide__img-wrap > img")?.attr("src")
        val qualityText = this.selectFirst("div.episodes")?.text()?.trim()
        return newMovieSearchResponse(title, href, TvType.Movie) {
            this.posterUrl = fixUrlNull(poster)
            if (!qualityText.isNullOrEmpty()) {
                addQuality(qualityText)
            }
        }
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val document = app.get(mainUrl).document
        val homePageList = ArrayList<HomePageList>()

        try {
            val mainSliderItems = document.select("div#splide01 ul.splide__list > li.splide__slide").mapNotNull { el ->
                val titleElement = el.selectFirst("div.crs-content__title h2")
                val title = titleElement?.text()?.trim() ?: return@mapNotNull null
                val href = el.selectFirst("div.crs-content > a")?.attr("href") ?: el.selectFirst("a")?.attr("href") ?: return@mapNotNull null
                val posterUrl = el.selectFirst("a > img")?.attr("src")
                val isSeries = el.selectFirst("span.update-set")?.text()?.contains("Tập", ignoreCase = true) == true ||
                               el.selectFirst("div.episode_number")?.text()?.contains("Phim bộ", ignoreCase = true) == true
                val tvType = if(isSeries) TvType.TvSeries else TvType.Movie

                newAnimeSearchResponse(title, href, tvType) {
                    this.posterUrl = fixUrlNull(posterUrl)
                    val quality = el.selectFirst("span.update-set")?.text()?.trim()
                    if(!quality.isNullOrEmpty()) addQuality(quality)
                }
            }
            if (mainSliderItems.isNotEmpty()) {
                // Sửa lỗi: Bỏ HorizontalSlider() nếu không được import hoặc không cần thiết
                homePageList.add(HomePageList("Phim Nổi Bật", mainSliderItems))
            }
        } catch (e: Exception) {
            System.err.println("Error parsing main slider: ${e.message}")
        }

        val sections = document.select("section.firm-by-category")
        for (section in sections) {
            try {
                val sectionTitle = section.selectFirst("h2.title-category")?.text()?.trim() ?: "Phim Mới"
                val items = section.select("ul.splide__list > li.splide__slide").mapNotNull { slideEl ->
                    slideEl.selectFirst("div.splide__item")?.toSearchResponseFromSplideItem()
                }
                if (items.isNotEmpty()) {
                     // Sửa lỗi: Bỏ HorizontalSlider()
                    homePageList.add(HomePageList(sectionTitle, items))
                }
            } catch (e: Exception) {
                 System.err.println("Error parsing section: ${section.selectFirst("h2.title-category")?.text()}. Error: ${e.message}")
            }
        }
        return HomePageResponse(homePageList)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val searchUrl = "$mainUrl/?search=$query"
        val document = app.get(searchUrl).document

        return document.select("section.list-item div.item-wrap").mapNotNull { el ->
            val title = el.selectFirst("div.item-title")?.text()?.trim() ?: return@mapNotNull null
            val href = el.selectFirst("a.item-link")?.attr("href") ?: return@mapNotNull null
            val posterUrl = el.selectFirst("div.item-img img")?.attr("src")
            val qualityText = el.selectFirst("div.update-info-mask")?.text()?.trim()
            val tvType = if (qualityText?.contains("Tập", ignoreCase = true) == true || qualityText?.contains("/") == true) {
                TvType.TvSeries
            } else {
                TvType.Movie
            }

            newAnimeSearchResponse(title, href, tvType) {
                this.posterUrl = fixUrlNull(posterUrl)
                if (!qualityText.isNullOrEmpty()) {
                    addQuality(qualityText)
                }
            }
        }
    }

    override suspend fun load(url: String): LoadResponse? {
        val document = app.get(url).document
        val title = document.selectFirst("div.banner-content__title h1")?.text()?.trim() ?: return null
        val poster = document.selectFirst("div.wrap-banner-img img.banner-img")?.attr("src")
        val yearText = document.selectFirst("div.banner-content__infor div.year")?.text()?.trim()
        val year = yearText?.toIntOrNull()
        val plot = document.selectFirst("div.banner-content__desc")?.ownText()?.trim()

        val genres = document.select("div.focus-info-tag.type a[href^='$mainUrl/the-loai/']")
            .mapNotNull { it.text().trim() }
        val actorsElements = document.select("div.focus-info-tag:has(span.key:containsOwn(Diễn viên:)) span a[href^='$mainUrl/dien-vien/']")
        val actors = actorsElements.map { ActorData(Actor(it.text().trim(), fixUrlNull(it.attr("href")))) }

        val recommendations = document.select("div.firm-propose div#splide01 ul.splide__list li.splide__slide").mapNotNull { el ->
            val recTitle = el.selectFirst("div.splide__item-title")?.text()?.trim() ?: return@mapNotNull null
            val recHref = el.selectFirst("a")?.attr("href") ?: return@mapNotNull null
            val recPoster = el.selectFirst("div.splide__img-wrap img")?.attr("src")
            newMovieSearchResponse(recTitle, recHref, TvType.Movie) {
                this.posterUrl = fixUrlNull(recPoster)
            }
        }

        val isTvSeriesBasedOnInfo = document.selectFirst("div.banner-content__infor div.episode_number")?.text()?.contains("Phim bộ", ignoreCase = true) == true
        // Kiểm tra cấu trúc danh sách tập trên trang chi tiết phim (không phải trang xem phim)
        // HTML của trang chi tiết phim (ví dụ: `https://vietsubtv.us/phim/hoai-thuy-truc-dinh_23245.html`)
        // sẽ quyết định cách lấy danh sách tập ở đây.
        // Dựa trên HTML của trang XEM PHIM (`hoai-thuy-truc-dinh/tap-1_519458.html`), danh sách tập nằm ở cột phải.
        // Còn trên trang CHI TIẾT PHIM (info.txt), nó có các tab server.

        // Logic cho trang chi tiết phim (`info.txt`)
        val serverTabButtons = document.select("ul#pills-tab li.nav-item button.nav-link[id^='pills-firm-tab-']")
        val episodeList = ArrayList<Episode>()

        var isDefinitelyTvSeries = isTvSeriesBasedOnInfo

        if (serverTabButtons.isNotEmpty()) {
            serverTabButtons.forEachIndexed { index, serverButton ->
                val serverName = serverButton.selectFirst("span")?.text()?.trim() ?: "Server ${index + 1}"
                val targetPaneId = serverButton.attr("data-bs-target").removePrefix("#")
                // Trong info.txt (Ván Cờ Vây - phim lẻ), mỗi server là một video-item
                val episodeElementsInPane = document.select("div#$targetPaneId div.video-list-wrapper a.video-item")

                if (episodeElementsInPane.isNotEmpty()) {
                    if(episodeElementsInPane.size > 1) isDefinitelyTvSeries = true
                    episodeElementsInPane.forEach { el ->
                        val epHref = fixUrl(el.attr("href")) // Đây là link đến trang XEM TẬP PHIM
                        val epName = el.selectFirst("div.video-item-name")?.text()?.trim() ?: "Tập ${episodeList.size + 1}"
                        val epNumRegex = Regex("""Tập\s*(\d+)""")
                        val epNum = epNumRegex.find(epName)?.groupValues?.get(1)?.toIntOrNull()

                        episodeList.add(Episode(
                            data = epHref, // URL này sẽ được truyền cho loadLinks
                            name = if (isTvSeriesBasedOnInfo || episodeElementsInPane.size > 1) "$epName - $serverName" else serverName,
                            episode = epNum
                        ))
                    }
                }
            }
        } else {
            // Fallback: nếu không có tab server, thử lấy từ cấu trúc ul#episodes trực tiếp như trong hoai-thuy-truc-dinh/tap-1...html (trang xem phim)
            // NHƯNG hàm load() này parse trang CHI TIẾT PHIM, không phải trang XEM PHIM.
            // Cần HTML của trang chi tiết Hoài Thủy Trúc Đình (https://vietsubtv.us/phim/hoai-thuy-truc-dinh_23245.html) để biết cấu trúc chính xác.
            // Giả sử cấu trúc tương tự trang xem phim cho danh sách tập ở bên phải (ít khả thi hơn là các tab server).
            val episodeGroups = document.select("ul#episodes") // Trên trang chi tiết, có thể là các tab pills-firm-X
             if (episodeGroups.isNotEmpty() && episodeGroups.first()?.select("li")?.size ?: 0 > 1) {
                isDefinitelyTvSeries = true
            }
            episodeGroups.forEachIndexed { groupIndex, groupEl ->
                // Sửa lỗi: prevElementSibling có thể không tồn tại. Tìm thẻ h3 gần nhất phía trước.
                val serverNameGuess = groupEl.select("div.w-full > h3").lastOrNull()?.text()?.trim() ?: "Server ${groupIndex + 1}"

                groupEl.select("li a").forEach { el ->
                    val epHref = fixUrl(el.attr("href"))
                    val epTitle = el.attr("title")?.trim() ?: el.text().trim()
                     val epNumRegex = Regex("""Tập\s*(\d+)""")
                     val epNum = epNumRegex.find(epTitle)?.groupValues?.get(1)?.toIntOrNull()

                    episodeList.add(Episode(
                        data = epHref,
                        name = "$epTitle - $serverNameGuess",
                        episode = epNum
                    ))
                }
            }
        }

        val finalTvType = if (isDefinitelyTvSeries) TvType.TvSeries else TvType.Movie

        return if (finalTvType == TvType.TvSeries) {
            newTvSeriesLoadResponse(title, url, finalTvType, episodeList.distinctBy { it.data }) { // distinctBy để tránh trùng lặp nếu logic parse chưa chuẩn
                this.posterUrl = fixUrlNull(poster)
                this.year = year
                this.plot = plot
                this.tags = genres
                this.actors = actors
                this.recommendations = recommendations
            }
        } else { // Movie
            newMovieLoadResponse(title, url, finalTvType, episodeList.firstOrNull()?.data ?: url) {
                this.posterUrl = fixUrlNull(poster)
                this.year = year
                this.plot = plot
                this.tags = genres
                this.actors = actors
                this.recommendations = recommendations
                this.dataUrl = url
                // Sửa lỗi: Sử dụng addEpisodes cho MovieLoadResponse
                addEpisodes(DubStatus.Subbed, episodeList.distinctBy { it.data }) // Giả sử là Subbed, cần logic để xác định
            }
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val document = app.get(data, referer = mainUrl).document
        var foundStream = false

        val serverElements = document.select("a.streaming-server[data-link]")

        serverElements.apmap { serverEl ->
            val streamLink = serverEl.attr("data-link").let {
                if (it.startsWith("//")) "https:$it" else it
            }.replaceFirst(Regex("^http://"), "https://")

            val serverName = serverEl.text().trim().replaceFirst("Server", "").trim()
            val streamType = serverEl.attr("data-type")

            if (streamType.equals("m3u8", ignoreCase = true) && streamLink.isNotBlank()) {
                // Sửa lỗi: Sử dụng newExtractorLink
                newExtractorLink(
                    source = this.name,
                    name = "${this.name} Server $serverName",
                    url = streamLink,
                    referer = data, // Referer là URL của trang xem phim
                    quality = Qualities.Unknown.value,
                    isM3u8 = true
                ).let { callback(it) }
                foundStream = true
            } else if (streamType.equals("embed", ignoreCase = true) && streamLink.isNotBlank()) {
                // Sửa lỗi: Thêm dấu phẩy
                loadExtractor(streamLink, data, subtitleCallback, callback)?.let { success ->
                    if(success) foundStream = true
                 }
            }
        }
        return foundStream
    }
}
