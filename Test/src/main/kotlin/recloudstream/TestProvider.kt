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
        val document = app.get(url).document 

        // Selector cho title và year, ưu tiên cấu trúc trong div.image > div.text từ load2.html
        val filmInfoImageTextDiv = document.selectFirst("div.film-info div.image div.text")
        val title = filmInfoImageTextDiv?.selectFirst("h1[itemprop=name]")?.text()?.trim()
            ?: document.selectFirst("div.film-info > div.text h1[itemprop=name]")?.text()?.trim() // Fallback cho cấu trúc khác
            ?: return null // Không tìm thấy tiêu đề thì thoát sớm
        
        val yearText = filmInfoImageTextDiv?.selectFirst("h2")?.text()
            ?: document.selectFirst("div.film-info > div.text h2")?.text() // Fallback
        val year = yearText?.substringAfterLast("(")?.substringBefore(")")?.toIntOrNull()

        var posterUrl = document.selectFirst("div.film-info div.image img.avatar")?.attr("abs:src")
        if (posterUrl.isNullOrBlank() || posterUrl.contains("lazy.png") || posterUrl.contains("blank.png")) {
            posterUrl = document.selectFirst("div.film-info div.image img.avatar")?.attr("abs:data-src")
        }
        if (posterUrl.isNullOrBlank()) {
            posterUrl = document.selectFirst("meta[property=og:image]")?.attr("content")
        }

        val originalPlot = document.selectFirst("meta[itemprop=description]")?.attr("content")?.trim()
            ?: document.selectFirst("meta[name=description]")?.attr("content")?.trim()
            ?: document.selectFirst("div.film-content#film-content-wrapper div#film-content")?.text()?.replace(Regex("^.*?Nội dung phim"),"")?.trim()

        val tags = document.select("div#tags ul.tags-list li.tag a")?.mapNotNull { it.text() }
        
        val durationString = document.select("ul.entry-meta.block-film li:containsOwn(Thời lượng:)")
            .firstOrNull()?.text()?.replace("Thời lượng:", "")?.trim()
        val durationInMinutes = parseDuration(durationString)

        val rating = document.selectFirst("div.box-rating span.average#average")?.text()?.toRatingInt()
        
        // --- Cập nhật cách tìm watchButton với debug chi tiết ---
        var watchButton: Element? = null
        var debugFindingWatchButton = "DEBUG WatchButton Finding:\n"
        // Tìm tất cả thẻ <a> trong div.film-info có href bắt đầu bằng /xem/
        val potentialWatchButtons = document.select("div.film-info a[href^=/xem/]")
        debugFindingWatchButton += "  Found ${potentialWatchButtons.size} potential buttons (href^=/xem/).\n"

        if (potentialWatchButtons.isNotEmpty()) {
            for (idx in potentialWatchButtons.indices) {
                val button = potentialWatchButtons[idx]
                val buttonText = button.text().trim()
                val buttonHref = button.attr("abs:href")
                val buttonClasses = button.className()
                debugFindingWatchButton += "  Checking button #${idx + 1}: Text='${buttonText.take(30)}', Href='$buttonHref', Classes='$buttonClasses'\n"
                
                if (buttonClasses.contains("btn-see") && buttonText.contains("Xem phim", ignoreCase = true)) {
                    watchButton = button
                    debugFindingWatchButton += "    -> MATCHED! (class 'btn-see' AND text 'Xem phim').\n"
                    break 
                } else {
                    if (!buttonClasses.contains("btn-see")) debugFindingWatchButton += "    -> Reason: No 'btn-see' class.\n"
                    if (!buttonText.contains("Xem phim", ignoreCase = true)) debugFindingWatchButton += "    -> Reason: Text does not contain 'Xem phim'.\n"
                }
            }
            if (watchButton == null) {
                debugFindingWatchButton += "  No button matched all criteria.\n"
            }
        } else {
            debugFindingWatchButton += "  No <a> tags with href^=/xem/ found in div.film-info.\n"
        }
        val episodeListPageUrl = watchButton?.attr("abs:href")
        // --- Kết thúc cập nhật cách tìm watchButton ---

        val recommendations = mutableListOf<SearchResponse>()
        document.select("div.block.film-related ul.list-film li.item").forEach { recElement ->
            recElement.toSearchResponseDefault()?.let { recommendations.add(it) }
        }
        
        val statusText = document.selectFirst("ul.entry-meta.block-film li:has(label:containsOwn(Đang phát:)) span")?.text()
        var tvType = if (statusText?.contains("Tập", ignoreCase = true) == true ||
                         (statusText?.contains("Hoàn Tất", ignoreCase = true) == true && !statusText.contains("Full", ignoreCase = true) )
                         ) {
            TvType.TvSeries
        } else {
             if (watchButton != null) TvType.Movie else TvType.TvSeries
        }
        
        if (episodeListPageUrl.isNullOrBlank() && tvType == TvType.Movie) { 
             tvType = TvType.TvSeries 
        }

        if (tvType == TvType.Movie) {
            if (episodeListPageUrl.isNullOrBlank()) return null 
            return newMovieLoadResponse(title, url, tvType, episodeListPageUrl) {
                this.posterUrl = posterUrl
                this.year = year
                this.plot = originalPlot
                this.tags = tags
                this.duration = durationInMinutes
                this.rating = rating
                this.recommendations = recommendations
            }
        } else { 
            var debugInfo = "DEBUG INFO TV SERIES:\n"
            debugInfo += "Trang Info URL: $url\n"
            debugInfo += debugFindingWatchButton // Thêm debug của việc tìm watchButton
            debugInfo += "episodeListPageUrl (final result): $episodeListPageUrl\n"

            val episodes = mutableListOf<Episode>()

            if (!episodeListPageUrl.isNullOrBlank()) {
                debugInfo += "Đang thử tải trang DS tập: $episodeListPageUrl\n"
                try {
                    val episodeListHTML = app.get(episodeListPageUrl).text 
                    debugInfo += "HTMLFetched (first 500 chars): ${episodeListHTML.take(500)}\n"
                    val episodeListDocument = Jsoup.parse(episodeListHTML, episodeListPageUrl)
                    
                    val listEpisodesParent = episodeListDocument.selectFirst("div#list-server div.server-group ul#list_episodes")
                    if (listEpisodesParent == null) {
                        debugInfo += "ERROR: KHÔNG TÌM THẤY 'ul#list_episodes' trong 'div#list-server div.server-group'.\n"
                        val listServerDiv = episodeListDocument.selectFirst("div#list-server")
                        debugInfo += "HTML của 'div#list-server' (nếu có): ${listServerDiv?.outerHtml()?.take(500) ?: "KHÔNG TÌM THẤY div#list-server"}\n"
                    } else {
                        debugInfo += "ĐÃ TÌM THẤY 'ul#list_episodes'.\n"
                        val episodeElements = listEpisodesParent.select("li a") 
                        debugInfo += "Số 'li a' tìm thấy: ${episodeElements.size}\n"

                        if (episodeElements.isNotEmpty()) {
                            episodeElements.forEachIndexed { index, epElement ->
                                val epHref = epElement.attr("abs:href")
                                var epName = epElement.ownText().trim() 
                                if (epName.isBlank()) epName = epElement.attr("title").trim()
                                if (epName.isBlank()) epName = epElement.text().trim().ifBlank { "Tập ?" } 
                                
                                if(index < 5) debugInfo += "DebugEp ${index + 1}: Name='$epName', Href='$epHref'\n"
                                
                                var episodeNumber: Int? = null
                                val nameLower = epName.lowercase()
                                val epNumMatch = Regex("""tập\s*(\d+)""").find(nameLower)
                                if (epNumMatch != null) episodeNumber = epNumMatch.groupValues[1].toIntOrNull()
                                
                                if (epHref.isNotBlank()) {
                                    episodes.add(Episode(data = epHref, name = epName, episode = episodeNumber))
                                }
                            }
                            if(episodeElements.size > 5) debugInfo += "...và ${episodeElements.size - 5} tập nữa.\n"
                        } else {
                             debugInfo += "Không có 'li a' trong 'ul#list_episodes'.\n"
                        }
                    }
                } catch (e: Exception) {
                    debugInfo += "EXCEPTION khi tải/parse DS tập: ${e.message?.take(100)}\n"
                }
            } else {
                debugInfo += "episodeListPageUrl RỖNG sau khi thử tìm watchButton.\n"
            }

            if (episodes.isEmpty()) {
                val placeholderName = if (!debugInfo.contains("episodeListPageUrl RỖNG")) 
                                        "Lỗi DS tập - Xem Debug Plot" 
                                      else 
                                        "Ko thấy link Xem Phim - Xem Debug Plot"
                episodes.add(
                    Episode(
                        data = episodeListPageUrl ?: url, 
                        name = placeholderName
                    )
                )
            }
            
            val finalPlot = (originalPlot ?: "Không có mô tả.") + "\n\n--- THÔNG TIN GỠ LỖI (DEBUG INFO) ---\n" + debugInfo

            return newTvSeriesLoadResponse(title, url, tvType, episodes) {
                this.posterUrl = posterUrl
                this.year = year
                this.plot = finalPlot 
                this.tags = tags
                this.duration = durationInMinutes 
                this.rating = rating
                this.recommendations = recommendations
            }
        }
    }
    // override suspend fun loadLinks(...)
}
