package com.lagradost.cloudstream3.movieprovider

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorApiKt.newEpisode
import com.lagradost.cloudstream3.utils.ExtractorApiKt.newMovieLoadResponse
import com.lagradost.cloudstream3.utils.ExtractorApiKt.newMovieSearchResponse
import com.lagradost.cloudstream3.utils.ExtractorApiKt.newTvSeriesLoadResponse
import org.jsoup.nodes.Element
import java.util.ArrayList

class MotchillProvider : MainAPI() {
    override var mainUrl = "https://www.motchill97.com"
    override var name = "Motchill"
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)
    override var lang = "vi"
    override val hasMainPage = true

    private fun Element.toSearchResponse(): SearchResponse? {
        val link = this.selectFirst("a")?.attr("href") ?: return null
        val title = this.selectFirst("h4.name a")?.text() ?: this.selectFirst(".name a")?.text() ?: return null
        val posterUrl = this.selectFirst("img")?.attr("src")

        return newMovieSearchResponse(title, link) {
            this.posterUrl = posterUrl
        }
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val document = app.get(mainUrl).document
        val homePageList = ArrayList<HomePageList>()

        document.select("div.heading-phim").forEach { heading ->
            val title = heading.selectFirst("h2 a span")?.text()?.trim() ?: return@forEach
            val filmList = heading.nextElementSibling()?.select("ul.list-film li")
            
            if (!filmList.isNullOrEmpty()) {
                val movies = filmList.mapNotNull { it.toSearchResponse() }
                if (movies.isNotEmpty()) {
                    homePageList.add(HomePageList(title, movies))
                }
            }
        }
        
        return HomePageResponse(homePageList)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val searchUrl = "$mainUrl/search/$query/"
        val document = app.get(searchUrl).document

        return document.select("ul.list-film li").mapNotNull {
            val link = it.selectFirst("a")?.attr("href") ?: return@mapNotNull null
            val title = it.selectFirst("div.name a")?.text() ?: return@mapNotNull null
            val posterUrl = it.selectFirst("img")?.attr("src")

            newMovieSearchResponse(title, link) {
                this.posterUrl = posterUrl
            }
        }
    }

    override suspend fun load(url: String): LoadResponse {
        val document = app.get(url).document

        val title = document.selectFirst("h1.movie-title span.title-1")?.text()?.trim() ?: "Loading..."
        val poster = document.selectFirst("div.poster img")?.attr("src")
        val plot = document.selectFirst("div.detail-content-main")?.text()?.trim()
        val yearText = document.selectFirst("span.title-year")?.text()?.filter { it.isDigit() }
        val year = yearText?.toIntOrNull()

        val episodes = document.select("div.page-tap ul li a").mapNotNull { epElement ->
            val epUrl = epElement.attr("href") ?: return@mapNotNull null
            val epName = "Tập ${epElement.selectFirst("span")?.text()?.trim()}"
            newEpisode(epUrl) {
                this.name = epName
            }
        }

        return if (episodes.size > 1) {
            newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
                this.posterUrl = poster
                this.plot = plot
                this.year = year
            }
        } else {
            newMovieLoadResponse(title, url, TvType.Movie, episodes.firstOrNull()?.data) {
                this.posterUrl = poster
                this.plot = plot
                this.year = year
            }
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val episodeDocument = app.get(data).document
        val scriptContent = episodeDocument.select("script").html()
        val videoUrl = Regex("""sources:\s*\[\{file:\s*"(.*?)"''').find(scriptContent)?.groupValues?.get(1)

        if (videoUrl != null) {
            callback(
                ExtractorLink(
                    source = this.name,
                    name = "Motchill Server",
                    url = videoUrl,
                    referer = data,
                    quality = Qualities.Unknown.value,
                    type = ExtractorLinkType.M3U8
                )
            )
            return true
        }

        return false
    }
}
