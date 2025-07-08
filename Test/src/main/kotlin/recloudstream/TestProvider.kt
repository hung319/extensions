package com.lagradost.cloudstream3

import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.Qualities
import okhttp3.Interceptor
import okhttp3.Response
import org.jsoup.nodes.Element

// Data class for parsing episode data
private data class LinkData(val hash: String, val id: String)

class AnimeVietsubProvider : MainAPI() {
    override var name = "AnimeVietsub"
    override var mainUrl = "https://animevietsub.moe" // Fallback, will be updated
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
        // Use a persistent cache for the base URL to avoid repeated lookups
        if (baseUrl == mainUrl) {
            val response = app.get("https://bit.ly/animevietsubtv", allowRedirects = true, timeout = 10)
            baseUrl = response.url.let { url ->
                val scheme = url.toHttpUrlOrNull()?.scheme ?: "https"
                val host = url.toHttpUrlOrNull()?.host ?: "animevietsub.moe"
                "$scheme://$host"
            }
        }
        return baseUrl
    }

    override val mainPage = mainPageOf(
        "/phim-moi-cap-nhat" to "Mới cập nhật",
        "/anime" to "Anime",
        "/live-action" to "Live Action",
        "/movie" to "Movie",
        "/daily-ranking" to "BXH ngày",
        "/weekly-ranking" to "BXH tuần",
        "/monthly-ranking" to "BXH tháng",
    )

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {
        val url = "${getBaseUrl()}${request.data}/trang-$page/"
        val document = app.get(url).document
        val home = document.select("ul.film-list > li").mapNotNull {
            it.toSearchResult()
        }
        return newHomePageResponse(request.name, home)
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val href = this.selectFirst("a")?.attr("href") ?: return null
        val title = this.selectFirst("h3.film-name")?.text() ?: return null
        val posterUrl = this.selectFirst("img")?.attr("data-src")
        val episodeText = this.selectFirst(".episode-latest")?.text()

        return newAnimeSearchResponse(title, href, TvType.Anime) {
            this.posterUrl = posterUrl
            if (episodeText != null) {
                addDubStatus(dubStatus = DubStatus.Subbed, episode = episodeText.toIntOrNull())
            }
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val url = "${getBaseUrl()}/tim-kiem/$query"
        val document = app.get(url).document
        return document.select("ul.film-list > li").mapNotNull {
            it.toSearchResult()
        }
    }

    override suspend fun load(url: String): LoadResponse {
        val document = app.get(url).document
        val title = document.selectFirst(".film-title h1")?.text() ?: "N/A"
        val poster = document.selectFirst(".film-poster img")?.attr("src")
        val plot = document.select(".film-description").text()
        val tags = document.select("ul.film-tag li a").map { it.text() }
        val year = document.select("div.film-release").text()
            .trim().split(" ").last().toIntOrNull()

        val recommendations = document.select("ul.film-list > li").mapNotNull {
            it.toSearchResult()
        }

        val episodes = document.select(".episode-list a").map {
            val epNum = it.text()
            val epData = it.attr("data-id") ?: ""
            newEpisode(epData) {
                this.name = "Tập $epNum"
                this.episode = epNum.toIntOrNull()
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
        // Fetch the encrypted source information
        val response = app.post(
            url = "${getBaseUrl()}/api/v2/episodes/$data",
            headers = mapOf("X-Requested-With" to "XMLHttpRequest"),
            referer = getBaseUrl()
        ).parsedSafe<EpisodeInfo>() ?: return false

        val sourceUrl = response.data

        // Store the M3U8 content to be served by the interceptor
        val m3u8Content = app.get(sourceUrl).text
        val key = sourceUrl.hashCode().toString()
        m3u8Contents[key] = m3u8Content

        // Create a fake URL that the interceptor will catch
        val interceptorUrl = "https://placeholder.google.com/animevietsub/$key.m3u8"

        callback.invoke(
            ExtractorLink(
                name,
                name,
                interceptorUrl,
                referer = getBaseUrl(),
                quality = Qualities.Unknown.value,
                isM3u8 = true
            )
        )

        return true
    }

    // A simple data class to parse the JSON response for episode info
    private data class EpisodeInfo(val status: Boolean?, val `data`: String)

    override fun getVideoInterceptor(url: String): Interceptor? {
        if (!url.contains("placeholder.google.com/animevietsub")) return null

        return Interceptor { chain ->
            val request = chain.request()
            val requestUrl = request.url.toString()
            val key = requestUrl.substringAfterLast("/").substringBeforeLast(".m3u8")

            val m3u8Data = m3u8Contents[key]

            if (m3u8Data != null) {
                val responseBody = okhttp3.ResponseBody.create(
                    okhttp3.MediaType.parse("application/vnd.apple.mpegurl"),
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
                // If data isn't in the map, proceed with the original request, which will likely fail.
                chain.proceed(request)
            }
        }
    }
}
