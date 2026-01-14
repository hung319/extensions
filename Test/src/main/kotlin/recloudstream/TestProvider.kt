package recloudstream

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.loadExtractor
import org.jsoup.nodes.Element

class AnimexProvider : MainAPI() {
    override var mainUrl = "https://animex.one"
    override var name = "AnimeX"
    override val hasMainPage = true
    override var lang = "en"
    override val supportedTypes = setOf(TvType.Anime, TvType.Movie)

    override val mainPage = mainPageOf(
        "$mainUrl/home" to "Home",
        "$mainUrl/catalog?sort=TRENDING_DESC" to "Trending",
        "$mainUrl/catalog?sort=POPULARITY_DESC" to "Popular"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val document = app.get(request.data).document
        
        val home = document.select("a.item-link").mapNotNull {
            it.toSearchResult()
        }
        return newHomePageResponse(request.name, home)
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val href = this.attr("href") ?: return null
        val img = this.selectFirst("img")?.attr("src")
        val title = this.selectFirst(".title-text")?.text() 
            ?: this.selectFirst("h5")?.text() 
            ?: return null

        return newAnimeSearchResponse(title, fixUrl(href), TvType.Anime) {
            this.posterUrl = img
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val url = "$mainUrl/catalog?search=$query"
        val document = app.get(url).document
        return document.select("a.item-link").mapNotNull {
            it.toSearchResult()
        }
    }

    override suspend fun load(url: String): LoadResponse {
        val document = app.get(url).document

        val title = document.selectFirst("h1")?.text() 
            ?: document.selectFirst("title")?.text()?.substringBefore(" -") 
            ?: "Unknown"
        val description = document.selectFirst("meta[name=description]")?.attr("content")
        val poster = document.selectFirst("meta[property=og:image]")?.attr("content")
        val bg = document.selectFirst(".absolute.inset-0.bg-cover")?.attr("style")
            ?.substringAfter("url('")?.substringBefore("')")

        val episodes = mutableListOf<Episode>()
        val watchButton = document.selectFirst("a[href^='/watch/']")

        if (watchButton != null) {
            val watchUrl = fixUrl(watchButton.attr("href"))
            val watchDoc = app.get(watchUrl).document
            
            val episodeElements = watchDoc.select("a[href^='/watch/']")
            
            if (episodeElements.isNotEmpty()) {
                episodeElements.forEach { el ->
                    val epHref = fixUrl(el.attr("href"))
                    val epName = el.text()
                    val epNum = Regex("Episode\\s+(\\d+)").find(epName)?.groupValues?.get(1)?.toIntOrNull()
                        ?: Regex("episode-(\\d+)").find(epHref)?.groupValues?.get(1)?.toIntOrNull()

                    episodes.add(
                        newEpisode(epHref) {
                            this.name = epName
                            this.episode = epNum
                            this.posterUrl = poster
                        }
                    )
                }
            } else {
                episodes.add(newEpisode(watchUrl) {
                    this.name = "Watch Now"
                })
            }
        }

        return newAnimeLoadResponse(title, url, TvType.Anime) {
            this.posterUrl = poster
            this.backgroundPosterUrl = bg
            this.plot = description
            // SỬA: Dùng DubStatus.Subbed thay vì Sub
            addEpisodes(DubStatus.Subbed, episodes) 
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val document = app.get(data).document

        val iframe = document.selectFirst("iframe")
        if (iframe != null) {
            var src = iframe.attr("src")
            if (src.startsWith("//")) src = "https:$src"
            
            if (src.isNotEmpty()) {
                loadExtractor(src, subtitleCallback, callback)
                return true
            }
        }
        
        return false
    }
}
