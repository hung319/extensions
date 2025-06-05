package com.phimmoichillprovider // Bạn có thể thay đổi tên package cho phù hợp

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

    // Hàm tiện ích để chuyển đổi chuỗi thời lượng thành số phút (Int?)
    private fun parseDuration(durationString: String?): Int? {
        if (durationString.isNullOrBlank()) return null
        var totalMinutes = 0
        // Chuẩn hóa "tiếng" thành "giờ" và xử lý chữ thường
        val cleanedString = durationString.lowercase()
            .replace("tiếng", "giờ")
            .replace("mins", "phút") // Thêm chuẩn hóa nếu cần
            .replace("min", "phút")
            .replace("hr", "giờ")
            .replace("h", "giờ") // Nếu 'h' đứng một mình và không phải là 1h30 (cần regex cẩn thận hơn)


        // Regex tìm "X giờ Y phút" hoặc "X giờ" hoặc "Y phút"
        val pattern = Regex("""(?:(\d+)\s*giờ)?\s*(?:(\d+)\s*phút)?""")
        val match = pattern.find(cleanedString)

        if (match != null) {
            val hours = match.groupValues[1].toIntOrNull() // groupValues[0] là toàn bộ match
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
        val document = app.get(url).document

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
        val durationInMinutes = parseDuration(durationString) // Sửa lỗi duration

        val rating = document.selectFirst("div.box-rating span.average#average")?.text()?.toRatingInt()

        val combinedActorData = mutableListOf<ActorData>()
        // Parse "Diễn viên"
        document.select("ul.entry-meta.block-film li:contains(Diễn viên:)")
            .firstOrNull()?.select("a")?.forEach { actorElement ->
                val actorName = actorElement.text()?.trim()
                if (!actorName.isNullOrBlank()) {
                    combinedActorData.add(ActorData(Actor(name = actorName), role = ActorRole.Actor))
                }
            }
        // Parse "Đạo diễn"
        document.select("ul.entry-meta.block-film li:contains(Đạo diễn:)")
            .firstOrNull()?.select("a")?.forEach { directorElement ->
                val directorName = directorElement.text()?.trim()
                if (!directorName.isNullOrBlank()) {
                    combinedActorData.add(ActorData(Actor(name = directorName), role = ActorRole.Director))
                }
            }

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
        if (watchUrl.isNullOrBlank() && tvType == TvType.Movie) {
             tvType = TvType.TvSeries
        }

        if (tvType == TvType.Movie) {
            if (watchUrl.isNullOrBlank()) return null
            return newMovieLoadResponse(title, url, tvType, watchUrl) {
                this.posterUrl = posterUrl
                this.year = year
                this.plot = plot
                this.tags = tags
                this.duration = durationInMinutes // Sửa lỗi duration
                this.rating = rating
                this.actors = combinedActorData // Sửa lỗi director
                this.recommendations = recommendations
                // Không có this.director = directorString, thay vào đó đã thêm vào actors
            }
        } else {
            val episodes = mutableListOf<Episode>()
            if (!watchUrl.isNullOrBlank()) {
                 episodes.add(
                    Episode(
                        data = watchUrl,
                        name = watchButton?.text()?.replace("Xem phim", "")?.trim()?.ifBlank { null } ?: "Trailer/Tập 1",
                    )
                )
            }
            
            return newTvSeriesLoadResponse(title, url, tvType, episodes) {
                this.posterUrl = posterUrl
                this.year = year
                this.plot = plot
                this.tags = tags
                this.duration = durationInMinutes // Sửa lỗi duration
                this.rating = rating
                this.actors = combinedActorData // Sửa lỗi director
                this.recommendations = recommendations
                // Không có this.director = directorString, thay vào đó đã thêm vào actors
            }
        }
    }

    // Hàm loadLinks sẽ được triển khai sau.
    // override suspend fun loadLinks(
    //    data: String,
    //    isCasting: Boolean,
    //    subtitleCallback: (SubtitleFile) -> Unit,
    //    callback: (ExtractorLink) -> Unit
    // ): Boolean {
    //    // ...
    //    return true
    // }
}
