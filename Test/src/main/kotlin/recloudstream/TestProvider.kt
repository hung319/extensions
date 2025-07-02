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

    // FIX: Reworked pagination logic to be more robust
    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val lists = ArrayList<HomePageList>()
        val hasNextPage: Boolean

        if (page <= 1) {
            // For page 1, load all sections from the main page
            val document = app.get("$mainUrl/").document
            val sections = document.select("div.w-full.lg\\:w-3\\/4")

            sections.forEach { section ->
                val header = section.selectFirst("div.gradient-title h3")?.text() ?: "Unknown Section"
                val movies = section.select("div.grid > a").mapNotNull { it.toSearchResult() }
                if (movies.isNotEmpty()) {
                    lists.add(HomePageList(header, movies))
                }
            }
            // Check if a link to page 2 exists for the pagination button
            hasNextPage = document.select("ul.pagination a[href='/phim?page=2']").isNotEmpty()
        } else {
            // For page > 1, load only the paginated list
            val document = app.get("$mainUrl/phim?page=$page").document
            val movies = document.select("div.grid > a").mapNotNull { it.toSearchResult() }
            if (movies.isNotEmpty()) {
                lists.add(HomePageList("Phim Mới Cập Nhật (Trang $page)", movies))
            }
            // Check if a link to the next page exists
            hasNextPage = document.select("ul.pagination a[href='/phim?page=${page + 1}']").isNotEmpty()
        }

        if (lists.isEmpty()) throw ErrorLoadingException("Không tìm thấy dữ liệu trang chủ")

        return HomePageResponse(lists, hasNext = hasNextPage)
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val href = this.attr("href")
        if (href.isBlank()) return null
        
        val title = this.selectFirst("h3.capitalize")?.text() ?: return null
        val posterUrl = this.selectFirst("img")?.attr("srcset")?.substringBefore(" ") ?: this.selectFirst("img")?.attr("src")
        
        return newAnimeSearchResponse(title, "$mainUrl$href", TvType.Cartoon) {
            this.posterUrl = posterUrl
        }
    }
    
    override suspend fun search(query: String): List<SearchResponse> {
        val url = "$mainUrl/tim-kiem/$query"
        val document = app.get(url).document

        return document.select("div.grid > a").mapNotNull {
            it.toSearchResult()
        }
    }
    
    override suspend fun load(url: String): LoadResponse {
        val document = app.get(url).document

        val title = document.selectFirst("meta[property=og:title]")?.attr("content")?.replace(" - HoatHinhQQ", "")
            ?: "Không tìm thấy tiêu đề"
        val poster = document.selectFirst("meta[property=og:image]")?.attr("content")
        val description = document.selectFirst("meta[property=og:description]")?.attr("content")
        
        val year = document.select("div.film-info-list-title:contains(Năm) + div")?.text()?.toIntOrNull()

        // FIX: Using newEpisode helper function as seen in the HentaiHaven example
        val episodes = document.selectFirst("div:has(p:contains(Tìm tập nhanh))")
            ?.select("ul > li > a")
            ?.mapNotNull { aTag ->
                val href = aTag.attr("href")
                // Let the helper function handle name and number parsing
                newEpisode(data = "$mainUrl$href") {
                    this.name = aTag.text().trim()
                }
            }?.reversed() ?: emptyList()
        
        // ADD: Placeholder for recommendations
        // When you find a page with recommendations, provide the HTML so I can update the selector below.
        // val recommendations = document.select("your_recommendations_selector_here").mapNotNull { it.toSearchResult() }

        return newTvSeriesLoadResponse(title, url, TvType.Cartoon, episodes) {
            this.posterUrl = poster
            this.year = year
            this.plot = description
            this.tags = document.select("li.film-info-list:contains(Thể loại) a").map { it.text() }
            // this.recommendations = recommendations
        }
    }
    
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
