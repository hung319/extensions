package com.phimmoichillprovider // Bạn có thể thay đổi tên package cho phù hợp

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.* // Đảm bảo bạn có các import cần thiết từ utils như AppUtils, ExtractorLink, etc.
import org.jsoup.nodes.Element
// KHÔNG import com.lagradost.cloudstream3.network. Επιτρέπονται όλα τα πιστοποιητικά

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

    // Khối init với mã không chuẩn đã được loại bỏ.
    // CloudStream 3 thường xử lý SSL tốt. Nếu có vấn đề SSL cụ thể,
    // có những cách khác để cấu hình client, nhưng thường không cần thiết cho provider.

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
        val duration = document.select("ul.entry-meta.block-film li:containsOwn(Thời lượng:)")
            .firstOrNull()?.text()?.replace("Thời lượng:", "")?.trim()

        val rating = document.selectFirst("div.box-rating span.average#average")?.text()?.toRatingInt()

        val actors = document.select("ul.entry-meta.block-film li:contains(Diễn viên:)")
            .firstOrNull()?.select("a")?.mapNotNull { an -> an.text().trim().let { ActorData(Actor(it)) } }

        val director = document.select("ul.entry-meta.block-film li:contains(Đạo diễn:)")
            .firstOrNull()?.select("a")?.mapNotNull { it.text()?.trim() }?.joinToString(", ")

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
                this.duration = duration
                this.rating = rating
                this.actors = actors
                this.recommendations = recommendations
                addDirector(director)
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
                this.duration = duration
                this.rating = rating
                this.actors = actors
                this.recommendations = recommendations
                addDirector(director)
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
