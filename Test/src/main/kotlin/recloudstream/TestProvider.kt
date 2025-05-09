package com.example.vietsubtvprovider // <-- THAY THẾ BẰNG PACKAGE CỦA BẠN

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.Qualities
import org.jsoup.nodes.Element

class VietsubTVProvider : MainAPI() {
    override var mainUrl = "https://vietsubtv.run"
    override var name = "VietsubTV"
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)
    override var lang = "vi"
    override val hasMainPage = true

    private fun getQualityFromString(str: String?): String {
        return str?.trim() ?: "Không rõ"
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse? {
        val document = 안전하게 { app.get(mainUrl).document } ?: return null
        val homePageList = ArrayList<HomePageList>()

        // --- Lấy Phim từ Slider chính (Phim Đề Cử) ---
        try {
            val sliderSection = document.selectFirst("section#slider ul.splide__list")
            val sliderItems = sliderSection?.select("li.splide__slide")?.mapNotNull { element ->
                val titleNullable = element.selectFirst(".crs-content__title h2")?.text()?.trim()
                val linkNullable = element.selectFirst("a")?.attr("href")
                val imageNullable = element.selectFirst("img")?.attr("src")
                val episodeText = element.selectFirst(".update-set")?.text() ?: ""
                val tvType = if (episodeText.contains("Tập") || episodeText.contains("/")) TvType.TvSeries else TvType.Movie

                if (titleNullable.isNullOrBlank() || linkNullable.isNullOrBlank() || imageNullable.isNullOrBlank()) return@mapNotNull null

                val title: String = titleNullable
                val link: String = linkNullable
                val image: String = imageNullable

                newMovieSearchResponse(title, link, tvType) {
                    this.posterUrl = image
                }
            } ?: emptyList()

            if (sliderItems.isNotEmpty()) {
                homePageList.add(HomePageList("Phim Đề Cử", sliderItems))
            }
        } catch (e: Exception) { e.printStackTrace() }

        // --- Lấy Phim từ các Slider Danh mục/Quốc gia ---
        try {
            document.select("section.firm-by-category").forEach { section ->
                val sectionTitle = section.selectFirst(".myui-block-header h2.title-category")?.text()?.trim() ?: "Danh mục khác"
                val categoryItems = section.selectFirst("ul.splide__list")?.select("li.splide__slide")?.mapNotNull { item ->
                    val linkNullable = item.selectFirst("a")?.attr("href")
                    val imageNullable = item.selectFirst("img.splide__img")?.attr("src")
                    val titleNullable = item.selectFirst(".splide__item-title")?.text()?.trim()
                    val episodeText = item.selectFirst(".episodes")?.text()?.trim()

                    if (titleNullable.isNullOrBlank() || linkNullable.isNullOrBlank() || imageNullable.isNullOrBlank()) return@mapNotNull null

                    val title: String = titleNullable
                    val link: String = linkNullable
                    val image: String = imageNullable

                    val tvType = if (episodeText?.contains("Tập", ignoreCase = true) == true || episodeText?.contains("/") == true) TvType.TvSeries else TvType.Movie

                    newMovieSearchResponse(title, link, tvType) {
                        this.posterUrl = image
                    }
                } ?: emptyList()

                if (categoryItems.isNotEmpty()) {
                    homePageList.add(HomePageList(sectionTitle, categoryItems))
                }
            }
        } catch (e: Exception) { e.printStackTrace() }

        if (homePageList.isEmpty()) {
            throw RuntimeException("Không thể tải dữ liệu từ trang chủ ${mainUrl}")
        }
        return HomePageResponse(homePageList)
    }

    override suspend fun search(query: String): List<SearchResponse> {
         val searchUrl = "$mainUrl/?search=$query"
        val document = app.get(searchUrl).document
        val results = document.select("section.list-item div.item-wrap")

        return results.mapNotNull { element ->
            try {
                val linkElement = element.selectFirst("a.item-link")
                val hrefNullable = linkElement?.attr("href")
                if (hrefNullable.isNullOrBlank()) return@mapNotNull null

                var titleNullable = element.selectFirst(".item-title")?.text()?.trim()
                if (titleNullable.isNullOrBlank()) {
                     titleNullable = linkElement?.attr("title")
                                ?.substringAfter("Phim ")?.substringBefore(" - ")?.trim()
                }
                 if (titleNullable.isNullOrBlank()) return@mapNotNull null

                val image = element.selectFirst("img.desc-img")?.attr("src")
                val qualityStatusText = element.selectFirst(".update-info-mask")?.text()?.trim()

                val tvType = if (qualityStatusText?.contains("Tập", ignoreCase = true) == true || qualityStatusText?.contains("/") == true) {
                    TvType.TvSeries
                } else {
                    TvType.Movie
                }

                val title: String = titleNullable
                val href: String = hrefNullable

                newMovieSearchResponse(title, href, tvType) {
                     this.posterUrl = image
                }

            } catch (e: Exception) {
                e.printStackTrace()
                null
            }
        }
    }

     override suspend fun load(url: String): LoadResponse? {
        val document = app.get(url).document
        val title = document.selectFirst(".banner-content__title h1")?.text()?.trim()
            ?: document.selectFirst("meta[property=og:title]")?.attr("content")?.substringAfter("Phim ")?.substringBefore(" VietSub")?.trim()
            ?: return null

        val poster = document.selectFirst(".wrap-banner-img img.banner-img")?.attr("src")
            ?: document.selectFirst("meta[property=og:image]")?.attr("content")
        val year = document.selectFirst(".year.after-item")?.text()?.trim()?.toIntOrNull()

        var plot = document.selectFirst("meta[name=description]")?.attr("content")?.trim()
        document.selectFirst(".banner-content__desc")?.let { descElement ->
            val fullText = descElement.ownText().trim()
            if (!fullText.isNullOrBlank() && (plot.isNullOrBlank() || fullText.length > plot.length)) {
                plot = fullText
            }
        }
         if (plot.isNullOrBlank()) {
              plot = document.selectFirst("meta[property=og:description]")?.attr("content")?.trim()
         }

        val tags = document.select(".focus-info-tag.type a span.type-style")
            .mapNotNull { it.text()?.trim() }
            .filter { it != "Vietsub" && it != "Lồng Tiếng" }

        val rating = document.selectFirst(".rate")?.text()?.trim()?.replace(" ", "")?.toIntOrNull()

        val durationText = document.selectFirst("meta[property=video:duration]")?.attr("content")
        var durationMinutes: Int? = null
         if (durationText != null) {
            try {
                var totalMinutes = 0
                val hours = Regex("""(\d+)h""").find(durationText)?.groupValues?.get(1)?.toIntOrNull() ?: 0
                val minutes = Regex("""(\d+)m""").find(durationText)?.groupValues?.get(1)?.toIntOrNull() ?: 0
                val seconds = Regex("""(\d+)s""").find(durationText)?.groupValues?.get(1)?.toIntOrNull() ?: 0
                totalMinutes = hours * 60 + minutes + if (seconds >= 30) 1 else 0
                if (totalMinutes > 0) durationMinutes = totalMinutes
            } catch (_: Exception) { }
        }

        // Bỏ qua actors
        // val actors = ...

        val isMovie = tags.contains("Phim lẻ") || document.selectFirst(".episode_number.after-item")?.text()?.contains("Phim lẻ") == true || document.selectFirst("#pills-firm-1 a[href*=tap-full]") != null
        val tvType = if (isMovie) TvType.Movie else TvType.TvSeries

        val episodes = ArrayList<Episode>()
         val episodeTabs = document.select("div#pills-tabContent div.tab-pane[id^=pills-firm-]")
        if (episodeTabs.isNotEmpty()) {
            episodeTabs.forEachIndexed { tabIndex, tab ->
                 val serverName = document.selectFirst("#pills-firm-tab-${tabIndex + 1} span")?.text()?.trim()?.replace("Danh sách tập","")?.trim() ?: "Server #${tabIndex + 1}"
                tab.select("a.video-item").forEach { epElement ->
                    val epHref = epElement.attr("href")
                    val epNameFromElement = epElement.selectFirst(".video-item-name")?.text()?.trim()
                    val epName = epNameFromElement?.replace("$title - ", "") ?: "Tập ${episodes.size + 1}"
                    val episodeNumberRegex = Regex("""\b(?:Tập|Tap)\s*(\d+)\b""", RegexOption.IGNORE_CASE)
                    val episodeNumber = episodeNumberRegex.find(epName)?.groupValues?.get(1)?.toIntOrNull()
                    val epPoster = epElement.selectFirst("img.desc-img")?.attr("src") ?: poster

                    if (epHref.isNotBlank()) {
                        episodes.add(
                            Episode(
                                data = epHref,
                                name = epName.ifBlank { null },
                                season = 1,
                                episode = episodeNumber,
                                posterUrl = epPoster,
                            )
                        )
                    }
                }
            }
        } else {
            val mainPlayButtonLink = document.selectFirst(".group-btn a.btn-s[href*=tap-]")?.attr("href")
            if (!mainPlayButtonLink.isNullOrBlank()) {
                 episodes.add(Episode(data = mainPlayButtonLink, name = "Xem phim"))
            }
        }

        // *Fix*: Không tạo recommendations nữa để tránh lỗi biên dịch
        val recommendations = emptyList<SearchResponse>() // Tạo danh sách rỗng


        return if (tvType == TvType.TvSeries) {
            TvSeriesLoadResponse(
                name = title, url = url, apiName = this.name, type = tvType, episodes = episodes,
                posterUrl = poster, year = year, plot = plot, tags = tags, rating = rating,
                recommendations = recommendations, // Truyền danh sách rỗng
                actors = null, duration = durationMinutes
            )
        } else {
            MovieLoadResponse(
                name = title, url = url, apiName = this.name, type = tvType, dataUrl = episodes.firstOrNull()?.data,
                posterUrl = poster, year = year, plot = plot, tags = tags, rating = rating,
                recommendations = recommendations, // Truyền danh sách rỗng
                actors = null, duration = durationMinutes
            )
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val document = app.get(data, referer = mainUrl).document
        var foundLinks = false

        document.select("a.streaming-server").apmap { serverButton ->
            try {
                val serverName = serverButton.text().trim()
                val sourceLink = serverButton.attr("data-link").trim()

                if (sourceLink.isNotBlank()) {
                     if (sourceLink.endsWith(".m3u8", ignoreCase = true)) {
                         callback(
                            newExtractorLink(
                                source = serverName, name = serverName, url = sourceLink
                            ) {
                                this.referer = mainUrl
                                this.quality = Qualities.Unknown.value
                            }
                        )
                        foundLinks = true
                    }
                    // else if (sourceLink.endsWith(".mp4", ignoreCase = true)) { ... } // Add MP4 check if needed
                    else { // Assume embed
                        loadExtractor(sourceLink, data, subtitleCallback, callback)
                        foundLinks = true
                    }
                }
            } catch (e: Exception) { e.printStackTrace() }
        }
        return foundLinks
    }

     // Hàm wrapper an toàn nếu cần thiết (giữ lại)
     suspend fun <T> 안전하게(block: suspend () -> T): T? {
        return try {
            block()
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }
}
