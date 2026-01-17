package recloudstream

import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.databind.JsonNode
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addAniListId
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import java.util.concurrent.TimeUnit
import android.net.Uri

class AnikuroProvider : MainAPI() {
    override var mainUrl = "https://anikuro.ru"
    override var name = "Anikuro"
    override val hasMainPage = true
    override var lang = "en"
    override val hasQuickSearch = true
    override val supportedTypes = setOf(TvType.Anime)

    // Timeout 30s
    private val extendedClient = app.baseClient.newBuilder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    // Cập nhật User-Agent chuẩn hơn để tránh bị chặn
    private val headers = mapOf(
        "authority" to "anikuro.ru",
        "accept" to "*/*",
        "accept-language" to "en-US,en;q=0.9",
        "referer" to "$mainUrl/",
        "sec-ch-ua" to "\"Not_A Brand\";v=\"8\", \"Chromium\";v=\"120\", \"Google Chrome\";v=\"120\"",
        "sec-ch-ua-mobile" to "?1",
        "sec-ch-ua-platform" to "\"Android\"",
        "sec-fetch-dest" to "empty",
        "sec-fetch-mode" to "cors",
        "sec-fetch-site" to "same-origin",
        "user-agent" to "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36"
    )

    private val supportedServers = listOf(
        "zoro", "animepahe", "animekai", "anizone", "animix",
        "allani", "anify", "animeheaven", "animez", "anigg",
        "anixl", "aniw", "anis", "animedunya", "anitaku",
        "anihq", "masterani", "kaa", "gogo", "9anime"
    )

    // =========================================================================
    // 1. MAIN PAGE (OPTIMIZED PARALLEL)
    // =========================================================================
    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val tasks = listOf(
            HomePageData("Trending") { 
                app.get("$mainUrl/api/gettrending/", headers = headers).parsed<TrendingResponse>()
                    .info.mapNotNull { it.toSearchResponse() }
            },
            HomePageData("Schedule") {
                app.get("$mainUrl/api/getschedule/", headers = headers).parsed<ScheduleResponse>()
                    .info.mapNotNull { it.media?.toSearchResponse(currentEpisode = "Ep ${it.episode}") }
            },
            HomePageData("Upcoming") {
                app.get("$mainUrl/api/getupcoming/", headers = headers).parsed<TrendingResponse>()
                    .info.mapNotNull { it.toSearchResponse() }
            },
            HomePageData("Just Updated (Anilist)") {
                fetchAnilistHome()
            }
        )

        // Chạy song song tất cả API trang chủ
        val items = tasks.amap { task ->
            try {
                val list = task.fetcher.invoke()
                if (list.isNotEmpty()) HomePageList(task.title, list) else null
            } catch (e: Exception) {
                null 
            }
        }.filterNotNull()

        return newHomePageResponse(items)
    }

    private data class HomePageData(
        val title: String,
        val fetcher: suspend () -> List<SearchResponse>
    )

    private suspend fun fetchAnilistHome(): List<SearchResponse> {
        val graphqlUrl = "https://graphql.anilist.co/"
        val currentTime = System.currentTimeMillis() / 1000
        val query = """
            query RecentlyAiredEpisodes(${"$"}page: Int, ${"$"}perPage: Int, ${"$"}currentTime: Int) {
                Page(page: ${"$"}page, perPage: ${"$"}perPage) {
                    airingSchedules(sort: TIME_DESC, airingAt_lesser: ${"$"}currentTime) {
                        episode
                        media { id title { romaji english native } coverImage { large medium } format }
                    }
                }
            }
        """
        val body = mapOf("query" to query, "variables" to mapOf("page" to 1, "perPage" to 20, "currentTime" to currentTime))
        val anilistHeaders = mapOf("Content-Type" to "application/json", "Origin" to mainUrl, "Accept" to "application/json")
        
        return try {
            app.post(graphqlUrl, headers = anilistHeaders, json = body).parsed<AnilistResponse>()
                .data?.page?.airingSchedules?.mapNotNull { 
                    it.media?.toSearchResponse(currentEpisode = "Ep ${it.episode}") 
                } ?: emptyList()
        } catch (e: Exception) { emptyList() }
    }

    // =========================================================================
    // 2. SEARCH
    // =========================================================================
    override suspend fun search(query: String): List<SearchResponse> {
        val graphqlUrl = "https://graphql.anilist.co/"
        val graphQuery = """
            query(${'$'}page:Int,${'$'}perPage:Int,${'$'}search:String,${'$'}sort:[MediaSort]) {
              Page(page:${'$'}page,perPage:${'$'}perPage){
                media(type:ANIME,sort:${'$'}sort,isAdult:false,search:${'$'}search){
                  id title{romaji english native} coverImage{extraLarge large medium} format
                }
              }
            }
        """
        val body = mapOf(
            "query" to graphQuery,
            "variables" to mapOf("page" to 1, "perPage" to 20, "sort" to listOf("POPULARITY_DESC"), "search" to query)
        )
        val headers = mapOf("Content-Type" to "application/json", "Origin" to mainUrl, "Accept" to "application/json")

        return try {
            val response = app.post(graphqlUrl, headers = headers, json = body).parsed<AnilistResponse>()
            response.data?.page?.media?.mapNotNull { it.toSearchResponse() } ?: emptyList()
        } catch (e: Exception) {
            emptyList()
        }
    }

    // =========================================================================
    // 3. LOAD
    // =========================================================================
    private fun getIdFromUrl(url: String): String? {
        val paramId = try { Uri.parse(url).getQueryParameter("id") } catch (e: Exception) { null }
        if (!paramId.isNullOrEmpty()) return paramId
        return Regex("""(\d+)""").find(url)?.groupValues?.get(1)
    }

    override suspend fun load(url: String): LoadResponse {
        val rawId = getIdFromUrl(url)
        val id = rawId?.filter { it.isDigit() } ?: ""
        if (id.isEmpty()) throw ErrorLoadingException("Invalid Anime ID")

        // 1. Load HTML (Parallel nếu muốn, nhưng tuần tự an toàn hơn để lấy cookie)
        val response = app.get(url, headers = headers)
        val doc = response.document

        val title = doc.selectFirst(".details-content h2")?.text()?.trim()
            ?: doc.selectFirst("script:containsData(animeTitle)")?.data()
                ?.substringAfter("animeTitle = \"")?.substringBefore("\";")
            ?: "Unknown Title"

        var poster = doc.selectFirst("meta[property=og:image]")?.attr("content")
            ?: doc.selectFirst("img.anime-cover")?.attr("src")
        
        if (poster.isNullOrEmpty()) poster = "https://anikuro.ru/static/images/Anikuro_LogoBlack.svg"

        val description = doc.selectFirst(".details-content .description p")?.text()?.trim()
            ?: doc.selectFirst("meta[name=description]")?.attr("content")

        // 2. Load Episodes
        val episodesUrl = "$mainUrl/api/getepisodelist/?id=$id"
        val epResponse = app.get(episodesUrl, headers = headers).parsed<EpisodeListResponse>()
        val episodes = epResponse.episodes.mapNotNull { (epNumStr, epData) ->
            val epNum = epNumStr.toIntOrNull() ?: return@mapNotNull null
            newEpisode(data = "$id|$epNum") {
                this.name = epData.title ?: "Episode $epNum"
                this.episode = epNum
                this.description = epData.overview
            }
        }.sortedBy { it.episode }

        // 3. Load Rating
        var ratingScore: Score? = null
        try {
            val csrfToken = response.cookies["csrftoken"] ?: doc.select("input[name=csrfmiddlewaretoken]").attr("value")
            if (!csrfToken.isNullOrEmpty()) {
                val ratingHeaders = headers.toMutableMap().apply {
                    put("content-type", "application/json")
                    put("x-csrftoken", csrfToken)
                    put("cookie", "csrftoken=$csrfToken")
                }
                val ratingJson = app.post("$mainUrl/api/getanimerating/", headers = ratingHeaders, json = mapOf("anilist_id" to id.toIntOrNull())).parsed<RatingResponse>()
                ratingJson.rating?.avgRating?.let { ratingScore = Score.from10(it) }
            }
        } catch (e: Exception) { /* Ignore */ }

        return newTvSeriesLoadResponse(title, url, TvType.Anime, episodes) {
            this.posterUrl = poster
            this.plot = description
            this.score = ratingScore
            this.backgroundPosterUrl = poster
            addAniListId(id.toIntOrNull())
        }
    }

    // =========================================================================
    // 4. LOAD LINKS (FIXED & DEBUG LOGGING)
    // =========================================================================
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val (rawId, episodeNum) = data.split("|")
        
        // --- FIX QUAN TRỌNG: Lọc bỏ ký tự rác nếu ID bị dính URL ---
        val id = rawId.filter { it.isDigit() }
        
        // Log start
        // System.out.println("ANIKURO: Getting links for ID: $id EP: $episodeNum")

        supportedServers.amap { serverCode ->
            try {
                val url = "$mainUrl/api/getsources/?id=$id&lol=$serverCode&ep=$episodeNum"
                
                val request = okhttp3.Request.Builder()
                    .url(url)
                    .headers(headers.toHeaders())
                    .get()
                    .build()
                
                val response = extendedClient.newCall(request).execute()
                val responseText = response.body?.string() ?: ""

                // LOG RAW RESPONSE ĐỂ DEBUG
                // System.out.println("ANIKURO RAW [$serverCode]: $responseText")

                if (responseText.contains("error") || responseText.length < 5) return@amap

                val sourceResponse = parseJson<SourceResponse>(responseText)
                
                // Parse Subtitles
                val rootSubs = sourceResponse.subtitles?.mapNotNull { it.toSubtitleFile() } ?: emptyList()
                rootSubs.forEach(subtitleCallback)

                // Parse Video
                var linksFound = 0
                sourceResponse.sub?.let { 
                    val count = parseSourceNode(it, serverCode, "Sub", subtitleCallback, callback)
                    linksFound += count
                }
                sourceResponse.dub?.let { 
                    val count = parseSourceNode(it, serverCode, "Dub", subtitleCallback, callback) 
                    linksFound += count
                }

                if (linksFound > 0) {
                    // System.out.println("ANIKURO: [$serverCode] Found $linksFound links")
                }

            } catch (e: Exception) {
                // System.err.println("ANIKURO_ERR: $serverCode -> ${e.message}")
            }
        }
        return true
    }

    private suspend fun parseSourceNode(
        node: JsonNode, server: String, type: String,
        subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit
    ): Int {
        var count = 0
        if (node.isTextual && node.asText().startsWith("http")) {
            generateLinks(node.asText(), server, type, mainUrl, callback)
            return 1
        }

        if (node.isObject) {
            val referer = node["referer"]?.asText() ?: node["sub_referer"]?.asText() ?: node["dub_referer"]?.asText() ?: mainUrl
            
            node["tracks"]?.forEach { it.toTrack()?.toSubtitleFile()?.let(subtitleCallback) }
            node["subtitles"]?.forEach { it.toTrack()?.toSubtitleFile()?.let(subtitleCallback) }

            val directUrl = node["url"]?.asText() ?: node["default"]?.asText()
            if (!directUrl.isNullOrEmpty()) {
                generateLinks(directUrl, server, type, referer, callback)
                count++
            }

            if (node.has("sources")) {
                node["sources"]?.fields()?.forEach { (k, v) ->
                    v["url"]?.asText()?.let { 
                        generateLinks(it, "$server $k", type, referer, callback)
                        count++
                    }
                }
                node["preferred"]?.get("url")?.asText()?.let { 
                    generateLinks(it, "$server Preferred", type, referer, callback)
                    count++
                }
            }

            node.fields().forEach { (k, v) ->
                if (v.isTextual && v.asText().startsWith("http")) {
                    val quality = if (k.matches(Regex("\\d+p"))) " $k" else ""
                    // Tránh duplicate với url/default key
                    if (k != "url" && k != "default") {
                        generateLinks(v.asText(), server, "$type$quality", referer, callback)
                        count++
                    }
                }
            }
        }
        return count
    }

    private suspend fun generateLinks(url: String, server: String, typeStr: String, referer: String, callback: (ExtractorLink) -> Unit) {
        val name = "Anikuro $server $typeStr"
        val linkHeaders = mapOf("Origin" to mainUrl, "Referer" to referer, "User-Agent" to (headers["user-agent"] ?: ""))
        val type = if (url.contains(".m3u8")) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO

        if (type == ExtractorLinkType.M3U8) {
            M3u8Helper.generateM3u8(name, url, referer, headers = linkHeaders).forEach(callback)
        } else {
            callback(newExtractorLink(source = name, name = name, url = url, type = type) { this.referer = referer })
        }
    }

    // =========================================================================
    // UTILS & CLASSES
    // =========================================================================
    private fun AnimeData.toSearchResponse(currentEpisode: String? = null): SearchResponse {
        val animeId = this.id
        val title = this.title?.english ?: this.title?.romaji ?: this.title?.native ?: "Unknown"
        // FIX: Thêm ảnh fallback mặc định
        val poster = this.coverImage?.large ?: this.coverImage?.medium ?: "https://anikuro.ru/static/images/Anikuro_LogoBlack.svg"
        val url = "$mainUrl/watch/?id=$animeId"
        return newAnimeSearchResponse(title, url, TvType.Anime) {
            this.posterUrl = poster
            if (currentEpisode != null) addDubStatus(false, true)
        }
    }

    private fun JsonNode.toTrack(): Track? = try { AppUtils.parseJson<Track>(this.toString()) } catch (e: Exception) { null }
    private fun Track.toSubtitleFile(): SubtitleFile? {
        val url = this.file ?: this.url ?: return null
        if (this.kind == "thumbnails") return null
        return SubtitleFile(this.lang ?: this.label ?: "Unknown", url)
    }
    private fun Map<String, String>.toHeaders(): okhttp3.Headers = okhttp3.Headers.Builder().apply { this@toHeaders.forEach { (k, v) -> add(k, v) } }.build()

    data class TrendingResponse(@JsonProperty("info") val info: List<AnimeData> = emptyList())
    data class ScheduleResponse(@JsonProperty("info") val info: List<ScheduleItem> = emptyList())
    data class ScheduleItem(@JsonProperty("media") val media: AnimeData? = null, @JsonProperty("episode") val episode: Int? = null)
    data class AnilistResponse(@JsonProperty("data") val data: AnilistData? = null)
    data class AnilistData(@JsonProperty("Page") val page: AnilistPage? = null)
    data class AnilistPage(
        @JsonProperty("airingSchedules") val airingSchedules: List<AnilistSchedule>? = null,
        @JsonProperty("media") val media: List<AnimeData>? = null
    )
    data class AnilistSchedule(@JsonProperty("episode") val episode: Int? = null, @JsonProperty("media") val media: AnimeData? = null)
    data class AnimeData(
        @JsonProperty("id") val id: Int? = null,
        @JsonProperty("title") val title: Title? = null,
        @JsonProperty("coverImage") val coverImage: CoverImage? = null
    )
    data class Title(@JsonProperty("native") val native: String? = null, @JsonProperty("romaji") val romaji: String? = null, @JsonProperty("english") val english: String? = null)
    data class CoverImage(@JsonProperty("large") val large: String? = null, @JsonProperty("medium") val medium: String? = null)
    data class EpisodeListResponse(@JsonProperty("episodes") val episodes: Map<String, ApiEpisodeDetail> = emptyMap())
    data class ApiEpisodeDetail(@JsonProperty("title") val title: String? = null, @JsonProperty("overview") val overview: String? = null)
    data class RatingResponse(@JsonProperty("rating") val rating: RatingData? = null)
    data class RatingData(@JsonProperty("avg_rating") val avgRating: Double? = null)
    data class SourceResponse(
        @JsonProperty("sub") val sub: JsonNode? = null,
        @JsonProperty("dub") val dub: JsonNode? = null,
        @JsonProperty("subtitles") val subtitles: List<Track>? = null,
        @JsonProperty("sub_referer") val subReferer: String? = null,
        @JsonProperty("dub_referer") val dubReferer: String? = null
    )
    data class Track(@JsonProperty("file") val file: String? = null, @JsonProperty("url") val url: String? = null, @JsonProperty("label") val label: String? = null, @JsonProperty("lang") val lang: String? = null, @JsonProperty("kind") val kind: String? = null)
}
