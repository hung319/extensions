package com.lagradost.cloudstream3.vi.providers

import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Element
import com.lagradost.cloudstream3.network.CloudflareKiller
// import android.util.Log // Đã comment out

class VietSubTvProvider : MainAPI() {
    override var mainUrl = "https://vietsubtv.us"
    override var name = "VietSubTV"
    override val hasMainPage = true
    override var lang = "vi"
    override val hasDownloadSupport = true
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
    ): HomePageResponse { // Không còn là HomePageResponse? nữa vì newHomePageResponse không nullable
        val url = if (request.data.startsWith("http")) request.data else "$mainUrl${request.data}"
        // Thêm header nếu cần, ví dụ:
        // val headers = mapOf("Referer" to mainUrl)
        // val document = app.get(url, interceptor = CloudflareKiller(), headers = headers).document
        val document = app.get(url, interceptor = CloudflareKiller()).document
        
        val items = mutableListOf<SearchResponse>()

        // Selector này cần bao quát các cấu trúc item trên các trang danh sách của bạn
        // Ví dụ: trang "Phim Mới", "Phim Hàn Quốc", etc.
        // Dựa trên home.txt và các cấu trúc danh sách chung
        document.select("div.slider__column li.splide__slide, ul.video-listing li.video-item, div.item-wrap, li.film-item, div.movie-item") 
            .forEach { element ->
                // Thử nhiều selector cho tiêu đề
                val title = element.selectFirst("div.splide__item-title, div.video-item-name, div.item-title, h3.film-title a, .movie-title a, .name a, .name")?.text()?.trim() ?: ""

                // Thử nhiều selector cho link
                var link = element.selectFirst("a")?.attr("href") 
                    ?: element.selectFirst("h3.film-title a")?.attr("href")
                    ?: element.selectFirst(".movie-title a")?.attr("href")

                if (link.isNullOrBlank()) { // Nếu thẻ a chính không có href, thử tìm trong các thẻ con
                     link = element.select("a[href]").firstOrNull()?.attr("href")
                }


                if (link?.isNotBlank() == true && !link.startsWith("http")) {
                    link = "$mainUrl$link"
                }
                
                // Thử nhiều selector cho poster, ưu tiên data-src
                var posterUrl = element.selectFirst("div.splide__img-wrap img, div.video-item-img img, div.item-img img, .film-poster-img, .movie-poster img")?.let { img ->
                    img.attr("data-src").ifBlank { img.attr("src") }
                }
                if (posterUrl?.startsWith("/") == true) {
                    posterUrl = "$mainUrl$posterUrl"
                }

                // Thử nhiều selector cho chất lượng/tập
                val quality = element.selectFirst("div.episodes, div.update-info-mask, span.episode-status, .trangthai, .episode_status")?.text()?.trim()


                if (title.isNotBlank() && link?.isNotBlank() == true) {
                    items.add(
                        newMovieSearchResponse(name = title, url = link) { // Sử dụng name = và url = cho rõ ràng
                            this.posterUrl = posterUrl
                            if (quality != null) {
                               this.quality = getQualityFromString(quality)
                            }
                        }
                    )
                }
            }
        
        // Tạo một HomePageList duy nhất cho request hiện tại
        val homePageListForCurrentRequest = HomePageList(request.name, items, isHorizontal = true) // isHorizontal có thể tùy chỉnh

        // Logic phân trang cơ bản: Giả sử trang web có tối đa 5 trang cho mỗi mục
        // Bạn cần logic chính xác hơn nếu trang web có cách phân trang khác (ví dụ: nút "Next")
        val hasNext = items.isNotEmpty() && page < 5 // Ví dụ đơn giản

        // Trả về newHomePageResponse chứa chỉ mục hiện tại
        // Tên của newHomePageResponse có thể là tên của mục, hoặc để trống nếu CloudStream tự xử lý
        return newHomePageResponse(request.name, listOf(homePageListForCurrentRequest), hasNextPage = hasNext)
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
        val initialDocument = app.get(url, interceptor = CloudflareKiller()).document

        val watchPageLink = initialDocument.selectFirst("a.btn-item.btn-s[href*=tap-]")?.attr("href")?.let {
            if (it.startsWith("/")) mainUrl + it else it
        } ?: url

        val watchPageDocument = app.get(watchPageLink, interceptor = CloudflareKiller()).document

        val title = watchPageDocument.selectFirst("div.info-detail h3.title span.intl-album-title-word-wrap")?.text()?.trim()
            ?: initialDocument.selectFirst("div.banner-content__title h1")?.text()?.trim()
            ?: watchPageDocument.selectFirst("meta[property=\"og:title\"]")?.attr("content")
                ?.replace("Xem phim ", "")?.replace(" VietSub Full HD", "")
                ?.replace(" Tập Full", "")?.substringBefore(" - ")
            ?: initialDocument.selectFirst("meta[property=\"og:title\"]")?.attr("content")
                ?.replace("Xem phim ", "")?.replace(" VietSub Full HD", "")
                ?.replace(" Tập Full", "")?.substringBefore(" - ")
            ?: return null

        var posterUrl = watchPageDocument.selectFirst("meta[property=\"og:image\"]")?.attr("content")
            ?: initialDocument.selectFirst("div.col__right div.wrap-banner-img img")?.attr("src")
            ?: initialDocument.selectFirst("meta[property=\"og:image\"]")?.attr("content")

        if (posterUrl?.startsWith("/") == true) {
            posterUrl = "$mainUrl$posterUrl"
        }

        val plot = watchPageDocument.selectFirst("div.banner-content__desc")?.textNodes()?.joinToString("") { it.text().trim() }?.replace("Miêu tả:", "")?.trim()
            ?: initialDocument.selectFirst("div.banner-content__desc")?.textNodes()?.joinToString("") { it.text().trim() }?.replace("Miêu tả:", "")?.trim()
            ?: watchPageDocument.selectFirst("meta[name=\"description\"]")?.attr("content")?.trim()
            ?: initialDocument.selectFirst("meta[name=\"description\"]")?.attr("content")?.trim()

        val yearText = watchPageDocument.select("div.intl-play-time span").find { it.text().matches(Regex("\\d{4}")) }?.text()
            ?: initialDocument.selectFirst("div.banner-content__infor div.year")?.text()?.trim()
        val year = yearText?.toIntOrNull()

        val ratingText = watchPageDocument.selectFirst("div.intl-play-time span.focus-item-label-original:contains(Điểm)")?.nextElementSibling()?.text()?.trim()
            ?: initialDocument.selectFirst("div.banner-content__infor div.rate")?.text()?.trim()
        val rating = ratingText?.filter { it.isDigit() || it == '.' }?.toFloatOrNull()?.times(10)?.toInt()

        val tags = (watchPageDocument.select("div.intl-play-item-tags a span") + initialDocument.select("div.focus-info-tag.type a span.type-style"))
            .mapNotNull { it.text()?.trim() }.distinct().filterNot { it.equals("Vietsub", true) || it.equals("Thuyết Minh", true) || it.contains("Năm") }

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

        val episodes = mutableListOf<Episode>()
        val episodeContainer = watchPageDocument.selectFirst("div.intl-play-right div.MainContainer section.SeasonBx ul.AZList#episodes")

        if (episodeContainer != null) {
            var currentSeasonNumberForEpisodes = 0
            var lastGroupNameProcessed = ""

            episodeContainer.children().forEach { child ->
                if (child.tagName() == "div" && child.hasClass("w-full")) {
                    val groupName = child.selectFirst("h3")?.text()?.trim()
                    if (groupName != null && groupName != lastGroupNameProcessed) {
                        currentSeasonNumberForEpisodes++
                        lastGroupNameProcessed = groupName
                    }
                } else if (child.tagName() == "li") {
                    val epElement = child.selectFirst("a")
                    if (epElement != null) {
                        val epTitleAttribute = epElement.attr("title")?.trim()
                        val epTextContent = epElement.text().trim()

                        var episodeDisplayName = epTitleAttribute
                        if (episodeDisplayName.isNullOrBlank()) {
                            episodeDisplayName = epTextContent
                        }

                        val numberRegex = Regex("""\d+""")
                        val episodeNumber = numberRegex.find(epTextContent)?.value?.toIntOrNull()
                            ?: numberRegex.find(epTitleAttribute ?: "")?.value?.toIntOrNull()

                        var epUrl = epElement.attr("href")
                        if (epUrl.isNotBlank() && !epUrl.startsWith("http")) {
                            epUrl = "$mainUrl$epUrl"
                        }
                        
                        val seasonForEpisode = if(currentSeasonNumberForEpisodes == 0) 1 else currentSeasonNumberForEpisodes

                        // Sử dụng constructor cũ của Episode nếu newEpisode yêu cầu runTime mà bạn không có
                        episodes.add( 
                            Episode( // <= Dòng 240 gốc
                                data = epUrl,
                                name = episodeDisplayName,
                                episode = episodeNumber,
                                season = seasonForEpisode,
                                posterUrl = posterUrl, // Poster của cả series/phim
                                rating = rating // Rating của cả series/phim
                            )
                        )
                    }
                }
            }
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
            val movieEpisode = Episode( // <= Dòng 269 gốc
                data = watchPageLink,
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
    } // Đóng hàm load

    override suspend fun loadLinks(
        data: String, // URL trang xem phim của tập (epUrl)
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        // Log.d("VietSubTvProvider", "loadLinks called with data: $data")
        val document = app.get(data, interceptor = CloudflareKiller()).document
        var foundLinks = false

        document.select("div.flex-row a.streaming-server").forEach { serverElement ->
            try {
                val serverName = serverElement.text()?.trim() ?: "Server"
                val streamLink = serverElement.attr("data-link")
                val dataType = serverElement.attr("data-type")?.lowercase() ?: ""

                // Log.d("VietSubTvProvider", "Found server: $serverName, Link: $streamLink, Type: $dataType")

                if (streamLink.isNotBlank()) {
                    when (dataType) {
                        "m3u8" -> {
                            callback(
                                ExtractorLink(
                                    source = this.name,
                                    name = "$name - $serverName",
                                    url = streamLink,
                                    referer = data,
                                    quality = Qualities.Unknown.value,
                                    type = ExtractorLinkType.M3U8
                                )
                            )
                            foundLinks = true
                        }
                        "mp4" -> {
                             callback(
                                ExtractorLink(
                                    source = this.name,
                                    name = "$name - $serverName",
                                    url = streamLink,
                                    referer = data,
                                    quality = Qualities.Unknown.value,
                                    type = ExtractorLinkType.VIDEO
                                )
                            )
                            foundLinks = true
                        }
                        // Trường hợp "embed" sẽ không được xử lý ở đây nữa
                        // Chỉ xử lý các type khác mà có thể là link trực tiếp
                        else -> {
                            if (streamLink.contains(".m3u8", ignoreCase = true)) {
                                callback(
                                    ExtractorLink(
                                        source = this.name,
                                        name = "$name - $serverName (M3U8)",
                                        url = streamLink,
                                        referer = data,
                                        quality = Qualities.Unknown.value,
                                        type = ExtractorLinkType.M3U8
                                    )
                                )
                                foundLinks = true
                            } else if (streamLink.contains(".mp4", ignoreCase = true)) {
                                callback(
                                    ExtractorLink(
                                        source = this.name,
                                        name = "$name - $serverName (MP4)",
                                        url = streamLink,
                                        referer = data,
                                        quality = Qualities.Unknown.value,
                                        type = ExtractorLinkType.VIDEO
                                    )
                                )
                                foundLinks = true
                            }
                            // Không gọi loadExtractor cho các trường hợp khác nếu bạn muốn bỏ qua embed hoàn toàn
                        }
                    }
                }
            } catch (e: Exception) {
                // Log.e("VietSubTvProvider", "Error loading link from server $serverName: ${e.localizedMessage}")
            }
        }
        return foundLinks
    }
} // Đóng class VietSubTvProvider
