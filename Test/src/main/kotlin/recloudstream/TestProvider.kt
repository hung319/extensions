package recloudstream

import android.util.Base64
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import com.lagradost.cloudstream3.utils.AppUtils.toJson // Cần thiết để chuyển map thành JSON string
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.loadExtractor
import org.jsoup.nodes.Element
import org.jsoup.Jsoup
import java.net.URLEncoder // Cần thiết cho việc encode URL

class Anime47Provider : MainAPI() {
    override var mainUrl = "https://anime47.fun"
    override var name = "Anime47"
    override val hasMainPage = true
    override var lang = "vi"
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(TvType.Anime, TvType.AnimeMovie, TvType.OVA)

    private data class MasterPlaylistApiResponse(val masterPlaylistUrl: String?)

    private suspend fun sendLog(logMessage: String) {
        val encodedLog = try {
            Base64.encodeToString(logMessage.toByteArray(), Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
        } catch (e: Exception) { "log_encoding_error::${e.message}".take(50) }
        val logUrl = "https://text.013666.xyz/upload/text/logs.txt/$encodedLog"
        try { app.get(logUrl, timeout = 5) } catch (e: Exception) { println("Failed to send log: ${e.message}") }
    }

    private fun getBackgroundImageUrl(element: Element?): String? {
        val style = element?.attr("style")
        return style?.let {
            Regex("""background-image:\s*url\(['"]?(.*?)['"]?\)""").find(it)?.groupValues?.getOrNull(1)
        }
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse? {
        if (page > 1) return null
        val lists = mutableListOf<HomePageList>()
        try {
            val document = app.get(mainUrl).document
            val mainPageContainer = document.selectFirst("div.block.update div.content[data-name=all] ul.last-film-box")
            if (mainPageContainer != null) {
                val homePageList = mainPageContainer.select("li")
                    .mapNotNull { element ->
                        val movieItemLink = element.selectFirst("a.movie-item") ?: return@mapNotNull null
                        val href = movieItemLink.attr("href")?.let { fixUrl(it) }
                        val title = movieItemLink.selectFirst("div.movie-title-1")?.text()?.trim()
                        val imageDiv = movieItemLink.selectFirst("div.public-film-item-thumb")
                        val image = getBackgroundImageUrl(imageDiv)
                        if (href != null && title != null) {
                            newAnimeSearchResponse(title, href, TvType.Anime) {
                                this.posterUrl = fixUrlNull(image)
                            }
                        } else {
                            null
                        }
                    }
                lists.add(HomePageList("Mới Cập Nhật", homePageList))
            }
            val nominatedContainer = document.selectFirst("div.nominated-movie ul#movie-carousel-top")
            if (nominatedContainer != null) {
                val nominatedList = nominatedContainer.select("li").mapNotNull { element ->
                    val movieItemLink = element.selectFirst("a.movie-item") ?: return@mapNotNull null
                    val href = movieItemLink.attr("href")?.let { fixUrl(it) }
                    val title = movieItemLink.selectFirst("div.movie-title-1")?.text()?.trim()
                    val imageDiv = movieItemLink.selectFirst("div.public-film-item-thumb")
                    val image = getBackgroundImageUrl(imageDiv)
                    if (href != null && title != null) {
                        newAnimeSearchResponse(title, href, TvType.Anime) {
                            this.posterUrl = fixUrlNull(image)
                        }
                    } else {
                        null
                    }
                }
                lists.add(HomePageList("Phim Đề Cử", nominatedList))
            }
        } catch (e: Exception) {
            sendLog("Error in getMainPage: ${e.message}")
            e.printStackTrace()
        }
        if (lists.isEmpty()) return null
        return HomePageResponse(lists)
    }

    override suspend fun search(query: String): List<SearchResponse>? {
        val searchUrl = "$mainUrl/tim-nang-cao/?keyword=${URLEncoder.encode(query, "UTF-8")}"
        return try {
            val document = app.get(searchUrl).document
            document.select("ul#movie-last-movie li")
                .mapNotNull { element ->
                    val movieItemLink = element.selectFirst("a.movie-item") ?: return@mapNotNull null
                    val href = movieItemLink.attr("href")?.let { fixUrl(it) }
                    val title = movieItemLink.selectFirst("div.movie-title-1")?.text()?.trim()
                    val imageDiv = movieItemLink.selectFirst("div.public-film-item-thumb")
                    val image = getBackgroundImageUrl(imageDiv)
                    if (href != null && title != null) {
                        newAnimeSearchResponse(title, href, TvType.Anime) {
                            this.posterUrl = fixUrlNull(image)
                        }
                    } else {
                        null
                    }
                }
        } catch (e: Exception) {
            sendLog("Error in search for query '$query': ${e.message}")
            e.printStackTrace()
            null
        }
    }

    override suspend fun load(url: String): LoadResponse? {
        try {
            val infoDocument = app.get(url).document
            val title = infoDocument.selectFirst("h1.movie-title span.title-1")?.text()?.trim()
            val poster = fixUrlNull(infoDocument.selectFirst("div.movie-l-img img")?.attr("src"))
            val description = infoDocument.selectFirst("div#film-content div.news-article")?.text()?.trim()
            val dlInfo = infoDocument.selectFirst("dl.movie-dl")
            val statusText = dlInfo?.selectFirst("dt:contains(Trạng thái:) + dd")?.text()?.trim()
            val genres = dlInfo?.select("dt:contains(Thể loại:) + dd a")?.mapNotNull { it.text() }
            val yearText = dlInfo?.selectFirst("dt:contains(Năm:) + dd a")?.text()?.trim()
            val yearFromTitle = infoDocument.selectFirst("h1.movie-title span.title-year")?.text()?.replace(Regex("[()]"), "")?.trim()?.toIntOrNull()
            val year = yearFromTitle ?: yearText?.toIntOrNull()
            val status = when {
                statusText == null -> null
                statusText.matches(Regex("""^\d+\s*/\s*\d+$""")) -> {
                    val parts = statusText.split("/").mapNotNull { it.trim().toIntOrNull() }
                    if (parts.size == 2 && parts[0] == parts[1]) ShowStatus.Completed else ShowStatus.Ongoing
                }
                statusText.contains("Hoàn thành", ignoreCase = true) -> ShowStatus.Completed
                statusText.contains("Đang tiến hành", ignoreCase = true) -> ShowStatus.Ongoing
                else -> ShowStatus.Ongoing
            }
            val recommendations = mutableListOf<SearchResponse>()
            infoDocument.select("div#div1 div.CSSTableGenerator tbody tr")?.forEach { row ->
                val titleElement = row.selectFirst("td:nth-child(1) a")
                val href = titleElement?.attr("href")?.let { fixUrl(it) }
                val recTitle = titleElement?.text()?.trim()
                if (href != null && recTitle != null && !url.contains(href)) {
                    recommendations.add(newAnimeSearchResponse(recTitle, href, TvType.Anime))
                }
            }

            var episodes = listOf<Episode>()
            val infoScriptContent = infoDocument.select("script:containsData(episodePlay =)").html()
            val firstEpisodeRelativeUrl = Regex("""episodePlay\s*=\s*['"](.[^'"]+)['"];""").find(infoScriptContent)?.groupValues?.getOrNull(1)
            val firstEpisodeUrl = firstEpisodeRelativeUrl?.let { fixUrl(it.removePrefix(".")) }

            if (firstEpisodeUrl != null) {
                try {
                    val watchPageDocument = app.get(firstEpisodeUrl).document
                    episodes = watchPageDocument.select("div.episodes ul li a")
                        .mapNotNull { element ->
                            val epHref = element.attr("href")?.let { fixUrl(it) }
                            val epTitle = element.attr("title")?.trim()
                            val epNameFromText = element.text()?.trim()
                            val potentialName = if (!epTitle.isNullOrBlank()) epTitle else epNameFromText
                            if (epHref != null && !potentialName.isNullOrBlank()) {
                                val epNum = potentialName.filter { it.isDigit() }.toIntOrNull()
                                val finalEpName = epNum?.let { "Tập $it" } ?: potentialName
                                Episode(
                                    data = epHref,
                                    name = finalEpName,
                                    episode = epNum
                                )
                            } else {
                                null
                            }
                        }.reversed()
                } catch (e: Exception) {
                    sendLog("Error fetching/parsing first episode page in load ($firstEpisodeUrl): ${e.message}")
                }
            } else {
                sendLog("Could not find first episode link in script for url: $url")
            }

            if (episodes.isEmpty()) {
                sendLog("Episode list is empty for url: $url. Trying fallback from 'anyEpisodes'.")
                val infoEpisodeHtmlString = Regex("""anyEpisodes\s*=\s*['"](.*?)['"];""", RegexOption.DOT_MATCHES_ALL).find(infoScriptContent)?.groupValues?.getOrNull(1)
                if (infoEpisodeHtmlString != null) {
                    episodes = Jsoup.parse(infoEpisodeHtmlString).select("div.episodes ul li a")
                        .mapNotNull { element ->
                            val epHref = element.attr("href")?.let { fixUrl(it) }
                            val epTitle = element.attr("title")?.trim()
                            val epNameFromText = element.text()?.trim()
                            val potentialName = if (!epTitle.isNullOrBlank()) epTitle else epNameFromText
                            if (epHref != null && !potentialName.isNullOrBlank()) {
                                val epNum = potentialName.filter { it.isDigit() }.toIntOrNull()
                                val finalEpName = epNum?.let { "Tập $it" } ?: potentialName
                                Episode(
                                    data = epHref,
                                    name = finalEpName,
                                    episode = epNum
                                )
                            } else {
                                null
                            }
                        }.reversed()
                } else {
                    sendLog("Could not extract episodes from anyEpisodes fallback for url: $url")
                }
            }

            return newTvSeriesLoadResponse(title ?: "Không có tiêu đề", url, TvType.Anime, episodes) {
                this.posterUrl = poster
                this.year = year
                this.plot = description
                this.tags = genres
                this.showStatus = status
                if (recommendations.isNotEmpty()) {
                    this.recommendations = recommendations
                }
            }
        } catch (e: Exception) {
            sendLog("Error in load function for url '$url': ${e.message}")
            e.printStackTrace()
            return null
        }
    }

    override suspend fun loadLinks(
        data: String, // Watch page URL
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        var sourceLoaded = false
        val episodeId = data.substringAfterLast('/').substringBefore('.').trim()
        val preferredServerId = "4" 
        val serverNameDisplay = "Fe" 

        val commonUA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/110.0.0.0 Safari/537.36"
        val thanhhoaRegex = Regex("""var\s+thanhhoa\s*=\s*atob\(['"](.*?)['"]\)""")
        val externalDecryptApiBase = "https://m3u8.013666.xyz/anime47/link/"
        val proxyBaseUrl = "https://proxy.h4rs.io.vn/proxy" // Thêm base URL cho proxy

        sendLog("loadLinks started for: $data - Attempting with preferred server $serverNameDisplay (ID: $preferredServerId) via external API.")

        try {
            sendLog("Attempting server $serverNameDisplay (ID: $preferredServerId)")
            val apiUrl = "$mainUrl/player/player.php"
            val apiHeaders = mapOf( "X-Requested-With" to "XMLHttpRequest", "User-Agent" to commonUA, "Origin" to mainUrl, "Referer" to data )
            val apiResponse = app.post(apiUrl, data = mapOf("ID" to episodeId, "SV" to preferredServerId), referer = data, headers = apiHeaders).document

            val apiScript = apiResponse.select("script:containsData(jwplayer(\"player\").setup)").html()
            val tracksRegex = Regex("""tracks:\s*(\[.*?\])""", RegexOption.DOT_MATCHES_ALL)
            tracksRegex.find(apiScript)?.groupValues?.getOrNull(1)?.let { tracksJson ->
                try {
                    val subtitleRegex = Regex("""file:\s*["'](.*?)["'].*?label:\s*["'](.*?)["']""")
                    subtitleRegex.findAll(tracksJson).forEach { match ->
                        subtitleCallback(SubtitleFile(match.groupValues[2], fixUrl(match.groupValues[1])))
                    }
                } catch (e: Exception) { sendLog("Error parsing subtitles from API server $preferredServerId: $e") }
            }

            val apiThanhhoaBase64 = thanhhoaRegex.find(apiScript)?.groupValues?.getOrNull(1)

            if (apiThanhhoaBase64 != null) {
                sendLog("Server $serverNameDisplay: Found thanhhoa Base64 string: ${apiThanhhoaBase64.take(50)}...")
                val decryptUrl = "$externalDecryptApiBase$apiThanhhoaBase64"
                sendLog("Server $serverNameDisplay: Calling external decrypt API: $decryptUrl")

                try {
                    val decryptApiResponseText = app.get(decryptUrl).text
                    sendLog("Server $serverNameDisplay: External API raw response: ${decryptApiResponseText.take(200)}...")

                    val masterPlaylistData = try {
                        parseJson<MasterPlaylistApiResponse>(decryptApiResponseText)
                    } catch (e: Exception) {
                        sendLog("Server $serverNameDisplay: Failed to parse JSON from external API: ${e.message}. Response: ${decryptApiResponseText.take(200)}")
                        null
                    }
                    val videoUrl = masterPlaylistData?.masterPlaylistUrl

                    if (videoUrl != null && videoUrl.startsWith("http")) {
                        sendLog("Server $serverNameDisplay: Success! Extracted original M3U8 URL: $videoUrl")

                        // === BẮT ĐẦU LOGIC PROXY ===
                        val proxyRequestHeaders = mapOf("Referer" to data) // `data` là URL trang xem phim gốc
                        val proxyHeadersJsonString = toJson(proxyRequestHeaders) // Sử dụng AppUtils.toJson

                        val encodedOriginalUrl = URLEncoder.encode(videoUrl, "UTF-8")
                        val encodedHeaders = URLEncoder.encode(proxyHeadersJsonString, "UTF-8")

                        val proxiedM3u8Url = "$proxyBaseUrl?url=$encodedOriginalUrl&headers=$encodedHeaders"
                        sendLog("Server $serverNameDisplay: Using proxied M3U8 URL: $proxiedM3u8Url")
                        // === KẾT THÚC LOGIC PROXY ===

                        callback(
                            ExtractorLink(
                                source = "$name $serverNameDisplay (Proxied)", // Cập nhật tên nguồn
                                name = "$name $serverNameDisplay HLS",
                                url = proxiedM3u8Url, // Sử dụng URL đã qua proxy
                                referer = data, // Vẫn giữ referer gốc cho context của player nếu cần
                                quality = Qualities.Unknown.value,
                                type = ExtractorLinkType.M3U8,
                            )
                        )
                        sourceLoaded = true
                    } else {
                        sendLog("Server $serverNameDisplay: Failed to get 'masterPlaylistUrl' from external API response or URL invalid. Parsed: $videoUrl, Raw: ${decryptApiResponseText.take(200)}")
                    }
                } catch (apiError: Exception) {
                    sendLog("Server $serverNameDisplay: Error calling external decrypt API ($decryptUrl): ${apiError.message}")
                    apiError.printStackTrace()
                }
            } else {
                sendLog("API Response (Server $serverNameDisplay) does not contain 'thanhhoa'. Checking for iframes in player response...")
                apiResponse.select("iframe[src]").forEach { iframe ->
                    if (sourceLoaded) return@forEach 
                    val iframeSrc = iframe.attr("src")?.let { if (it.startsWith("//")) "https:$it" else it } ?: return@forEach
                     if (!iframeSrc.contains("facebook.com")) {
                        sendLog("Server $serverNameDisplay: Found iframe fallback in player response: $iframeSrc")
                        if (loadExtractor(iframeSrc, data, subtitleCallback, callback)) {
                           sourceLoaded = true
                        }
                    }
                }
            }
        } catch (e: Exception) {
            sendLog("Error loading or processing with preferred server $preferredServerId: ${e.message}")
            e.printStackTrace()
        }

        if (!sourceLoaded) {
            sendLog("Preferred server API or its iframe fallbacks failed. Trying iframe fallback on original page: $data")
            try {
                val document = app.get(data, referer = data).document
                document.select("iframe[src]").forEach { iframe ->
                    if (sourceLoaded) return@forEach
                    val iframeSrc = iframe.attr("src")?.let { if (it.startsWith("//")) "https:$it" else it } ?: return@forEach
                    if (!iframeSrc.contains("facebook.com")) {
                        sendLog("Found iframe fallback on original page: $iframeSrc")
                        if (loadExtractor(iframeSrc, data, subtitleCallback, callback)) {
                            sourceLoaded = true
                        }
                    }
                }
            } catch (e: Exception) {
                sendLog("Error searching for iframe fallback on original page ($data): $e")
                e.printStackTrace()
            }
        }

        if (!sourceLoaded) {
            sendLog("Failed to load video link from any source for: $data")
            println("Không thể load được link video từ bất kỳ nguồn nào cho: $data")
        }
        return sourceLoaded
    }

    private fun getQualityFromString(str: String?): Int {
        return Regex("""\b(\d{3,4})p?\b""").find(str ?: "")?.groupValues?.getOrNull(1)?.toIntOrNull()
            ?: Qualities.Unknown.value
    }
}
