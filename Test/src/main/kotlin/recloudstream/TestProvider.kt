package com.lagradost.cloudstream3.vi.providers

import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Element
import com.lagradost.cloudstream3.network.CloudflareKiller
// import android.util.Log // Tạm thời comment out nếu gây lỗi build

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
    ): HomePageResponse {
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
        
        // Logic kiểm tra HomePageList trống đã được điều chỉnh ở phiên bản trước
        if (homePageList.isEmpty() && page == 1 && mainPage.any { it.data == request.data }) {
             // Allow empty for subsequent pages or dynamic pages not in mainPage
             // Hoặc throw lỗi nếu bạn chắc chắn mục này phải có dữ liệu
             // throw ErrorLoadingException("Không tải được dữ liệu cho mục: ${request.name}")
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
            var currentSeasonNumberForEpisodes = 0 // Bắt đầu từ 0, sẽ tăng lên 1 cho nhóm đầu tiên
            var lastGroupNameProcessed = "" // Để theo dõi tên nhóm đã xử lý

            episodeContainer.children().forEach { child ->
                if (child.tagName() == "div" && child.hasClass("w-full")) {
                    val groupName = child.selectFirst("h3")?.text()?.trim()
                    // Chỉ tăng season number nếu tên nhóm mới khác tên nhóm trước đó
                    // Và phải đảm bảo đã có ít nhất 1 tập được thêm vào season trước (nếu không phải lần đầu)
                    if (groupName != null && groupName != lastGroupNameProcessed) {
                        currentSeasonNumberForEpisodes++ // Tăng season cho nhóm mới
                        lastGroupNameProcessed = groupName
                    }
                } else if (child.tagName() == "li") {
                    val epElement = child.selectFirst("a")
                    if (epElement != null) {
                        val epTitleAttribute = epElement.attr("title")?.trim() // Ví dụ: "Tập 1", "Full"
                        val epTextContent = epElement.text().trim()      // Ví dụ: "1", "Full"

                        // Ưu tiên title attribute làm tên hiển thị
                        var episodeDisplayName = epTitleAttribute
                        if (episodeDisplayName.isNullOrBlank()) { // Nếu title rỗng
                            episodeDisplayName = epTextContent // Thì dùng text bên trong a
                        }

                        // Trích xuất số tập cho trường 'episode'
                        val numberRegex = Regex("""\d+""")
                        val episodeNumber = numberRegex.find(epTextContent)?.value?.toIntOrNull() // Ưu tiên số từ text content (vd: "1")
                            ?: numberRegex.find(epTitleAttribute ?: "")?.value?.toIntOrNull() // Hoặc từ title (vd: "Tập 1")

                        var epUrl = epElement.attr("href")
                        if (epUrl.isNotBlank() && !epUrl.startsWith("http")) {
                            epUrl = "$mainUrl$epUrl"
                        }

                        // Nếu currentSeasonNumberForEpisodes vẫn là 0 (chưa gặp div.w-full nào), đặt là 1
                        val seasonForEpisode = if(currentSeasonNumberForEpisodes == 0) 1 else currentSeasonNumberForEpisodes

                        episodes.add(
                            Episode(
                                data = epUrl,
                                name = episodeDisplayName, // Sẽ là "Tập X" hoặc "Full"
                                episode = episodeNumber,   // Số của tập, hoặc null
                                season = seasonForEpisode,  // Phân nhóm theo server/loại
                                posterUrl = posterUrl,
                                rating = rating
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
            val movieEpisode = Episode(
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
    }
    override suspend fun loadLinks(
        data: String, // URL trang xem phim của tập (epUrl)
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        // Log.d("VietSubTvProvider", "loadLinks called with data: $data") // Để debug
        val document = app.get(data, interceptor = CloudflareKiller()).document
        var foundLinks = false

        // Tìm tất cả các thẻ <a> là server stream
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
                                    source = this.name, // Hoặc serverName
                                    name = "$name - $serverName", // Tên hiển thị cho link này
                                    url = streamLink,
                                    referer = data, // Referer là trang chứa link này
                                    quality = Qualities.Unknown.value, // Hoặc thử parse từ tên server nếu có
                                    isM3u8 = true
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
                                    isM3u8 = false // MP4 không phải m3u8
                                )
                            )
                            foundLinks = true
                        }
                        "embed" -> {
                            // CloudStream3 có hàm loadExtractor để xử lý các link embed phổ biến
                            // Bạn cần đảm bảo các extractor tương ứng được kích hoạt trong CloudStream3
                            // Hoặc nếu là embed của một trang cụ thể (ví dụ: streamc.xyz), bạn có thể cần logic riêng
                            // để lấy link trực tiếp từ embed đó nếu loadExtractor không xử lý được.
                            // Đây là một ví dụ chung:
                            foundLinks = loadExtractor(streamLink, data, subtitleCallback, callback) || foundLinks
                        }
                        else -> {
                            // Nếu không xác định được type, có thể thử load như một link embed
                            // hoặc thử đoán type dựa trên phần mở rộng của link (.m3u8, .mp4)
                            if (streamLink.contains(".m3u8")) {
                                callback(
                                    ExtractorLink(
                                        source = this.name,
                                        name = "$name - $serverName (M3U8)",
                                        url = streamLink,
                                        referer = data,
                                        quality = Qualities.Unknown.value,
                                        isM3u8 = true
                                    )
                                )
                                foundLinks = true
                            } else if (streamLink.contains(".mp4")) {
                                callback(
                                    ExtractorLink(
                                        source = this.name,
                                        name = "$name - $serverName (MP4)",
                                        url = streamLink,
                                        referer = data,
                                        quality = Qualities.Unknown.value,
                                        isM3u8 = false
                                    )
                                )
                                foundLinks = true
                            } else {
                                // Thử load như embed nếu không rõ
                                foundLinks = loadExtractor(streamLink, data, subtitleCallback, callback) || foundLinks
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                // Log.e("VietSubTvProvider", "Error loading link: ${e.localizedMessage}")
            }
        }
        return foundLinks
    }
} // Đảm bảo dấu } này đóng class VietSubTvProvider
