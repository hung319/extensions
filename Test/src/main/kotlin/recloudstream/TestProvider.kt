package recloudstream

import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.databind.JsonNode
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import com.lagradost.cloudstream3.LoadResponse.Companion.addAniListId

// Extend MainAPI instead of AnimeProvider if AnimeProvider is not found
class AnikuroProvider : MainAPI() {
    override var mainUrl = "https://anikuro.ru"
    override var name = "Anikuro"
    override val hasMainPage = true
    override var lang = "vi"
    override val hasQuickSearch = true
    override val supportedTypes = setOf(TvType.Anime)

    // Headers chung giả lập trình duyệt Android
    private val headers = mapOf(
        "authority" to "anikuro.ru",
        "accept" to "*/*",
        "accept-language" to "vi-VN,vi;q=0.9",
        "referer" to "$mainUrl/",
        "sec-ch-ua" to "\"Chromium\";v=\"137\", \"Not/A)Brand\";v=\"24\"",
        "sec-ch-ua-mobile" to "?1",
        "sec-ch-ua-platform" to "\"Android\"",
        "sec-fetch-dest" to "empty",
        "sec-fetch-mode" to "cors",
        "sec-fetch-site" to "same-origin",
        "user-agent" to "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/137.0.0.0 Mobile Safari/537.36"
    )

    // Danh sách server đầy đủ để chạy song song
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

        // 1. Trending (Anikuro API)
        try {
            val trendingUrl = "$mainUrl/api/gettrending/"
            val response = app.get(trendingUrl, headers = headers).parsed<TrendingResponse>()
            val list = response.info.mapNotNull { it.toSearchResponse() }
            if (list.isNotEmpty()) items.add(HomePageList("Thịnh hành", list))
        } catch (e: Exception) { e.printStackTrace() }

        // 2. Lịch chiếu (Anikuro API)
        try {
            val scheduleUrl = "$mainUrl/api/getschedule/"
            val response = app.get(scheduleUrl, headers = headers).parsed<ScheduleResponse>()
            val list = response.info.mapNotNull {
                it.media?.toSearchResponse(currentEpisode = "Ep ${it.episode}")
            }
            if (list.isNotEmpty()) items.add(HomePageList("Lịch chiếu", list))
        } catch (e: Exception) { e.printStackTrace() }

        // 3. Sắp ra mắt (Anikuro API)
        try {
            val upcomingUrl = "$mainUrl/api/getupcoming/"
            val response = app.get(upcomingUrl, headers = headers).parsed<TrendingResponse>()
            val list = response.info.mapNotNull { it.toSearchResponse() }
            if (list.isNotEmpty()) items.add(HomePageList("Sắp ra mắt", list))
        } catch (e: Exception) { e.printStackTrace() }

        // 4. Mới cập nhật (Anilist GraphQL)
        try {
            val list = fetchAnilistHome()
            if (list.isNotEmpty()) items.add(HomePageList("Mới cập nhật (Anilist)", list))
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

        // Metadata
        val title = doc.selectFirst("meta[property=og:title]")?.attr("content")
            ?.replace("Watch ", "")?.replace(" Episode.*".toRegex(), "")?.trim() ?: "Unknown Title"
        val poster = doc.selectFirst("meta[property=og:image]")?.attr("content")
        val description = doc.selectFirst("meta[name=description]")?.attr("content")
            ?: doc.select(".description").text()

        // Episodes
        val episodesUrl = "$mainUrl/api/getepisodelist/?id=$id"
        val epResponse = app.get(episodesUrl, headers = headers).parsed<EpisodeListResponse>()
        val episodes = epResponse.episodes.mapNotNull { (epNumStr, epData) ->
            val epNum = epNumStr.toIntOrNull() ?: return@mapNotNull null
            newEpisode(
                data = "$id|$epNum",
            ) {
                this.name = epData.title ?: "Episode $epNum"
                this.episode = epNum
                this.description = epData.overview
            }
        }.sortedBy { it.episode }

        // Rating (Optional CSRF)
        var ratingInt: Int? = null
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
                ratingInt = (ratingJson.rating?.avgRating?.times(1000))?.toInt() // Rating 10.0 -> 10000 scale usually
            }
        } catch (e: Exception) { /* Ignore */ }

        return newTvSeriesLoadResponse(title, url, TvType.Anime, episodes) {
            this.posterUrl = poster
            this.plot = description
            this.rating = ratingInt
            this.backgroundPosterUrl = doc.selectFirst("meta[name=twitter:image]")?.attr("content") ?: poster
            addAniListId(id.toIntOrNull())
        }
    }

    // =========================================================================
    // 4. LOAD LINKS
    // =========================================================================
    override suspend fun loadLinks(data: String, isBackup: Boolean, onLink: suspend (Link) -> Unit) {
        val (id, episodeNum) = data.split("|")

        supportedServers.apmap { serverCode ->
            try {
                val url = "$mainUrl/api/getsources/?id=$id&lol=$serverCode&ep=$episodeNum"
                val response = app.get(url, headers = headers).parsed<SourceResponse>()
                val rootSubs = response.subtitles?.mapNotNull { it.toSubtitleFile() } ?: emptyList()

                response.sub?.let { parseSourceNode(it, serverCode, "Sub", rootSubs, onLink) }
                response.dub?.let { parseSourceNode(it, serverCode, "Dub", rootSubs, onLink) }
            } catch (e: Exception) {
                // Ignore errors
            }
        }
    }

    private suspend fun parseSourceNode(
        node: JsonNode,
        server: String,
        type: String,
        rootSubs: List<SubtitleFile>,
        onLink: suspend (Link) -> Unit
    ) {
        if (node.isTextual) {
            val url = node.asText()
            if (url.startsWith("http")) generateLinks(url, server, type, mainUrl, rootSubs, onLink)
            return
        }

        if (node.isObject) {
            val referer = node["referer"]?.asText() ?: node["sub_referer"]?.asText() ?: node["dub_referer"]?.asText() ?: mainUrl
            val innerSubs = ArrayList(rootSubs)
            node["tracks"]?.forEach { it.toTrack()?.toSubtitleFile()?.let { s -> innerSubs.add(s) } }
            node["subtitles"]?.forEach { it.toTrack()?.toSubtitleFile()?.let { s -> innerSubs.add(s) } }

            // Case A: url/default key
            val directUrl = node["url"]?.asText() ?: node["default"]?.asText()
            if (!directUrl.isNullOrEmpty()) {
                generateLinks(directUrl, server, type, referer, innerSubs, onLink)
                return
            }

            // Case B: sources object
            if (node.has("sources")) {
                node["sources"]?.fields()?.forEach { (k, v) ->
                    v["url"]?.asText()?.let { generateLinks(it, "$server $k", type, referer, innerSubs, onLink) }
                }
                node["preferred"]?.get("url")?.asText()?.let { generateLinks(it, "$server Preferred", type, referer, innerSubs, onLink) }
                return
            }

            // Case C: Quality map (480p, 720p...)
            node.fields().forEach { (k, v) ->
                if (v.isTextual && v.asText().startsWith("http")) {
                    val quality = if (k.matches(Regex("\\d+p"))) " $k" else ""
                    generateLinks(v.asText(), server, "$type$quality", referer, innerSubs, onLink)
                }
            }
        }
    }

    private suspend fun generateLinks(
        url: String,
        server: String,
        typeStr: String, // Label text like "Sub 720p"
        referer: String,
        subs: List<SubtitleFile>,
        onLink: suspend (Link) -> Unit
    ) {
        val name = "Anikuro $server $typeStr"
        // Quan trọng: Header Origin để bypass proxy
        val headers = mapOf("Origin" to mainUrl, "Referer" to referer)

        if (url.contains(".m3u8")) {
            // Dùng M3u8Helper để auto-parse qualities
            M3u8Helper.generateM3u8(
                name,
                url,
                referer,
                headers = headers
            ).forEach { link ->
                onLink(link.copy(subtitles = link.subtitles + subs))
            }
        } else {
            // Dùng newExtractorLink như yêu cầu
            onLink(
                newExtractorLink(
                    source = name,
                    name = name,
                    url = url,
                    type = ExtractorLinkType.VIDEO // Mặc định MP4 là VIDEO
                ) {
                    this.referer = referer
                    this.subtitles = subs
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
        // URL giả định dựa trên ID
        val url = "$mainUrl/watch/?id=$animeId"

        return newAnimeSearchResponse(title, url, TvType.Anime) {
            this.posterUrl = poster
            // id là String trong SearchResponse, nhưng animeId có thể là Int?
            // fix: set proper ID string
            // this.id = animeId.toString() // This might be read-only in some versions, but usually set in init
            if (currentEpisode != null) {
                addDubStatus(dubStatus = false, subStatus = true)
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
