package com.lagradost.cloudstream3

import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.newExtractorLink
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.Response
import org.jsoup.nodes.Element

// Data class for parsing episode data
private data class LinkData(val hash: String, val id: String)

class AnimeVietsubProvider : MainAPI() {
    override var name = "AnimeVietsub"
    // The bit.ly link seems to be the most stable entry point
    override var mainUrl = "https://animevietsub.lol" 
    private var baseUrl = mainUrl
    override var lang = "vi"
    override val hasMainPage = true

    override val supportedTypes = setOf(
        TvType.Anime,
        TvType.TvSeries,
        TvType.Cartoon,
        TvType.Movie
    )

    // Cache for M3U8 content to be served by the interceptor
    private val m3u8Contents = mutableMapOf<String, String>()

    private suspend fun getBaseUrl(): String {
        // The domain changes frequently, so we resolve it from a stable bit.ly link
        if (baseUrl == mainUrl || !app.get(baseUrl, timeout = 5).isSuccessful) {
             try {
                val response = app.get("https://bit.ly/animevietsubtv", allowRedirects = true, timeout = 10)
                val newUrl = response.url.toHttpUrlOrNull()
                if (newUrl != null) {
                    baseUrl = "${newUrl.scheme}://${newUrl.host}"
                }
            } catch (e: Exception) {
                // Fallback to the last known working URL if bit.ly fails
                baseUrl = "https://animevietsub.pro"
            }
        }
        return baseUrl
    }
    
    // Updated homepage sections to reflect current site structure
    override val mainPage = mainPageOf(
        "/danh-sach/phim-moi-cap-nhat.html" to "Mới cập nhật",
        "/the-loai/hanh-dong.html" to "Hành Động",
        "/the-loai/romance.html" to "Lãng mạn",
        "/the-loai/school.html" to "Học Đường",
        "/bang-xep-hang/day.html" to "BXH Ngày",
        "/bang-xep-hang/week.html" to "BXH Tuần",
        "/bang-xep-hang/month.html" to "BXH Tháng",
    )

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {
        val url = "${getBaseUrl()}${request.data.replace(".html", "")}/trang-$page.html"
        val document = app.get(url).document
        // FIXED: Updated selector for movie list items
        val home = document.select("div.TPostMv article").mapNotNull {
            it.toSearchResult()
        }
        return newHomePageResponse(request.name, home)
    }

    private fun Element.toSearchResult(): SearchResponse? {
        // FIXED: Updated selectors for search result elements
        val link = this.selectFirst("a") ?: return null
        val href = link.attr("href")
        val title = this.selectFirst(".Title")?.text() ?: return null
        val posterUrl = this.selectFirst("img")?.let {
            it.attr("data-src").ifBlank { it.attr("src") }
        }
        val episodeText = this.selectFirst(".Info .Ep")?.text()

        return newAnimeSearchResponse(title, href, TvType.Anime) {
            this.posterUrl = posterUrl
            if (episodeText != null) {
                addDubStatus(DubStatus.Subbed, episodes = episodeText.split("/").firstOrNull()?.filter { it.isDigit() }?.toIntOrNull())
            }
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val url = "${getBaseUrl()}/tim-kiem/$query/"
        val document = app.get(url).document
        // FIXED: Updated selector for search result list
        return document.select("div.TPostMv article").mapNotNull {
            it.toSearchResult()
        }
    }

    override suspend fun load(url: String): LoadResponse {
        val document = app.get(url).document
        // FIXED: Updated selectors for movie detail page elements
        val title = document.selectFirst(".TPost .Title")?.text() ?: "N/A"
        val poster = document.selectFirst(".Image img")?.attr("src")
        val plot = document.selectFirst(".Description")?.text()
        val tags = document.select("a[rel=tag]").map { it.text() }
        
        var year: Int? = null
        document.select(".Info li").forEach {
            val text = it.text()
            if (text.contains("Năm phát hành")) {
                year = text.filter { c -> c.isDigit() }.toIntOrNull()
            }
        }
        
        val recommendations = document.select("div.MovieListRelated article").mapNotNull {
            it.toSearchResult()
        }
        
        // The watch page URL is needed to get the episode list
        val watchUrl = document.selectFirst("a.watch_button")?.attr("href") ?: return newMovieLoadResponse(title, url, TvType.Movie, null)
        val watchDocument = app.get(watchUrl).document

        val episodes = watchDocument.select(".list-episode a").mapNotNull {
            val epNum = it.attr("title").filter{ c -> c.isDigit() }.ifBlank { "0" }.toIntOrNull()
            val epData = it.attr("data-id") ?: return@mapNotNull null
            newEpisode(epData) {
                this.name = it.attr("title").ifBlank { "Tập $epNum" }
                this.episode = epNum
            }
        }.reversed()

        val tvType = if (episodes.size > 1) TvType.TvSeries else TvType.Movie

        return newTvSeriesLoadResponse(title, url, tvType, episodes) {
            this.posterUrl = poster
            this.year = year
            this.plot = plot
            this.tags = tags
            this.recommendations = recommendations
        }
    }


    override suspend fun loadLinks(
        data: String, // Episode ID
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        // AJAX request to get the player data
        val response = app.post(
            url = "${getBaseUrl()}/ajax/player?v=2019a",
            headers = mapOf(
                "X-Requested-With" to "XMLHttpRequest",
                "Content-Type" to "application/x-www-form-urlencoded"
            ),
            data = mapOf("id" to data, "play" to "api")
        ).parsedSafe<AjaxResponse>() ?: return false

        val sourceUrl = response.link?.firstOrNull()?.file ?: return false

        // In a real provider, this would involve decryption. Here, we assume a direct link or simple obfuscation.
        // For this provider, we use an interceptor to handle potential streaming issues.
        val m3u8Content = app.get(sourceUrl).text
        val key = sourceUrl.hashCode().toString()
        m3u8Contents[key] = m3u8Content

        val interceptorUrl = "https://placeholder.google.com/animevietsub/$key.m3u8"

        newExtractorLink(
            source = name,
            name = name,
            url = interceptorUrl,
            type = ExtractorLinkType.M3U8
        ) {
            this.referer = getBaseUrl()
            this.quality = Qualities.Unknown.value
        }.let(callback)

        return true
    }
    
    private data class AjaxResponse(val success: Int?, val link: List<LinkSource>?)
    private data class LinkSource(val file: String?, val type: String?, val label: String?)


    override fun getVideoInterceptor(extractorLink: ExtractorLink): Interceptor? {
        if (!extractorLink.url.contains("placeholder.google.com/animevietsub")) return null

        return Interceptor { chain ->
            val request = chain.request()
            val requestUrl = request.url.toString()
            val key = requestUrl.substringAfterLast("/").substringBeforeLast(".m3u8")

            val m3u8Data = m3u8Contents[key]

            if (m3u8Data != null) {
                val responseBody = okhttp3.ResponseBody.create(
                    "application/vnd.apple.mpegurl".toMediaTypeOrNull(),
                    m3u8Data
                )
                Response.Builder()
                    .code(200)
                    .protocol(okhttp3.Protocol.HTTP_2)
                    .request(request)
                    .message("OK")
                    .body(responseBody)
                    .build()
            } else {
                chain.proceed(request)
            }
        }
    }
}
