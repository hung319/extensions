package com.example.motchill // Make sure this matches your project structure

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.AppUtils // Keep for fixUrlNull if needed, but parseHTML removed
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.SubtitleFile // *** IMPORT ADDED ***
import org.jsoup.nodes.Element
import com.lagradost.cloudstream3.network.CloudflareKiller
import com.lagradost.cloudstream3.SearchQuality // Ensure this is the correct import for SearchQuality

class MotChillProvider : MainAPI() {
    override var mainUrl = "https://www.motchill86.com"
    override var name = "MotChill86"
    override val hasMainPage = true
    override var lang = "vi"
    override val hasDownloadSupport = false
    override val supportedTypes = setOf(
        TvType.Movie,
        TvType.TvSeries
    )

    private val cfKiller = CloudflareKiller()

    private fun getQualityFromString(qualityString: String?): SearchQuality? {
        return when {
            qualityString == null -> null
            qualityString.contains("720") -> SearchQuality.HD
            qualityString.contains("1080") -> SearchQuality.FullHd // *** FIXED: was FullHd, ensure this exists ***
            qualityString.contains("4K", ignoreCase = true) || qualityString.contains("2160") -> SearchQuality.UHD
            qualityString.contains("HD", ignoreCase = true) -> SearchQuality.HD // General HD
            qualityString.contains("Bản Đẹp", ignoreCase = true) -> SearchQuality.HD
            else -> null
        }
    }

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {
        if (page > 1) return HomePageResponse(emptyList())

        val document = app.get(mainUrl, interceptor = cfKiller).document // *** REVERTED TO .document ***
        val homePageList = ArrayList<HomePageList>()

        fun parseMovieListFromUl(element: Element?, title: String): HomePageList? {
            if (element == null) return null
            val movies = element.select("li").mapNotNull { item -> // item should be Element
                val titleElement = item.selectFirst("div.info h4.name a")
                val nameText = titleElement?.text()
                val yearText = item.selectFirst("div.info h4.name")?.ownText()?.trim()
                val name = nameText?.substringBeforeLast(yearText ?: "")?.trim() ?: nameText

                val movieUrl = AppUtils.fixUrlNull(titleElement?.attr("href")) // Use AppUtils for URL fixing
                var posterUrl = AppUtils.fixUrlNull(item.selectFirst("img")?.attr("src"))
                if (posterUrl.isNullOrEmpty() || posterUrl.contains("p21-ad-sg.ibyteimg.com")) {
                    val onerrorPoster = item.selectFirst("img")?.attr("onerror")
                    if (onerrorPoster?.contains("this.src=") == true) {
                        posterUrl = AppUtils.fixUrlNull(onerrorPoster.substringAfter("this.src='").substringBefore("';"))
                    }
                }
                if (posterUrl.isNullOrEmpty()) {
                    posterUrl = AppUtils.fixUrlNull(item.selectFirst("img")?.attr("data-src"))
                }

                val status = item.selectFirst("div.status")?.text()?.trim()
                val type = if (status?.contains("Tập") == true || (status?.contains("/") == true && !status.contains("Full", ignoreCase = true))) TvType.TvSeries else TvType.Movie
                val qualityText = item.selectFirst("div.HD")?.text()?.trim() ?: status

                if (name != null && !name.startsWith("Advertisement") && movieUrl != null) {
                    MovieSearchResponse(
                        name = name,
                        url = movieUrl,
                        apiName = this.name,
                        type = type,
                        posterUrl = posterUrl,
                        year = yearText?.toIntOrNull(),
                        quality = getQualityFromString(qualityText)
                    )
                } else {
                    null
                }
            }
            return if (movies.isNotEmpty()) HomePageList(title, movies) else null
        }

        document.selectFirst("#owl-demo.owl-carousel")?.let { owl ->
            val hotMovies = owl.select("div.item").mapNotNull { item ->
                val linkTag = item.selectFirst("a")
                val movieUrl = AppUtils.fixUrlNull(linkTag?.attr("href"))
                var name = linkTag?.attr("title")
                if (name.isNullOrEmpty()) {
                    name = item.selectFirst("div.overlay h4.name a")?.text()?.trim()
                }

                var posterUrl = AppUtils.fixUrlNull(item.selectFirst("img")?.attr("src"))
                if (posterUrl.isNullOrEmpty() || posterUrl.contains("p21-ad-sg.ibyteimg.com")) {
                    val onerrorPoster = item.selectFirst("img")?.attr("onerror")
                    if (onerrorPoster?.contains("this.src=") == true) {
                        posterUrl = AppUtils.fixUrlNull(onerrorPoster.substringAfter("this.src='").substringBefore("';"))
                    }
                }
                if (posterUrl.isNullOrEmpty()) {
                    posterUrl = AppUtils.fixUrlNull(item.selectFirst("img")?.attr("data-src"))
                }

                val status = item.selectFirst("div.status")?.text()?.trim()
                val type = if (status?.contains("Tập") == true || (status?.contains("/") == true && !status.contains("Full", ignoreCase = true))) TvType.TvSeries else TvType.Movie
                
                if (name != null && movieUrl != null) {
                    MovieSearchResponse(
                        name = name,
                        url = movieUrl,
                        apiName = this.name,
                        type = type,
                        posterUrl = posterUrl,
                        year = null, 
                        quality = getQualityFromString(status)
                    )
                } else null
            }
            if (hotMovies.isNotEmpty()) {
                homePageList.add(HomePageList("Phim Hot", hotMovies))
            }
        }

        document.select("div.heading-phim").forEach { sectionTitleElement ->
            val sectionTitleText = sectionTitleElement.selectFirst("h2.title_h1_st1 a span")?.text() 
                ?: sectionTitleElement.selectFirst("h2.title_h1_st1 a")?.text()
                ?: sectionTitleElement.selectFirst("h2.title_h1_st1")?.text()

            var movieListElementContainer = sectionTitleElement.nextElementSibling()
            var movieListElement = movieListElementContainer?.selectFirst("ul.list-film")
            
            if (movieListElement == null) {
                 movieListElementContainer = sectionTitleElement.parent()?.select("ul.list-film")?.first()
                 movieListElement = movieListElementContainer
            }
            
            val sectionTitle = sectionTitleText?.trim()
            if (sectionTitle != null && movieListElement != null) {
                parseMovieListFromUl(movieListElement, sectionTitle)?.let { homePageList.add(it) }
            }
        }
        
        return HomePageResponse(homePageList.filter { it.list.isNotEmpty() })
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val searchQuery = query.trim().replace(Regex("\\s+"), "-").lowercase()
        val searchUrl = "$mainUrl/search/$searchQuery/" 
        
        val document = app.get(searchUrl, interceptor = cfKiller).document // *** REVERTED TO .document ***

        return document.select("ul.list-film li").mapNotNull { item ->
            val titleElement = item.selectFirst("div.info div.name a")
            val nameText = titleElement?.text()
            val movieUrl = AppUtils.fixUrlNull(titleElement?.attr("href"))

            val yearRegex = Regex("""\s+(\d{4})$""")
            val yearMatch = nameText?.let { yearRegex.find(it) }
            val year = yearMatch?.groupValues?.get(1)?.toIntOrNull()
            val name = yearMatch?.let { nameText.removeSuffix(it.value) }?.trim() ?: nameText?.trim()

            var posterUrl = AppUtils.fixUrlNull(item.selectFirst("img")?.attr("src"))
            if (posterUrl.isNullOrEmpty() || posterUrl.contains("p21-ad-sg.ibyteimg.com")) { 
                val onerrorPoster = item.selectFirst("img")?.attr("onerror")
                if (onerrorPoster?.contains("this.src=") == true) {
                     posterUrl = AppUtils.fixUrlNull(onerrorPoster.substringAfter("this.src='").substringBefore("';"))
                }
            }
            if (posterUrl.isNullOrEmpty()){
                 posterUrl = AppUtils.fixUrlNull(item.selectFirst("img")?.attr("data-src"))
             }

            val statusText = item.selectFirst("div.status")?.text()?.trim()
            val hdText = item.selectFirst("div.HD")?.text()?.trim()
            val qualityString = hdText ?: statusText

            val type = if (statusText?.contains("Tập") == true || (statusText?.contains("/") == true && statusText != "Full")) TvType.TvSeries else TvType.Movie
            
            if (name != null && movieUrl != null) {
                MovieSearchResponse(
                    name = name,
                    url = movieUrl,
                    apiName = this.name,
                    type = type,
                    posterUrl = posterUrl,
                    year = year,
                    quality = getQualityFromString(qualityString)
                )
            } else {
                null
            }
        }
    }

    override suspend fun load(url: String): LoadResponse? {
        val document = app.get(url, interceptor = cfKiller).document // *** REVERTED TO .document ***

        val title = document.selectFirst("h1.movie-title span.title-1")?.text()?.trim() ?: return null
        val yearText = document.selectFirst("h1.movie-title span.title-year")?.text()?.replace("(", "")?.replace(")", "")?.trim()
        val year = yearText?.toIntOrNull()

        var poster = AppUtils.fixUrlNull(document.selectFirst("div.movie-image div.poster img")?.attr("src"))
        if (poster.isNullOrEmpty() || poster.contains("p21-ad-sg.ibyteimg.com")) {
            val onerrorPoster = document.selectFirst("div.movie-image div.poster img")?.attr("onerror")
            if (onerrorPoster?.contains("this.src=") == true) {
                 poster = AppUtils.fixUrlNull(onerrorPoster.substringAfter("this.src='").substringBefore("';"))
            }
        }
         if (poster.isNullOrEmpty()){
             poster = AppUtils.fixUrlNull(document.selectFirst("div.movie-image div.poster img")?.attr("data-src"))
         }

        val plot = document.selectFirst("div#info-film div.detail-content-main")?.text()?.trim()
        val genres = document.select("dl.movie-dl dd.movie-dd.dd-cat a").mapNotNull { it.text().trim() }.toMutableList()
        document.select("div#tags div.tag-list h3 a").forEach { tagElement ->
            val tagText = tagElement.text().trim()
            if (!genres.contains(tagText)) {
                genres.add(tagText) // add is a standard method for MutableList
            }
        }
        
        val recommendations = document.select("div#movie-hot div.owl-carousel div.item").mapNotNull { item ->
            val recLinkTag = item.selectFirst("a")
            val recUrl = AppUtils.fixUrlNull(recLinkTag?.attr("href"))
            var recName = recLinkTag?.attr("title")
             if (recName.isNullOrEmpty()) {
                recName = item.selectFirst("div.overlay h4.name a")?.text()?.trim()
            }
            var recPosterUrl = AppUtils.fixUrlNull(item.selectFirst("img")?.attr("src"))
             if (recPosterUrl.isNullOrEmpty() || recPosterUrl.contains("p21-ad-sg.ibyteimg.com")) {
                val onerrorPoster = item.selectFirst("img")?.attr("onerror")
                if (onerrorPoster?.contains("this.src=") == true) {
                     recPosterUrl = AppUtils.fixUrlNull(onerrorPoster.substringAfter("this.src='").substringBefore("';"))
                }
            }
             if (recPosterUrl.isNullOrEmpty()){
                 recPosterUrl = AppUtils.fixUrlNull(item.selectFirst("img")?.attr("data-src"))
             }

            if (recName != null && recUrl != null) {
                MovieSearchResponse(
                    name = recName,
                    url = recUrl,
                    apiName = this.name,
                    type = TvType.Movie, 
                    posterUrl = recPosterUrl
                )
            } else null
        }

        val episodes = ArrayList<Episode>()
        val episodeElements = document.select("div.page-tap ul li a")
        
        val isTvSeriesBasedOnNames = episodeElements.any {
            val epName = it.selectFirst("span")?.text()?.trim() ?: it.text().trim() // it is Element here
            Regex("""Tập\s*\d+""", RegexOption.IGNORE_CASE).containsMatchIn(epName) && !epName.contains("Full", ignoreCase = true)
        }
        val isTvSeries = episodeElements.size > 1 || isTvSeriesBasedOnNames
        
        if (episodeElements.isNotEmpty()) {
            episodeElements.forEachIndexed { index, element -> // element is Element
                val episodeLink = AppUtils.fixUrl(element.attr("href"))
                var episodeName = element.selectFirst("span")?.text()?.trim() 
                                    ?: element.text()?.trim() // text() is a Jsoup Element method
                                    ?: "Tập ${index + 1}"
                if (episodeName.isBlank()) episodeName = "Server ${index + 1}"
                episodes.add(Episode(data = episodeLink, name = episodeName))
            }
        } else {
             document.selectFirst("a#btn-film-watch.btn-red[href]")?.let { watchButton -> // watchButton is Element
                val movieWatchLink = AppUtils.fixUrl(watchButton.attr("href"))
                if (movieWatchLink.isNotBlank()){
                     episodes.add(Episode(data = movieWatchLink, name = title))
                }
            }
        }

        val currentSyncData = mutableMapOf("url" to url)

        if (isTvSeries || (episodes.size > 1 && !episodes.all { it.name == title })) {
            return TvSeriesLoadResponse(
                name = title,
                url = url,
                apiName = this.name,
                type = TvType.TvSeries,
                episodes = episodes.distinctBy { it.data }.toList(),
                posterUrl = poster,
                year = year,
                plot = plot,
                tags = genres.distinct().toList(),
                recommendations = recommendations,
                syncData = currentSyncData 
            )
        } else { 
            val movieDataUrl = episodes.firstOrNull()?.data ?: url 
            return MovieLoadResponse(
                name = title,
                url = url,
                apiName = this.name,
                type = TvType.Movie,
                dataUrl = movieDataUrl, 
                posterUrl = poster,
                year = year,
                plot = plot,
                tags = genres.distinct().toList(),
                recommendations = recommendations,
                syncData = currentSyncData
            )
        }
    }

    override suspend fun loadLinks(
        data: String, 
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        println("MotChillProvider: loadLinks for $data is not yet implemented.")
        return false 
    }
}
