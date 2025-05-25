package com.lagradost.cloudstream3.plugins.hhninjaprovider

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Element
// import com.lagradost.cloudstream3.network.CloudflareKiller
// import com.lagradost.cloudstream3.syncproviders.AccountManager

class HHNinjaProvider : MainAPI() {
    override var mainUrl = "https://hhninja.top" 
    override var name = "HHNinja.top"
    override val hasMainPage = true
    override var lang = "vi"
    override val hasDownloadSupport = true 
    override val supportedTypes = setOf(
        TvType.Movie,
        TvType.TvSeries,
        TvType.Cartoon,
        TvType.Anime,
        TvType.AsianDrama
    )

    override val mainPage = mainPageOf(
        "$mainUrl/phim-moi-cap-nhap.html?p=" to "Mới Cập Nhật",
        "$mainUrl/the-loai/phim-2d.html?p=" to "Phim 2D",
        "$mainUrl/the-loai/phim-3d.html?p=" to "Phim 3D",
        "$mainUrl/loc-phim/W1tdLFtdLFsxXSxbXV0=?p=" to "Phim Lẻ",
        "$mainUrl/loc-phim/W1tdLFtdLFsyXSxbXV0=?p=" to "Phim Bộ"
    )

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {
        val url = request.data + page
        val document = app.get(url).document 
        val home = document.select("div.movies-list div.movie-item").mapNotNull {
            it.toSearchResult()
        }
        return newHomePageResponse(request.name, home)
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val title = this.selectFirst("div.name-movie")?.text()?.trim() ?: return null
        var href = this.selectFirst("a")?.attr("href") ?: return null
        if (!href.startsWith("http") && !href.startsWith("/")) {
            href = "/$href"
        }

        val posterUrl = this.selectFirst("img")?.attr("src")
           ?: this.selectFirst("img")?.attr("data-src")

        val episodeStr = this.selectFirst("div.episode-latest span")?.text()?.trim()

        return newAnimeSearchResponse(title, fixUrl(href)) {
            this.posterUrl = posterUrl?.let { fixUrl(it) }
            if (episodeStr != null) {
                val epRegex = Regex("""(?:Tập\s*)?(\d+)(?:(?:[/| ]\s*\d+\s*)?|$)""")
                val matchResult = epRegex.find(episodeStr)
                val currentEpisode = matchResult?.groupValues?.get(1)?.toIntOrNull()

                val isDub = episodeStr.contains("Lồng tiếng", ignoreCase = true) || episodeStr.contains("Thuyết Minh", ignoreCase = true)
                val isSub = episodeStr.contains("Vietsub", ignoreCase = true) || !isDub 

                addDubStatus(
                    dubExist = isDub,
                    subExist = isSub,
                    dubEpisodes = if (isDub) currentEpisode else null,
                    subEpisodes = if (isSub) currentEpisode else null 
                )
            }
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val searchUrl = "$mainUrl/tim-kiem/${query.replace(" ", "-")}.html"
        val document = app.get(searchUrl).document

        return document.select("div.movies-list div.movie-item").mapNotNull {
            it.toSearchResult()
        }
    }

    override suspend fun load(url: String): LoadResponse? {
        val document = app.get(url).document

        val title = document.selectFirst("h1.heading_movie")?.text()?.trim() ?: return null
        val poster = document.selectFirst("div.info-movie div.head div.first img")?.attr("src")
            ?: document.selectFirst("div.info-movie div.head div.first img")?.attr("data-src")
        val plot = document.selectFirst("div.desc div[style*='overflow:auto'] p")?.text()?.trim()
        
        val yearText = document.select("div.info-movie div.head div.last div.update_time > div:contains(Năm) + div")?.text()?.trim()
        val year = yearText?.toIntOrNull()

        val statusText = document.select("div.info-movie div.head div.last div.status > div:contains(Trạng Thái) + div")?.text()?.trim()
        val showStatus = when {
            statusText?.contains("Đang Cập Nhật", ignoreCase = true) == true -> ShowStatus.Ongoing
            statusText?.contains("Hoàn Thành", ignoreCase = true) == true || statusText?.contains("Full", ignoreCase = true) == true -> ShowStatus.Completed
            else -> null
        }

        val episodes = document.select("div.list_episode div.list-item-episode a").mapNotNull { el ->
            val epNameFull = el.selectFirst("span")?.text()?.trim() ?: el.text().trim()
            val epNumRegex = Regex("""Tập\s*(\S+)""")
            val epNumMatch = epNumRegex.find(epNameFull)
            val epNumString = epNumMatch?.groupValues?.get(1)
            
            val epName = if (epNumString != null && !epNumString.contains("Trailer", ignoreCase = true) && !epNumString.contains("PV", ignoreCase = true) ) "Tập $epNumString" else epNameFull

            val epHref = el.attr("href")
            if (epName.contains("Trailer", ignoreCase = true) || epName.contains("PV", ignoreCase = true)) {
                null 
            } else {
                Episode(
                    data = fixUrl(epHref),
                    name = epName,
                    episode = epNumString?.replace(Regex("[^0-9]"), "")?.toIntOrNull()
                )
            }
        }.reversed()

        val tvType = if (episodes.size > 1 || episodes.any { it.name?.contains("Tập", ignoreCase = true) == true && it.name?.contains("Full", ignoreCase = true) == false}) {
            TvType.TvSeries
        } else {
            TvType.Movie
        }
        
        // Đây là phần lấy recommendations
        val recommendations = document.select("div.list_episode_relate div.movies-list div.movie-item").mapNotNull {
            it.toSearchResult()
        }
        
        val genres = document.select("div.info-movie div.head div.last div.list_cate > div:contains(Thể Loại) + div a").mapNotNull { it.text() }

        return if (tvType == TvType.TvSeries) {
            newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
                this.posterUrl = poster?.let { fixUrl(it) }
                this.plot = plot
                this.year = year
                this.recommendations = recommendations // Truyền recommendations vào đây
                this.showStatus = showStatus
                this.tags = genres
            }
        } else {
            newMovieLoadResponse(title, url, TvType.Movie, fixUrl(episodes.firstOrNull()?.data ?: "")) {
                this.posterUrl = poster?.let { fixUrl(it) }
                this.plot = plot
                this.year = year
                this.recommendations = recommendations // Và vào đây
                this.tags = genres
            }
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        throw NotImplementedError("loadLinks is not implemented yet for this provider.")
    }
}
