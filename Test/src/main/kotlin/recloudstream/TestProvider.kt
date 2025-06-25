package recloudstream

import android.util.Base64
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.utils.AppUtils.parseJson // Để parse JSON
import com.lagradost.cloudstream3.utils.AppUtils.toJson // Để chuyển object sang JSON string
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.loadExtractor
import org.jsoup.nodes.Element
import org.jsoup.Jsoup
import java.net.URLEncoder // Để encode URL components

class Anime47Provider : MainAPI() {
    override var mainUrl = "https://anime47.fun"
    override var name = "Anime47"
    override val hasMainPage = true
    override var lang = "vi"
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(TvType.Anime, TvType.AnimeMovie, TvType.OVA)

    // Data class cho phản hồi JSON từ API giải mã link
    private data class MasterPlaylistApiResponse(val masterPlaylistUrl: String?)

    // Hàm gửi log (nếu cần thiết cho việc debug)
    private suspend fun sendLog(logMessage: String) {
        val encodedLog = try {
            Base64.encodeToString(logMessage.toByteArray(), Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
        } catch (e: Exception) { "log_encoding_error::${e.message}".take(50) }
        val logUrl = "https://text.013666.xyz/upload/text/logs.txt/$encodedLog" // Thay bằng URL server log của bạn
        try { app.get(logUrl, timeout = 5) } catch (e: Exception) { println("Failed to send log: ${e.message}") }
    }

    // Hàm lấy URL ảnh nền từ style attribute
    private fun getBackgroundImageUrl(element: Element?): String? {
        val style = element?.attr("style")
        return style?.let {
            Regex("""background-image:\s*url\(['"]?(.*?)['"]?\)""").find(it)?.groupValues?.getOrNull(1)
        }
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse? {
        if (page > 1) return null // Trang chủ chỉ có 1 trang

        val lists = mutableListOf<HomePageList>()
        try {
            val document = app.get(mainUrl).document

            // Phần "Mới Cập Nhật"
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
                if (homePageList.isNotEmpty()) {
                    lists.add(HomePageList("Mới Cập Nhật", homePageList))
                }
            }

            // Phần "Phim Đề Cử"
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
                if (nominatedList.isNotEmpty()) {
                    lists.add(HomePageList("Phim Đề Cử", nominatedList))
                }
            }
        } catch (e: Exception) {
            sendLog("Error in getMainPage: ${e.message}")
            e.printStackTrace()
        }
        if (lists.isEmpty()) return null
        return HomePageResponse(lists)
    }

    override suspend fun search(query: String): List<SearchResponse>? {
        // Luôn encode query để tránh lỗi với ký tự đặc biệt
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
                else -> ShowStatus.Ongoing // Mặc định
            }

            val recommendations = mutableListOf<SearchResponse>()
            infoDocument.select("div#div1 div.CSSTableGenerator tbody tr")?.forEach { row ->
                val titleElement = row.selectFirst("td:nth-child(1) a")
                val href = titleElement?.attr("href")?.let { fixUrl(it) }
                val recTitle = titleElement?.text()?.trim()
                // Đảm bảo không tự đề xuất chính nó
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
                        }.reversed() // Đảo ngược để tập mới nhất ở đầu danh sách (nếu cần)
                } catch (e: Exception) {
                    sendLog("Error fetching/parsing first episode page in load ($firstEpisodeUrl): ${e.message}")
                    // Không ném lỗi ở đây để thử fallback
                }
            } else {
                sendLog("Could not find first episode link in script for url: $url")
            }

            // Fallback nếu không lấy được episode từ trang xem phim
            if (episodes.isEmpty()) {
                sendLog("Episode list is empty from watch page for url: $url. Trying fallback from 'anyEpisodes'.")
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
        data: String, // Watch page URL (e.g., https://anime47.fun/.../episode-1.html)
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        var sourceLoaded = false
        val episodeId = data.substringAfterLast('/').substringBefore('.').trim()
        val preferredServerId = "4" // Server "Fe"
        val serverNameDisplay = "Fe"

        val commonUA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/110.0.0.0 Safari/537.36"
        val thanhhoaRegex = Regex("""var\s+thanhhoa\s*=\s*atob\(['"](.*?)['"]\)""")
        val externalDecryptApiBase = "https://m3u8.013666.xyz/anime47/link/"
        val proxyBaseUrl = "https://proxy.h4rs.io.vn/proxy"

        sendLog("loadLinks started for: $data - Attempting with preferred server $serverNameDisplay (ID: $preferredServerId) via external API and M3U8 Proxy.")

        try {
            sendLog("Attempting server $serverNameDisplay (ID: $preferredServerId) to get 'thanhhoa'")
            val apiUrl = "$mainUrl/player/player.php"
            // Headers cho request lấy 'thanhhoa'
            val apiHeaders = mapOf(
                "X-Requested-With" to "XMLHttpRequest",
                "User-Agent" to commonUA,
                "Origin" to mainUrl, // Origin là trang anime47.fun
                "Referer" to data    // Referer là trang xem phim hiện tại
            )
            val apiResponse = app.post(apiUrl, data = mapOf("ID" to episodeId, "SV" to preferredServerId), referer = data, headers = apiHeaders).document

            // Lấy phụ đề từ player.php response
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
                sendLog("Server $serverNameDisplay: Found thanhhoa Base64: ${apiThanhhoaBase64.take(50)}...")
                val decryptUrl = "$externalDecryptApiBase$apiThanhhoaBase64"
                sendLog("Server $serverNameDisplay: Calling external decrypt API: $decryptUrl")

                try {
                    // Request tới API giải mã link
                    val decryptApiResponseText = app.get(decryptUrl).text // API này có thể không cần header đặc biệt
                    sendLog("Server $serverNameDisplay: External API raw response: ${decryptApiResponseText.take(200)}...")

                    val masterPlaylistData = try {
                        parseJson<MasterPlaylistApiResponse>(decryptApiResponseText)
                    } catch (e: Exception) {
                        sendLog("Server $serverNameDisplay: Failed to parse JSON from external API: ${e.message}. Response: ${decryptApiResponseText.take(200)}")
                        null
                    }
                    val videoUrl = masterPlaylistData?.masterPlaylistUrl // Đây là link M3U8 gốc từ pl.vlogphim.net

                    if (videoUrl != null && videoUrl.startsWith("http")) {
                        sendLog("Server $serverNameDisplay: Success! Extracted original M3U8 URL: $videoUrl")

                        // === BẮT ĐẦU LOGIC PROXY M3U8 ===
                        // Headers mà proxy sẽ sử dụng khi nó yêu cầu M3U8 gốc và các segment
                        val proxyRequestHeaders = mapOf(
                            "Referer" to data,          // Referer là trang xem phim anime47.fun
                            "User-Agent" to commonUA,   // User-Agent trình duyệt
                            "Origin" to mainUrl         // Origin là anime47.fun
                        )
                        val proxyHeadersJsonString = proxyRequestHeaders.toJson()

                        val encodedOriginalUrl = URLEncoder.encode(videoUrl, "UTF-8")
                        val encodedHeaders = URLEncoder.encode(proxyHeadersJsonString, "UTF-8")

                        val proxiedM3u8Url = "$proxyBaseUrl?url=$encodedOriginalUrl&headers=$encodedHeaders"
                        sendLog("Server $serverNameDisplay: Using proxied M3U8 URL: $proxiedM3u8Url with proxy headers: $proxyHeadersJsonString")
                        // === KẾT THÚC LOGIC PROXY M3U8 ===

                        callback(
                            ExtractorLink(
                                source = "$name $serverNameDisplay (Proxied)",
                                name = "$name $serverNameDisplay HLS",
                                url = proxiedM3u8Url, // Sử dụng URL đã qua proxy
                                referer = data,       // Vẫn truyền referer gốc cho player
                                quality = Qualities.Unknown.value,
                                type = ExtractorLinkType.M3U8
                                // Không cần ExtractorLink.headers riêng ở đây, vì proxy đã đảm nhiệm việc chèn header
                            )
                        )
                        sourceLoaded = true
                    } else {
                        sendLog("Server $serverNameDisplay: Failed to get 'masterPlaylistUrl' from external API or URL invalid. Parsed: $videoUrl, Raw: ${decryptApiResponseText.take(200)}")
                    }
                } catch (apiError: Exception) {
                    sendLog("Server $serverNameDisplay: Error calling external decrypt API ($decryptUrl): ${apiError.message}")
                    apiError.printStackTrace()
                }
            } else {
                // Fallback: Nếu không có 'thanhhoa', thử tìm iframe trong phản hồi của player.php
                sendLog("API Response (Server $serverNameDisplay) does not contain 'thanhhoa'. Checking for iframes in player response...")
                apiResponse.select("iframe[src]").forEach { iframe ->
                    if (sourceLoaded) return@forEach
                    val iframeSrc = iframe.attr("src")?.let { if (it.startsWith("//")) "https:$it" else it } ?: return@forEach
                     if (!iframeSrc.contains("facebook.com")) { // Bỏ qua iframe của facebook
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

        // Fallback cuối cùng: Nếu phương thức API server ưu tiên (và iframe trong đó) thất bại, thử tìm iframe trên trang gốc
        if (!sourceLoaded) {
            sendLog("Preferred server API (or its internal iframe) failed. Trying iframe fallback on original page: $data")
            try {
                val document = app.get(data, referer = data).document // Thêm referer cho request này
                document.select("iframe[src]").forEach { iframe ->
                    if (sourceLoaded) return@forEach // Đã tìm thấy ở iframe trước đó
                    val iframeSrc = iframe.attr("src")?.let { if (it.startsWith("//")) "https:$it" else it } ?: return@forEach
                    if (!iframeSrc.contains("facebook.com")) { // Bỏ qua iframe của facebook
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
            // println("Không thể load được link video từ bất kỳ nguồn nào cho: $data") // Có thể bỏ comment nếu cần debug trên console
        }
        return sourceLoaded
    }

    private fun getQualityFromString(str: String?): Int {
        // Trích xuất số từ chuỗi (ví dụ "720p" -> 720)
        return Regex("""\b(\d{3,4})p?\b""").find(str ?: "")?.groupValues?.getOrNull(1)?.toIntOrNull()
            ?: Qualities.Unknown.value
    }
}
