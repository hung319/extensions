// Provider for HoatHinhQQ
package recloudstream

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import org.jsoup.nodes.Element

class HoatHinhQQProvider : MainAPI() {
    // Basic provider information
    override var mainUrl = "https://hoathinhqq.com"
    override var name = "HoatHinhQQ"
    override val hasMainPage = true
    override var lang = "vi"
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(
        TvType.Cartoon
    )

    // Function to get the homepage with correct pagination
    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val lists = ArrayList<HomePageList>()
        var hasNextPage = false

        if (page <= 1) {
            val document = app.get("$mainUrl/").document
            val sections = document.select("div.w-full.lg\\:w-3\\/4")

            sections.forEach { section ->
                val header = section.selectFirst("div.gradient-title h3")?.text() ?: "Unknown Section"
                val movies = section.select("div.grid > a").mapNotNull { it.toSearchResult() }
                if (movies.isNotEmpty()) {
                    lists.add(HomePageList(header, movies))
                }
            }
            hasNextPage = document.select("ul.pagination a:contains(>)").isNotEmpty()
        } else {
            val document = app.get("$mainUrl/phim?page=$page").document
            val movies = document.select("div.grid > a").mapNotNull { it.toSearchResult() }
            if (movies.isNotEmpty()) {
                lists.add(HomePageList("Phim Mới Cập Nhật (Trang $page)", movies))
            }
            hasNextPage = document.select("ul.pagination a:contains(>)").isNotEmpty()
        }

        if (lists.isEmpty()) throw ErrorLoadingException("Không tìm thấy dữ liệu trang chủ")

        return HomePageResponse(lists, hasNext = hasNextPage)
    }

    // Helper function to parse search results from an element
    private fun Element.toSearchResult(): SearchResponse? {
        val href = this.attr("href")
        if (href.isBlank()) return null
        
        val title = this.selectFirst("h3.capitalize")?.text() ?: return null
        val posterUrl = this.selectFirst("img")?.attr("srcset")?.substringBefore(" ") ?: this.selectFirst("img")?.attr("src")
        val latestEp = this.selectFirst("div.absolute.top-0.left-0 > div")?.text()

        return newAnimeSearchResponse(title, "$mainUrl$href", TvType.Cartoon) {
            this.posterUrl = posterUrl
            addDubStatus(false, latestEp?.contains("Tập") == true)
        }
    }
    
    // Function to search for content
    override suspend fun search(query: String): List<SearchResponse> {
        val url = "$mainUrl/tim-kiem/$query"
        val document = app.get(url).document

        return document.select("div.grid > a").mapNotNull {
            it.toSearchResult()
        }
    }
    
    // Function to load movie/series details
    override suspend fun load(url: String): LoadResponse {
        val document = app.get(url).document

        // FIX: Use meta tags for more reliable data extraction
        val title = document.selectFirst("meta[property=og:title]")?.attr("content")?.replace(" - HoatHinhQQ", "")
            ?: "Không tìm thấy tiêu đề"
        val poster = document.selectFirst("meta[property=og:image]")?.attr("content")
        val description = document.selectFirst("meta[property=og:description]")?.attr("content")
        
        val year = document.select("li.film-info-list:contains(Năm) div.flex-row p.text-sm")?.text()?.toIntOrNull()

        val episodes = document.select("div[class*='max-h'] ul.grid li a").mapNotNull { aTag ->
            val epHref = aTag.attr("href")
            val epName = aTag.text().trim() // e.g., "Tập 54"
            
            // Set episode parameter to null to disable UI's number extraction
            Episode(
                data = "$mainUrl$epHref", 
                name = epName, 
                episode = 0 // Vô hiệu hóa epNum
            )
        }.reversed()
        
        val isMovie = episodes.isEmpty()
        
        return if (isMovie) {
             newMovieLoadResponse(title, url, TvType.Cartoon, dataUrl = url) {
                this.posterUrl = poster
                this.year = year
                this.plot = description
                this.tags = document.select("li.film-info-list:contains(Thể loại) a").map { it.text() }
            }
        } else {
             newTvSeriesLoadResponse(title, url, TvType.Cartoon, episodes) {
                this.posterUrl = poster
                this.year = year
                this.plot = description
                this.tags = document.select("li.film-info-list:contains(Thể loại) a").map { it.text() }
            }
        }
    }
    
    // Function to load video links for an episode
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val m3u8Regex = """https?://[^\s"'<>]+\.m3u8""".toRegex()
        val document = app.get(data).document.html()

        m3u8Regex.find(document)?.let { matchResult ->
            val m3u8Url = matchResult.value
            callback.invoke(
                ExtractorLink(
                    source = this.name,
                    name = this.name,
                    url = m3u8Url,
                    referer = "$mainUrl/",
                    quality = Qualities.Unknown.value, 
                    type = ExtractorLinkType.M3U8
                )
            )
            return true
        }

        return false
    }
}
