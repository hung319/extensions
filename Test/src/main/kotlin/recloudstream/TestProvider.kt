package recloudstream

import android.util.Base64
import com.lagradost.cloudstream3.* // Wildcard: MainAPI, TvType, SearchResponse, HomePageResponse...
import com.lagradost.cloudstream3.utils.* // Wildcard: ExtractorLink, ExtractorLinkType, newExtractorLink, etc.
import org.jsoup.nodes.Element

class SexxTrungQuoc : MainAPI() {
    override var mainUrl = "https://sexxtrungquoc.vip"
    override var name = "SexxTrungQuoc"
    override val hasMainPage = true
    override var lang = "vi"
    
    // Website nội dung người lớn
    override val supportedTypes = setOf(TvType.NSFW) 

    override val mainPage = mainPageOf(
        "$mainUrl/" to "Mới Nhất",
        "$mainUrl/category/phim-sex-trung-quoc/" to "Trung Quốc",
        "$mainUrl/category/phim-sex-hong-kong/" to "Hồng Kông",
        "$mainUrl/category/phim-sex-vietsub/" to "Vietsub",
        "$mainUrl/category/phim-sex-khong-che/" to "Không Che",
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        // Pagination: /page/2/
        val url = if (page == 1) request.data else "${request.data}page/$page/"
        val document = app.get(url).document
        
        val home = document.select("ul.list-movies li.item-movie").mapNotNull {
            it.toSearchResult()
        }
        
        return newHomePageResponse(request.name, home)
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val linkElement = this.selectFirst("a") ?: return null
        val title = this.selectFirst(".title-movie")?.text() ?: linkElement.attr("title")
        val href = fixUrl(linkElement.attr("href"))
        val img = this.selectFirst("img")?.attr("src") ?: ""
        val quality = this.selectFirst(".label")?.text()

        return newMovieSearchResponse(title, href, TvType.NSFW) {
            this.posterUrl = img
            this.quality = getQualityFromString(quality)
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val url = "$mainUrl/?s=$query"
        val document = app.get(url).document
        
        return document.select("ul.list-movies li.item-movie").mapNotNull {
            it.toSearchResult()
        }
    }

    override suspend fun load(url: String): LoadResponse {
        val document = app.get(url).document

        val title = document.selectFirst("h1.single-title")?.text()?.trim() ?: ""
        val description = document.select(".entry-content p").text().trim()
        val poster = document.selectFirst("meta[property=og:image]")?.attr("content")
        val tags = document.select("#extras a").map { it.text() }
        
        val recommendations = document.select(".tab-movies1 .list-movies .item-movie").mapNotNull {
            it.toSearchResult()
        }

        return newMovieLoadResponse(title, url, TvType.NSFW, url) {
            this.posterUrl = poster
            this.plot = description
            this.tags = tags
            this.recommendations = recommendations
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val document = app.get(data).document

        // Tìm các script base64 chứa link video
        val scriptSrcs = document.select("script[src^='data:text/javascript;base64,']")
        
        var videoUrl: String? = null

        for (script in scriptSrcs) {
            try {
                // Decode Base64 từ src
                val base64Data = script.attr("src").substringAfter("base64,")
                val decoded = String(Base64.decode(base64Data, Base64.DEFAULT))
                
                // Regex tìm biến video_url
                val regex = """var\s+video_url\s*=\s*['"]([^'"]+)['"]""".toRegex()
                val match = regex.find(decoded)
                
                if (match != null) {
                    videoUrl = match.groupValues[1]
                    break 
                }
            } catch (e: Exception) {
                continue
            }
        }

        if (videoUrl != null && videoUrl.isNotEmpty()) {
            callback(
                newExtractorLink(
                    source = name,
                    name = "$name HD",
                    url = videoUrl,
                    type = ExtractorLinkType.M3U8
                ) {
                    referer = "$mainUrl/" 
                }
            )
            return true
        }

        return false
    }
}
