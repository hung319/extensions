// Save this file as HHTQProvider.kt
package recloudstream

import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import com.lagradost.cloudstream3.utils.AppUtils.toJson
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import android.util.Log
import org.jsoup.Jsoup
import org.jsoup.nodes.Element

// Data class to parse the JSON response from the server AJAX call
// NOTE: This is no longer used by loadLinks but kept in case the site reverts.
data class PlayerResponse(
    @JsonProperty("status") val status: Boolean,
    @JsonProperty("html") val html: String
)

// Data class for parsing episode info from the script tag's JSON
data class EpisodeJson(
    @JsonProperty("postUrl") val postUrl: String?,
    @JsonProperty("episodeName") val episodeName: String?,
    @JsonProperty("postId") val postId: Int?,
    @JsonProperty("episodeSlug") val episodeSlug: String?,
    @JsonAlias("serverld", "serverId") // Accept both correct and incorrect key
    val serverId: Int?
)

// Data class to pass all necessary info from `load` to `loadLinks`
data class EpisodeData(
    val postId: String,
    val episodeSlug: String,
    val serverId: String,
    val referer: String
)

class HHTQProvider : MainAPI() {
    companion object {
        const val TAG = "HHTQProvider"
    }

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
        mainUrl to "Trang Chủ"
    )

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {
        val document = app.get(request.data).document
        
        val home = document.select("div.halim_box article.grid-item")
            .mapNotNull { it.toSearchResult() }
            
        return newHomePageResponse(request.name, home)
    }

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
                parseJson<List<List<EpisodeJson>>>(jsonString ?: "[]")
                    .flatten()
                    .mapNotNull { epJson ->
                        val epName = epJson.episodeName
                        val episodeData = EpisodeData(
                            postId = epJson.postId.toString(),
                            episodeSlug = epJson.episodeSlug ?: "",
                            serverId = epJson.serverId.toString(),
                            referer = epJson.postUrl ?: url
                        ).toJson()

                        newEpisode(episodeData) {
                            name = "Tập $epName"
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
        val episodeData = parseJson<EpisodeData>(data)

        (1..2).toList().apmap { subsvId ->
            try {
                val serverName = "Server $subsvId"
                val ajaxUrl = "$mainUrl/wp-content/themes/halimmovies/player.php?" + 
                              "episode_slug=${episodeData.episodeSlug}&" + 
                              "server_id=${episodeData.serverId}&" +
                              "subsv_id=$subsvId&" +
                              "post_id=${episodeData.postId}"
                
                val headers = mapOf(
                    "Accept" to "text/html, */*; q=0.01", // Adjusted accept header
                    "X-Requested-With" to "XMLHttpRequest",
                    "Referer" to episodeData.referer
                )
                
                // ================================ CRITICAL FIX START ================================
                // LỖI Ở ĐÂY: Server trả về HTML, không phải JSON.
                // val playerResponse = app.get(ajaxUrl, headers = headers).parsed<PlayerResponse>()
                // val playerHtml = playerResponse.html
                
                // SỬA LẠI: Đọc phản hồi dưới dạng văn bản thuần túy.
                val playerHtml = app.get(ajaxUrl, headers = headers).text
                // ================================= CRITICAL FIX END =================================

                if (playerHtml.contains("jwplayer('ajax-player')")) {
                    val m3u8Regex = Regex("""sources:\s*\[\{"file":"([^"]+?)","type":"hls"\}\]""")
                    val m3u8Url = m3u8Regex.find(playerHtml)?.groupValues?.get(1)
                    if (m3u8Url != null) {
                        val cleanUrl = m3u8Url.replace("\\/", "/")
                        callback(
                            ExtractorLink(
                                source = this.name,
                                name = "$name $serverName",
                                url = cleanUrl,
                                referer = mainUrl,
                                quality = Qualities.Unknown.value,
                                type = ExtractorLinkType.M3U8
                            )
                        )
                    }
                } 
                else if (playerHtml.contains("helvid.net")) {
                    val iframeSrc = Jsoup.parse(playerHtml).selectFirst("iframe")?.attr("src")
                    if (iframeSrc != null) {
                        val helvidPage = app.get(iframeSrc, referer = ajaxUrl).text
                        val helvidRegex = Regex("""file:\s*"([^"]+\.m3u8)"""")
                        val m3u8Url = helvidRegex.find(helvidPage)?.groupValues?.get(1)
                        if (m3u8Url != null) {
                            callback(
                                ExtractorLink(
                                    source = this.name,
                                    name = "$name $serverName (Helvid)",
                                    url = m3u8Url,
                                    referer = "https://helvid.net/",
                                    quality = Qualities.Unknown.value,
                                    type = ExtractorLinkType.M3U8
                                )
                            )
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error in loadLinks for subsvId $subsvId: ${e.message}", e)
                throw e
            }
        }
        return true
    }
}
