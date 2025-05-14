package recloudstream // Hoặc package đúng của bạn

import android.util.Base64
import com.lagradost.cloudstream3.*
// Đảm bảo các import này có thể được phân giải sau khi sửa build.gradle
import com.lagradost.cloudstream3.utils.AppUtils // Cần cho AppUtils.parseJson
import com.lagradost.cloudstream3.utils.AppUtils.toJson // Extension function cho Any.toJson()
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.loadExtractor
import org.jsoup.nodes.Element
import org.jsoup.Jsoup
import java.net.URLEncoder

// Đặt tên class theo file của bạn, ví dụ: TestProvider nếu file là TestProvider.kt
class Anime47Provider : MainAPI() {
    override var mainUrl = "https://anime47.lat"
    override var name = "Anime47"
    override val hasMainPage = true
    override var lang = "vi"
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(TvType.Anime, TvType.AnimeMovie, TvType.OVA)

    // Data class cho phản hồi giải mã
    private data class DecryptionResponse(val decryptedResult: String?)

    // Hàm gửi log (từ code gốc của bạn)
    private suspend fun sendLog(logMessage: String) {
        val encodedLog = try {
            Base64.encodeToString(logMessage.toByteArray(), Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
        } catch (e: Exception) { "log_encoding_error::${e.message}".take(50) }
        val logUrl = "https://text.013666.xyz/upload/text/logs.txt/$encodedLog"
        try { app.get(logUrl, timeout = 5) } catch (e: Exception) { println("Failed to send log: ${e.message}") }
    }

    // Hàm lấy URL ảnh nền từ style (từ code gốc của bạn)
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

            // Mục "Mới Cập Nhật"
            document.selectFirst("div.block.update div.content[data-name=all] ul.last-film-box")?.let { mainPageContainer ->
                val homePageList = mainPageContainer.select("li").mapNotNull { element ->
                    val movieItemLink = element.selectFirst("a.movie-item") ?: return@mapNotNull null
                    val href = movieItemLink.attr("href")?.let { fixUrl(it) }
                    val title = movieItemLink.selectFirst("div.movie-title-1")?.text()?.trim()
                    val imageDiv = movieItemLink.selectFirst("div.public-film-item-thumb")
                    val image = getBackgroundImageUrl(imageDiv)
                    if (href != null && title != null) {
                        newAnimeSearchResponse(title, href, TvType.Anime) {
                            this.posterUrl = fixUrlNull(image)
                        }
                    } else null
                }
                if (homePageList.isNotEmpty()) lists.add(HomePageList("Mới Cập Nhật", homePageList))
            }

            // Mục "Phim Đề Cử"
            document.selectFirst("div.nominated-movie ul#movie-carousel-top")?.let { nominatedContainer ->
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
                    } else null
                }
                if (nominatedList.isNotEmpty()) lists.add(HomePageList("Phim Đề Cử", nominatedList))
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
            app.get(searchUrl).document.select("ul#movie-last-movie li").mapNotNull { element ->
                val movieItemLink = element.selectFirst("a.movie-item") ?: return@mapNotNull null
                val href = movieItemLink.attr("href")?.let { fixUrl(it) }
                val title = movieItemLink.selectFirst("div.movie-title-1")?.text()?.trim()
                val imageDiv = movieItemLink.selectFirst("div.public-film-item-thumb")
                val image = getBackgroundImageUrl(imageDiv)
                if (href != null && title != null) {
                    newAnimeSearchResponse(title, href, TvType.Anime) {
                        this.posterUrl = fixUrlNull(image)
                    }
                } else null
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
                else -> ShowStatus.Ongoing // Mặc định nếu không khớp
            }

            val recommendations = infoDocument.select("div#div1 div.CSSTableGenerator tbody tr").mapNotNull { row ->
                val titleElement = row.selectFirst("td:nth-child(1) a")
                val href = titleElement?.attr("href")?.let { fixUrl(it) }
                val recTitle = titleElement?.text()?.trim()
                if (href != null && recTitle != null && !url.contains(href)) {
                    newAnimeSearchResponse(recTitle, href, TvType.Anime)
                } else null
            }

            var episodes = listOf<Episode>()
            val infoScriptContent = infoDocument.select("script:containsData(episodePlay =),script:containsData(anyEpisodes =)").html() // Lấy cả hai nếu có
            val firstEpisodeRelativeUrl = Regex("""episodePlay\s*=\s*['"](.[^'"]+)['"];""").find(infoScriptContent)?.groupValues?.getOrNull(1)
            val firstEpisodeUrl = firstEpisodeRelativeUrl?.let { fixUrl(it.removePrefix(".")) }

            if (firstEpisodeUrl != null) {
                try {
                    val watchPageDocument = app.get(firstEpisodeUrl).document
                    episodes = watchPageDocument.select("div.episodes ul li a").mapNotNull { element ->
                        val epHref = element.attr("href")?.let { fixUrl(it) }
                        val epTitleAttr = element.attr("title")?.trim()
                        val epNameFromText = element.text()?.trim()
                        val potentialName = epTitleAttr?.takeIf { it.isNotBlank() } ?: epNameFromText
                        if (epHref != null && !potentialName.isNullOrBlank()) {
                            val epNum = potentialName.filter { it.isDigit() }.toIntOrNull()
                            Episode(epHref, potentialName, episode = epNum)
                        } else null
                    }.reversed()
                } catch (e: Exception) {
                    sendLog("Error fetching/parsing first episode page in load ($firstEpisodeUrl): ${e.message}")
                }
            }

            if (episodes.isEmpty()) {
                sendLog("Episode list from watch page is empty for url: $url. Trying fallback from anyEpisodes.")
                val infoEpisodeHtmlString = Regex("""anyEpisodes\s*=\s*'(.*?)';""").find(infoScriptContent)?.groupValues?.getOrNull(1)
                if (infoEpisodeHtmlString != null) {
                    episodes = Jsoup.parse(infoEpisodeHtmlString).select("div.episodes ul li a").mapNotNull { element ->
                         val epHref = element.attr("href")?.let { fixUrl(it) }
                         val epTitleAttr = element.attr("title")?.trim()
                         val epNameFromText = element.text()?.trim()
                         val potentialName = epTitleAttr?.takeIf { it.isNotBlank() } ?: epNameFromText
                         if (epHref != null && !potentialName.isNullOrBlank()) {
                            val epNum = potentialName.filter { it.isDigit() }.toIntOrNull()
                            Episode(epHref, potentialName, episode = epNum)
                        } else null
                    }.reversed()
                } else {
                    sendLog("Could not extract episodes from anyEpisodes fallback for url: $url")
                }
            }
            
            if (episodes.isEmpty() && firstEpisodeUrl == null) { // Trường hợp không có link tập nào cả, thử tạo 1 tập từ link hiện tại nếu là trang xem phim
                 if (url.contains("/xem-phim/") && title != null) {
                     episodes = listOf(Episode(data = url, name = title))
                     sendLog("No episodes found, creating a single episode from current watch URL: $url")
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
        val serverIds = listOf("4", "2", "7") // Ưu tiên Fe, Fa, Hy
        val commonUA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/110.0.0.0 Safari/537.36"
        val thanhhoaRegex = Regex("""var\s+thanhhoa\s*=\s*atob\(['"](.*?)['"]\)""")
        val externalDecryptApiBase = "https://m3u8.013666.xyz/anime47/link/"
        val m3u8ProxyUrlBase = "https://proxy.h4rs.io.vn/proxy"

        sendLog("loadLinks started for: $data - EpisodeID: $episodeId, Servers: ${serverIds.joinToString()}. Using M3U8 proxy if applicable.")

        suspend fun tryLoadFromServerExternalApi(serverId: String): Boolean {
            var successInner = false
            val serverName = when(serverId) { "2" -> "Fa"; "4" -> "Fe"; "7" -> "Hy"; else -> "SV$serverId" }
            try {
                sendLog("Attempting server $serverName (ID: $serverId) for episode $episodeId")
                val playerPageResponse = app.post(
                    "$mainUrl/player/player.php",
                    data = mapOf("ID" to episodeId, "SV" to serverId),
                    referer = data,
                    headers = mapOf("X-Requested-With" to "XMLHttpRequest", "User-Agent" to commonUA, "Origin" to mainUrl, "Referer" to data)
                )
                val apiScript = playerPageResponse.document.select("script:containsData(jwplayer(\"player\").setup)").html()

                val tracksRegex = Regex("""tracks:\s*(\[.*?\])""", RegexOption.DOT_MATCHES_ALL)
                tracksRegex.find(apiScript)?.groupValues?.getOrNull(1)?.let { tracksJson ->
                    try {
                        val subtitleRegex = Regex("""file:\s*["'](.*?)["'].*?label:\s*["'](.*?)["']""")
                        subtitleRegex.findAll(tracksJson).forEach { match ->
                            val subUrl = fixUrl(match.groupValues[1])
                            val subLabel = match.groupValues[2]
                            subtitleCallback(SubtitleFile(subLabel, subUrl))
                            sendLog("Found subtitle: $subLabel - $subUrl")
                        }
                    } catch (e: Exception) { sendLog("Error parsing subtitles from API server $serverId: $e") }
                }

                val apiThanhhoaBase64 = thanhhoaRegex.find(apiScript)?.groupValues?.getOrNull(1)

                if (apiThanhhoaBase64 != null) {
                    sendLog("Server $serverName: Found thanhhoa Base64: ${apiThanhhoaBase64.take(50)}...")
                    val decryptUrl = "$externalDecryptApiBase$apiThanhhoaBase64"
                    try {
                        val decryptApiResponseText = app.get(decryptUrl).text
                        sendLog("Server $serverName: Decrypt API raw response: ${decryptApiResponseText.take(200)}...")

                        val decryptionResultObject: DecryptionResponse? = try {
                            AppUtils.parseJson<DecryptionResponse>(decryptApiResponseText) // Sử dụng AppUtils.parseJson
                        } catch (e: Exception) {
                            sendLog("Server $serverName: JSON parsing error for DecryptionResponse: ${e.message}")
                            null
                        }
                        val videoUrl: String? = decryptionResultObject?.decryptedResult

                        if (videoUrl != null && videoUrl.startsWith("http")) {
                            sendLog("Server $serverName: URL from decrypt API: $videoUrl")
                            if (videoUrl.contains(".m3u8", ignoreCase = true) || videoUrl.contains("/hls", ignoreCase = true)) {
                                sendLog("Server $serverName: M3U8 link detected. Applying proxy.")
                                val originalM3U8Headers = mapOf(
                                    "Referer" to data,
                                    "User-Agent" to commonUA,
                                    "Origin" to mainUrl
                                )
                                // Sử dụng extension function Any.toJson()
                                val headersJson = originalM3U8Headers.toJson()

                                val encodedVideoUrl = URLEncoder.encode(videoUrl, "UTF-8")
                                val encodedHeadersJson = URLEncoder.encode(headersJson, "UTF-8")
                                val proxiedUrl = "$m3u8ProxyUrlBase?url=$encodedVideoUrl&headers=$encodedHeadersJson"
                                sendLog("Server $serverName: Proxied M3U8 URL: $proxiedUrl")

                                callback(
                                    ExtractorLink(
                                        source = "$name $serverName (Proxy)",
                                        name = "$name $serverName HLS (Proxied)",
                                        url = proxiedUrl,
                                        referer = data,
                                        quality = Qualities.Unknown.value,
                                        type = ExtractorLinkType.M3U8,
                                        headers = emptyMap() // Proxy xử lý headers
                                    )
                                )
                            } else {
                                sendLog("Server $serverName: Non-M3U8 link. Using direct link: $videoUrl")
                                val linkHeaders = mapOf("Referer" to data, "User-Agent" to commonUA, "Origin" to mainUrl)
                                callback(
                                    ExtractorLink(
                                        source = "$name $serverName (Ext)",
                                        name = "$name $serverName",
                                        url = videoUrl,
                                        referer = data,
                                        quality = getQualityFromString(videoUrl), // Thử lấy quality từ URL
                                        type = if (videoUrl.contains(".mp4", ignoreCase = true)) ExtractorLinkType.VIDEO else ExtractorLinkType.M3U8, // Sửa MP4 thành STREAM
                                        headers = linkHeaders
                                    )
                                )
                            }
                            successInner = true
                        } else {
                            sendLog("Server $serverName: Failed to parse video URL or URL invalid. videoUrl: $videoUrl")
                        }
                    } catch (apiError: Exception) {
                        sendLog("Server $serverName: Error calling/processing external decrypt API ($decryptUrl): ${apiError.message}")
                        apiError.printStackTrace()
                    }
                } else {
                    sendLog("API Response (Server $serverName) does not contain 'thanhhoa'. Checking for iframes...")
                    playerPageResponse.document.select("iframe[src]").forEach { iframe ->
                        if (successInner) return@forEach
                        val iframeSrc = iframe.attr("src") ?: return@forEach
                        if (!iframeSrc.contains("facebook.com")) { // Bỏ qua iframe của Facebook
                           sendLog("Server $serverName: Found iframe fallback: $iframeSrc")
                           successInner = loadExtractor(iframeSrc, data, subtitleCallback, callback) || successInner
                        }
                    }
                }
            } catch (e: Exception) {
                sendLog("Error loading or processing server $serverId: ${e.message}")
                e.printStackTrace()
            }
            return successInner
        }

        for (serverId in serverIds) {
            if (tryLoadFromServerExternalApi(serverId)) {
                sourceLoaded = true
                break // Thoát vòng lặp nếu đã tìm thấy link
            }
        }

        if (!sourceLoaded) {
            sendLog("All API server attempts failed. Trying iframe fallback on original page: $data")
            try {
                val document = app.get(data, referer = data).document // data ở đây là URL của trang xem phim
                document.select("div#player iframe[src], div.box-player iframe[src], iframe#player[src]").forEach { iframe ->
                    if (sourceLoaded) return@forEach
                    val iframeSrc = iframe.attr("src")?.let { fixUrl(it) } ?: return@forEach
                     if (!iframeSrc.contains("facebook.com")) {
                        sendLog("Found iframe fallback on original page: $iframeSrc")
                        sourceLoaded = loadExtractor(iframeSrc, data, subtitleCallback, callback) || sourceLoaded
                    }
                }
            } catch (e: Exception) {
                sendLog("Error searching for iframe fallback on original page: ${e.message}")
            }
        }

        if (!sourceLoaded) {
            sendLog("Failed to load video link from any source for: $data")
        }
        return sourceLoaded
    }

    // Hàm lấy chất lượng từ chuỗi (từ code gốc của bạn, có thể cải tiến)
    private fun getQualityFromString(str: String?): Int {
        return Regex("""\b(\d{3,4})p?\b""").find(str ?: "")?.groupValues?.getOrNull(1)?.toIntOrNull()
            ?: Qualities.Unknown.value
    }

    // Các hàm tiện ích như fixUrl, fixUrlNull, newAnimeSearchResponse, newTvSeriesLoadResponse
    // thường là một phần của MainAPI hoặc được cung cấp bởi Cloudstream.
    // Nếu bạn đang ở trong một module "Test" không kế thừa đầy đủ hoặc không có các extension functions này,
    // bạn có thể cần phải tự định nghĩa chúng hoặc import đúng cách.
    // Ví dụ:
    // fun fixUrl(url: String): String {
    //     if (url.startsWith("//")) return "https:$url"
    //     if (!url.startsWith("http")) return "$mainUrl/$url".removeSuffix("/")
    //     return url
    // }
    // fun fixUrlNull(url: String?): String? {
    //    return url?.let { fixUrl(it) }
    // }
}
