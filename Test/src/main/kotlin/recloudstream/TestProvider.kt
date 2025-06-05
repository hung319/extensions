package com.phimmoichillprovider

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
// import android.util.Log // Bỏ comment nếu bạn muốn dùng Log.d để debug trên Android

class PhimMoiChillProvider : MainAPI() {
    override var mainUrl = "https://phimmoichill.day"
    override var name = "PhimMoiChill"
    override val hasMainPage = true
    override var lang = "vi"
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(
        TvType.Movie,
        TvType.TvSeries,
        TvType.Anime
    )

    // Headers để giả lập yêu cầu từ trình duyệt thật
    private val browserHeaders = mapOf(
        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/108.0.0.0 Safari/537.36",
        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,image/apng,*/*;q=0.8,application/signed-exchange;v=b3;q=0.9",
        "Accept-Language" to "en-US,en;q=0.5",
    )

    private fun parseDuration(durationString: String?): Int? {
        if (durationString.isNullOrBlank()) return null
        var totalMinutes = 0
        val cleanedString = durationString.lowercase()
            .replace("tiếng", "giờ")
            .replace("mins", "phút")
            .replace("min", "phút")
            .replace("hr", "giờ")

        val pattern = Regex("""(?:(\d+)\s*giờ)?\s*(?:(\d+)\s*phút)?""")
        val match = pattern.find(cleanedString)

        if (match != null) {
            val hours = match.groupValues[1].toIntOrNull()
            val minutes = match.groupValues[2].toIntOrNull()
            if (hours != null) totalMinutes += hours * 60
            if (minutes != null) totalMinutes += minutes
        }
        
        if (totalMinutes == 0 && cleanedString.matches(Regex("""^\d+$"""))) {
            cleanedString.toIntOrNull()?.let {
                if (!cleanedString.contains("giờ") && !cleanedString.contains("phút")) {
                    totalMinutes = it
                }
            }
        }
        return if (totalMinutes > 0) totalMinutes else null
    }

    private fun Element.toSearchResponseDefault(isSearchPage: Boolean = false): SearchResponse? {
        val aTag = this.selectFirst("a") ?: return null
        val href = aTag.attr("abs:href")
        if (href.isBlank()) return null

        val title = if (isSearchPage) {
            aTag.selectFirst("h3")?.text()
        } else {
            aTag.selectFirst("p")?.text() ?: aTag.selectFirst("h3")?.text()
        } ?: aTag.attr("title").ifBlank { null } ?: return null

        var posterUrl = aTag.selectFirst("img")?.attr("abs:data-src")
            ?: aTag.selectFirst("img")?.attr("abs:src")

        val qualityText = this.selectFirst("span.label")?.text()?.trim()
        val statusDivText = this.selectFirst("span.label div.status")?.text()?.trim()
        val currentQuality = statusDivText ?: qualityText

        var tvType = TvType.Movie
        if (currentQuality != null) {
            if (currentQuality.contains("Tập", ignoreCase = true) ||
                (currentQuality.contains("Hoàn Tất", ignoreCase = true) && !currentQuality.contains("Full", ignoreCase = true)) ||
                currentQuality.matches(Regex("""\d+/\d+""")) || 
                currentQuality.contains("Trailer", ignoreCase = true) && href.contains("/info/") 
            ) {
                tvType = TvType.TvSeries
            }
        }
        if (href.contains("/genre/phim-sap-chieu", ignoreCase = true) || title.contains("Trailer", ignoreCase = true)){
            tvType = TvType.TvSeries
        }
        if (title.contains("anime", ignoreCase = true)) {
            tvType = TvType.Anime
        }

        return newMovieSearchResponse(title, href, tvType) {
            this.posterUrl = posterUrl
            if (!currentQuality.isNullOrBlank()) {
                addQuality(currentQuality)
            }
        }
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val document = app.get(mainUrl, headers = browserHeaders).document
        val homePageList = mutableListOf<HomePageList>()

        document.select("div.block:has(h2.caption)").forEach { block ->
            val name = block.selectFirst("h2.caption")?.text() ?: ""
            val movies = block.select("ul.list-film li.item").mapNotNull { it.toSearchResponseDefault() }
            if (movies.isNotEmpty()) {
                homePageList.add(HomePageList(name, movies))
            }
        }
        return newHomePageResponse(homePageList)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val searchUrl = "$mainUrl/tim-kiem/$query/"
        val document = app.get(searchUrl, headers = browserHeaders).document
        return document.select("div#binlist ul.list-film li.item.small").mapNotNull {
            it.toSearchResponseDefault(isSearchPage = true)
        }
    }

    override suspend fun load(url: String): LoadResponse? {
        val document = app.get(url, headers = browserHeaders).document 

        val title = document.selectFirst("div.film-info h1[itemprop=name]")?.text()?.trim() ?: return null
        val year = document.selectFirst("div.film-info h2")?.text()?.let { Regex("""\((\d{4})\)""").find(it)?.groupValues?.get(1)?.toIntOrNull() }

        var posterUrl = document.selectFirst("div.film-info div.image img.avatar")?.attr("abs:data-src")
            ?: document.selectFirst("div.film-info div.image img.avatar")?.attr("abs:src")
            ?: document.selectFirst("meta[property=og:image]")?.attr("content")
        
        val plotElement = document.selectFirst("div#film-content")?.clone()
        plotElement?.select("span[itemprop=author]")?.remove()
        val plot = plotElement?.text()?.trim() ?: document.selectFirst("meta[itemprop=description]")?.attr("content")?.trim()

        val tags = document.select("div#tags ul.tags-list li.tag a")?.map { it.text() }
        val durationText = document.selectFirst("ul.entry-meta.block-film li:has(label:containsOwn(Thời lượng:))")?.text()
        val durationInMinutes = parseDuration(durationText)
        val rating = document.selectFirst("div.box-rating span.average#average")?.text()?.toRatingInt()
        
        var episodeListPageUrl: String? = 
            document.selectFirst("div.latest-episode a")?.attr("abs:href")
            ?: document.selectFirst("div.film-info a.btn-see")?.attr("abs:href")
            ?: document.selectFirst("div.film-info div.image a.icon-play")?.attr("abs:href")
        
        val recommendations = document.select("div.block.film-related ul.list-film li.item").mapNotNull { it.toSearchResponseDefault() }
        
        val genres = document.select("ul.entry-meta.block-film li:has(label:contains(Thể loại)) a")
        val isAnime = genres.any { it.attr("href").contains("/genre/phim-anime", ignoreCase = true) || it.text().contains("Anime", ignoreCase = true) }
        val statusText = document.selectFirst("ul.entry-meta.block-film li:has(label:containsOwn(Đang phát:)) span")?.text()
        val hasLatestEpisodeDiv = document.selectFirst("div.latest-episode") != null
        val isTvSeries = statusText?.contains("Tập", ignoreCase = true) == true ||
                         (statusText?.contains("Hoàn Tất", ignoreCase = true) == true && statusText != "Hoàn Tất") ||
                         durationText?.contains("Tập", ignoreCase = true) == true ||
                         hasLatestEpisodeDiv

        val tvType = if (isAnime) TvType.Anime else if (isTvSeries) TvType.TvSeries else TvType.Movie

        if (tvType == TvType.Movie) {
            return newMovieLoadResponse(title, url, tvType, episodeListPageUrl ?: url) {
                this.posterUrl = posterUrl; this.year = year; this.plot = plot; this.tags = tags; this.duration = durationInMinutes; this.rating = rating; this.recommendations = recommendations
            }
        } else { // TvSeries và Anime
            val episodes = mutableListOf<Episode>()
            if (!episodeListPageUrl.isNullOrBlank()) {
                try {
                    val episodeListDocument = app.get(episodeListPageUrl, headers = browserHeaders).document
                    val episodeElements = episodeListDocument.select("div#list-server div.server-group ul#list_episodes li a")
                    if (episodeElements.isNotEmpty()) {
                        episodes.addAll(episodeElements.reversed().mapNotNull { ep ->
                            val epHref = ep.attr("abs:href")
                            val epName = ep.ownText().trim().ifBlank { ep.attr("title").trim() }
                            val episodeNumber = Regex("""tập\s*(\d+)""").find(epName.lowercase())?.groupValues?.get(1)?.toIntOrNull()
                            if (epHref.isNotBlank()) newEpisode(epHref) { this.name = epName; this.episode = episodeNumber } else null
                        })
                    }
                } catch (_: Exception) { }
            }

            if (episodes.isEmpty()) {
                episodes.add(newEpisode(url) { this.name = "Không tìm thấy danh sách tập" })
            }
            
            return newTvSeriesLoadResponse(title, url, tvType, episodes) {
                this.posterUrl = posterUrl; this.year = year; this.plot = plot; this.tags = tags; this.duration = durationInMinutes; this.rating = rating; this.recommendations = recommendations
            }
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val episodeId = Regex("-pm(\\d+)").find(data)?.groupValues?.get(1) ?: return false

        val document = app.get(data, headers = browserHeaders).document
        val servers = document.select("#pm-server a.btn-link-backup")

        servers.apmap { server ->
            val serverIndex = server.attr("data-index")
            val serverName = server.text()
            
            val apiResponse = app.post(
                url = "$mainUrl/chillsplayer.php",
                headers = browserHeaders + mapOf(
                    "Content-Type" to "application/x-www-form-urlencoded; charset=UTF-8",
                    "X-Requested-With" to "XMLHttpRequest",
                    "Referer" to data
                ),
                data = mapOf("qcao" to episodeId, "sv" to serverIndex)
            ).text

            val directLink = Regex("""initPlayer\("([^"]+)""").find(apiResponse)?.groupValues?.get(1)
            if (directLink != null) {
                // *** SỬA LỖI DEPRECATED 4 ***
                callback.invoke(
                    ExtractorLink(
                        source = this.name,
                        name = serverName,
                        url = directLink,
                        referer = data,
                        quality = Qualities.Unknown.value,
                        type = ExtractorLinkType.M3U8, // Dùng type thay cho isM3u8
                        headers = browserHeaders // Thêm headers
                    )
                )
            } else {
                 val contentId = Regex("""iniPlayers\("([^"]*)""").find(apiResponse)?.groupValues?.get(1)
                 if (!contentId.isNullOrBlank()) {
                    val m3u8Link = when(serverIndex) {
                        "0", "1" -> "https://sotrim.topphimmoi.org/raw/$contentId/index.m3u8"
                        "2" -> "https://dash.megacdn.xyz/raw/$contentId/index.m3u8"
                        else -> null
                    }
                    if (m3u8Link != null) {
                        // *** SỬA LỖI DEPRECATED 5 ***
                         callback.invoke(
                            ExtractorLink(
                                source = this.name,
                                name = serverName,
                                url = m3u8Link,
                                referer = data,
                                quality = Qualities.Unknown.value,
                                type = ExtractorLinkType.M3U8, // Dùng type thay cho isM3u8
                                headers = browserHeaders // Thêm headers
                            )
                        )
                    }
                }
            }
        }
        return true
    }
}
