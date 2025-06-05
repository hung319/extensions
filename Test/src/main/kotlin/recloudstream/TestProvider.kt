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

    // Thêm một User-Agent trình duyệt phổ biến
    private val a = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/108.0.0.0 Safari/537.36"

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
        val document = app.get(mainUrl, headers = mapOf("User-Agent" to a)).document
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
        val document = app.get(searchUrl, headers = mapOf("User-Agent" to a)).document
        return document.select("div#binlist ul.list-film li.item.small").mapNotNull {
            it.toSearchResponseDefault(isSearchPage = true)
        }
    }

    override suspend fun load(url: String): LoadResponse? {
        val document = app.get(url, headers = mapOf("User-Agent" to a)).document 

        val filmInfoImageTextDiv = document.selectFirst("div.film-info div.image div.text")
        val title = filmInfoImageTextDiv?.selectFirst("h1[itemprop=name]")?.text()?.trim()
            ?: document.selectFirst("div.film-info > div.text h1[itemprop=name]")?.text()?.trim() 
            ?: return null
        
        val yearText = filmInfoImageTextDiv?.selectFirst("h2")?.text()
            ?: document.selectFirst("div.film-info > div.text h2")?.text()
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
        
        var debugFindingUrl = "DEBUG Finding URL:\n"
        // --- LOGIC MỚI: Ưu tiên lấy link từ "Tập mới nhất" ---
        // Cách 1: Tìm link từ danh sách tập mới nhất
        var episodeListPageUrl = document.selectFirst("div.latest-episode a")?.attr("abs:href")
        if (episodeListPageUrl != null) {
            debugFindingUrl += "  Found URL from 'div.latest-episode': $episodeListPageUrl\n"
        }

        // Cách 2: Nếu không có, tìm nút "Xem phim"
        if (episodeListPageUrl.isNullOrBlank()) {
            debugFindingUrl += "  'div.latest-episode' not found. Trying 'btn-see' button...\n"
            val watchButton = document.select("div.film-info a.btn-see[href^=/xem/]")
                .firstOrNull { it.text().contains("Xem phim", ignoreCase = true) }
            episodeListPageUrl = watchButton?.attr("abs:href")
            if(watchButton != null) {
                debugFindingUrl += "  Found URL from 'btn-see': $episodeListPageUrl\n"
            } else {
                debugFindingUrl += "  'btn-see' button also not found.\n"
            }
        }
        // --- Kết thúc logic mới ---
        
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
             if (!episodeListPageUrl.isNullOrBlank()) TvType.Movie else TvType.TvSeries
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
            debugInfo += debugFindingUrl
            debugInfo += "episodeListPageUrl (final result): $episodeListPageUrl\n"
            val episodes = mutableListOf<Episode>()

            if (!episodeListPageUrl.isNullOrBlank()) {
                debugInfo += "Đang thử tải trang danh sách tập: $episodeListPageUrl\n"
                try {
                    val episodeListDocument = app.get(episodeListPageUrl, headers = mapOf("User-Agent" to a)).document 
                    
                    val listEpisodesParent = episodeListDocument.selectFirst("div#list-server div.server-group ul#list_episodes")
                    if (listEpisodesParent == null) {
                        debugInfo += "ERROR: KHÔNG TÌM THẤY 'ul#list_episodes'...\n"
                    } else {
                        debugInfo += "ĐÃ TÌM THẤY 'ul#list_episodes'.\n"
                        val episodeElements = listEpisodesParent.select("li a") 
                        debugInfo += "Số 'li a' tìm thấy: ${episodeElements.size}\n"
                        if(episodeElements.isNotEmpty()){
                            // Lấy danh sách tập và đảo ngược lại để có thứ tự từ 1 -> N
                            episodeElements.reversed().forEach { epElement ->
                                val epHref = epElement.attr("abs:href")
                                var epName = epElement.ownText().trim() 
                                if (epName.isBlank()) epName = epElement.attr("title").trim()
                                if (epName.isBlank()) epName = epElement.text().trim().ifBlank { "Tập ?" } 
                                
                                var episodeNumber: Int? = null
                                val nameLower = epName.lowercase()
                                val epNumMatch = Regex("""tập\s*(\d+)""").find(nameLower)
                                if (epNumMatch != null) episodeNumber = epNumMatch.groupValues[1].toIntOrNull()
                                
                                if (epHref.isNotBlank()) {
                                    episodes.add(Episode(data = epHref, name = epName, episode = episodeNumber))
                                }
                            }
                        } else {
                            debugInfo += "Không có 'li a' trong 'ul#list_episodes'.\n"
                        }
                    }
                } catch (e: Exception) {
                    debugInfo += "EXCEPTION khi tải/parse DS tập: ${e.message?.take(100)}\n"
                }
            } else {
                debugInfo += "episodeListPageUrl RỖNG.\n"
            }

            if (episodes.isEmpty()) {
                val placeholderName = "Lỗi/Chưa có DS tập - Xem Debug Plot"
                episodes.add(Episode(data = episodeListPageUrl ?: url, name = placeholderName))
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
