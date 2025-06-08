package recloudstream

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.getAndUnpack
import org.jsoup.nodes.Element

class MissAVProvider : MainAPI() {
    override var name = "MissAV"
    override var mainUrl = "https://missav.live"
    override var lang = "en"
    override val hasMainPage = true
    override val supportedTypes = setOf(TvType.NSFW)

    override val mainPage = mainPageOf(
        "/dm22/en/new" to "Latest",
        "/dm588/en/release" to "New Releases",
        "/dm291/en/today-hot" to "Most Viewed Today",
        "/dm169/en/weekly-hot" to "Most Viewed Weekly",
    )

    private fun Element.toSearchResponse(): SearchResponse? {
        val a = this.selectFirst("a") ?: return null
        val href = a.attr("href")
        if (href.isBlank() || href == "#") return null

        val title = this.selectFirst("div.my-2 a")?.text()?.trim() ?: return null
        val posterUrl = this.selectFirst("img")?.let {
            it.attr("data-src").ifBlank { it.attr("src") }
        }

        return newMovieSearchResponse(title, href, TvType.NSFW) {
            this.posterUrl = posterUrl
        }
    }

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {
        val url = "$mainUrl${request.data}/page/$page"
        val document = app.get(url).document
        
        val items = document.select("div.thumbnail").mapNotNull {
            it.toSearchResponse()
        }

        return newHomePageResponse(request.name, items)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val url = "$mainUrl/en/search/$query"
        val document = app.get(url).document
        
        return document.select("div.thumbnail").mapNotNull {
            it.toSearchResponse()
        }
    }

    override suspend fun load(url: String): LoadResponse? {
        val document = app.get(url).document

        val title = document.selectFirst("h1.text-base, h1.text-lg")?.text()?.trim() ?: return null
        val posterUrl = document.selectFirst("meta[property=og:image]")?.attr("content")
        val description = document.selectFirst("div.line-clamp-2, div.line-clamp-none")?.text()

        // SỬA LỖI: Bọc mỗi đối tượng 'Actor' trong một đối tượng 'ActorData'
        val actors = document.select("a[href*=/actresses/]").map {
            ActorData(Actor(it.text().trim()))
        }

        val packedJS = document.select("script").firstOrNull { script ->
            script.data().contains("eval(function(p,a,c,k,e,d)")
        }?.data() ?: return null

        val m3u8Link = getAndUnpack(packedJS).substringAfter("source='").substringBefore("'")
        
        if (m3u8Link.isBlank()) return null

        return newMovieLoadResponse(title, url, TvType.NSFW, m3u8Link) {
            this.posterUrl = posterUrl
            this.plot = description
            this.actors = actors
        }
    }
}
