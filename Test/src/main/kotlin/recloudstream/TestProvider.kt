package com.example.motchill // Thay "com.example.motchill" bằng package mong muốn của bạn

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Element
import com.lagradost.cloudstream3.network.CloudflareKiller // For Cloudflare bypass
// Import for BouncyCastle if you plan to use it later for crypto in loadLinks
// import org.bouncycastle.jce.provider.BouncyCastleProvider
// import java.security.Security

class MotChillProvider : MainAPI() {
    override var mainUrl = "https://www.motchill86.com"
    override var name = "MotChill86"
    override val hasMainPage = true
    override var lang = "vi"
    // Set to true if you plan to implement download functionality via loadLinks later
    override val hasDownloadSupport = false 
    override val supportedTypes = setOf(
        TvType.Movie,
        TvType.TvSeries
    )

    // Initialize BouncyCastle if you plan to use it later (e.g., for loadLinks)
    // init {
    //     Security.removeProvider("BC") // Remove an old provider to prevent collision.
    //     Security.addProvider(BouncyCastleProvider())
    // }

    // Cloudflare Interceptor
    private val cfKiller = CloudflareKiller()

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {
        // For simplicity, we'll assume page 1 for now. 
        // Pagination can be added later if needed by checking `page` argument.
        if (page > 1) return HomePageResponse(emptyList())

        val document = app.get(mainUrl, interceptor = cfKiller).document
        val homePageList = ArrayList<HomePageList>()

        // Helper function to parse movie lists from ul.list-film
        fun parseMovieListFromUl(element: Element?, title: String): HomePageList? {
            if (element == null) return null
            val movies = element.select("li").mapNotNull { item ->
                val titleElement = item.selectFirst("div.info h4.name a")
                // Extract year from the name's own text, e.g., "Phim XYZ 2023" -> "2023"
                val nameText = titleElement?.text()
                val yearText = item.selectFirst("div.info h4.name")?.ownText()?.trim()
                val name = nameText?.substringBeforeLast(yearText ?: "")?.trim() ?: nameText

                val movieUrl = fixUrlNull(titleElement?.attr("href"))
                var posterUrl = fixUrlNull(item.selectFirst("img")?.attr("src"))
                if (posterUrl.isNullOrEmpty() || posterUrl.contains("p21-ad-sg.ibyteimg.com")) { // Prioritize local webp if cdn fails or is ad-related
                    val onerrorPoster = item.selectFirst("img")?.attr("onerror")
                    if (onerrorPoster?.contains("this.src=") == true) {
                         posterUrl = fixUrlNull(onerrorPoster.substringAfter("this.src='").substringBefore("';"))
                    }
                }
                 if (posterUrl.isNullOrEmpty()){ // Fallback if onerror is also empty
                     posterUrl = fixUrlNull(item.selectFirst("img")?.attr("data-src")) // Some sites use data-src for lazy loading
                 }


                val name2 = item.selectFirst("div.info div.name2")?.text()?.trim()
                val status = item.selectFirst("div.status")?.text()?.trim()
                val type = if (status?.contains("Tập") == true || status?.contains("/") == true && !status.contains("Full")) TvType.TvSeries else TvType.Movie

                if (name != null && !name.startsWith("Advertisement") && movieUrl != null) {
                    MovieSearchResponse(
                        name = name,
                        url = movieUrl,
                        apiName = this.name,
                        type = type,
                        posterUrl = posterUrl,
                        year = yearText?.toIntOrNull(),
                        otherName = name2,
                        quality = if (item.selectFirst("div.HD") != null || status?.contains("HD") == true || status?.contains("Bản Đẹp") == true) Qualität.HD else null
                    )
                } else {
                    null
                }
            }
            return if (movies.isNotEmpty()) HomePageList(title, movies) else null
        }
        
        // Parse "Phim Hot" Slider (owl-demo) [main.html]
        document.selectFirst("#owl-demo.owl-carousel")?.let { owl ->
            val hotMovies = owl.select("div.item").mapNotNull { item ->
                val linkTag = item.selectFirst("a")
                val movieUrl = fixUrlNull(linkTag?.attr("href"))
                var name = linkTag?.attr("title") // Title is often in the 'title' attribute of the link for sliders
                if (name.isNullOrEmpty()) { // Fallback to h4.name if title attribute is missing
                    name = item.selectFirst("div.overlay h4.name a")?.text()?.trim()
                }
                
                var posterUrl = fixUrlNull(item.selectFirst("img")?.attr("src"))
                 if (posterUrl.isNullOrEmpty() || posterUrl.contains("p21-ad-sg.ibyteimg.com")) {
                    val onerrorPoster = item.selectFirst("img")?.attr("onerror")
                    if (onerrorPoster?.contains("this.src=") == true) {
                         posterUrl = fixUrlNull(onerrorPoster.substringAfter("this.src='").substringBefore("';"))
                    }
                }
                if (posterUrl.isNullOrEmpty()){
                     posterUrl = fixUrlNull(item.selectFirst("img")?.attr("data-src"))
                 }

                val name2 = item.selectFirst("div.overlay div.name2")?.text()?.trim()
                val status = item.selectFirst("div.status")?.text()?.trim()
                val type = if (status?.contains("Tập") == true || status?.contains("/") == true && !status.contains("Full")) TvType.TvSeries else TvType.Movie
                // Year might not be directly available in slider, set to null or try to parse from name if possible
                
                if (name != null && movieUrl != null) {
                    MovieSearchResponse(
                        name = name,
                        url = movieUrl,
                        apiName = this.name,
                        type = type,
                        posterUrl = posterUrl,
                        year = null, // Or try to parse from name/status if available
                        otherName = name2,
                        quality = Qualität.HD // Default or parse from status
                    )
                } else null
            }
            if (hotMovies.isNotEmpty()) {
                homePageList.add(HomePageList("Phim Hot", hotMovies))
            }
        }

        // Parse sections like "Motchill Đề Cử", "Phim Bộ Mới Cập Nhật", "Phim Lẻ Mới Cập Nhật" [main.html]
        document.select("div.heading-phim").forEach { sectionTitleElement ->
            val sectionTitle = sectionTitleElement.selectFirst("h2.title_h1_st1 a span")?.text() 
                ?: sectionTitleElement.selectFirst("h2.title_h1_st1 a")?.text()
                ?: sectionTitleElement.selectFirst("h2.title_h1_st1")?.text() // Fallback if span/a is not present

            // Find the next sibling that contains the list of films
            var movieListElementContainer = sectionTitleElement.nextElementSibling()
            var movieListElement = movieListElementContainer?.selectFirst("ul.list-film")
            
            // Sometimes the ul.list-film might be nested further or the direct next sibling isn't the one
            if (movieListElement == null) {
                 movieListElementContainer = sectionTitleElement.parent()?.select("ul.list-film")?.first()
                 movieListElement = movieListElementContainer
            }


            if (sectionTitle != null && movieListElement != null) {
                parseMovieListFromUl(movieListElement, sectionTitle.trim())?.let { homePageList.add(it) }
            }
        }
        
        return HomePageResponse(homePageList.filter { it.list.isNotEmpty() })
    }

    override suspend fun search(query: String): List<SearchResponse> {
        // The website uses JS for search: removeVietnameseTonesAndSpecialChars then replace spaces with hyphens.
        // For now, a simple replacement. This might need improvement for better search accuracy.
        val searchQuery = query.trim().replace(Regex("\\s+"), "-").lowercase()
        // The search URL is /search/keyword/ based on search.html
        val searchUrl = "$mainUrl/search/$searchQuery/" 
        
        val document = app.get(searchUrl, interceptor = cfKiller).document

        return document.select("ul.list-film li").mapNotNull { item -> // [search.html]
            val titleElement = item.selectFirst("div.info div.name a")
            val nameText = titleElement?.text() // Full text like "Movie Title 2023"
            val movieUrl = fixUrlNull(titleElement?.attr("href"))

            // Attempt to extract year if it's appended to the title
            val yearRegex = Regex("""\s+(\d{4})$""")
            val yearMatch = nameText?.let { yearRegex.find(it) }
            val year = yearMatch?.groupValues?.get(1)?.toIntOrNull()
            val name = yearMatch?.let { nameText.removeSuffix(it.value) }?.trim() ?: nameText?.trim()

            var posterUrl = fixUrlNull(item.selectFirst("img")?.attr("src"))
            // Fallback for poster from onerror as seen in other parts of the site
             if (posterUrl.isNullOrEmpty() || posterUrl.contains("p21-ad-sg.ibyteimg.com")) { 
                val onerrorPoster = item.selectFirst("img")?.attr("onerror")
                if (onerrorPoster?.contains("this.src=") == true) {
                     posterUrl = fixUrlNull(onerrorPoster.substringAfter("this.src='").substringBefore("';"))
                }
            }
            if (posterUrl.isNullOrEmpty()){
                 posterUrl = fixUrlNull(item.selectFirst("img")?.attr("data-src"))
             }


            val name2 = item.selectFirst("div.info div.name2")?.text()?.trim()
            val statusText = item.selectFirst("div.status")?.text()?.trim()
            val hdText = item.selectFirst("div.HD")?.text()?.trim()

            val type = if (statusText?.contains("Tập") == true || statusText?.contains("/") == true && statusText != "Full") TvType.TvSeries else TvType.Movie
            
            if (name != null && movieUrl != null) {
                MovieSearchResponse(
                    name = name,
                    url = movieUrl,
                    apiName = this.name,
                    type = type,
                    posterUrl = posterUrl,
                    year = year,
                    otherName = name2,
                    quality = if (hdText == "Bản Đẹp" || hdText == "HD" || statusText?.contains("HD") == true) Qualität.HD else null
                )
            } else {
                null
            }
        }
    }

    override suspend fun load(url: String): LoadResponse? {
        val document = app.get(url, interceptor = cfKiller).document // [load.html]

        val title = document.selectFirst("h1.movie-title span.title-1")?.text()?.trim() ?: return null
        val originalTitle = document.selectFirst("h1.movie-title span.title-2")?.text()?.trim()
        val yearText = document.selectFirst("h1.movie-title span.title-year")?.text()?.replace("(", "")?.replace(")", "")?.trim()
        val year = yearText?.toIntOrNull()

        var poster = fixUrlNull(document.selectFirst("div.movie-image div.poster img")?.attr("src"))
        // Fallback for poster from onerror
        if (poster.isNullOrEmpty() || poster.contains("p21-ad-sg.ibyteimg.com")) {
            val onerrorPoster = document.selectFirst("div.movie-image div.poster img")?.attr("onerror")
            if (onerrorPoster?.contains("this.src=") == true) {
                 poster = fixUrlNull(onerrorPoster.substringAfter("this.src='").substringBefore("';"))
            }
        }
         if (poster.isNullOrEmpty()){
             poster = fixUrlNull(document.selectFirst("div.movie-image div.poster img")?.attr("data-src"))
         }


        val plot = document.selectFirst("div#info-film div.detail-content-main")?.text()?.trim()
        // Combining genres from dd.movie-dd.dd-cat and tags from div#tags
        val genres = document.select("dl.movie-dl dd.movie-dd.dd-cat a").mapNotNull { it.text().trim() }.toMutableList()
        document.select("div#tags div.tag-list h3 a").forEach { tagElement ->
            val tagText = tagElement.text().trim()
            if (!genres.contains(tagText)) {
                genres.add(tagText)
            }
        }
        
        val recommendations = document.select("div#movie-hot div.owl-carousel div.item").mapNotNull { item ->
            val recLinkTag = item.selectFirst("a")
            val recUrl = fixUrlNull(recLinkTag?.attr("href"))
            var recName = recLinkTag?.attr("title")
             if (recName.isNullOrEmpty()) {
                recName = item.selectFirst("div.overlay h4.name a")?.text()?.trim()
            }
            var recPosterUrl = fixUrlNull(item.selectFirst("img")?.attr("src"))
             if (recPosterUrl.isNullOrEmpty() || recPosterUrl.contains("p21-ad-sg.ibyteimg.com")) {
                val onerrorPoster = item.selectFirst("img")?.attr("onerror")
                if (onerrorPoster?.contains("this.src=") == true) {
                     recPosterUrl = fixUrlNull(onerrorPoster.substringAfter("this.src='").substringBefore("';"))
                }
            }
             if (recPosterUrl.isNullOrEmpty()){
                 recPosterUrl = fixUrlNull(item.selectFirst("img")?.attr("data-src"))
             }


            if (recName != null && recUrl != null) {
                MovieSearchResponse(
                    name = recName,
                    url = recUrl,
                    apiName = this.name,
                    type = TvType.Movie, // Assuming recommendations are movies, adjust if needed
                    posterUrl = recPosterUrl
                )
            } else null
        }


        val episodes = ArrayList<Episode>()
        val episodeElements = document.select("div.page-tap ul li a") // [load.html] / [loadlinks.html]
        
        // Determine if it's a TV Series or Movie based on episode names or count
        val isTvSeries = episodeElements.size > 1 || 
                         (episodeElements.size == 1 && episodeElements.firstOrNull()?.selectFirst("span")?.text()?.contains(Regex("""Tập\s*\d+"""), RegexOption.IGNORE_CASE) == true &&
                          episodeElements.firstOrNull()?.selectFirst("span")?.text()?.contains("Full", ignoreCase = true) == false)


        if (episodeElements.isNotEmpty()) {
            episodeElements.forEachIndexed { index, element ->
                val episodeLink = fixUrl(element.attr("href"))
                var episodeName = element.selectFirst("span")?.text()?.trim() 
                                    ?: element.text().trim() 
                                    ?: "Tập ${index + 1}"
                if (episodeName.isBlank()) episodeName = "Server ${index + 1}"


                // `data` for Episode should be the URL that `loadLinks` will process
                episodes.add(Episode(data = episodeLink, name = episodeName))
            }
        } else {
            // Fallback if no episode list found, possibly a movie with a direct watch button
            // that might have been missed or needs different parsing.
            // For now, we assume episodes list is primary.
            // If it's a movie and `episodeElements` is empty, but there's a "Xem Online" button:
             document.selectFirst("a#btn-film-watch.btn-red[href]")?.let { watchButton ->
                val movieWatchLink = fixUrl(watchButton.attr("href"))
                if (movieWatchLink.isNotBlank()){
                     episodes.add(Episode(data = movieWatchLink, name = title)) // Use movie title as episode name for single movie
                }
            }
        }


        if (isTvSeries || (episodes.size > 1 && !episodes.all {it.name == title})) { // Second condition for cases where a movie might have multiple server links named after itself
            return TvSeriesLoadResponse(
                name = title,
                url = url,
                apiName = this.name,
                type = TvType.TvSeries,
                episodes = episodes.distinctBy { it.data }.toList(), // Ensure unique episode data URLs
                posterUrl = poster,
                year = year,
                plot = plot,
                tags = genres.distinct().toList(),
                recommendations = recommendations,
                syncData = url // For quickResume
            )
        } else { // Movie
            // For a movie, dataUrl should be the URL to the watch page (first episode/server link)
            val movieDataUrl = episodes.firstOrNull()?.data ?: url 
            return MovieLoadResponse(
                name = title,
                url = url,
                apiName = this.name,
                type = TvType.Movie,
                dataUrl = movieDataUrl, // This will be passed to loadLinks for a movie
                posterUrl = poster,
                year = year,
                plot = plot,
                tags = genres.distinct().toList(),
                recommendations = recommendations,
                syncData = url // For quickResume
            )
        }
    }

    override suspend fun loadLinks(
        data: String, // This `data` is the episode URL from `Episode.data` (for TVSeries) or `MovieLoadResponse.dataUrl` (for Movie)
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        // Placeholder for loadLinks - To be implemented when requested
        // For now, it will do nothing and return false, indicating no links found.

        // Example of how it might start (actual implementation is complex due to encryption):
        // val document = app.get(data, interceptor = cfKiller).document
        // // ... find encrypted string ...
        // // ... decrypt ...
        // // ... callback(ExtractorLink(...)) ...

        println("MotChillProvider: loadLinks for $data is not yet implemented.")
        return false 
    }

    // Dummy Enum for Quality to make the code runnable in Gist context
    // In a real plugin, use com.lagradost.cloudstream3.Qualities
    private enum class Qualität { 
        HD, SD, Unknown
    }
}
