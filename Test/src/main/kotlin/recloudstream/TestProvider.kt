package com.phimmoichillprovider

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

    // *** THAY ĐỔI CUỐI CÙNG: Tạo một map chứa header giống hệt curl ***
    private val curlHeaders = mapOf(
        "User-Agent" to "curl/8.13.0",
        "Accept" to "*/*"
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
        val document = app.get(mainUrl, headers = curlHeaders).document
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
        val document = app.get(searchUrl, headers = curlHeaders).document
        return document.select("div#binlist ul.list-film li.item.small").mapNotNull {
            it.toSearchResponseDefault(isSearchPage = true)
        }
    }

    override suspend fun load(url: String): LoadResponse? {
        val document = app.get(url, headers = curlHeaders).document 

        val title = document.selectFirst("div.film-info h1[itemprop=name]")?.text()?.trim() ?: return null
        
        val yearText = document.selectFirst("div.film-info h2")?.text()
        val year = yearText?.substringAfterLast("(")?.substringBefore(")")?.toIntOrNull()

        val posterUrl = document.selectFirst("div.film-info div.image img.avatar")?.attr("abs:src")
            ?: document.selectFirst("meta[property=og:image]")?.attr("content")

        val originalPlot = document.selectFirst("meta[itemprop=description]")?.attr("content")?.trim()
            ?: document.selectFirst("div#film-content")?.text()?.trim()

        val tags = document.select("div#tags ul.tags-list li.tag a")?.mapNotNull { it.text() }
        
        val durationString = document.select("ul.entry-meta.block-film li:containsOwn(Thời lượng:)")
            .firstOrNull()?.text()?.replace("Thời lượng:", "")?.trim()
        val durationInMinutes = parseDuration(durationString)

        val rating = document.selectFirst("div.box-rating span.average#average")?.text()?.toRatingInt()
        
        var episodeListPageUrl: String? = null
        val watchButton = document.selectFirst("div.film-info a.btn-see")
        if (watchButton != null) {
            episodeListPageUrl = watchButton.attr("abs:href")
        } else {
            episodeListPageUrl = document.selectFirst("div.latest-episode a")?.attr("abs:href")
        }
        if (episodeListPageUrl.isNullOrBlank()){
            episodeListPageUrl = document.selectFirst("div.film-info div.image a.icon-play")?.attr("abs:href")
        }
        
        val recommendations = document.select("div.block.film-related ul.list-film li.item").mapNotNull { it.toSearchResponseDefault() }
        
        val statusText = document.selectFirst("ul.entry-meta.block-film li:has(label:containsOwn(Đang phát:)) span")?.text()
        var tvType = if (statusText?.contains("Tập", ignoreCase = true) == true || statusText?.contains("Hoàn Tất", ignoreCase = true) == true) {
            TvType.TvSeries
        } else {
            TvType.Movie
        }

        if (tvType == TvType.Movie) {
            return newMovieLoadResponse(title, url, tvType, episodeListPageUrl ?: url) {
                this.posterUrl = posterUrl
                this.year = year
                this.plot = originalPlot
                this.tags = tags
                this.duration = durationInMinutes
                this.rating = rating
                this.recommendations = recommendations
            }
        } else {
            val episodes = mutableListOf<Episode>()
            if (!episodeListPageUrl.isNullOrBlank()) {
                try {
                    val episodeListDocument = app.get(episodeListPageUrl, headers = curlHeaders).document
                    val episodeElements = episodeListDocument.select("div#list-server div.server-group ul#list_episodes li a")
                    if (episodeElements.isNotEmpty()) {
                        episodes.addAll(episodeElements.reversed().mapNotNull { ep ->
                            val epHref = ep.attr("abs:href")
                            val epName = ep.ownText().trim().ifBlank { ep.attr("title").trim() }
                            val episodeNumber = Regex("""tập\s*(\d+)""").find(epName.lowercase())?.groupValues?.get(1)?.toIntOrNull()
                            if (epHref.isNotBlank()) Episode(epHref, epName, episode = episodeNumber) else null
                        })
                    }
                } catch (e: Exception) {
                    // Ignored
                }
            }

            if (episodes.isEmpty()) {
                episodes.add(Episode(data = url, name = "Không tìm thấy danh sách tập"))
            }
            
            return newTvSeriesLoadResponse(title, url, tvType, episodes) {
                this.posterUrl = posterUrl
                this.year = year
                this.plot = originalPlot
                this.tags = tags
                this.duration = durationInMinutes
                this.rating = rating
                this.recommendations = recommendations
            }
        }
    }
    // override suspend fun loadLinks(...)
}
