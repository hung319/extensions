// Provider for HoatHinhQQ
package recloudstream 

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities // FIX: Changed import path to utils
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

    // Function to get the homepage
    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val document = app.get("$mainUrl/phim?page=$page").document
        val homePageList = ArrayList<HomePageList>()

        // Find all sections on the main page
        val sections = document.select("div.w-full.lg\\:w-3\\/4")
        
        sections.forEach { section ->
            val header = section.selectFirst("div.gradient-title h3")?.text() ?: "Unknown Section"
            val movies = section.select("div.grid > a").mapNotNull { it.toSearchResult() }
            if (movies.isNotEmpty()) {
                homePageList.add(HomePageList(header, movies))
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
        val posterUrl = this.selectFirst("img")?.attr("srcset")?.substringBefore(" ")
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

        val title = document.selectFirst("h1.text-lg.text-[#cf8e19]")?.text() ?: "Không tìm thấy tiêu đề"
        val poster = document.selectFirst("div.relative.aspect-\\[3\\/4\\] img")?.attr("src")
        val description = document.selectFirst("p.prose.prose-sm")?.text()
        val year = document.select("div.flex.flex-row.items-center.gap-1 p.text-sm").getOrNull(0)?.text()?.toIntOrNull()
        
        val tvType = if (document.select("div.ep-container").size > 1) TvType.TvSeries else TvType.Movie
        
        val episodes = document.select("div.mt-4 ul.grid > li").mapNotNull {
            val epHref = it.selectFirst("a")?.attr("href") ?: return@mapNotNull null
            val epName = it.selectFirst("div.ep-container")?.text()?.let { name ->
                if (name.contains("Tập")) name else "Tập $name"
            } ?: "Tập"
            Episode("$mainUrl$epHref", epName)
        }.reversed()

        return newTvSeriesLoadResponse(title, url, tvType, episodes) {
            this.posterUrl = poster
            this.year = year
            this.plot = description
            this.tags = document.select("li.film-info-list a.bg-\\[\\#333940\\]").map { it.text() }
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
