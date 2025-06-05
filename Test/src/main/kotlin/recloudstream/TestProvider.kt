package com.phimmoichillprovider // Bạn có thể thay đổi tên package cho phù hợp

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.Jsoup // Đảm bảo import Jsoup
import org.jsoup.nodes.Element
// import android.util.Log // Bỏ comment nếu bạn muốn dùng Log.d để debug trên Android

class PhimMoiChillProvider : MainAPI() {
    override var mainUrl = "https://phimmoichill.day"
    override var name = "PhimMoiChill"
    override val hasMainPage = true
    override var lang = "vi"
    override val hasDownloadSupport = true // Giả định là có hỗ trợ tải về
    override val supportedTypes = setOf(
        TvType.Movie,
        TvType.TvSeries
    )

    // --- Hàm tiện ích parse thời lượng phim ---
    private fun parseDuration(durationString: String?): Int? {
        if (durationString.isNullOrBlank()) return null
        var totalMinutes = 0
        val cleanedString = durationString.lowercase()
            .replace("tiếng", "giờ")
            .replace("mins", "phút")
            .replace("min", "phút")
            .replace("hr", "giờ")

        // Regex tìm "X giờ Y phút" hoặc "X giờ" hoặc "Y phút"
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
        
        // Nếu không tìm thấy giờ/phút nhưng chuỗi chỉ là một số (ví dụ "90")
        if (totalMinutes == 0 && cleanedString.matches(Regex("""^\d+$"""))) {
            cleanedString.toIntOrNull()?.let {
                // Giả định là phút nếu không có đơn vị và totalMinutes vẫn là 0
                if (!cleanedString.contains("giờ") && !cleanedString.contains("phút")) {
                    totalMinutes = it
                }
            }
        }
        
        return if (totalMinutes > 0) totalMinutes else null
    }

    // --- Hàm tiện ích chuyển đổi Element thành SearchResponse ---
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

        var tvType = TvType.Movie // Mặc định là phim lẻ
        if (currentQuality != null) {
            if (currentQuality.contains("Tập", ignoreCase = true) ||
                (currentQuality.contains("Hoàn Tất", ignoreCase = true) && !currentQuality.contains("Full", ignoreCase = true)) ||
                currentQuality.matches(Regex("""\d+/\d+""")) || 
                currentQuality.contains("Trailer", ignoreCase = true) && href.contains("/info/") 
            ) {
                tvType = TvType.TvSeries
            }
        }
        // Phim Sắp Chiếu / Trailer thường là TvSeries trong ngữ cảnh CloudStream để hiển thị thông tin
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

    // --- Hàm lấy nội dung trang chủ ---
    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val document = app.get(mainUrl).document
        val homePageList = mutableListOf<HomePageList>()

        // Section: Phim Đề Cử (Hot) - ul#film-hot
        document.selectFirst("div.block.top-slide:has(h2.caption:containsOwn(Phim Đề Cử))")?.let { block ->
            val name = block.selectFirst("h2.caption")?.text() ?: "Phim Đề Cử"
            val movies = block.select("ul#film-hot li.item").mapNotNull { it.toSearchResponseDefault() }
            if (movies.isNotEmpty()) {
                homePageList.add(HomePageList(name, movies))
            }
        }

        // Các sections khác dùng chung cấu trúc ul.list-film.horizontal
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

    // --- Hàm tìm kiếm phim ---
    override suspend fun search(query: String): List<SearchResponse> {
        val searchUrl = "$mainUrl/tim-kiem/$query/"
        // Log.d("PhimMoiChillProvider", "Search URL: $searchUrl") // Debug URL
        val document = app.get(searchUrl).document
        return document.select("div#binlist ul.list-film li.item.small").mapNotNull {
            it.toSearchResponseDefault(isSearchPage = true)
        }
    }

    // --- Hàm tải thông tin chi tiết phim và danh sách tập ---
    override suspend fun load(url: String): LoadResponse? {
        val document = app.get(url).document // Trang info phim
        // Log.d("PhimMoiChillProvider", "Loading URL: $url") // Debug URL
        // Log.d("PhimMoiChillProvider", "Info Page HTML (first 1000 chars): ${document.html().take(1000)}")

        val title = document.selectFirst("div.film-info div.text h1[itemprop=name]")?.text()?.trim()
            ?: return null
        val yearText = document.selectFirst("div.film-info div.text h2")?.text()
        val year = yearText?.substringAfterLast("(")?.substringBefore(")")?.toIntOrNull()

        var posterUrl = document.selectFirst("div.film-info div.image img.avatar")?.attr("abs:src")
        if (posterUrl.isNullOrBlank() || posterUrl.contains("lazy.png") || posterUrl.contains("blank.png")) {
            posterUrl = document.selectFirst("div.film-info div.image img.avatar")?.attr("abs:data-src")
        }
        if (posterUrl.isNullOrBlank()) { // Fallback
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

        // Nút "Xem phim" hoặc "Trailer" trên trang info
        val watchButton = document.selectFirst("div.film-info div.text a.btn-see, div.film-info div.text a.btn-download")
        // Đây là URL dẫn đến trang xem phim (có thể chứa danh sách tập)
        val episodeListPageUrl = watchButton?.attr("abs:href") 

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
        
        // Nếu phim lẻ chưa có link thì coi như series để hiển thị info, không có tập nào
        if (episodeListPageUrl.isNullOrBlank() && tvType == TvType.Movie) { 
             tvType = TvType.TvSeries 
        }

        if (tvType == TvType.Movie) {
            // Phim lẻ không có episodeListPageUrl (watchUrl) thì không load
            if (episodeListPageUrl.isNullOrBlank()) {
                // Log.d("PhimMoiChillProvider", "Movie '$title' has no watch URL, returning null.")
                return null 
            }
            return newMovieLoadResponse(title, url, tvType, episodeListPageUrl) { // data cho MovieLoadResponse là episodeListPageUrl
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
            if (!episodeListPageUrl.isNullOrBlank()) {
                // Log.d("PhimMoiChillProvider", "Fetching episode list from: $episodeListPageUrl")
                try {
                    val episodeListHTML = app.get(episodeListPageUrl).text 
                    // --- GỢI Ý LOGGING QUAN TRỌNG ---
                    // Bỏ comment các dòng Log.d dưới đây nếu bạn có thể xem log để debug
                    // Log.d("PhimMoiChillProvider", "HTML trang danh sách tập (${episodeListPageUrl.take(50)}..., ${episodeListHTML.length} ký tự): ${episodeListHTML.take(3000)}")
                    // val listEpisodesHtmlCheck = Jsoup.parse(episodeListHTML).selectFirst("ul#list_episodes")?.outerHtml()
                    // Log.d("PhimMoiChillProvider", "HTML của ul#list_episodes: ${listEpisodesHtmlCheck ?: "KHÔNG TÌM THẤY ul#list_episodes TRONG HTML NHẬN ĐƯỢC"}")
                    // --- KẾT THÚC GỢI Ý LOGGING ---

                    val episodeListDocument = Jsoup.parse(episodeListHTML, episodeListPageUrl)

                    val episodeElements = episodeListDocument.select("ul#list_episodes li a")
                    
                    // Log.d("PhimMoiChillProvider", "Tìm thấy ${episodeElements.size} phần tử tập phim với selector 'ul#list_episodes li a'")

                    if (episodeElements.isNotEmpty()) {
                        episodeElements.forEach { epElement ->
                            val epHref = epElement.attr("abs:href")
                            var epName = epElement.ownText().trim() 
                            if (epName.isBlank()) { 
                                epName = epElement.attr("title").trim()
                            }
                            if (epName.isBlank()) { 
                                epName = epElement.text().trim().ifBlank { "Tập ?" } 
                            }
                            
                            var episodeNumber: Int? = null
                            val nameLower = epName.lowercase()
                            val epNumMatch = Regex("""tập\s*(\d+)""").find(nameLower)
                            if (epNumMatch != null) {
                                episodeNumber = epNumMatch.groupValues[1].toIntOrNull()
                            }
                            
                            // Log.d("PhimMoiChillProvider", "Đã parse tập: Tên='$epName', Href='$epHref', Số tập='$episodeNumber'")

                            if (epHref.isNotBlank()) {
                                episodes.add(
                                    Episode(
                                        data = epHref,
                                        name = epName,
                                        episode = episodeNumber,
                                    )
                                )
                            }
                        }
                    } else {
                        // Log.d("PhimMoiChillProvider", "Selector 'ul#list_episodes li a' không tìm thấy phần tử nào trên trang: $episodeListPageUrl. Có thể nội dung được tải bằng JS.")
                    }
                } catch (e: Exception) {
                    // Log.e("PhimMoiChillProvider", "Lỗi khi tải/phân tích trang danh sách tập từ $episodeListPageUrl: ${e.message}")
                }
            }

            // Fallback nếu không tìm được tập nào hoặc không có episodeListPageUrl cho series
            if (episodes.isEmpty() && !episodeListPageUrl.isNullOrBlank()) {
                // Log.d("PhimMoiChillProvider", "Không parse được tập nào từ list, tạo tập giữ chỗ.")
                episodes.add(
                    Episode(
                        data = episodeListPageUrl,
                        name = watchButton?.text()?.replace("Xem phim", "")?.trim()?.ifBlank { null } ?: "Tập 1 / Xem nội dung"
                    )
                )
            }
            
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

    // Hàm loadLinks sẽ được triển khai sau khi phần load() ổn định
    // override suspend fun loadLinks(
    //    data: String, // Đây sẽ là epHref từ đối tượng Episode
    //    isCasting: Boolean,
    //    subtitleCallback: (SubtitleFile) -> Unit,
    //    callback: (ExtractorLink) -> Unit
    // ): Boolean {
    //    // Logic để lấy link video từ data (epHref)
    //    // Ví dụ: tải trang data, tìm iframe hoặc ajax call đến chillplayer.php
    //    // Log.d("PhimMoiChillProvider", "loadLinks called with: $data")
    //    return true
    // }
}
