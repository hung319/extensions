// Save this file as HHTQProvider.kt
package recloudstream

import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.*
// ================================ FIX: ADD MISSING IMPORT ================================
import com.lagradost.cloudstream3.utils.AppUtils.parseJson 
// =========================================================================================
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.M3u8Helper
import org.jsoup.Jsoup
import org.jsoup.nodes.Element

// Data class to parse the JSON response from the server AJAX call
data class PlayerResponse(
    @JsonProperty("status") val status: Boolean,
    @JsonProperty("html") val html: String
)

// Data class for parsing episode info from the script tag's JSON
data class EpisodeJson(
    @JsonProperty("postUrl") val postUrl: String?,
    @JsonProperty("episodeName") val episodeName: String?
)

class HHTQProvider : MainAPI() {
    override var mainUrl = "https://hhtq4k.top"
    override var name = "HHTQ4K"
    override val hasMainPage = true
    override var lang = "vi"
    override val supportedTypes = setOf(
        TvType.TvSeries,
        TvType.Movie,
        TvType.Anime
    )

    override val mainPage = mainPageOf(
        "$mainUrl/phim-moi-cap-nhat/page/" to "Phim Mới Cập Nhật",
        "$mainUrl/the-loai/hoat-hinh-trung-quoc/page/" to "Hoạt Hình Trung Quốc",
        "$mainUrl/the-loai/tien-hiep/page/" to "Tiên Hiệp",
        "$mainUrl/the-loai/huyen-huyen/page/" to "Huyền Huyễn",
    )

    private fun Element.toSearchResult(): SearchResponse? {
        val thumb = this.selectFirst("a.halim-thumb") ?: return null
        val href = thumb.attr("href")
        val title = thumb.selectFirst("h2.entry-title")?.text() ?: thumb.attr("title")
        if (title.isBlank()) return null

        val posterUrl = thumb.selectFirst("img")?.attr("data-src")
        val qualityString = thumb.selectFirst("span.status")?.text()

        return newAnimeSearchResponse(title, href, TvType.TvSeries) {
            this.posterUrl = posterUrl
            this.quality = getQualityFromString(qualityString)
        }
    }

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {
        val url = if (page == 1) request.data.removeSuffix("page/") else request.data + page
        val document = app.get(url).document
        
        val home = document.select("div.halim_box article.grid-item")
            .mapNotNull { it.toSearchResult() }
            
        return newHomePageResponse(request.name, home)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val searchUrl = "$mainUrl/search/$query"
        val document = app.get(searchUrl).document
        
        return document.select("div.halim_box article.grid-item").mapNotNull {
            it.toSearchResult()
        }
    }

    override suspend fun load(url: String): LoadResponse? {
        val document = app.get(url).document

        val title = document.selectFirst("h1.entry-title")?.text()?.trim() ?: return null
        val poster = document.selectFirst("div.movie-poster img.movie-thumb")?.attr("src")
        val description = document.selectFirst("div.entry-content.htmlwrap")?.text()?.trim()

        val scriptContent = document.select("script").find { it.data().contains("var jsonEpisodes") }?.data()
        
        val episodes = scriptContent?.let { script ->
            val jsonRegex = Regex("""var jsonEpisodes\s*=\s*(\[\[.*?\]\])""")
            val jsonString = jsonRegex.find(script)?.groupValues?.get(1)
            
            try {
                // With the import, parseJson can now be resolved correctly.
                val parsedData = parseJson<List<List<EpisodeJson>>>(jsonString ?: "[]")
                parsedData.flatten().mapNotNull { epJson ->
                        val epUrl = epJson.postUrl ?: return@mapNotNull null
                        val epName = epJson.episodeName
                        newEpisode(epUrl) {
                            name = epName
                        }
                    }.reversed()
            } catch (e: Exception) {
                e.printStackTrace()
                null
            }
        }

        if (episodes.isNullOrEmpty()) {
            return null
        }

        val recommendations = document.select("section.related-movies article.grid-item").mapNotNull {
            it.toSearchResult()
        }

        return newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
            this.posterUrl = poster
            this.plot = description
            this.recommendations = recommendations
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val episodeDocument = app.get(data).document
        val servers = episodeDocument.select("ul.halim-list-server li.halim-server-items")

        servers.apmap { server ->
            try {
                val episodeSlug = server.attr("data-episode-slug")
                val serverId = server.attr("data-server-id")
                val subsvId = server.attr("data-subsv-id")
                val postId = server.attr("data-post-id")
                val serverName = server.text().trim()

                val ajaxUrl = "$mainUrl/wp-content/themes/halimmovies/player.php?episode_slug=$episodeSlug&server_id=$serverId&subsv_id=$subsvId&post_id=$postId"
                val headers = mapOf(
                    "Accept" to "application/json, text/javascript, */*; q=0.01",
                    "X-Requested-With" to "XMLHttpRequest",
                    "Referer" to data
                )
                
                val playerResponse = app.get(ajaxUrl, headers = headers).parsed<PlayerResponse>()
                val playerHtml = playerResponse.html

                if (playerHtml.contains("jwplayer('ajax-player')")) {
                    val m3u8Regex = Regex("""sources:\s*\[\{"file":"([^"]+?)","type":"hls"\}\]""")
                    m3u8Regex.find(playerHtml)?.groupValues?.get(1)?.let { m3u8Url ->
                        val cleanUrl = m3u8Url.replace("\\/", "/")
                        M3u8Helper.generateM3u8(
                            "$name $serverName",
                            cleanUrl,
                            mainUrl
                        ).forEach(callback)
                    }
                } 
                else if (playerHtml.contains("helvid.net")) {
                    Jsoup.parse(playerHtml).selectFirst("iframe")?.attr("src")?.let { iframeSrc ->
                        val helvidPage = app.get(iframeSrc, referer = ajaxUrl).text
                        val helvidRegex = Regex("""file:\s*"([^"]+\.m3u8)"""")
                        helvidRegex.find(helvidPage)?.groupValues?.get(1)?.let { m3u8Url ->
                            M3u8Helper.generateM3u8(
                                "$name $serverName (Helvid)",
                                m3u8Url,
                                "https://helvid.net/"
                            ).forEach(callback)
                        }
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }

        return true
    }
}
