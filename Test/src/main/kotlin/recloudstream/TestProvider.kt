// TvPhimProvider.kt

package recloudstream

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Element
import com.lagradost.cloudstream3.network.CloudflareKiller
import com.lagradost.cloudstream3.ActorData

class TvPhimProvider : MainAPI() {
    override var mainUrl = "https://tvphim.bid"
    override var name = "TVPhim"
    override val hasMainPage = true
    override var lang = "vi"
    override val supportedTypes = setOf(
        TvType.Movie,
        TvType.TvSeries
    )

    // Interceptor to handle Cloudflare
    private val cfInterceptor = CloudflareKiller()

    // Helper function to parse search results from HTML elements
    private fun Element.toSearchResponse(): SearchResponse? {
        val title = this.selectFirst("div.h-14 span")?.text()?.trim() ?: return null
        val href = this.selectFirst("a")?.attr("href") ?: return null
        val posterUrl = this.selectFirst("img")?.attr("src")
        val year = this.selectFirst("div.year")?.text()?.toIntOrNull()

        // Determine if it's a TV Series or a Movie based on the episode status text
        val statusText = this.selectFirst("span.bg-red-800")?.text() ?: ""
        val isTvSeries = "/" in statusText || "tập" in statusText.lowercase()

        return if (isTvSeries) {
            newTvSeriesSearchResponse(title, href) {
                this.posterUrl = posterUrl
                this.year = year
            }
        } else {
            newMovieSearchResponse(title, href) {
                this.posterUrl = posterUrl
                this.year = year
            }
        }
    }

    override val mainPage = mainPageOf(
        "$mainUrl/phim-le/" to "Phim Lẻ Mới",
        "$mainUrl/phim-bo/" to "Phim Bộ Mới",
        "$mainUrl/phim-hay/" to "Phim Hay Đề Cử",
    )

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {
        val url = if (page == 1) request.data else "${request.data}page/$page/"
        val document = app.get(url, interceptor = cfInterceptor).document
        val home = document.select("div.grid div.relative").mapNotNull {
            it.toSearchResponse()
        }
        return newHomePageResponse(request.name, home)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val searchUrl = "$mainUrl/search/$query/"
        val document = app.get(searchUrl, interceptor = cfInterceptor).document
        return document.select("div.grid div.relative").mapNotNull {
            it.toSearchResponse()
        }
    }

    override suspend fun load(url: String): LoadResponse {
        val document = app.get(url, interceptor = cfInterceptor).document

        val title = document.selectFirst("h1[itemprop=name]")?.text()?.trim()
            ?: document.selectFirst("h1.entry-title")?.text()?.trim() ?: "Không tìm thấy tiêu đề"

        val poster = document.selectFirst("img[itemprop=image]")?.attr("src")
        val year = document.selectFirst("span.text-gray-400:contains(Năm Sản Xuất) + span")?.text()?.toIntOrNull()
        
        // Get plot and votes
        var plot = document.selectFirst("div[itemprop=description] p")?.text()
            ?: document.selectFirst("div.entry-content p")?.text()
        val votes = document.selectFirst("span.liked")?.text()?.trim()?.toIntOrNull()

        // Append votes to plot if available
        if (votes != null) {
            plot = "$plot\n\n❤️ Yêu thích: $votes"
        }

        val tags = document.select("span.text-gray-400:contains(Thể loại) a").map { it.text() }
        val actors = document.select("span.text-gray-400:contains(Diễn viên) a").map { it.text() }
        
        // Recommendations
        val recommendations = document.select("div.mt-2:has(span:contains(Cùng Series)) a").mapNotNull {
            val recTitle = it.selectFirst("span")?.text()
            val recHref = it.attr("href")
            val recPoster = it.selectFirst("img")?.attr("src")
            if (recTitle != null && recHref.isNotBlank()) {
                newMovieSearchResponse(recTitle, recHref) {
                    this.posterUrl = recPoster
                }
            } else null
        }

        // Check if the content is a TV Series based on status text
        val status = document.selectFirst("span:contains(Tình Trạng:) + span")?.text()?.lowercase() ?: ""
        val isTvSeries = status.contains("/") || status.contains("tập")

        if (isTvSeries) {
            // **FIXED:** Use a more specific selector for episodes
            val episodes = document.select("span:contains(Tập Mới Nhất) + div a").mapNotNull { ep ->
                val epName = ep.attr("title").ifBlank { ep.text() }
                val epUrl = ep.attr("href")
                if (epUrl.isBlank()) return@mapNotNull null
                newEpisode(epUrl) {
                    name = epName
                }
            }.reversed()
            
            return newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
                this.posterUrl = poster
                this.year = year
                this.plot = plot
                this.tags = tags
                this.actors = actors.map { ActorData(Actor(it)) }
                this.recommendations = recommendations
            }
        } else {
            // It's a movie, get the watch URL from "Xem Phim" button
            val dataUrl = document.selectFirst("a:contains(Xem Phim)")?.attr("href") ?: url
            return newMovieLoadResponse(title, url, TvType.Movie, dataUrl) {
                this.posterUrl = poster
                this.year = year
                this.plot = plot
                this.tags = tags
                this.actors = actors.map { ActorData(Actor(it)) }
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
        // Placeholder: This function will be implemented later.
        throw NotImplementedError("Hàm loadLinks chưa được hoàn thiện.")
        // return true
    }
}
