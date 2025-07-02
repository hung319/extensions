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
        TvType.Movie,
        TvType.TvSeries,
        TvType.Cartoon
    )

    // Function to get the homepage with pagination
    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        // Only paginate for the main "phim" page (Newly Updated)
        if (page > 1) {
            val document = app.get("$mainUrl/phim?page=$page").document
            val movies = document.select("div.grid > a").mapNotNull { it.toSearchResult() }
            // Check if there are more movies on the next page to determine hasNextPage
            val hasNextPage = document.select("ul.flex.flex-wrap a[href='/phim?page=${page + 1}']").isNotEmpty()
            return newHomePageResponse(
                HomePageList("Phim Mới Cập Nhật (Trang $page)", movies, hasNextPage = hasNextPage)
            )
        }

        // For the first page, load all sections as before
        val document = app.get("$mainUrl/").document
        val homePageList = ArrayList<HomePageList>()

        val sections = document.select("div.w-full.lg\\:w-3\\/4")
        
        sections.forEach { section ->
            val header = section.selectFirst("div.gradient-title h3")?.text() ?: "Unknown Section"
            // For the first section ("Mới cập nhật"), enable pagination
            val hasNextPage = header.contains("Mới cập nhật")
            val movies = section.select("div.grid > a").mapNotNull { it.toSearchResult() }
            if (movies.isNotEmpty()) {
                homePageList.add(HomePageList(header, movies, hasNextPage = hasNextPage))
            }
        }

        if (homePageList.isEmpty()) throw ErrorLoadingException("Không tìm thấy dữ liệu trang chủ")

        return HomePageResponse(homePageList)
    }

    // Helper function to parse search results from an element
    private fun Element.toSearchResult(): SearchResponse? {
        val href = this.attr("href")
        if (href.isBlank()) return null
        
        val title = this.selectFirst("h3.capitalize")?.text() ?: return null
        // Use srcset for better image quality, fallback to src
        val posterUrl = this.selectFirst("img")?.attr("srcset")?.substringBefore(" ") ?: this.selectFirst("img")?.attr("src")
        val latestEp = this.selectFirst("div.absolute.top-0.left-0 > div")?.text()

        return newAnimeSearchResponse(title, "$mainUrl$href", TvType.TvSeries) {
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

        // FIX: More reliable selectors for title and poster
        val title = document.selectFirst("div.film-info-list:contains(Tên) h1.font-semibold")?.text()
            ?: document.selectFirst("h1.text-lg.text-\\[\\#cf8e19\\]")?.text()
            ?: "Không tìm thấy tiêu đề"

        val poster = document.selectFirst("div.relative.aspect-\\[3\\/4\\] img")?.attr("src")
            ?: document.selectFirst("img[alt=poster]")?.attr("src")
        
        val description = document.selectFirst("div.detail-container p.prose")?.text() 
            ?: document.selectFirst("div[class*=description] p")?.text()

        val year = document.selectFirst("li.film-info-list:contains(Tập mới nhất) + li.film-info-list p.text-sm")?.text()?.toIntOrNull()

        val episodes = document.select("div.max-h-\\[175px\\] ul.grid > li").mapNotNull {
            val epHref = it.selectFirst("a")?.attr("href") ?: return@mapNotNull null
            val epName = it.selectFirst("div.ep-container")?.text()?.let { name ->
                if (name.contains("Tập", true)) name else "Tập $name"
            } ?: "Tập"
            Episode("$mainUrl$epHref", epName)
        }.reversed()

        // Check if it's a TV Series or Movie
        val isMovie = episodes.isEmpty() && document.selectFirst("div:contains(Đang cập nhật…)") != null || document.select("div.ep-container").size <= 1
        
        return if (isMovie) {
             newMovieLoadResponse(title, url, TvType.Movie, dataUrl = url) {
                this.posterUrl = poster
                this.year = year
                this.plot = description
                this.tags = document.select("li.film-info-list:contains(Thể loại) a").map { it.text() }
            }
        } else {
             newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
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
        // Regex to find m3u8 links in the HTML content
        val m3u8Regex = """https?://[^\s"'<>]+\.m3u8""".toRegex()
        
        // Fetch the HTML content of the episode page
        val document = app.get(data).document.html()

        // Find the first m3u8 link using regex
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
