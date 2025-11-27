package recloudstream

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.AppUtils
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.mvvm.logError
import java.net.URLEncoder
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch // Cần import launch

class Kuudere : MainAPI() {
    override var mainUrl = "https://kuudere.to"
    override var name = "Kuudere"
    override var hasMainPage = true
    override var hasChromecastSupport = true
    override var hasDownloadSupport = true
    override var supportedTypes = setOf(TvType.Anime, TvType.AnimeMovie, TvType.OVA)
    override var lang = "en"

    private val commonHeaders = mapOf(
        "User-Agent" to "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/137.0.0.0 Mobile Safari/537.36",
        "Referer" to "$mainUrl/",
        "Sec-Ch-Ua" to "\"Chromium\";v=\"137\", \"Not/A)Brand\";v=\"24\"",
        "Sec-Ch-Ua-Mobile" to "?1",
        "Sec-Ch-Ua-Platform" to "\"Android\"",
        "Accept" to "application/json, text/plain, */*"
    )

    // ================= DATA CLASSES =================
    data class HomeResult(val success: Boolean, val data: List<HomeItem>?)
    data class HomeItem(val title: String, val image: String?, val url: String, val stats: Map<String, Int>?)

    data class SearchResult(val success: Boolean, val results: List<SearchItem>?)
    data class SearchItem(val id: String, val title: String, val details: String?, val coverImage: String?)

    data class DetailsResult(val success: Boolean, val data: AnimeInfo?)
    data class AnimeInfo(
        val id: String,
        val english: String?,
        val romaji: String?,
        val native: String?,
        val description: String?,
        val cover: String?,
        val banner: String?,
        val status: String?,
        val genres: List<String>?,
        val year: Int?,
        val epCount: Int?
    )

    data class RecommendationResponse(val success: Boolean, val recommendations: List<RecommendationItem>?)
    data class RecommendationItem(
        val id: String, val title: String, val cover: String?, val type: String?, val year: Int?, val status: String?, val averageScore: Int?
    )

    data class SvelteResponse(val body: String)
    data class PlayerData(val episode_links: List<PlayerLink>?)
    data class PlayerLink(val serverName: String, val dataLink: String, val dataType: String)

    // ================= MAIN PAGE =================
    override val mainPage = mainPageOf(
        "$mainUrl/api/top/anime?tab=today&limit=20" to "Top Anime (Today)",
        "$mainUrl/api/top/anime?tab=week&limit=20" to "Top Anime (Week)",
        "$mainUrl/api/top/anime?tab=month&limit=20" to "Top Anime (Month)"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse? {
        val json = app.get(request.data, headers = commonHeaders).parsedSafe<HomeResult>()
        val home = json?.data?.map { item ->
            newAnimeSearchResponse(item.title, "$mainUrl${item.url}", TvType.Anime) {
                this.posterUrl = item.image
                val sub = item.stats?.get("subbed") ?: 0
                val dub = item.stats?.get("dubbed") ?: 0
                addQuality("Sub: $sub | Dub: $dub")
            }
        } ?: listOf()
        return newHomePageResponse(request.name, home)
    }

    // ================= SEARCH =================
    override suspend fun search(query: String): List<SearchResponse> {
        val encodedQuery = URLEncoder.encode(query, "UTF-8")
        val url = "$mainUrl/api/search?q=$encodedQuery"
        val json = app.get(url, headers = commonHeaders).parsedSafe<SearchResult>()
        return json?.results?.map { item ->
            val href = "$mainUrl/anime/${item.id}"
            val year = item.details?.substringBefore("•")?.trim()?.toIntOrNull()
            newAnimeSearchResponse(item.title, href, TvType.Anime) {
                this.posterUrl = item.coverImage
                this.year = year
                if (item.details?.contains("Movie") == true) addQuality("Movie")
            }
        } ?: emptyList()
    }

    // ================= LOAD =================
    override suspend fun load(url: String): LoadResponse {
        val response = app.get(url, headers = commonHeaders)
        val json = response.parsedSafe<DetailsResult>()
        val data = json?.data ?: throw ErrorLoadingException("Không thể tải thông tin phim")

        val title = data.english ?: data.romaji ?: data.native ?: "Unknown Title"
        val showStatus = when (data.status) {
            "RELEASING" -> ShowStatus.Ongoing
            "FINISHED" -> ShowStatus.Completed
            else -> null
        }

        val recUrl = "$mainUrl/api/anime/${data.id}/recommendations"
        val recJson = app.get(recUrl, headers = commonHeaders).parsedSafe<RecommendationResponse>()
        val recommendations = recJson?.recommendations?.map { item ->
            newAnimeSearchResponse(item.title, "$mainUrl/anime/${item.id}", TvType.Anime) {
                this.posterUrl = item.cover
                this.year = item.year
                if (item.averageScore != null) addQuality("${item.averageScore}%")
            }
        }

        val watchUrl = "$mainUrl/watch/${data.id}/1"
        val doc = app.get(watchUrl, headers = commonHeaders).document
        var htmlEpisodes = doc.select(".episodes-list a, .list-episodes a, #episodes-page-1 a")
        if (htmlEpisodes.isEmpty()) {
             val detailDoc = app.get("$mainUrl/anime/${data.id}", headers = commonHeaders).document
             htmlEpisodes = detailDoc.select(".episodes-list a, .list-episodes a")
        }

        val episodesList = htmlEpisodes.mapNotNull { element ->
            val href = fixUrl(element.attr("href"))
            val epNum = element.attr("data-num").toIntOrNull() 
                ?: element.select(".num").text().toIntOrNull()
                ?: href.substringAfterLast("/").toIntOrNull()
                ?: return@mapNotNull null
            val rawName = element.attr("title").ifEmpty { element.select(".name").text() }
            val finalName = if (rawName.isNotBlank() && rawName != "Episode $epNum") rawName else "Episode $epNum"
            val isDub = element.hasClass("dub") || href.contains("lang=dub") || element.text().contains("Dub", true)
            val tag = if (isDub) "[DUB]" else "[SUB]"
            newEpisode(href) {
                this.episode = epNum
                this.name = "$finalName $tag"
            }
        }.reversed()

        val finalEpisodes = if (episodesList.isNotEmpty()) episodesList else {
            val count = data.epCount ?: 0
            (1..count).map { epNum ->
                newEpisode("$mainUrl/watch/${data.id}/$epNum") {
                    this.episode = epNum
                    this.name = "Episode $epNum [SUB]"
                }
            }.reversed()
        }

        return newAnimeLoadResponse(title, url, TvType.Anime) {
            this.posterUrl = data.cover
            this.backgroundPosterUrl = data.banner
            this.plot = data.description
            this.tags = data.genres
            this.year = data.year
            this.showStatus = showStatus
            this.episodes = mutableMapOf(DubStatus.Subbed to finalEpisodes)
            this.recommendations = recommendations
        }
    }

    // ================= LOAD LINKS (UPDATED) =================
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean = coroutineScope { 
        val debugLog = StringBuilder()
        debugLog.append("Target: $data\n")
        val htmlHeaders = commonHeaders.toMutableMap()
        htmlHeaders["Accept"] = "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8"
        
        var linksFound = 0

        try {
            val doc = app.get(data, headers = htmlHeaders).document
            val scriptTag = doc.select("script[data-sveltekit-fetched]").find { 
                it.attr("data-url").contains("/api/watch/") 
            }

            if (scriptTag == null) {
                debugLog.append("❌ Error: Svelte script tag NOT found.\n")
            } else {
                debugLog.append("✅ Svelte script tag found.\n")
                val svelteResponse = AppUtils.parseJson<SvelteResponse>(scriptTag.data())
                val playerData = AppUtils.parseJson<PlayerData>(svelteResponse.body)
                val links = playerData.episode_links

                if (links.isNullOrEmpty()) {
                    debugLog.append("⚠️ JSON parsed but 'episode_links' is empty/null.\n")
                } else {
                    debugLog.append("Processing ${links.size} links in parallel...\n")
                    
                    links.map { link ->
                        async {
                            val rawLink = link.dataLink
                            val serverName = "${link.serverName} [${link.dataType.uppercase()}]"
                            debugLog.append("Found: $serverName -> $rawLink\n")

                            if (rawLink.startsWith("http")) {
                                val fixedLink = fixUrl(rawLink)
                                
                                // Gọi loadExtractor
                                val handled = loadExtractor(fixedLink, data, subtitleCallback) { extractorLink ->
                                    linksFound++
                                    
                                    // SỬ DỤNG newExtractorLink (trong launch vì nó là suspend)
                                    launch {
                                        val type = if (extractorLink.isM3u8) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                                        
                                        val newLink = newExtractorLink(
                                            source = extractorLink.source,
                                            name = "$serverName ${extractorLink.name}",
                                            url = extractorLink.url,
                                            type = type
                                        ) {
                                            this.referer = extractorLink.referer
                                            this.quality = extractorLink.quality
                                            this.headers = extractorLink.headers
                                        }
                                        callback(newLink)
                                    }
                                }
                                
                                if (!handled) debugLog.append("  ⚠️ No extractor found for: $fixedLink\n")
                            }
                        }
                    }.awaitAll()
                }
            }

            if (linksFound == 0) {
                debugLog.append("Checking fallback Iframes...\n")
                val iframes = doc.select("iframe")
                iframes.map { iframe ->
                    async {
                        val src = iframe.attr("src")
                        if (src.isNotBlank() && src.startsWith("http")) {
                            debugLog.append("Iframe found: $src\n")
                            loadExtractor(src, data, subtitleCallback) { link ->
                                linksFound++
                                callback(link)
                            }
                        }
                    }
                }.awaitAll()
            }

        } catch (e: Exception) {
            debugLog.append("🔥 Exception: ${e.message}\n")
            e.printStackTrace()
        }

        if (linksFound == 0) {
            throw ErrorLoadingException("No links found!\nLOGS:\n$debugLog")
        }

        return@coroutineScope true
    }
}
