package recloudstream

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.google.gson.JsonParser

class Phim4kProvider : MainAPI() {
    override var mainUrl = "https://4animo.xyz"
    override var name = "4Animo"
    override var lang = "en"
    override val supportedTypes = setOf(
        TvType.Anime,
        TvType.AnimeMovie,
        TvType.OVA,
    )
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
                "SPECIAL" -> TvType.Anime
                else -> TvType.Anime
            }
        }

        private fun parseShowStatus(status: String?): ShowStatus? {
            return when (status?.uppercase()) {
                "RELEASING", "ONGOING" -> ShowStatus.Ongoing
                "FINISHED", "COMPLETED" -> ShowStatus.Completed
                else -> null
            }
        }

        private fun getJsonMap(obj: com.google.gson.JsonObject): Map<String, Any> {
            return obj.entrySet().associate { e ->
                e.key to parseJsonElement(e.value)
            }
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

    private fun parseAnimeItem(map: Map<String, Any>): AnimeSearchResponse? {
        val id = (map["id"] as? Number)?.toInt() ?: return null
        val slug = map["slug"] as? String ?: return null
        val title = extractTitle(map["titles"]) ?: return null
        val posterUrl = (map["images"] as? Map<*, *>)?.get("poster") as? String
        val type = parseType(map["type"] as? String)

        return newAnimeSearchResponse(title, slugToUrl(slug), type) {
            this.posterUrl = posterUrl ?: ""
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
            "spotlight" to "Featured",
            "trending" to "Trending",
            "latestEpisode" to "Latest Episodes",
            "topAiring" to "Top Airing",
            "mostPopular" to "Most Popular",
            "mostFavorite" to "Most Favorited",
            "justCompleted" to "Just Completed",
            "newAdded" to "New Added",
            "topUpcoming" to "Top Upcoming",
        )

        val homePageList = mutableListOf<HomePageList>()

        for ((key, label) in sections) {
            val items = try {
                homeData.getAsJsonArray(key)
            } catch (_: Exception) { null } ?: continue
            if (items.size() == 0) continue

            val parsed = items.mapNotNull { el ->
                if (!el.isJsonObject) return@mapNotNull null
                parseAnimeItem(getJsonMap(el.asJsonObject))
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
            parseAnimeItem(getJsonMap(el.asJsonObject))
        }
    }

    // --- Load Anime Detail ---

    override suspend fun load(url: String): LoadResponse {
        val slug = url.trimStart('/')
        val animeId = slugToId(slug)

        val resp = app.get("$apiBase/anime/$animeId")
        val root = try {
            JsonParser.parseString(resp.text).asJsonObject
        } catch (_: Exception) {
            throw ErrorLoadingException("Failed to load anime detail")
        }

        val map = getJsonMap(root)
        val title = extractTitle(map["titles"]) ?: throw ErrorLoadingException("No title found")
        val posterUrl = (map["images"] as? Map<*, *>)?.get("poster") as? String
        val type = parseType(map["type"] as? String)
        val showStatus = parseShowStatus(map["status"] as? String)
        val synopsis = map["synopsis"] as? String ?: map["description"] as? String

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
        val duration = map["duration_min"] as? Int
        val scoreNum = map["score"] as? Number

        // Parse recommendations
        val recommendations = (map["recommendations"] as? List<*>)?.mapNotNull { item ->
            val recMap = item as? Map<*, *> ?: return@mapNotNull null
            // Recommendations have the same shape as anime items
            val recId = (recMap["id"] as? Number)?.toInt() ?: return@mapNotNull null
            val recSlug = recMap["slug"] as? String ?: return@mapNotNull null
            val recTitle = extractTitle(recMap["titles"]) ?: return@mapNotNull null
            val recPoster = (recMap["images"] as? Map<*, *>)?.get("poster") as? String
            val recType = parseType(recMap["type"] as? String)
            newAnimeSearchResponse(recTitle, slugToUrl(recSlug), recType) {
                this.posterUrl = recPoster ?: ""
            }
        }

        // Load episodes
        val episodesMap = loadEpisodes(animeId)

        return newAnimeLoadResponse(title, slugToUrl(slug), type) {
            this.posterUrl = posterUrl ?: ""
            this.plot = synopsis
            this.tags = genres
            this.year = year
            this.showStatus = showStatus
            this.duration = duration
            if (scoreNum != null) {
                this.score = Score.from10(scoreNum.toFloat())
            }
            this.recommendations = recommendations ?: emptyList()
            this.episodes = episodesMap
        }
    }

    private suspend fun loadEpisodes(animeId: Int): MutableMap<DubStatus, List<Episode>> {
        val resp = app.get("$apiBase/anime/$animeId/episodes")
        val root = try {
            JsonParser.parseString(resp.text).asJsonObject
        } catch (_: Exception) {
            return mutableMapOf()
        }

        val data = root.getAsJsonArray("data") ?: return mutableMapOf()

        val episodes = data.mapNotNull { el ->
            if (!el.isJsonObject) return@mapNotNull null
            val obj = el.asJsonObject

            val epNumber = obj.get("number")?.asInt ?: obj.get("id")?.asInt ?: return@mapNotNull null
            val embedId = obj.get("embed_id")?.asString ?: return@mapNotNull null

            val epTitle = try {
                val titles = obj.getAsJsonObject("titles")
                titles?.get("en")?.asString ?: "Episode $epNumber"
            } catch (_: Exception) {
                "Episode $epNumber"
            }

            val thumbnail = obj.get("thumbnail")?.asString

            newEpisode("/$animeId/$embedId") {
                this.name = epTitle
                this.episode = epNumber
                this.posterUrl = thumbnail
                this.data = embedId
            }
        }.sortedBy { it.episode }

        return mutableMapOf(DubStatus.Subbed to episodes)
    }

    // --- Load Video Links ---

    private fun extractJsonArray(text: String, key: String): String? {
        // Find key in JavaScript object: "sources:", "sources :", or '"sources":'
        var startIdx = text.indexOf("$key:")
        if (startIdx < 0) startIdx = text.indexOf("$key :")
        if (startIdx < 0) startIdx = text.indexOf("\"$key\":")
        if (startIdx < 0) return null
        val bracketStart = text.indexOf('[', startIdx)
        if (bracketStart < 0) return null
        var depth = 0
        var pos = bracketStart
        while (pos < text.length) {
            val c = text[pos]
            when {
                c == '[' -> depth++
                c == ']' -> { depth--; if (depth == 0) return text.substring(bracketStart, pos + 1) }
                c == '"' -> {
                    pos++
                    while (pos < text.length) {
                        if (text[pos] == '\\') pos++
                        else if (text[pos] == '"') break
                        pos++
                    }
                }
            }
            pos++
        }
        return null
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val embedId = data

        // Try servers in priority order: HD-1 sub first, then others
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
                // Use the embed page directly (it has directUrl for video/subtitles)
                val embedUrl = "$cdnBase/api/embed/$server/$embedId/$type?k=1"
                val resp = app.get(embedUrl)
                val html = resp.text

                // Extract sources array from var cfg (contains directUrl)
                val sourcesText = extractJsonArray(html, "sources") ?: continue
                val sources = try {
                    JsonParser.parseString(sourcesText).asJsonArray
                } catch (_: Exception) { continue }
                if (sources.size() == 0) continue

                // Extract subtitles (also try directUrl from embed config)
                try {
                    val tracksText = extractJsonArray(html, "tracks")
                    if (tracksText != null) {
                        val tracks = JsonParser.parseString(tracksText).asJsonArray
                        for (i in 0 until tracks.size()) {
                            val track = tracks[i]?.asJsonObject ?: continue
                            val file = track.get("file")?.asString ?: continue
                            val label = track.get("label")?.asString ?: "Unknown"
                            val directUrl = track.get("directUrl")?.asString
                            val subUrl = directUrl ?: if (file.startsWith("http")) file else "$cdnBase$file"
                            subtitleCallback(SubtitleFile(label, subUrl))
                        }
                    }
                } catch (_: Exception) { }

                // Pick the highest quality source only (avoid multiple entries per server)
                var bestSrc: com.google.gson.JsonObject? = null
                var bestQuality = -1
                for (i in 0 until sources.size()) {
                    val src = sources[i]?.asJsonObject ?: continue
                    val file = src.get("file")?.asString ?: continue
                    val qualityStr = src.get("label")?.asString
                        ?: src.get("quality")?.asString ?: continue
                    val qualityNum = Regex("(\\d+)").find(qualityStr)
                        ?.groupValues?.get(1)?.toIntOrNull() ?: -1
                    if (qualityNum > bestQuality) {
                        bestQuality = qualityNum
                        bestSrc = src
                    }
                }

                if (bestSrc != null) {
                    val file = bestSrc.get("file")?.asString ?: continue
                    val qualityStr = bestSrc.get("label")?.asString
                        ?: bestSrc.get("quality")?.asString
                        ?: server.uppercase()
                    val qualityInt = Regex("(\\d+)").find(qualityStr)
                        ?.groupValues?.get(1)?.toIntOrNull() ?: -1

                    // Use token URL (same domain = no Cloudflare issues).
                    // directUrl (s1.streamzone1.site) is behind Cloudflare and returns 403.
                    val videoUrl = if (file.startsWith("http")) file else "$cdnBase$file"

                    callback.invoke(
                        newExtractorLink(
                            source = "4Animo",
                            name = "4Animo - $qualityStr ($server $type)",
                            url = videoUrl,
                            type = ExtractorLinkType.M3U8
                        ) {
                            this.referer = "$cdnBase/"
                            this.quality = qualityInt
                            this.headers = mapOf(
                                "Referer" to "$cdnBase/",
                                "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
                            )
                        }
                    )
                    hasSources = true
                }

            } catch (_: Exception) {
                continue
            }
        }

        return hasSources
    }
}
