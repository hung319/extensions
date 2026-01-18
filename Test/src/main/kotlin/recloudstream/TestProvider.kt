package recloudstream

import android.widget.Toast
import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.databind.JsonNode
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addAniListId
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import com.lagradost.cloudstream3.CommonActivity.showToast
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit
import android.net.Uri

class AnikuroProvider : MainAPI() {
    override var mainUrl = "https://anikuro.ru"
    override var name = "Anikuro"
    override val hasMainPage = true
    override var lang = "en"
    override val hasQuickSearch = true
    override val supportedTypes = setOf(TvType.Anime)

    private val extendedClient = app.baseClient.newBuilder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

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
    // 1. MAIN PAGE
    // =========================================================================
    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        if (page == 1) {
            withContext(Dispatchers.Main) {
                CommonActivity.activity?.let { activity ->
                    showToast(activity, "Free Repo From H4RS", Toast.LENGTH_LONG)
                }
            }
        }

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

        val items = tasks.amap { task ->
            try {
                val list = task.fetcher.invoke()
                if (list.isNotEmpty()) HomePageList(task.title, list) else null
            } catch (e: Exception) { null }
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
            val responseText = app.post(graphqlUrl, headers = anilistHeaders, json = body).text
            val json = parseJson<JsonNode>(responseText)
            
            json["data"]["Page"]["airingSchedules"].mapNotNull { item ->
                val episode = item["episode"]?.asInt()
                val media = item["media"]
                media?.toAnimeData()?.toSearchResponse(currentEpisode = "Ep $episode")
            }
        } catch (e: Exception) { emptyList() }
    }

    private suspend fun fetchAnilistRecommendations(anilistId: Int): List<SearchResponse> {
        val graphqlUrl = "https://graphql.anilist.co/"
        val query = """
            query(${'$'}id: Int) {
                Media(id: ${'$'}id) {
                    recommendations(sort: RATING_DESC, page: 1, perPage: 10) {
                        nodes {
                            mediaRecommendation {
                                id
                                title { romaji english native }
                                coverImage { large medium }
                                format
                            }
                        }
                    }
                }
            }
        """
        val body = mapOf("query" to query, "variables" to mapOf("id" to anilistId))
        val anilistHeaders = mapOf("Content-Type" to "application/json", "Origin" to mainUrl, "Accept" to "application/json")

        return try {
            val responseText = app.post(graphqlUrl, headers = anilistHeaders, json = body).text
            val json = parseJson<JsonNode>(responseText)
            
            json["data"]["Media"]["recommendations"]["nodes"].mapNotNull { node ->
                node["mediaRecommendation"]?.toAnimeData()?.toSearchResponse()
            }
        } catch (e: Exception) { emptyList() }
    }

    private fun JsonNode.toAnimeData(): AnimeData {
        val id = this["id"]?.asInt()
        val titleNode = this["title"]
        val coverNode = this["coverImage"]
        
        return AnimeData(
            id = id,
            title = Title(
                native = titleNode?.get("native")?.asText(),
                romaji = titleNode?.get("romaji")?.asText(),
                english = titleNode?.get("english")?.asText()
            ),
            coverImage = CoverImage(
                large = coverNode?.get("large")?.asText(),
                medium = coverNode?.get("medium")?.asText()
            )
        )
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
            val responseText = app.post(graphqlUrl, headers = headers, json = body).text
            val json = parseJson<JsonNode>(responseText)
            json["data"]["Page"]["media"].mapNotNull { it.toAnimeData().toSearchResponse() }
        } catch (e: Exception) { emptyList() }
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

        val watchUrl = if (url.contains("/watch/")) url else "$mainUrl/watch/?id=$id"
        val response = app.get(watchUrl, headers = headers)
        val doc = response.document
        val htmlText = response.text

        val lastAiredEpisode = Regex("""let\s+lastAiredEpisode\s*=\s*(\d+);""")
            .find(htmlText)?.groupValues?.get(1)?.toIntOrNull() ?: Int.MAX_VALUE

        val title = doc.selectFirst(".anime-details .details-content h2")?.text()?.trim()
            ?: doc.selectFirst(".preview-title")?.text()?.trim()
            ?: "Unknown Title"

        var poster = doc.selectFirst("img.anime-cover")?.attr("src")
            ?: doc.selectFirst("img.preview-cover")?.attr("src")
            ?: doc.selectFirst("meta[property=og:image]")?.attr("content")
        
        if (poster.isNullOrEmpty()) poster = "https://anikuro.ru/static/images/Anikuro_LogoBlack.svg"

        val description = doc.selectFirst(".description p")?.text()?.trim()
            ?: doc.selectFirst("meta[name=description]")?.attr("content")

        var year: Int? = null
        try {
            val dateText = doc.select("p:contains(Start Date), p:contains(Season)").text()
            val yearMatch = Regex("""\d{4}""").find(dateText)
            year = yearMatch?.value?.toIntOrNull()
        } catch (e: Exception) { }

        val episodesUrl = "$mainUrl/api/getepisodelist/?id=$id"
        val apiResponse = app.get(episodesUrl, headers = headers).parsed<EpisodeListResponse>()
        
        val episodes = apiResponse.episodes.mapNotNull { (epNumStr, epData) ->
            val epNum = epNumStr.toIntOrNull() ?: return@mapNotNull null
            if (epNum > lastAiredEpisode) return@mapNotNull null

            newEpisode(data = "$id|$epNum") {
                this.name = epData.title ?: "Episode $epNum"
                this.episode = epNum
                this.description = epData.overview
                this.posterUrl = epData.thumbnail
            }
        }.sortedBy { it.episode }

        var ratingScore: Score? = null
        try {
            val ratingText = doc.selectFirst(".stats-value")?.text()?.replace("%", "")
            if (!ratingText.isNullOrEmpty()) {
                ratingScore = Score.from10(ratingText.toDouble() / 10.0)
            }
        } catch (e: Exception) { }

        val recommendations = id.toIntOrNull()?.let { fetchAnilistRecommendations(it) } ?: emptyList()

        return newTvSeriesLoadResponse(title, url, TvType.Anime, episodes) {
            this.posterUrl = poster
            this.plot = description
            this.year = year
            this.score = ratingScore
            this.backgroundPosterUrl = poster
            this.recommendations = recommendations
            addAniListId(id.toIntOrNull())
        }
    }

    // =========================================================================
    // 4. LOAD LINKS
    // =========================================================================
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val (rawId, episodeNum) = data.split("|")
        val id = rawId.filter { it.isDigit() }
        
        supportedServers.amap { serverCode ->
            try {
                val url = "$mainUrl/api/getsources/?id=$id&lol=$serverCode&ep=$episodeNum"
                // FIX: Pass headers as Map (removed .toHeaders())
                val responseText = app.get(url, headers = headers).text
                if (responseText.contains("error") || responseText.length < 5) return@amap

                val sourceJson = parseJson<JsonNode>(responseText)
                
                // Parse Subtitles
                sourceJson["subtitles"]?.forEach { track ->
                    track.toTrack()?.toSubtitleFile()?.let(subtitleCallback)
                }

                sourceJson["sub"]?.let { parseSourceNode(it, serverCode, "Sub", subtitleCallback, callback) }
                sourceJson["dub"]?.let { parseSourceNode(it, serverCode, "Dub", subtitleCallback, callback) }
            } catch (e: Exception) { }
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
                    val isNotMetaField = k != "url" && k != "default" && 
                                         !k.contains("referer") && 
                                         k != "backup" && k != "preview"

                    if (isNotMetaField) {
                        val quality = if (k.matches(Regex("\\d+p"))) " $k" else ""
                        generateLinks(v.asText(), server, "$type$quality", referer, callback)
                        count++
                    }
                }
            }
        }
        return count
    }

    private fun getLinkType(url: String): ExtractorLinkType {
        val cleanUrl = url.substringBefore("?").lowercase()
        return when {
            cleanUrl.endsWith(".m3u8") -> ExtractorLinkType.M3U8
            cleanUrl.endsWith(".mp4") -> ExtractorLinkType.VIDEO
            cleanUrl.endsWith(".mkv") -> ExtractorLinkType.VIDEO
            cleanUrl.contains("googlevideo") -> ExtractorLinkType.VIDEO
            cleanUrl.contains("hls") -> ExtractorLinkType.M3U8
            else -> ExtractorLinkType.VIDEO 
        }
    }

    private suspend fun generateLinks(url: String, server: String, typeStr: String, referer: String, callback: (ExtractorLink) -> Unit) {
        val name = "Anikuro $server $typeStr"
        val type = getLinkType(url)

        callback(newExtractorLink(source = name, name = name, url = url, type = type) { 
            this.referer = referer 
        })
    }

    // =========================================================================
    // UTILS & CLASSES
    // =========================================================================
    private fun AnimeData.toSearchResponse(currentEpisode: String? = null): SearchResponse {
        val animeId = this.id
        val title = this.title?.english ?: this.title?.romaji ?: this.title?.native ?: "Unknown"
        val poster = this.coverImage?.large ?: this.coverImage?.medium ?: "https://anikuro.ru/static/images/Anikuro_LogoBlack.svg"
        val url = "$mainUrl/watch/?id=$animeId"
        return newAnimeSearchResponse(title, url, TvType.Anime) {
            this.posterUrl = poster
            if (currentEpisode != null) addDubStatus(false, true)
        }
    }

    private fun JsonNode.toTrack(): Track? = try { AppUtils.parseJson<Track>(this.toString()) } catch (e: Exception) { null }
    
    private suspend fun Track.toSubtitleFile(): SubtitleFile? {
        val url = this.file ?: this.url ?: return null
        if (this.kind == "thumbnails") return null
        val lang = this.lang ?: this.label ?: "Unknown"
        return newSubtitleFile(lang, url)
    }
    
    // Removed toHeaders() extension because it's not needed anymore

    // Minimal Data Classes
    data class TrendingResponse(@JsonProperty("info") val info: List<AnimeData> = emptyList())
    data class ScheduleResponse(@JsonProperty("info") val info: List<ScheduleItem> = emptyList())
    data class ScheduleItem(@JsonProperty("media") val media: AnimeData? = null, @JsonProperty("episode") val episode: Int? = null)
    
    data class AnimeData(
        @JsonProperty("id") val id: Int? = null,
        @JsonProperty("title") val title: Title? = null,
        @JsonProperty("coverImage") val coverImage: CoverImage? = null
    )
    data class Title(@JsonProperty("native") val native: String? = null, @JsonProperty("romaji") val romaji: String? = null, @JsonProperty("english") val english: String? = null)
    data class CoverImage(@JsonProperty("large") val large: String? = null, @JsonProperty("medium") val medium: String? = null)
    
    data class EpisodeListResponse(@JsonProperty("episodes") val episodes: Map<String, ApiEpisodeDetail> = emptyMap())
    data class ApiEpisodeDetail(
        @JsonProperty("title") val title: String? = null, 
        @JsonProperty("overview") val overview: String? = null,
        @JsonProperty("thumbnail") val thumbnail: String? = null
    )
    
    data class Track(@JsonProperty("file") val file: String? = null, @JsonProperty("url") val url: String? = null, @JsonProperty("label") val label: String? = null, @JsonProperty("lang") val lang: String? = null, @JsonProperty("kind") val kind: String? = null)
}
