package recloudstream

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.fasterxml.jackson.annotation.JsonProperty
import com.google.gson.JsonParser

class Phim4kProvider : MainAPI() {
    override var mainUrl = "https://4animo.xyz"
    override var name = "4Animo"
    override var lang = "vi"
    override var type = TvType.Anime
    override var hasMainPage = true
    override var hasQuickSearch = false
    override var hasChromecastSupport = true

    private val apiBase = "https://api.kryzox.xyz"
    private val cdnBase = "https://cdn.4animo.xyz"

    companion object {
        private fun slugToId(slug: String): Int {
            return slug.split("-").last().toIntOrNull()
                ?: throw ErrorLoadingException("Invalid slug: $slug")
        }

        private fun slugToUrl(slug: String): String = "/$slug"

        private fun getStringSafe(map: Map<*, *>, vararg keys: String): String? {
            for (key in keys) {
                val value = map[key] as? String
                if (value != null) return value
            }
            return null
        }

        private fun getIntSafe(map: Map<*, *>, vararg keys: String): Int? {
            for (key in keys) {
                val value = map[key] as? Number
                if (value != null) return value.toInt()
            }
            return null
        }

        private fun getFloatSafe(map: Map<*, *>, vararg keys: String): Float? {
            for (key in keys) {
                val value = map[key] as? Number
                if (value != null) return value.toFloat()
            }
            return null
        }

        private fun extractTitle(titles: Any?): String? {
            if (titles == null) return null
            when (titles) {
                is Map<*, *> -> {
                    return (titles["english"] as? String)
                        ?: (titles["romaji"] as? String)
                        ?: (titles["native"] as? String)
                }
                is String -> return titles
            }
            return null
        }

        private fun parseType(typeStr: String?): TvType {
            return when (typeStr?.uppercase()) {
                "TV" -> TvType.Anime
                "MOVIE" -> TvType.AnimeMovie
                "ONA" -> TvType.Anime
                "OVA" -> TvType.OVA
                "SPECIAL" -> TvType.AnimeSpecial
                "MUSIC" -> TvType.AnimeMusic
                else -> TvType.Anime
            }
        }

        private fun parseStatus(status: String?): com.lagradost.cloudstream3.Status? {
            return when (status?.uppercase()) {
                "RELEASING", "ONGOING" -> com.lagradost.cloudstream3.Status.Ongoing
                "FINISHED", "COMPLETED" -> com.lagradost.cloudstream3.Status.Completed
                "NOT_YET_AIRED", "UPCOMING" -> com.lagradost.cloudstream3.Status.NotYetAired
                "CANCELLED" -> com.lagradost.cloudstream3.Status.Cancelled
                else -> null
            }
        }

        private fun parseAnimeItem(item: Map<*, *>): AnimeSearchResponse? {
            val id = (item["id"] as? Number)?.toInt() ?: return null
            val slug = item["slug"] as? String ?: return null
            val title = extractTitle(item["titles"]) ?: return null
            val posterUrl = (item["images"] as? Map<*, *>)?.get("poster") as? String
            val type = parseType(item["type"] as? String)

            return AnimeSearchResponse(
                name = title,
                url = slugToUrl(slug),
                posterUrl = posterUrl ?: "",
                type = type
            ).apply {
                this.score = (item["score"] as? Number)?.toInt()
            }
        }

        private fun extractJsonFromHtml(html: String): Map<String, Any>? {
            // Try to find window.config = {...} or var cfg = {...} or playerConfig = {...}
            val patterns = listOf(
                Regex("window\\.config\\s*=\\s*(\\{[^;]+\\})"),
                Regex("var\\s+cfg\\s*=\\s*(\\{[^;]+\\})"),
                Regex("window\\.playerConfig\\s*=\\s*(\\{[^;]+\\})"),
                Regex("window\\.megacloud\\s*=\\s*\\{\\s*config\\s*:\\s*(\\{[^;]+\\})\\s*\\}"),
            )
            for (pattern in patterns) {
                val match = pattern.find(html)?.groupValues?.getOrNull(1) ?: continue
                try {
                    val json = JsonParser.parseString(match)
                    if (json.isJsonObject) {
                        return json.asJsonObject.entrySet().associate { 
                            it.key to parseJsonElement(it.value) 
                        }
                    }
                } catch (_: Exception) { continue }
            }
            return null
        }

        private fun parseJsonElement(el: com.google.gson.JsonElement): Any {
            return when {
                el.isJsonObject -> el.asJsonObject.entrySet().associate { it.key to parseJsonElement(it.value) }
                el.isJsonArray -> el.asJsonArray.map { parseJsonElement(it) }
                el.isJsonPrimitive -> {
                    val p = el.asJsonPrimitive
                    when {
                        p.isString -> p.asString
                        p.isBoolean -> p.asBoolean
                        p.isNumber -> p.asNumber
                        else -> p.toString()
                    }
                }
                else -> el.toString()
            }
        }
    }

    // --- Main Page ---

    override suspend fun getMainPage(page: Int, requestCtx: MainPageRequest): HomePageResponse {
        if (page > 1) return newHomePageResponse(emptyList())

        val resp = app.get("$apiBase/home")
        val root = try {
            JsonParser.parseString(resp.text).asJsonObject
        } catch (_: Exception) {
            return newHomePageResponse(emptyList())
        }

        val homeData = root.getAsJsonObject("data") ?: return newHomePageResponse(emptyList())

        val sections = listOf(
            "spotlight" to "Nổi Bật",
            "trending" to "Xu Hướng",
            "latestEpisode" to "Tập Mới Nhất",
            "topAiring" to "Đang Chiếu",
            "mostPopular" to "Phổ Biến",
            "mostFavorite" to "Yêu Thích",
            "justCompleted" to "Vừa Hoàn Thành",
            "newAdded" to "Mới Thêm",
            "topUpcoming" to "Sắp Chiếu",
        )

        val homePageList = mutableListOf<HomePageList>()

        for ((key, label) in sections) {
            val items = try {
                homeData.getAsJsonArray(key)
            } catch (_: Exception) { null } ?: continue
            if (items.size() == 0) continue

            val parsed = items.mapNotNull { el ->
                if (!el.isJsonObject) return@mapNotNull null
                val map = el.asJsonObject.entrySet().associate { e ->
                    e.key to parseJsonElement(e.value)
                }
                parseAnimeItem(map)
            }
            if (parsed.isNotEmpty()) {
                homePageList.add(HomePageList(label, parsed))
            }
        }

        return newHomePageResponse(homePageList)
    }

    // --- Search ---

    override suspend fun search(query: String): List<SearchResponse> {
        if (query.isBlank()) return emptyList()

        val resp = app.get("$apiBase/anime/search?keyword=${java.net.URLEncoder.encode(query, "UTF-8")}")
        val root = try {
            JsonParser.parseString(resp.text).asJsonObject
        } catch (_: Exception) {
            return emptyList()
        }

        val data = root.getAsJsonArray("data") ?: return emptyList()

        return data.mapNotNull { el ->
            if (!el.isJsonObject) return@mapNotNull null
            val map = el.asJsonObject.entrySet().associate { e ->
                e.key to parseJsonElement(e.value)
            }
            parseAnimeItem(map)
        }
    }

    // --- Load Anime Detail ---

    override suspend fun load(url: String): LoadResponse {
        val slug = url.trimStart('/')
        val animeId = slugToId(slug)

        // Fetch anime detail
        val resp = app.get("$apiBase/anime/$animeId")
        val root = try {
            JsonParser.parseString(resp.text).asJsonObject
        } catch (_: Exception) {
            throw ErrorLoadingException("Failed to load anime detail")
        }

        val map = root.entrySet().associate { e ->
            e.key to parseJsonElement(e.value)
        }

        val title = extractTitle(map["titles"]) ?: throw ErrorLoadingException("No title found")
        val posterUrl = (map["images"] as? Map<*, *>)?.get("poster") as? String
        val type = parseType(map["type"] as? String)
        val status = parseStatus(map["status"] as? String)
        val synopsis = map["synopsis"] as? String ?: map["description"] as? String

        // Genres/tags
        val genres = when (val g = map["genres"]) {
            is List<*> -> g.mapNotNull {
                when (it) {
                    is String -> it
                    is Map<*, *> -> it["name"] as? String
                    else -> null
                }
            }
            else -> null
        } ?: (map["tags"] as? List<*>)?.mapNotNull {
            when (it) {
                is String -> it
                is Map<*, *> -> it["name"] as? String
                else -> null
            }
        }

        val year = map["season_year"] as? Int ?: (map["year"] as? Int)
        val score = map["score"] as? Number
        val ratingInt = score?.toFloat()?.let { (it * 10).toInt() }
        val episodeCount = getIntSafe(map, "episodes_count", "episodes", "sub_count")
        val duration = getIntSafe(map, "duration_min")

        // Load episodes
        val episodes = loadEpisodes(animeId, slug)

        val loadResponse = AnimeLoadResponse(
            name = title,
            url = slugToUrl(slug),
            posterUrl = posterUrl ?: "",
            type = type,
            source = mainUrl + slugToUrl(slug)
        ).apply {
            this.plot = synopsis
            this.tags = genres
            this.year = year
            this.status = status
            this.duration = duration
            if (ratingInt != null) this.rating = ratingInt
            episodeCount?.let { this.totalEpisodes = it }
            this.episodes = episodes
        }

        return loadResponse
    }

    private suspend fun loadEpisodes(animeId: Int, slug: String): List<Episode> {
        val resp = app.get("$apiBase/anime/$animeId/episodes")
        val root = try {
            JsonParser.parseString(resp.text).asJsonObject
        } catch (_: Exception) {
            return emptyList()
        }

        val data = root.getAsJsonArray("data") ?: return emptyList()

        return data.mapNotNull { el ->
            if (!el.isJsonObject) return@mapNotNull null
            val obj = el.asJsonObject

            val epNumber = obj.get("number")?.asInt ?: obj.get("id")?.asInt ?: return@mapNotNull null
            val embedId = obj.get("embed_id")?.asString ?: return@mapNotNull null

            val epTitle = try {
                val titles = obj.getAsJsonObject("titles")
                titles?.get("en")?.asString ?: "Tập $epNumber"
            } catch (_: Exception) {
                "Tập $epNumber"
            }

            val thumbnail = obj.get("thumbnail")?.asString

            newEpisode(
                url = "/$slug/$embedId",
                name = epTitle,
                episode = epNumber,
                posterUrl = thumbnail,
                data = embedId
            )
        }.sortedBy { it.episode }
    }

    // --- Load Video Links ---

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val embedId = data

        // Try servers in order: HD-1 sub, HD-2 sub, HD-1 dub
        val serverTypes = listOf(
            "hd-1" to "sub",
            "hd-2" to "sub",
            "hd-3" to "sub",
            "hd-1" to "dub",
            "hd-2" to "dub",
            "hd-3" to "dub",
        )

        var hasSources = false

        for ((server, type) in serverTypes) {
            try {
                val sourcesUrl = "$cdnBase/stream/getSources?id=$embedId&server=$server&type=$type"
                val resp = app.get(sourcesUrl)
                val root = try {
                    JsonParser.parseString(resp.text).asJsonObject
                } catch (_: Exception) {
                    continue
                }

                val sources = root.getAsJsonArray("sources") ?: continue
                if (sources.size() == 0) continue

                // Extract subtitles
                val tracks = root.getAsJsonArray("tracks")
                if (tracks != null) {
                    for (i in 0 until tracks.size()) {
                        val track = tracks[i]?.asJsonObject ?: continue
                        val file = track.get("file")?.asString ?: continue
                        val label = track.get("label")?.asString ?: "Unknown"
                        // Some tracks might have directUrl for subtitles
                        val directUrl = track.get("directUrl")?.asString
                        val subUrl = directUrl ?: if (file.startsWith("http")) file else "$cdnBase$file"
                        subtitleCallback(SubtitleFile(label, subUrl))
                    }
                }

                // Extract video sources
                for (i in 0 until sources.size()) {
                    val src = sources[i]?.asJsonObject ?: continue
                    val file = src.get("file")?.asString ?: continue
                    val quality = src.get("label")?.asString
                        ?: src.get("quality")?.asString
                        ?: server.uppercase()

                    // Prefer directUrl if available
                    val directUrl = src.get("directUrl")?.asString
                    val videoUrl = directUrl ?: if (file.startsWith("http")) file else "$cdnBase$file"
                    val isM3u8 = videoUrl.contains(".m3u8") || videoUrl.contains(".m3u")

                    callback.invoke(
                        newExtractorLink(
                            source = "4Animo",
                            name = "4Animo - $quality ($server $type)",
                            url = videoUrl,
                            type = if (isM3u8) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                        ) {
                            this.referer = "$cdnBase/"
                        }
                    )
                    hasSources = true
                }

                // Once we have HD-1 sub sources, that's usually enough
                if (server == "hd-1" && type == "sub") break
            } catch (_: Exception) {
                continue
            }
        }

        return hasSources
    }
}
