// Save this file as HHTQProvider.kt
package recloudstream // Tên package đã được thay đổi

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.M3u8Helper
import org.jsoup.nodes.Element

class HHTQProvider : MainAPI() {
    override var mainUrl = "https://hhtq4k.top"
    override var name = "HHTQ4K"
    override val hasMainPage = true
    override var lang = "vi"
    override val supportedTypes = setOf(
        TvType.Cartoon,
        TvType.Anime,
        TvType.Movie
    )

    override val mainPage = mainPageOf(
        "$mainUrl/phim-moi-cap-nhat/page/" to "Phim Mới Cập Nhật",
        "$mainUrl/the-loai/hoat-hinh-trung-quoc/page/" to "Hoạt Hình Trung Quốc",
        "$mainUrl/the-loai/tien-hiep/page/" to "Tiên Hiệp",
        "$mainUrl/the-loai/huyen-huyen/page/" to "Huyền Huyễn",
    )

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {
        val url = if (page == 1) request.data.removeSuffix("page/") else request.data + page
        val document = app.get(url).document
        val home = document.select("div.halim_box article > div.halim-content > div.movies-list > div.item")
            .mapNotNull { it.toSearchResult() }
        return newHomePageResponse(request.name, home)
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val title = this.selectFirst("p.entry-title a")?.text() ?: return null
        val href = this.selectFirst("a.halim-thumb")?.attr("href") ?: return null
        val posterUrl = this.selectFirst("a.halim-thumb img.lazy")?.attr("data-src")
        val quality = this.selectFirst("span.halim-btn")?.text()
        
        return newMovieSearchResponse(title, href, TvType.Movie) {
            this.posterUrl = posterUrl
            this.quality = getQualityFromString(quality)
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val searchUrl = "$mainUrl/search/$query"
        val document = app.get(searchUrl).document
        return document.select("div.halim-content > div.movies-list > div.item").mapNotNull {
            it.toSearchResult()
        }
    }

    override suspend fun load(url: String): LoadResponse? {
        val document = app.get(url).document
        val title = document.selectFirst("h1.entry-title")?.text()?.trim() ?: return null
        val poster = document.selectFirst("div.movie-thumb img.wp-post-image")?.attr("src")
        val description = document.selectFirst("div.summary-content div.entry-content")?.text()?.trim()

        // Sử dụng newEpisode thay vì constructor Episode()
        val episodes = document.select("div#halim-list-episode ul.halim-list-eps li.halim-episode").mapNotNull {
            val epUrl = it.selectFirst("a")?.attr("href") ?: return@mapNotNull null
            newEpisode(epUrl) {
                name = it.selectFirst("a")?.text()
            }
        }.reversed()

        return newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
            this.posterUrl = poster
            this.plot = description
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        // Step 1: Get the main episode page to find the iframe URL
        val episodePage = app.get(data).document
        val iframeSrc = episodePage.selectFirst("div#halim-player-wrapper iframe")?.attr("src")
            ?: return false

        // Step 2: Get the content of the iframe
        val iframeContent = app.get(iframeSrc, referer = data).text

        // Step 3: Extract the m3u8 link from the iframe's content
        val m3u8Pattern = Regex("""(https?://[^\s'"]+\.m3u8)""")
        val m3u8Link = m3u8Pattern.find(iframeContent)?.value ?: return false
        
        M3u8Helper.generateM3u8(
            this.name,
            m3u8Link,
            referer = mainUrl
        ).forEach(callback)

        return true
    }
}
