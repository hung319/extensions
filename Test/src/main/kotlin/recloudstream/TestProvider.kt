// Đặt tên package cho plugin của bạn
package com.example.vietsubtv // Thay "com.example" bằng tên miền đảo ngược của bạn nếu có

import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Element

class VietSubTVProvider : MainAPI() {
    override var mainUrl = "https://vietsubtv.us" // ĐÃ CẬP NHẬT URL
    override var name = "VietSubTV"
    override val hasMainPage = true
    override var lang = "vi"
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(
        TvType.Movie,
        TvType.TvSeries
    )

    // Lưu trữ thông tin từ data-wrap_data trên trang chủ
    data class DataWrap(
        @JsonProperty("title") val title: String?,
        @JsonProperty("linkF") val linkF: String?, // URL đến trang chi tiết phim
        @JsonProperty("img_url") val imgUrl: String?, // Thường là poster
        @JsonProperty("type") val type: String?, // Ví dụ: "Phim lẻ ", "Phim Bộ " (có khoảng trắng thừa)
        @JsonProperty("year") val year: String?,
        // Có thể thêm các trường khác như desc, rate, firm_cate nếu cần
    )

    // Hàm helper để parse các item phim từ trang chủ và trang tìm kiếm
    private fun Element.toSearchResponseFromSplideItem(): SearchResponse? {
        val json = this.attr("data-wrap_data")
        if (json.isNotBlank()) {
            try {
                val data = AppUtils.parseJson<DataWrap>(json)
                val title = data.title?.trim() ?: return null
                val href = data.linkF ?: return null
                // Ưu tiên imgUrl từ JSON (poster), sau đó mới đến ảnh thumbnail
                val poster = data.imgUrl?.trim()?.ifEmpty { null } ?: this.selectFirst("div.splide__img-wrap > img")?.attr("src")

                // Xác định TvType từ JSON
                val typeString = data.type?.trim()?.lowercase()
                val tvType = if (typeString?.contains("bộ") == true) TvType.TvSeries else TvType.Movie

                return newAnimeSearchResponse(title, href, tvType) { // Sử dụng newAnimeSearchResponse để hỗ trợ addQuality
                    this.posterUrl = fixUrlNull(poster)
                    val qualityText = this@toSearchResponseFromSplideItem.selectFirst("div.episodes")?.text()?.trim()
                    if (!qualityText.isNullOrEmpty()) {
                        addQuality(qualityText)
                    }
                    // Có thể thêm năm nếu cần: this.year = data.year?.toIntOrNull()
                }
            } catch (e: Exception) {
                // Log lỗi nếu parse JSON thất bại
                System.err.println("Failed to parse JSON for item: $json. Error: ${e.message}")
            }
        }
        // Fallback nếu không có data-wrap_data hoặc parse lỗi
        val title = this.selectFirst("div.splide__item-title")?.text()?.trim() ?: return null
        val href = this.parent()?.attr("href") ?: return null
        val poster = this.selectFirst("div.splide__img-wrap > img")?.attr("src")
        val qualityText = this.selectFirst("div.episodes")?.text()?.trim()
        // Cần logic xác định TvType tốt hơn nếu không có JSON
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

        // Slider chính (Phim nổi bật)
        try {
            val mainSliderItems = document.select("div#splide01 ul.splide__list > li.splide__slide").mapNotNull { el ->
                val titleElement = el.selectFirst("div.crs-content__title h2")
                val title = titleElement?.text()?.trim() ?: return@mapNotNull null
                val href = el.selectFirst("div.crs-content > a")?.attr("href") ?: el.selectFirst("a")?.attr("href") ?: return@mapNotNull null
                val posterUrl = el.selectFirst("a > img")?.attr("src")
                // Xác định tvType dựa trên thông tin có sẵn, ví dụ "update-set" có chữ "Tập"
                val isSeries = el.selectFirst("span.update-set")?.text()?.contains("Tập", ignoreCase = true) == true ||
                               el.selectFirst("div.episode_number")?.text()?.contains("Phim bộ", ignoreCase = true) == true


                val tvType = if(isSeries) TvType.TvSeries else TvType.Movie

                newAnimeSearchResponse(title, href, tvType) {
                    this.posterUrl = fixUrlNull(posterUrl)
                    // Thêm các thông tin khác nếu cần
                    val quality = el.selectFirst("span.update-set")?.text()?.trim()
                    if(!quality.isNullOrEmpty()) addQuality(quality)
                }
            }
            if (mainSliderItems.isNotEmpty()) {
                homePageList.add(HomePageList("Phim Nổi Bật", mainSliderItems, HorizontalSlider()))
            }
        } catch (e: Exception) {
            System.err.println("Error parsing main slider: ${e.message}")
        }


        // Các section khác (Phim Hàn Quốc Mới, Phim Trung Quốc Mới, ...)
        val sections = document.select("section.firm-by-category")
        for (section in sections) {
            try {
                val sectionTitle = section.selectFirst("h2.title-category")?.text()?.trim() ?: "Phim Mới"
                // Trong các section này, item phim nằm trong thẻ li của splide, và thẻ a bao ngoài div.splide__item
                val items = section.select("ul.splide__list > li.splide__slide").mapNotNull { slideEl ->
                    slideEl.selectFirst("div.splide__item")?.toSearchResponseFromSplideItem()
                }
                if (items.isNotEmpty()) {
                    homePageList.add(HomePageList(sectionTitle, items, HorizontalSlider()))
                }
            } catch (e: Exception) {
                 System.err.println("Error parsing section: ${section.selectFirst("h2.title-category")?.text()}. Error: ${e.message}")
            }
        }
        return HomePageResponse(homePageList)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val searchUrl = "$mainUrl/?search=$query" // Trang tìm kiếm không có &page={page} ở URL đầu, nhưng có phân trang bên dưới
        val document = app.get(searchUrl).document

        return document.select("section.list-item div.item-wrap").mapNotNull { el ->
            val title = el.selectFirst("div.item-title")?.text()?.trim() ?: return@mapNotNull null
            val href = el.selectFirst("a.item-link")?.attr("href") ?: return@mapNotNull null
            val posterUrl = el.selectFirst("div.item-img img")?.attr("src")
            val qualityText = el.selectFirst("div.update-info-mask")?.text()?.trim()

            // Heuristic để xác định TvType: nếu qualityText chứa "Tập" hoặc "/", khả năng cao là series
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
        val document = app.get(url).document // url này là của trang chi tiết phim (info page)
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
            // Cần xác định TvType cho recommendation, tạm để Movie
            newMovieSearchResponse(recTitle, recHref, TvType.Movie) {
                this.posterUrl = fixUrlNull(recPoster)
            }
        }

        // Xác định TvType
        val isTvSeriesBasedOnInfo = document.selectFirst("div.banner-content__infor div.episode_number")?.text()?.contains("Phim bộ", ignoreCase = true) == true
        val isTvSeriesBasedOnEpisodeStructure = document.select("ul#episodes li a[href*='/tap-']").size > 1 ||
                                                document.select("ul#episodes h3").size > 1 // Nếu có nhiều hơn 1 server/nhóm tập

        val tvType = if (isTvSeriesBasedOnInfo || isTvSeriesBasedOnEpisodeStructure) TvType.TvSeries else TvType.Movie

        val episodeList = ArrayList<Episode>()

        // Danh sách tập/server từ trang chi tiết phim (info.txt / hoai-thuy-truc-dinh_23245.html)
        // Cấu trúc này dành cho trang chi tiết phim, không phải trang xem phim.
        // Trang xem phim (watch.txt) có cấu trúc server khác.
        // Mã này lấy episode từ trang CHI TIẾT, URL của episode sẽ được dùng trong loadLinks.

        val serverTabButtons = document.select("ul#pills-tab li.nav-item button.nav-link[id^='pills-firm-tab-']")
        if (serverTabButtons.isNotEmpty()) {
            serverTabButtons.forEachIndexed { index, serverButton ->
                val serverName = serverButton.selectFirst("span")?.text()?.trim() ?: "Server ${index + 1}"
                val targetPaneId = serverButton.attr("data-bs-target").removePrefix("#")
                val episodeElements = document.select("div#$targetPaneId div.video-list-wrapper a.video-item") // Phim lẻ có thể có cấu trúc này

                if(episodeElements.isNotEmpty()){
                     episodeElements.forEach { el ->
                        val epHref = fixUrl(el.attr("href"))
                        val epName = el.selectFirst("div.video-item-name")?.text()?.trim() ?: "Tập ${episodeList.size + 1}"
                        episodeList.add(Episode(
                            data = epHref,
                            name = if (tvType == TvType.TvSeries) "$epName - $serverName" else serverName, // Phim lẻ thì chỉ cần tên server
                            // episode = epNum // có thể parse số tập từ epName nếu là phim bộ
                        ))
                    }
                } else { // Fallback cho cấu trúc danh sách tập phim bộ như trong hoai-thuy-truc-dinh
                    document.select("div#$targetPaneId ul.AZList li a").forEach { epEl ->
                         val epHref = fixUrl(epEl.attr("href"))
                         val epTitle = epEl.attr("title") ?: epEl.text()
                         episodeList.add(Episode(
                            data = epHref,
                            name = "$epTitle - $serverName"
                        ))
                    }
                }
            }
        } else {
            // Trường hợp không có tab server rõ ràng, thử lấy từ #episodes trực tiếp (thường cho phim bộ có 1 nguồn)
            val episodeGroups = document.select("ul#episodes") // Có thể có nhiều ul#episodes nếu chia theo server
            episodeGroups.forEachIndexed { groupIndex, groupEl ->
                val serverNameGuess = groupEl.prevElementSibling()?.selectFirst("h3")?.text()?.trim() ?: "Server ${groupIndex + 1}"
                groupEl.select("li a").forEach { el ->
                    val epHref = fixUrl(el.attr("href"))
                    val epTitle = el.attr("title") ?: el.text().trim()
                    episodeList.add(Episode(
                        data = epHref,
                        name = "$epTitle - $serverNameGuess"
                    ))
                }
            }
        }


        return if (tvType == TvType.TvSeries) {
            newTvSeriesLoadResponse(title, url, tvType, episodeList) {
                this.posterUrl = fixUrlNull(poster)
                this.year = year
                this.plot = plot
                this.tags = genres
                this.actors = actors
                this.recommendations = recommendations
            }
        } else { // Movie
            // Đối với phim lẻ, mỗi "Episode" thực chất là một server/link xem.
            // `data` của `newMovieLoadResponse` không thực sự được dùng nếu episodeList được cung cấp.
            // CloudStream sẽ dùng `data` từ `Episode` để gọi `loadLinks`.
            newMovieLoadResponse(title, url, tvType, episodeList.firstOrNull()?.data ?: url) {
                this.posterUrl = fixUrlNull(poster)
                this.year = year
                this.plot = plot
                this.tags = genres
                this.actors = actors
                this.recommendations = recommendations
                // Quan trọng: Truyền episodeList cho phim lẻ để CloudStream hiển thị lựa chọn server
                this.episodes = episodeList
            }
        }
    }

    override suspend fun loadLinks(
        data: String, // data là URL của trang XEM PHIM (ví dụ: .../tap-1_519458.html)
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val document = app.get(data, referer = mainUrl).document // Quan trọng: referer có thể cần
        var foundStream = false

        // Tìm các server link từ các thẻ <a> có class 'streaming-server' trên trang XEM PHIM
        val serverElements = document.select("a.streaming-server[data-link]")

        serverElements.apmap { serverEl ->
            val streamLink = serverEl.attr("data-link").let {
                if (it.startsWith("//")) "https:$it" else it // Đảm bảo có scheme
            }.replace(/^http:\/\//i, "https://") // Luôn dùng HTTPS nếu có thể

            val serverName = serverEl.text().trim().replaceFirst("Server", "").trim()
            val streamType = serverEl.attr("data-type")

            if (streamType.equals("m3u8", ignoreCase = true) && streamLink.isNotBlank()) {
                callback(
                    ExtractorLink(
                        source = this.name,
                        name = "${this.name} Server $serverName",
                        url = streamLink,
                        referer = mainUrl, //  Hoặc có thể là data (URL trang xem phim)
                        quality = Qualities.Unknown.value,
                        isM3u8 = true
                    )
                )
                foundStream = true
            } else if (streamType.equals("embed", ignoreCase = true) && streamLink.isNotBlank()) {
                 loadExtractor(streamLink, data, subtitleCallback, callback)?.let { success ->
                    if(success) foundStream = true
                 }
            }
        }
        return foundStream
    }
}
