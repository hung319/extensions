package com.phimmoichillprovider // Hoặc tên package của bạn

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Element

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
        val document = app.get(url).document // Đây là trang info

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
        val watchUrl = watchButton?.attr("abs:href")

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
        if (watchUrl.isNullOrBlank() && tvType == TvType.Movie) { // Phim lẻ chưa có link thì coi như series để hiển thị info
             tvType = TvType.TvSeries
        }

        if (tvType == TvType.Movie) {
            if (watchUrl.isNullOrBlank()) return null // Phim lẻ không có link xem thì không load
            return newMovieLoadResponse(title, url, tvType, watchUrl) { // data cho MovieLoadResponse là watchUrl
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
            if (!watchUrl.isNullOrBlank()) {
                try {
                    // Tải trang watchUrl để tìm danh sách tập phim
                    val episodeListPageDoc = app.get(watchUrl).document

                    // Thử các selector phổ biến để tìm danh sách tập.
                    // **LƯU Ý:** Đây là phần phỏng đoán và cần HTML thực tế của trang danh sách tập phim
                    // từ phimmoichill.day để có selector chính xác.
                    // Ví dụ: #list-episode a, .list_ep a, #server_data .episode a, etc.
                    // Cần kiểm tra xem phimmoichill.day dùng cấu trúc nào.
                    // Dưới đây là một số ví dụ selector, bạn cần thay thế bằng selector đúng:
                    val episodeElements = episodeListPageDoc.select(
                        "#list_episodes a, .episodes_list a, #list-server a[data-episode-id], .list-episode li a" 
                        // Thêm các selector khác bạn nghĩ có thể đúng
                    )
                    
                    if (episodeElements.isNotEmpty()) {
                        episodeElements.forEach { epElement ->
                            val epHref = epElement.attr("abs:href")
                            var epName = epElement.text().trim()
                            if (epName.isBlank()) { // Nếu text rỗng, thử lấy từ title hoặc thuộc tính khác
                                epName = epElement.attr("title").ifBlank { "Tập ?" }
                            }

                            if (epHref.isNotBlank() && epName.isNotBlank()) {
                                episodes.add(Episode(data = epHref, name = epName))
                            }
                        }
                    }
                } catch (e: Exception) {
                    // Lỗi khi tải hoặc parse trang danh sách tập, không làm gì cả, sẽ fallback ở dưới
                    // Log.e("PhimMoiChillProvider", "Error fetching/parsing episode list page: $watchUrl", e)
                }
            }

            // Nếu không tìm thấy danh sách tập nào từ watchUrl (ví dụ do selector sai, trang không có, hoặc lỗi)
            // thì tạo một tập mặc định trỏ đến watchUrl (link "Xem phim" ban đầu)
            if (episodes.isEmpty() && !watchUrl.isNullOrBlank()) {
                episodes.add(
                    Episode(
                        data = watchUrl,
                        name = watchButton?.text()?.replace("Xem phim", "")?.trim()?.ifBlank { null } ?: "Tập 1 / Xem phim"
                    )
                )
            }
            
            // Nếu là TVSeries mà không có watchUrl (ví dụ phim sắp chiếu chưa có trailer)
            // thì episodes sẽ rỗng, nhưng vẫn trả về thông tin phim.
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
    // Hàm loadLinks sẽ được triển khai sau
}
