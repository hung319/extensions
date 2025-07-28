package recloudstream // Đã đổi package name

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType // Import mới
import com.lagradost.cloudstream3.utils.newExtractorLink
import org.jsoup.nodes.Element

class BluphimProvider : MainAPI() {
    override var mainUrl = "https://bluphim.uk.com"
    override var name = "Bluphim"
    override val hasMainPage = true
    override var lang = "vi"
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(
        TvType.Movie,
        TvType.TvSeries
    )

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {
        val document = app.get(mainUrl).document
        val homePageList = ArrayList<HomePageList>()

        val sections = listOf(
            Pair("Phim Hot", "div.list-films.film-hot ul#film_hot li.item"),
            Pair("Phim Mới Cập Nhật", "div.list-films.film-new#film-new ul.film-moi li.item"),
            Pair("Phim Hoạt Hình", "div.list-films.film-new:contains(Phim hoạt hình) ul li.item")
        )

        for ((title, selector) in sections) {
            val movies = document.select(selector)
            if (movies.isNotEmpty()) {
                homePageList.add(
                    HomePageList(
                        title,
                        movies.map { it.toSearchResult() }
                    )
                )
            }
        }
        
        return HomePageResponse(homePageList)
    }

    private fun Element.toSearchResult(): SearchResponse {
        val link = this.selectFirst("a")
        val title = link?.attr("title")?.replace("Xem phim ", "")?.replace(" online", "") ?: ""
        val href = link?.attr("href") ?: ""
        val posterUrl = this.selectFirst("img")?.attr("src")
        val quality = this.selectFirst("span.label")?.text()

        return newTvSeriesSearchResponse(title, href, TvType.TvSeries) {
            this.posterUrl = posterUrl
            if (quality != null) {
                addQuality(quality)
            }
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val searchUrl = "$mainUrl/search?k=$query"
        val document = app.get(searchUrl).document
        
        return document.select("div.list-films ul li.item").map {
            val title = it.selectFirst("div.name span a")?.text() ?: ""
            val href = it.selectFirst("a")?.attr("href") ?: ""
            val posterUrl = it.selectFirst("img.img-film")?.attr("src")
            newMovieSearchResponse(title, href, TvType.Movie) {
                this.posterUrl = posterUrl
            }
        }
    }

    override suspend fun load(url: String): LoadResponse {
        val document = app.get(url).document
        
        val title = document.selectFirst("h1 span.title")?.text()?.trim() ?: ""
        val poster = document.selectFirst("div.poster img")?.attr("src")
        val plot = document.selectFirst("div.detail div.tab")?.text()?.trim()
        val year = document.selectFirst("dl.col dd:matches(\\d{4})")?.text()?.toIntOrNull()
        val tags = document.select("dd.theloaidd a").map { it.text() }
        
        val watchUrl = document.selectFirst("a.btn-stream-link")?.attr("href") ?: url.replace("/phim/", "/xem-phim/")
        val episodeDocument = app.get(watchUrl).document

        val episodes = episodeDocument.select("div.control-box div.list-episode a").map {
            Episode(
                data = it.attr("href"),
                name = it.attr("title").ifEmpty { it.text() },
            )
        }
        
        return if (episodes.any { it.name?.contains("Tập") == true } && episodes.size > 1) {
            newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes.reversed()) {
                this.posterUrl = poster
                this.year = year
                this.plot = plot
                this.tags = tags
            }
        } else {
            newMovieLoadResponse(title, url, TvType.Movie, watchUrl) {
                this.posterUrl = poster
                this.year = year
                this.plot = plot
                this.tags = tags
            }
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val episodePageDoc = app.get(data, referer = "$mainUrl/").document
        val initialIframeUrl = episodePageDoc.selectFirst("iframe#iframeStream")?.attr("src") ?: return false

        val embedDoc = app.get(initialIframeUrl, referer = "$mainUrl/").document
        val videoId = embedDoc.selectFirst("input[name=videoId]")?.attr("value") ?: return false
        val dynamicId = embedDoc.selectFirst("input[name=id]")?.attr("value") ?: return false

        val getUrlEndpoint = "https://moviking.childish2x2.fun/geturl"
        val postData = mapOf(
            "renderer" to "ANGLE (ARM, Mali-G78, OpenGL ES 3.2)",
            "id" to dynamicId,
            "videoId" to videoId,
            "domain" to "$mainUrl/"
        )

        val tokenResponse = app.post(
            getUrlEndpoint,
            data = postData,
            referer = initialIframeUrl,
            headers = mapOf(
                "X-Requested-With" to "XMLHttpRequest",
                "Origin" to "https://moviking.childish2x2.fun"
            )
        ).text

        val tokens = tokenResponse.split("&").associate {
            val parts = it.split("=")
            if (parts.size == 2) parts[0] to parts[1] else parts[0] to ""
        }
        val token1 = tokens["token1"] ?: return false
        val token3 = tokens["token3"] ?: return false

        val finalReferer = "https://cdn3.xosokienthiet.fun/"
        val finalUrl = "https://cdn.xosokienthiet.fun/segment/$videoId/?token1=$token1&token3=$token3"

        newExtractorLink(
            source = this.name,
            name = "Server Gốc",
            url = finalUrl,
            referer = finalReferer,
            type = ExtractorLinkType.M3U8 // Đã thay đổi từ isM3u8 = true
        ).let {
            callback(it)
        }

        return true
    }
}
