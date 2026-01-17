package recloudstream

import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.databind.JsonNode
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addAniListId
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.utils.AppUtils.parseJson

class AnikuroProvider : MainAPI() {
    override var mainUrl = "https://anikuro.ru"
    override var name = "Anikuro"
    override val hasMainPage = true
    override var lang = "en"
    override val hasQuickSearch = true
    override val supportedTypes = setOf(TvType.Anime)

    private val headers = mapOf(
        "authority" to "anikuro.ru",
        "accept" to "*/*",
        "accept-language" to "en-US,en;q=0.9",
        "referer" to "$mainUrl/",
        "sec-ch-ua" to "\"Chromium\";v=\"137\", \"Not/A)Brand\";v=\"24\"",
        "sec-ch-ua-mobile" to "?1",
        "sec-ch-ua-platform" to "\"Android\"",
        "sec-fetch-dest" to "empty",
        "sec-fetch-mode" to "cors",
        "sec-fetch-site" to "same-origin",
        "user-agent" to "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/137.0.0.0 Mobile Safari/537.36"
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
        val items = ArrayList<HomePageList>()

        try {
            val trendingUrl = "$mainUrl/api/gettrending/"
            val response = app.get(trendingUrl, headers = headers).parsed<TrendingResponse>()
            val list = response.info.mapNotNull { it.toSearchResponse() }
            if (list.isNotEmpty()) items.add(HomePageList("Trending", list))
        } catch (e: Exception) { e.printStackTrace() }

        try {
            val scheduleUrl = "$mainUrl/api/getschedule/"
            val response = app.get(scheduleUrl, headers = headers).parsed<ScheduleResponse>()
            val list = response.info.mapNotNull {
                it.media?.toSearchResponse(currentEpisode = "Ep ${it.episode}")
            }
            if (list.isNotEmpty()) items.add(HomePageList("Schedule", list))
        } catch (e: Exception) { e.printStackTrace() }

        try {
            val upcomingUrl = "$mainUrl/api/getupcoming/"
            val response = app.get(upcomingUrl, headers = headers).parsed<TrendingResponse>()
            val list = response.info.mapNotNull { it.toSearchResponse() }
            if (list.isNotEmpty()) items.add(HomePageList("Upcoming", list))
        } catch (e: Exception) { e.printStackTrace() }

        try {
            val list = fetchAnilistHome()
            if (list.isNotEmpty()) items.add(HomePageList("Just Updated (Anilist)", list))
        } catch (e: Exception) { e.printStackTrace() }

        return newHomePageResponse(items)
    }

    private suspend fun fetchAnilistHome(): List<SearchResponse> {
        val graphqlUrl = "https://graphql.anilist.co/"
        val currentTime = System.currentTimeMillis() / 1000
        val query = """
            query RecentlyAiredEpisodes(${"$"}page: Int, ${"$"}perPage: Int, ${"$"}currentTime: Int) {
                Page(page: ${"$"}page, perPage: ${"$"}perPage) {
                    airingSchedules(sort: TIME_DESC, airingAt_lesser: ${"$"}currentTime) {
                        episode
                        media {
                            id
                            title { romaji english native }
                            coverImage { large medium }
                            format
                        }
                    }
                }
            }
        """
        val body = mapOf(
            "query" to query,
            "variables" to mapOf("page" to 1, "perPage" to 20, "currentTime" to currentTime)
        )
        val anilistHeaders = mapOf(
            "Content-Type" to "application/json",
            "Origin" to mainUrl,
            "Accept" to "application/json"
        )
        val response = app.post(graphqlUrl, headers = anilistHeaders, json = body).parsed<AnilistResponse>()
        return response.data?.page?.airingSchedules?.mapNotNull { schedule ->
            schedule.media?.toSearchResponse(currentEpisode = "Ep ${schedule.episode}")
        } ?: emptyList()
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
                  id
                  title{romaji english native}
                  coverImage{extraLarge large medium}
                  format
                }
              }
            }
        """
        val body = mapOf(
            "query" to graphQuery,
            "variables" to mapOf(
                "page" to 1,
                "perPage" to 20,
                "sort" to listOf("POPULARITY_DESC"),
                "search" to query
            )
        )
        val headers = mapOf("Content-Type" to "application/json", "Origin" to mainUrl, "Accept" to "application/json")

        return try {
            val response = app.post(graphqlUrl, headers = headers, json = body).parsed<AnilistResponse>()
            response.data?.page?.media?.mapNotNull { it.toSearchResponse() } ?: emptyList()
        } catch (e: Exception) {
            e.printStackTrace()
            emptyList()
        }
    }

    // =========================================================================
    // 3. LOAD
    // =========================================================================
    override suspend fun load(url: String): LoadResponse {
        val response = app.get(url, headers = headers)
        val doc = response.document
        val id = url.substringAfter("id=").substringBefore("&")

        val title = doc.selectFirst(".details-content h2")?.text()?.trim()
            ?: doc.selectFirst("script:containsData(animeTitle)")?.data()
                ?.substringAfter("animeTitle = \"")?.substringBefore("\";")
            ?: "Unknown Title"

        val poster = doc.selectFirst("img.anime-cover")?.attr("src")
            ?: doc.selectFirst(".preview-cover")?.attr("src")

        val backgroundStyle = doc.selectFirst(".preview-banner")?.attr("style")
        val backgroundPoster = backgroundStyle?.substringAfter("url('")?.substringBefore("')") 
            ?: poster

        val description = doc.selectFirst(".details-content .description p")?.text()?.trim()
            ?: doc.selectFirst("meta[name=description]")?.attr("content")

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

        var ratingDouble: Double? = null
        try {
            val csrfToken = response.cookies["csrftoken"]
                ?: doc.select("input[name=csrfmiddlewaretoken]").attr("value")
            if (!csrfToken.isNullOrEmpty()) {
                val ratingHeaders = headers.toMutableMap().apply {
                    put("content-type", "application/json")
                    put("x-csrftoken", csrfToken)
                    put("cookie", "csrftoken=$csrfToken")
                }
                val ratingJson = app.post("$mainUrl/api/getanimerating/", headers = ratingHeaders, json = mapOf("anilist_id" to id.toIntOrNull()))
                    .parsed<RatingResponse>()
                ratingDouble = ratingJson.rating?.avgRating
            }
        } catch (e: Exception) { /* Ignore */ }

        return newTvSeriesLoadResponse(title, url, TvType.Anime, episodes) {
            this.posterUrl = poster
            this.plot = description
            if (ratingDouble != null) {
                this.score = Score.from10(ratingDouble)
            }
            this.backgroundPosterUrl = backgroundPoster
            addAniListId(id.toIntOrNull())
        }
    }

    // =========================================================================
    // 4. LOAD LINKS (FIXED ERROR HANDLING)
    // =========================================================================
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val (id, episodeNum) = data.split("|")

        supportedServers.amap { serverCode ->
            try {
                // Tách riêng request để debug nếu lỗi
                val url = "$mainUrl/api/getsources/?id=$id&lol=$serverCode&ep=$episodeNum"
                val responseText = app.get(url, headers = headers).text
                
                // Nếu response rỗng hoặc lỗi, bỏ qua
                if (responseText.contains("\"error\"") || responseText.length < 5) return@amap

                val response = parseJson<SourceResponse>(responseText)
                
                val rootSubs = response.subtitles?.mapNotNull { it.toSubtitleFile() } ?: emptyList()
                rootSubs.forEach(subtitleCallback)

                response.sub?.let { parseSourceNode(it, serverCode, "Sub", subtitleCallback, callback) }
                response.dub?.let { parseSourceNode(it, serverCode, "Dub", subtitleCallback, callback) }
            } catch (e: Exception) {
                // In lỗi ra log để biết server nào tạch
                // e.printStackTrace() 
            }
        }
        return true
    }

    private suspend fun parseSourceNode(
        node: JsonNode,
        server: String,
        type: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        if (node.isTextual) {
            val url = node.asText()
            if (url.startsWith("http")) generateLinks(url, server, type, mainUrl, callback)
            return
        }

        if (node.isObject) {
            val referer = node["referer"]?.asText() ?: node["sub_referer"]?.asText() ?: node["dub_referer"]?.asText() ?: mainUrl
            
            node["tracks"]?.forEach { it.toTrack()?.toSubtitleFile()?.let { s -> subtitleCallback(s) } }
            node["subtitles"]?.forEach { it.toTrack()?.toSubtitleFile()?.let { s -> subtitleCallback(s) } }

            val directUrl = node["url"]?.asText() ?: node["default"]?.asText()
            if (!directUrl.isNullOrEmpty()) {
                generateLinks(directUrl, server, type, referer, callback)
                return
            }

            if (node.has("sources")) {
                node["sources"]?.fields()?.forEach { (k, v) ->
                    v["url"]?.asText()?.let { generateLinks(it, "$server $k", type, referer, callback) }
                }
                node["preferred"]?.get("url")?.asText()?.let { generateLinks(it, "$server Preferred", type, referer, callback) }
                return
            }

            node.fields().forEach { (k, v) ->
                if (v.isTextual && v.asText().startsWith("http")) {
                    val quality = if (k.matches(Regex("\\d+p"))) " $k" else ""
                    generateLinks(v.asText(), server, "$type$quality", referer, callback)
                }
            }
        }
    }

    private suspend fun generateLinks(
        url: String,
        server: String,
        typeStr: String,
        referer: String,
        callback: (ExtractorLink) -> Unit
    ) {
        val name = "Anikuro $server $typeStr"
        // Thêm User-Agent vào header link để tránh bị chặn bởi Proxy
        val linkHeaders = mapOf(
            "Origin" to mainUrl, 
            "Referer" to referer,
            "User-Agent" to (headers["user-agent"] ?: "")
        )
        
        val type = if (url.contains(".m3u8")) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO

        if (url.contains(".m3u8")) {
            M3u8Helper.generateM3u8(
                name,
                url,
                referer,
                headers = linkHeaders
            ).forEach(callback)
        } else {
            callback(
                newExtractorLink(
                    source = name,
                    name = name,
                    url = url,
                    type = type
                ) {
                    this.referer = referer
                }
            )
        }
    }

    // =========================================================================
    // HELPER FUNCTIONS & DATA CLASSES
    // =========================================================================

    private fun AnimeData.toSearchResponse(currentEpisode: String? = null): SearchResponse {
        val animeId = this.id
        val title = this.title?.english ?: this.title?.romaji ?: this.title?.native ?: "Unknown"
        val poster = this.coverImage?.large ?: this.coverImage?.medium
        val url = "$mainUrl/watch/?id=$animeId"

        return newAnimeSearchResponse(title, url, TvType.Anime) {
            this.posterUrl = poster
            if (currentEpisode != null) {
                addDubStatus(dubExist = false, subExist = true)
            }
        }
    }

    private fun JsonNode.toTrack(): Track? = try { AppUtils.parseJson<Track>(this.toString()) } catch (e: Exception) { null }

    private fun Track.toSubtitleFile(): SubtitleFile? {
        val url = this.file ?: this.url ?: return null
        if (this.kind == "thumbnails") return null
        return SubtitleFile(this.lang ?: this.label ?: "Unknown", url)
    }

    // --- JSON MAPPING CLASSES ---

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
    data class Title(
        @JsonProperty("native") val native: String? = null,
        @JsonProperty("romaji") val romaji: String? = null,
        @JsonProperty("english") val english: String? = null
    )
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
    data class Track(
        @JsonProperty("file") val file: String? = null,
        @JsonProperty("url") val url: String? = null,
        @JsonProperty("label") val label: String? = null,
        @JsonProperty("lang") val lang: String? = null,
        @JsonProperty("kind") val kind: String? = null
    )
}
