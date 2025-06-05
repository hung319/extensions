package com.phimmoichillprovider // Bạn có thể thay đổi tên package cho phù hợp

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
// import android.util.Log // Bỏ comment nếu bạn muốn dùng Log.d để debug trên Android

class PhimMoiChillProvider : MainAPI() {
    override var mainUrl = "https://phimmoichill.day"
    override var name = "PhimMoiChill"
    override val hasMainPage = true
    override var lang = "vi"
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(
        TvType.Movie,
        TvType.TvSeries
    )

    private fun parseDuration(durationString: String?): Int? {
        if (durationString.isNullOrBlank()) return null
        var totalMinutes = 0
        val cleanedString = durationString.lowercase()
            .replace("tiếng", "giờ")
            .replace("mins", "phút")
            .replace("min", "phút")
            .replace("hr", "giờ")

        val pattern = Regex("""(?:(\d+)\s*giờ)?\s*(?:(\d+)\s*phút)?""")
        val match = pattern.find(cleanedString)

        if (match != null) {
            val hours = match.groupValues[1].toIntOrNull()
            val minutes = match.groupValues[2].toIntOrNull()

            if (hours != null) {
                totalMinutes += hours * 60
            }
            if (minutes != null) {
                totalMinutes += minutes
            }
        }
        
        if (totalMinutes == 0 && cleanedString.matches(Regex("""^\d+$"""))) {
            cleanedString.toIntOrNull()?.let {
                if (!cleanedString.contains("giờ") && !cleanedString.contains("phút")) {
                    totalMinutes = it
                }
            }
        }
        
        return if (totalMinutes > 0) totalMinutes else null
    }

    private fun Element.toSearchResponseDefault(isSearchPage: Boolean = false): SearchResponse? {
        val aTag = this.selectFirst("a") ?: return null
        val href = aTag.attr("abs:href")
        if (href.isBlank()) return null

        val title = if (isSearchPage) {
            aTag.selectFirst("h3")?.text()
        } else {
            aTag.selectFirst("p")?.text() ?: aTag.selectFirst("h3")?.text()
        } ?: aTag.attr("title").ifBlank { null } ?: return null


        var posterUrl = aTag.selectFirst("img")?.attr("abs:src")
        if (posterUrl.isNullOrBlank() || posterUrl.contains("lazy.png") || posterUrl.contains("blank.png")) {
            posterUrl = aTag.selectFirst("img")?.attr("abs:data-src")
        }

        val qualityText = this.selectFirst("span.label")?.text()?.trim()
        val statusDivText = this.selectFirst("span.label div.status")?.text()?.trim()
        val currentQuality = statusDivText ?: qualityText

        var tvType = TvType.Movie
        if (currentQuality != null) {
            if (currentQuality.contains("Tập", ignoreCase = true) ||
                (currentQuality.contains("Hoàn Tất", ignoreCase = true) && !currentQuality.contains("Full", ignoreCase = true)) ||
                currentQuality.matches(Regex("""\d+/\d+""")) || 
                currentQuality.contains("Trailer", ignoreCase = true) && href.contains("/info/") 
            ) {
                tvType = TvType.TvSeries
            }
        }
        if (href.contains("/genre/phim-sap-chieu", ignoreCase = true) || title.contains("Trailer", ignoreCase = true)){
            tvType = TvType.TvSeries
        }

        return newMovieSearchResponse(title, href, tvType) {
            this.posterUrl = posterUrl
            if (!currentQuality.isNullOrBlank()) {
                addQuality(currentQuality)
            }
        }
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val document = app.get(mainUrl).document
        val homePageList = mutableListOf<HomePageList>()

        document.selectFirst("div.block.top-slide:has(h2.caption:containsOwn(Phim Đề Cử))")?.let { block ->
            val name = block.selectFirst("h2.caption")?.text() ?: "Phim Đề Cử"
            val movies = block.select("ul#film-hot li.item").mapNotNull { it.toSearchResponseDefault() }
            if (movies.isNotEmpty()) {
                homePageList.add(HomePageList(name, movies))
            }
        }

        val sectionSelectors = listOf(
            "Phim lẻ mới cập nhật",
            "Phim chiếu rạp mới",
            "Phim bộ mới cập nhật",
            "Phim Thịnh Hành",
            "Phim Mới Sắp Chiếu"
        )

        document.select("div.block:not(.top-slide)").forEach { block ->
            val captionElement = block.selectFirst("h2.caption")
            var captionText = captionElement?.ownText()?.trim() 
            if (captionText.isNullOrBlank()) { 
                 captionText = captionElement?.selectFirst("a")?.text()?.trim()
            }

            if (captionText != null && sectionSelectors.any { captionText.contains(it, ignoreCase = true) }) {
                val movies = block.select("ul.list-film.horizontal li.item").mapNotNull { it.toSearchResponseDefault() }
                if (movies.isNotEmpty()) {
                    homePageList.add(HomePageList(captionText, movies))
                }
            }
        }
        return HomePageResponse(homePageList)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val searchUrl = "$mainUrl/tim-kiem/$query/"
        val document = app.get(searchUrl).document
        return document.select("div#binlist ul.list-film li.item.small").mapNotNull {
            it.toSearchResponseDefault(isSearchPage = true)
        }
    }

    override suspend fun load(url: String): LoadResponse? {
        val document = app.get(url).document // Trang info phim

        val title = document.selectFirst("div.film-info div.text h1[itemprop=name]")?.text()?.trim()
            ?: return null
        val yearText = document.selectFirst("div.film-info div.text h2")?.text()
        val year = yearText?.substringAfterLast("(")?.substringBefore(")")?.toIntOrNull()

        var posterUrl = document.selectFirst("div.film-info div.image img.avatar")?.attr("abs:src")
        if (posterUrl.isNullOrBlank() || posterUrl.contains("lazy.png") || posterUrl.contains("blank.png")) {
            posterUrl = document.selectFirst("div.film-info div.image img.avatar")?.attr("abs:data-src")
        }
        if (posterUrl.isNullOrBlank()) {
            posterUrl = document.selectFirst("meta[property=og:image]")?.attr("content")
        }

        val plot = document.selectFirst("meta[itemprop=description]")?.attr("content")?.trim()
            ?: document.selectFirst("meta[name=description]")?.attr("content")?.trim()
            ?: document.selectFirst("div.film-content#film-content-wrapper div#film-content")?.text()?.replace(Regex("^.*?Nội dung phim"),"")?.trim()

        val tags = document.select("div#tags ul.tags-list li.tag a")?.mapNotNull { it.text() }
        
        val durationString = document.select("ul.entry-meta.block-film li:containsOwn(Thời lượng:)")
            .firstOrNull()?.text()?.replace("Thời lượng:", "")?.trim()
        val durationInMinutes = parseDuration(durationString)

        val rating = document.selectFirst("div.box-rating span.average#average")?.text()?.toRatingInt()
        
        val watchButton = document.selectFirst("div.film-info div.text a.btn-see, div.film-info div.text a.btn-download")
        val episodeListPageUrl = watchButton?.attr("abs:href") // Đây là link "Xem phim" từ trang info

        val recommendations = mutableListOf<SearchResponse>()
        document.select("div.block.film-related ul.list-film li.item").forEach { recElement ->
            recElement.toSearchResponseDefault()?.let { recommendations.add(it) }
        }
        
        val statusText = document.selectFirst("ul.entry-meta.block-film li:has(label:containsOwn(Đang phát:)) span")?.text()
        var tvType = if (statusText?.contains("Tập", ignoreCase = true) == true ||
                         (statusText?.contains("Hoàn Tất", ignoreCase = true) == true && !statusText.contains("Full", ignoreCase = true) ) ||
                         watchButton?.attr("title")?.contains("Trailer", ignoreCase = true) == true
                         ) {
            TvType.TvSeries
        } else {
            TvType.Movie
        }
        
        if (episodeListPageUrl.isNullOrBlank() && tvType == TvType.Movie) { 
             tvType = TvType.TvSeries 
        }

        if (tvType == TvType.Movie) {
            if (episodeListPageUrl.isNullOrBlank()) return null 
            return newMovieLoadResponse(title, url, tvType, episodeListPageUrl) {
                this.posterUrl = posterUrl
                this.year = year
                this.plot = plot
                this.tags = tags
                this.duration = durationInMinutes
                this.rating = rating
                this.recommendations = recommendations
            }
        } else { // TvType.TvSeries
            val episodes = mutableListOf<Episode>()
            
            // Chỉ tạo một Episode đại diện nếu có episodeListPageUrl
            // Việc lấy danh sách tập đầy đủ sẽ được xử lý trong loadLinks sau này
            if (!episodeListPageUrl.isNullOrBlank()) {
                var episodeName = watchButton?.text()?.replace("Xem phim", "")?.trim()
                if (episodeName.isNullOrBlank()) {
                    episodeName = title // Lấy tên phim làm tên tập nếu nút không có text rõ ràng
                }
                if (statusText?.contains("Tập", ignoreCase = true) == true && !episodeName.contains("Tập", ignoreCase = true)) {
                     // Ví dụ: statusText là "Tập 5", watchButton text chỉ là "Xem phim"
                    val currentEpisodeMatch = Regex("""Tập\s*(\d+)""").find(statusText)
                    val currentEpisodeNumber = currentEpisodeMatch?.groupValues?.get(1)
                    if(currentEpisodeNumber != null) {
                        episodeName = "Tập $currentEpisodeNumber"
                    } else if (watchButton?.attr("title")?.contains("Trailer", ignoreCase = true) == true) {
                        episodeName = "Trailer"
                    } else {
                         episodeName = "Xem phim / Danh sách tập"
                    }
                } else if (episodeName.isNullOrBlank()) {
                     episodeName = "Xem phim / Danh sách tập"
                }


                episodes.add(
                    Episode(
                        data = episodeListPageUrl, // URL này sẽ được truyền cho loadLinks
                        name = episodeName 
                    )
                )
            }
            // Nếu không có episodeListPageUrl (ví dụ phim sắp chiếu chưa có link), episodes sẽ rỗng.
            
            return newTvSeriesLoadResponse(title, url, tvType, episodes) {
                this.posterUrl = posterUrl
                this.year = year
                this.plot = plot
                this.tags = tags
                this.duration = durationInMinutes 
                this.rating = rating
                this.recommendations = recommendations
            }
        }
    }

    // Hàm loadLinks sẽ là nơi bạn thực sự tải trang episodeListPageUrl (từ Episode.data)
    // và phân tích <ul id="list_episodes"> li a để lấy danh sách tập đầy đủ
    // cũng như link video cho tập được chọn.
    // override suspend fun loadLinks(
    //    data: String, // Đây sẽ là episodeListPageUrl cho tập đầu tiên, hoặc href của tập cụ thể
    //    isCasting: Boolean,
    //    subtitleCallback: (SubtitleFile) -> Unit,
    //    callback: (ExtractorLink) -> Unit
    // ): Boolean {
    //    
    //    // 1. Tải trang `data` (chính là episodeListPageUrl hoặc link của tập cụ thể)
    //    // val pageDocument = app.get(data).document
    //    
    //    // 2. **QUAN TRỌNG**: Tại đây, bạn sẽ parse danh sách TẤT CẢ các tập từ `pageDocument`
    //    //    dùng selector "ul#list_episodes li a"
    //    //    và có thể cần cập nhật lại danh sách tập trong UI của CloudStream nếu API hỗ trợ
    //    //    (CloudStream có thể tự xử lý việc này nếu loadLinks trả về thông tin đúng cách
    //    //    hoặc nếu bạn gọi một hàm API của CloudStream để cập nhật danh sách tập).
    //    //    Hoặc đơn giản là loadLinks chỉ tập trung vào việc lấy link cho `data` hiện tại.
    //
    //    // 3. Tìm server và link video cho tập `data` hiện tại.
    //    //    Ví dụ: dựa vào `filmInfo.episodeID` và gọi AJAX đến `chillplayer.php`
    //    //    (cần phân tích `data` để lấy `episodeID` dạng số, ví dụ `pm122389` -> `122389`)
    //    //    val episodeId = data.substringAfterLast("-pm").substringBefore("?") // Cần làm cẩn thận hơn
    //
    //    //    val servers = pageDocument.select("#pm-server ul.server-list li.backup-server ul.list-episode li.episode a.btn-link-backup")
    //    //    servers.forEach { serverElement ->
    //    //        val serverIndex = serverElement.attr("data-index")
    //    //        val serverName = serverElement.text() 
    //    //        // Gọi AJAX tới chillplayer.php với episodeId và serverIndex
    //    //        // val videoPlayerHtml = app.post( ... ).text
    //    //        // Trích xuất link video từ videoPlayerHtml
    //    //        // callback.invoke(ExtractorLink(source = this.name, name = serverName, url = extractedVideoUrl, referer = data, quality = Qualities.Unknown.value))
    //    //    }
    //    return true // Trả về true nếu lấy được link, false nếu không
    // }
}
