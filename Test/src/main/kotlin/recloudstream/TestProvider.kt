package com.phimmoichillprovider // Hoặc tên package của bạn

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.Jsoup // Thêm import cho Jsoup nếu chưa có ở đầu file
import org.jsoup.nodes.Element
// import com.lagradost.cloudstream3.utils.AppUtils.toJson // Cho Log nếu cần
// import android.util.Log // Cho Log nếu chạy trên Android

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
            if (!episodeListPageUrl.isNullOrBlank()) {
                try {
                    val episodeListHTML = app.get(episodeListPageUrl).text 
                    // --- GỢI Ý LOGGING QUAN TRỌNG ---
                    // Nếu bạn có thể xem log, hãy in ra một phần HTML này để kiểm tra:
                    // Ví dụ: Log.d("PhimMoiChillProvider", "HTML trang danh sách tập (2000 ký tự đầu): ${episodeListHTML.take(2000)}")
                    // Hoặc cụ thể hơn, tìm xem thẻ ul#list_episodes có tồn tại không:
                    // val listEpisodesHtml = Jsoup.parse(episodeListHTML).selectFirst("ul#list_episodes")?.outerHtml()
                    // Log.d("PhimMoiChillProvider", "HTML của ul#list_episodes: $listEpisodesHtml")
                    // --- KẾT THÚC GỢI Ý LOGGING ---

                    val episodeListDocument = Jsoup.parse(episodeListHTML, episodeListPageUrl) // Parse với base URI để abs:href hoạt động đúng

                    // Selector dựa trên thông tin bạn cung cấp: <ul class="episodes" id="list_episodes"> <li><a>...</a></li> ... </ul>
                    val episodeElements = episodeListDocument.select("ul#list_episodes li a")
                    
                    // --- GỢI Ý LOGGING ---
                    // Log.d("PhimMoiChillProvider", "Tìm thấy ${episodeElements.size} phần tử tập phim với selector 'ul#list_episodes li a'")
                    // --- KẾT THÚC GỢI Ý LOGGING ---

                    if (episodeElements.isNotEmpty()) {
                        // episodes.clear() // Không cần thiết nếu episodes được khởi tạo rỗng ở trên
                        episodeElements.forEach { epElement ->
                            val epHref = epElement.attr("abs:href")
                            // Ưu tiên lấy text trực tiếp của thẻ <a>, tránh text của thẻ con nếu có
                            var epName = epElement.ownText().trim() 
                            if (epName.isBlank()) { // Nếu ownText rỗng, thử lấy từ attribute title
                                epName = epElement.attr("title").trim()
                            }
                            if (epName.isBlank()) { // Nếu vẫn rỗng, thử lấy toàn bộ text của thẻ a
                                epName = epElement.text().trim().ifBlank { "Tập ?" } // Fallback cuối cùng
                            }
                            
                            var episodeNumber: Int? = null
                            val nameLower = epName.lowercase()
                            // Trích xuất số tập từ tên, ví dụ "Tập 1", "Tập 10 - Cuối"
                            val epNumMatch = Regex("""tập\s*(\d+)""").find(nameLower)
                            if (epNumMatch != null) {
                                episodeNumber = epNumMatch.groupValues[1].toIntOrNull()
                            }
                            
                            // --- GỢI Ý LOGGING ---
                            // Log.d("PhimMoiChillProvider", "Đã parse tập: Tên='$epName', Href='$epHref', Số tập='$episodeNumber'")
                            // --- KẾT THÚC GỢI Ý LOGGING ---

                            if (epHref.isNotBlank()) { // Cần có href, tên có thể là "?"
                                episodes.add(
                                    Episode(
                                        data = epHref,
                                        name = epName,
                                        episode = episodeNumber,
                                        // season = N/A từ HTML này
                                    )
                                )
                            }
                        }
                    } else {
                        // --- GỢI Ý LOGGING ---
                        // Log.d("PhimMoiChillProvider", "Selector 'ul#list_episodes li a' không tìm thấy phần tử nào trên trang: $episodeListPageUrl")
                        // --- KẾT THÚC GỢI Ý LOGGING ---
                    }
                } catch (e: Exception) {
                    // --- GỢI Ý LOGGING ---
                    // Log.e("PhimMoiChillProvider", "Lỗi khi tải/phân tích trang danh sách tập từ $episodeListPageUrl: ${e.message}")
                    // e.printStackTrace() // Nếu có thể xem stack trace
                    // --- KẾT THÚC GỢI Ý LOGGING ---
                }
            }

            if (episodes.isEmpty() && !episodeListPageUrl.isNullOrBlank()) {
                // --- GỢI Ý LOGGING ---
                // Log.d("PhimMoiChillProvider", "Không parse được tập nào, tạo tập giữ chỗ.")
                // --- KẾT THÚC GỢI Ý LOGGING ---
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
    // Hàm loadLinks sẽ được triển khai sau
}
