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
        val scoreNum = map["score"] as? Number
        val duration = map["duration_min"] as? Int

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
                this.score = Score(scoreNum.toFloat())
            }
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

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val embedId = data

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

                if (server == "hd-1" && type == "sub") break
            } catch (_: Exception) {
                continue
            }
        }

        return hasSources
    }
}
