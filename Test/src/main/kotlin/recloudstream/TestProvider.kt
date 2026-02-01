package recloudstream

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.extractors.VidCloud
import com.lagradost.cloudstream3.mvvm.logError
import org.jsoup.nodes.Element

class BFlixProvider : MainAPI() {
    override var mainUrl = "https://bflix.sh"
    override var name = "BFlix"
    override val hasMainPage = true
    override var lang = "en"
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)

    private val TAG = "BFlixDebug"

    // Custom Extractor cho subdrc.xyz (dựa trên VidCloud)
    class Subdrc : VidCloud() {
        override var name = "Subdrc"
        override var mainUrl = "https://subdrc.xyz"
        override val requiresReferer = true
    }

    private val commonHeaders = mapOf(
        "User-Agent" to "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/137.0.0.0 Mobile Safari/537.36",
        "X-Requested-With" to "XMLHttpRequest",
        "Referer" to "$mainUrl/"
    )

    private fun debugLog(message: String) {
        println("[$TAG] $message")
    }

    // Đăng ký Extractor tùy chỉnh khi khởi tạo
    init {
        addPosterVDUrl("subdrc.xyz")
    }
    
    // Hàm phụ trợ để map domain vào VidCloud logic nếu addPosterVDUrl không đủ
    private fun addPosterVDUrl(domain: String) {
        // CloudStream tự động map các domain này nếu Extractor VidCloud được support
        // Nhưng an toàn nhất là ta gọi trực tiếp class Subdrc trong loadLinks
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val items = mutableListOf<HomePageList>()
        
        // 1. Load Movies Page
        try {
            val moviesUrl = "$mainUrl/movies/"
            val moviesDoc = app.get(moviesUrl, headers = commonHeaders).document
            val moviesList = moviesDoc.select(".film").mapNotNull { it.toSearchResponse() }
            if (moviesList.isNotEmpty()) {
                items.add(HomePageList("Latest Movies", moviesList))
            }
        } catch (e: Exception) {
            logError(e)
            debugLog("Error loading Movies: ${e.message}")
        }

        // 2. Load TV Series Page
        try {
            val tvUrl = "$mainUrl/tv-series/"
            val tvDoc = app.get(tvUrl, headers = commonHeaders).document
            val tvList = tvDoc.select(".film").mapNotNull { it.toSearchResponse() }
            if (tvList.isNotEmpty()) {
                items.add(HomePageList("Latest TV Series", tvList))
            }
        } catch (e: Exception) {
            logError(e)
            debugLog("Error loading TV Series: ${e.message}")
        }

        return newHomePageResponse(items)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val url = "$mainUrl/search?keyword=$query"
        return try {
            val document = app.get(url, headers = commonHeaders).document
            document.select(".film").mapNotNull { it.toSearchResponse() }
        } catch (e: Exception) {
            emptyList()
        }
    }

    private fun Element.toSearchResponse(): SearchResponse? {
        // Selector dựa trên file bflix.sh_movies.html
        // <a href="..." class="film-name">Name</a>
        // <div class="film-meta"> ... </div>
        
        val nameElement = this.selectFirst(".film-name")
        val title = nameElement?.text()?.trim() ?: return null
        val url = nameElement.attr("href")
        
        if (url.isEmpty()) return null

        val posterElement = this.selectFirst(".film-poster img")
        val poster = posterElement?.attr("src")
            ?: posterElement?.attr("data-src") // Fallback
        
        // Fix link ảnh TMDB w185 -> w300
        val finalPoster = poster?.replace("/w185/", "/w300/")

        // Check Type
        val metaText = this.select(".film-meta").text()
        val isTv = url.contains("/series/") || metaText.contains("SS") || metaText.contains("EP")

        return if (isTv) {
            newTvSeriesSearchResponse(title, url, TvType.TvSeries) {
                this.posterUrl = finalPoster
            }
        } else {
            newMovieSearchResponse(title, url, TvType.Movie) {
                this.posterUrl = finalPoster
            }
        }
    }

    override suspend fun load(url: String): LoadResponse? {
        debugLog("Loading details: $url")
        val document = app.get(url, headers = commonHeaders).document

        val title = document.selectFirst("h1.film-title")?.text()?.trim() ?: return null
        val desc = document.selectFirst(".film-desc")?.text()?.trim()
        val poster = document.selectFirst(".film-poster img")?.attr("src")?.replace("w185", "original")
        
        // Background từ style="background-image: url(...)"
        val bgStyle = document.selectFirst(".film-background")?.attr("style")
        val background = bgStyle?.substringAfter("url(")?.substringBefore(")")

        val tags = document.select(".film-meta div").map { it.text() }
        val year = tags.firstOrNull { it.contains("Year") || it.matches(Regex("\\d{4}")) }?.replace("Year:", "")?.trim()?.toIntOrNull()

        // Recommendations (Suggestions)
        val recommendations = document.select(".site-sidebar .film").mapNotNull { it.toSearchResponse() }

        val isTv = url.contains("/series/")

        if (isTv) {
            val episodes = mutableListOf<Episode>()
            val scriptContent = document.select("script").html()

            // Logic lấy AJAX cho TV Series
            val ajaxUrlMatches = Regex("""['"]([^"']*ajax\.php\?episode=[^"']*)['"]""").find(scriptContent)
                ?: Regex("""['"]([^"']*ajax\.php\?vds=[^"']*)['"]""").find(scriptContent)
            
            val episodeAjaxUrl = ajaxUrlMatches?.groupValues?.get(1)

            if (episodeAjaxUrl != null) {
                val fullUrl = if (episodeAjaxUrl.startsWith("http")) episodeAjaxUrl else "$mainUrl$episodeAjaxUrl"
                
                try {
                    val responseHtml = app.get(fullUrl, headers = commonHeaders).text
                    val epDoc = org.jsoup.Jsoup.parse(responseHtml)
                    
                    epDoc.select("li a").forEach { aTag ->
                        val epUrl = aTag.attr("href")
                        val epName = aTag.select("span").joinToString(" ") { it.text() }
                        
                        if (epUrl.contains("/series/")) {
                            episodes.add(newEpisode(epUrl) {
                                this.name = epName
                            })
                        }
                    }
                } catch (e: Exception) {
                    debugLog("Error parsing episodes: ${e.message}")
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
        debugLog("Loading links: $data")
        val document = app.get(data, headers = commonHeaders).document
        val scriptContent = document.select("script").html()

        // Tìm Hash vdkz
        val hash = Regex("""vdkz\s*[:=]\s*['"]([^'"]+)['"]""").find(scriptContent)?.groupValues?.get(1)
            ?: Regex("""vdkz=([^'&"]+)""").find(scriptContent)?.groupValues?.get(1)
            ?: Regex("""episode\s*[:=]\s*['"]([^'"]+)['"]""").find(scriptContent)?.groupValues?.get(1)

        if (hash != null) {
            val ajaxUrl = if (hash.startsWith("http")) hash else "$mainUrl/ajax/ajax.php?vdkz=$hash"
            
            try {
                val response = app.get(ajaxUrl, headers = commonHeaders).text
                val serverDoc = org.jsoup.Jsoup.parse(response)
                var foundLinks = false

                serverDoc.select(".sv-item, .server-item").forEach { server ->
                    val embedUrl = server.attr("data-id")
                    debugLog("Found embed URL: $embedUrl")

                    if (embedUrl.isNotBlank()) {
                        foundLinks = true
                        
                        // Xử lý đặc biệt cho Subdrc (VidCloud clone)
                        if (embedUrl.contains("subdrc.xyz") || embedUrl.contains("f16px.com")) {
                            // Gọi trực tiếp Extractor của chúng ta
                            Subdrc().getSafeUrl(embedUrl, "$mainUrl/", subtitleCallback, callback)
                        } else {
                            // Link khác (Vidstream, MegaUp...) dùng hệ thống chung
                            loadExtractor(
                                url = embedUrl,
                                referer = "$mainUrl/",
                                subtitleCallback = subtitleCallback,
                                callback = callback
                            )
                        }
                    }
                }
                return foundLinks
            } catch (e: Exception) {
                logError(e)
                debugLog("Error in loadLinks AJAX: ${e.message}")
            }
        }
        return false
    }
}
