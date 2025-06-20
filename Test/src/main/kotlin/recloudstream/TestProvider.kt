// Bạn có thể cần thay đổi package này cho phù hợp với cấu trúc dự án của mình
package com.lagradost.cloudstream3.plugins.vi

// Thêm thư viện Jsoup để phân tích cú pháp HTML
import org.jsoup.nodes.Element

// CẬP NHẬT: Import tường minh các hàm và lớp cần thiết từ API CloudStream
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.HomePageResponse
import com.lagradost.cloudstream3.HomePageList
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.MovieLoadResponse
import com.lagradost.cloudstream3.TvSeriesLoadResponse
import com.lagradost.cloudstream3.AnimeSearchResponse
import com.lagradost.cloudstream3.Episode
import com.lagradost.cloudstream3.ExtractorLink
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.newHomePageResponse
import com.lagradost.cloudstream3.newAnimeSearchResponse
import com.lagradost.cloudstream3.newMovieLoadResponse
import com.lagradost.cloudstream3.newTvSeriesLoadResponse
import com.lagradost.cloudstream3.newEpisode

// Định nghĩa lớp chính cho plugin
class Bluphim3Provider : MainAPI() {
    // Ghi đè các thuộc tính cơ bản của plugin
    override var mainUrl = "https://bluphim3.com"
    override var name = "Bluphim3"
    override val hasMainPage = true
    override var lang = "vi"
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(
        TvType.Movie,
        TvType.TvSeries,
        TvType.Anime
    )

    // Hàm lấy danh sách phim cho trang chính
    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val document = app.get(mainUrl).document
        val homePageList = mutableListOf<HomePageList>()

        document.select("div.list-films").forEach { block ->
            val title = block.selectFirst("h2.title-box")?.text()?.trim() ?: return@forEach
            val movies = block.select("li.item, li.film-item-ver").mapNotNull {
                it.toSearchResult()
            }
            if (movies.isNotEmpty()) {
                homePageList.add(HomePageList(title, movies))
            }
        }
        return newHomePageResponse(homePageList)
    }

    // Hàm chuyển đổi một phần tử HTML thành đối tượng SearchResponse
    private fun Element.toSearchResult(): SearchResponse? {
        var title = this.attr("title").trim()
        if (title.isBlank()) {
            title = this.selectFirst("a")?.attr("title")?.trim() ?: ""
        }
        title = title.replace("Xem phim ", "").replace(" online", "")
        if (title.isBlank()) return null

        val href = this.selectFirst("a")?.attr("href")?.let { fixUrl(it) } ?: return null
        val posterUrl = this.selectFirst("img")?.attr("src")?.let { fixUrl(it) }

        return newAnimeSearchResponse(title, href) {
            this.posterUrl = posterUrl
        }
    }

    // Hàm tìm kiếm phim
    override suspend fun search(query: String): List<SearchResponse> {
        val searchUrl = "$mainUrl/search?k=$query"
        val document = app.get(searchUrl).document

        return document.select("div.list-films ul li.item").mapNotNull {
            it.toSearchResult()
        }
    }
    
    // Hàm tải thông tin chi tiết của phim/series
    override suspend fun load(url: String): LoadResponse? {
        val document = app.get(url).document

        val title = document.selectFirst("span.title")?.text()?.trim() ?: return null
        val poster = document.selectFirst("div.poster img")?.attr("src")?.let { fixUrl(it) }
        val year = document.select("div.dinfo dl.col dd").getOrNull(3)?.text()?.trim()?.toIntOrNull()
        val description = document.selectFirst("div.detail div.tab")?.text()?.trim()
        val tags = document.select("dd.theloaidd a").map { it.text() }

        val isAnime = tags.any { it.contains("Hoạt hình", ignoreCase = true) }
        
        val watchUrl = document.selectFirst("a.btn-stream-link")?.attr("href")?.let { fixUrl(it) } ?: url
        val watchDocument = app.get(watchUrl).document
        
        val recommendations = watchDocument.select(".list-films.film-related li.item").mapNotNull { it.toSearchResult() }

        val episodeElements = watchDocument.select("div.episodes div.list-episode a:not(:contains(Server bên thứ 3))")

        val isMovieByEpisodeRule = episodeElements.size == 1 && episodeElements.first()?.text()?.contains("Tập Full", ignoreCase = true) == true
        
        if (isMovieByEpisodeRule) {
            val movieDataUrl = episodeElements.first()?.attr("href")?.let { fixUrl(it) } ?: watchUrl
            return newMovieLoadResponse(title, url, if (isAnime) TvType.Anime else TvType.Movie, movieDataUrl) {
                this.posterUrl = poster
                this.year = year
                this.plot = description
                this.tags = tags
                this.recommendations = recommendations
            }
        } else {
            val episodes = episodeElements.map {
                val originalName = it.attr("title").ifBlank { it.text() }
                val simplifiedName = "Tập \\d+".toRegex().find(originalName)?.value ?: originalName
                
                newEpisode(fixUrl(it.attr("href"))) {
                    this.name = simplifiedName
                }
            }

            if (episodes.isNotEmpty()) {
                return newTvSeriesLoadResponse(title, url, if (isAnime) TvType.Anime else TvType.TvSeries, episodes) {
                    this.posterUrl = poster
                    this.year = year
                    this.plot = description
                    this.tags = tags
                    this.recommendations = recommendations
                }
            } else {
                return newMovieLoadResponse(title, url, if (isAnime) TvType.Anime else TvType.Movie, watchUrl) {
                    this.posterUrl = poster
                    this.year = year
                    this.plot = description
                    this.tags = tags
                    this.recommendations = recommendations
                }
            }
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val document = app.get(data).document
        val iframeSrc = document.selectFirst("iframe#iframeStream")?.attr("src")
        
        if (iframeSrc != null) {
            return loadExtractor(iframeSrc, data, subtitleCallback, callback)
        }
        
        return false
    }
}
