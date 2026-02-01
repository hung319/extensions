package recloudstream

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Element
import com.lagradost.cloudstream3.mvvm.logError

class BFlixProvider : MainAPI() {
    override var mainUrl = "https://bflix.sh"
    override var name = "BFlix"
    override val hasMainPage = true
    override var lang = "en"
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)

    private val TAG = "BFlixDebug"

    // Hàm log tiện ích
    private fun debugLog(message: String) {
        println("[$TAG] $message")
    }

    private val commonHeaders = mapOf(
        "User-Agent" to "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/137.0.0.0 Mobile Safari/537.36",
        "X-Requested-With" to "XMLHttpRequest",
        "Referer" to "$mainUrl/"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        debugLog("Fetching Main Page: $mainUrl")
        try {
            val document = app.get(mainUrl, headers = commonHeaders).document
            val homeSets = mutableListOf<HomePageList>()

            // 1. Trending (Swiper)
            val trendingElements = document.select(".swiper-slide .swiper-inner")
            debugLog("Found ${trendingElements.size} trending items")
            
            if (trendingElements.isNotEmpty()) {
                val trendingList = trendingElements.mapNotNull { it.toSearchResponse() }
                homeSets.add(HomePageList("Trending", trendingList))
            }

            // 2. Các mục theo Zone (Latest Movies, TV Series...)
            val zones = document.select(".zone")
            debugLog("Found ${zones.size} zones")

            zones.forEach { zone ->
                val title = zone.select(".zone-title").text().trim()
                val elements = zone.select(".film")
                
                debugLog("Zone '$title' has ${elements.size} items")

                if (title.isNotEmpty() && elements.isNotEmpty() && !title.contains("Comment")) {
                    val list = elements.mapNotNull { it.toSearchResponse() }
                    homeSets.add(HomePageList(title, list))
                }
            }

            if (homeSets.isEmpty()) {
                debugLog("Warning: No homepage items found! Check selectors or Cloudflare.")
            }

            return newHomePageResponse(homeSets)
        } catch (e: Exception) {
            logError(e)
            debugLog("Error getMainPage: ${e.message}")
            return newHomePageResponse(emptyList())
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val url = "$mainUrl/search?keyword=$query"
        debugLog("Searching: $url")
        val document = app.get(url, headers = commonHeaders).document
        return document.select(".film").mapNotNull { it.toSearchResponse() }
    }

    private fun Element.toSearchResponse(): SearchResponse? {
        val url = this.selectFirst("a")?.attr("href") ?: return null
        val title = this.selectFirst(".film-name")?.text()?.trim() ?: return null
        
        // Fix ảnh: ưu tiên data-src (lazy load) sau đó đến src
        val poster = this.selectFirst("img")?.let { img ->
            val dataSrc = img.attr("data-src")
            val src = img.attr("src")
            if (dataSrc.isNotEmpty()) dataSrc else src
        }?.replace("w185", "w300")

        // Xác định loại phim
        val isTv = url.contains("/series/") || 
                   this.select(".film-meta .end").text().contains("TV", true) ||
                   this.select(".film-label").text().contains("TV", true)

        return if (isTv) {
            newTvSeriesSearchResponse(title, url, TvType.TvSeries) {
                this.posterUrl = poster
            }
        } else {
            newMovieSearchResponse(title, url, TvType.Movie) {
                this.posterUrl = poster
            }
        }
    }

    override suspend fun load(url: String): LoadResponse? {
        debugLog("Loading details: $url")
        val document = app.get(url, headers = commonHeaders).document

        val title = document.selectFirst("h1.film-title")?.text()?.trim() ?: return null
        val desc = document.selectFirst(".film-desc")?.text()?.trim()
        val poster = document.selectFirst(".film-poster img")?.attr("src")?.replace("w185", "original")
        val bgStyle = document.selectFirst(".film-background")?.attr("style")
        val background = bgStyle?.substringAfter("url(")?.substringBefore(")")

        val tags = document.select(".film-meta div").map { it.text() }
        val year = tags.firstOrNull { it.contains("Year:") }?.substringAfter("Year:")?.trim()?.toIntOrNull()

        // === RECOMMENDATIONS (You may also like) ===
        // Thường nằm trong .zone ở trang chi tiết, nhưng cần lọc để tránh trùng với header/footer
        val recommendations = document.select(".zone .film").mapNotNull { it.toSearchResponse() }
        debugLog("Found ${recommendations.size} recommendations")

        val isTv = url.contains("/series/")

        if (isTv) {
            val episodes = mutableListOf<Episode>()
            val scriptContent = document.select("script").html()

            // Regex tìm episode hash hoặc url ajax
            // Pattern 1: ajax.php?episode=...
            // Pattern 2: ajax.php?vds=...
            val episodeAjaxUrl = Regex("""['"]([^"']*ajax\.php\?episode=[^"']*)['"]""").find(scriptContent)?.groupValues?.get(1)
                ?: Regex("""['"]([^"']*ajax\.php\?vds=[^"']*)['"]""").find(scriptContent)?.groupValues?.get(1)
            
            debugLog("TV Series Ajax URL match: $episodeAjaxUrl")

            if (episodeAjaxUrl != null) {
                val fullUrl = if (episodeAjaxUrl.startsWith("http")) episodeAjaxUrl else "$mainUrl$episodeAjaxUrl"
                
                try {
                    val responseHtml = app.get(fullUrl, headers = commonHeaders).text
                    val epDoc = org.jsoup.Jsoup.parse(responseHtml)
                    
                    epDoc.select("li a").forEach { aTag ->
                        val epUrl = aTag.attr("href")
                        val epName = aTag.select("span").joinToString(" ") { it.text() }
                        
                        // Chỉ lấy link dẫn tới series/episode cụ thể
                        if (epUrl.contains("/series/")) {
                            episodes.add(newEpisode(epUrl) {
                                this.name = epName
                            })
                        }
                    }
                    debugLog("Parsed ${episodes.size} episodes")
                } catch (e: Exception) {
                    debugLog("Error loading episodes: ${e.message}")
                }
            }

            return newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
                this.posterUrl = poster
                this.backgroundPosterUrl = background
                this.plot = desc
                this.year = year
                this.recommendations = recommendations
            }
        } else {
            return newMovieLoadResponse(title, url, TvType.Movie, url) {
                this.posterUrl = poster
                this.backgroundPosterUrl = background
                this.plot = desc
                this.year = year
                this.recommendations = recommendations
            }
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        debugLog("Loading links for data: $data")
        
        try {
            val document = app.get(data, headers = commonHeaders).document
            val scriptContent = document.select("script").html()

            // 1. Tìm Hash (vdkz hoặc episode hash)
            // Regex được mở rộng để bắt nhiều trường hợp hơn
            val vdkzMatch = Regex("""vdkz\s*[:=]\s*['"]([^'"]+)['"]""").find(scriptContent)?.groupValues?.get(1)
                ?: Regex("""vdkz=([^'&"]+)""").find(scriptContent)?.groupValues?.get(1)
            
            val episodeMatch = Regex("""episode\s*[:=]\s*['"]([^'"]+)['"]""").find(scriptContent)?.groupValues?.get(1)

            // Ưu tiên vdkz cho Movie, episode cho TV (nhưng site này khá lộn xộn, nên ta thử cả hai)
            val hashesToCheck = listOfNotNull(vdkzMatch, episodeMatch, 
                Regex("""['"]([^"']*ajax\.php\?vdkz=[^"']*)['"]""").find(scriptContent)?.groupValues?.get(1)
            ).distinct()

            debugLog("Found potential hashes: $hashesToCheck")

            if (hashesToCheck.isEmpty()) {
                debugLog("Error: No hash found in script content.")
                return false
            }

            var linksFound = false

            // Thử từng hash tìm được
            for (hash in hashesToCheck) {
                // Xử lý nếu hash là URL đầy đủ
                val ajaxUrl = if (hash.startsWith("http")) {
                    hash
                } else {
                    // Nếu hash ngắn, thử ghép vào vdkz, nếu thất bại thử episode
                     "$mainUrl/ajax/ajax.php?vdkz=$hash"
                }
                
                debugLog("Requesting AJAX: $ajaxUrl")
                
                try {
                    val serverResponse = app.get(ajaxUrl, headers = commonHeaders)
                    val serverResponseHtml = serverResponse.text
                    debugLog("AJAX Response Code: ${serverResponse.code}")
                    // debugLog("AJAX Content: $serverResponseHtml") // Uncomment nếu cần xem full html

                    val serverDoc = org.jsoup.Jsoup.parse(serverResponseHtml)
                    val serverItems = serverDoc.select(".sv-item, .server-item") // Backup selector
                    
                    if (serverItems.isEmpty()) {
                        debugLog("No server items found in response.")
                        // Fallback: Thử gọi lại với param 'episode' nếu URL trước đó là 'vdkz' và ngược lại?
                        // Ở đây ta đơn giản hóa là loop tiếp hash khác nếu có.
                        continue
                    }

                    serverItems.forEach { server ->
                        val serverName = server.select("div > div, span").text()
                        val embedUrl = server.attr("data-id") // Target chính
                        
                        debugLog("Server: $serverName | URL: $embedUrl")

                        if (embedUrl.isNotBlank()) {
                            linksFound = true
                            loadExtractor(
                                url = embedUrl,
                                referer = "$mainUrl/", // Quan trọng
                                subtitleCallback = subtitleCallback,
                                callback = callback
                            )
                        }
                    }
                    
                    if (linksFound) break // Nếu đã tìm thấy link ở hash này rồi thì stop
                    
                } catch (e: Exception) {
                    debugLog("Error fetching AJAX for hash $hash: ${e.message}")
                }
            }
            
            return linksFound

        } catch (e: Exception) {
            logError(e)
            debugLog("Critical error in loadLinks: ${e.message}")
            return false
        }
    }
}
