package recloudstream

import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*

class AnimexProvider : MainAPI() {
    override var mainUrl = "https://animex.one"
    override var name = "AnimeX"
    override val hasMainPage = true
    override var lang = "en"
    override val supportedTypes = setOf(TvType.Anime, TvType.Movie)

    private val anilistApi = "https://graphql.anilist.co"

    // --- GraphQL Query ---
    private val queryMedia = """
        query (${"$"}page: Int = 1, ${"$"}perPage: Int = 20, ${"$"}type: MediaType = ANIME, ${"$"}search: String, ${"$"}sort: [MediaSort]) {
          Page(page: ${"$"}page, perPage: ${"$"}perPage) {
            media(type: ${"$"}type, sort: ${"$"}sort, search: ${"$"}search) {
              id
              title {
                english
                romaji
              }
              coverImage {
                extraLarge
                large
                medium
              }
              bannerImage
            }
          }
        }
    """.trimIndent()

    override val mainPage = mainPageOf(
        "TRENDING_DESC" to "Trending",
        "POPULARITY_DESC" to "Popular",
        "FAVOURITES_DESC" to "Top Rated",
        "UPDATED_AT_DESC" to "Just Updated"
    )

    // --- Data Classes ---
    data class AnilistTitle(
        @JsonProperty("english") val english: String?,
        @JsonProperty("romaji") val romaji: String?
    )
    data class AnilistCover(
        @JsonProperty("extraLarge") val extraLarge: String?,
        @JsonProperty("large") val large: String?,
        @JsonProperty("medium") val medium: String?
    )
    data class AnilistMedia(
        @JsonProperty("id") val id: Int,
        @JsonProperty("title") val title: AnilistTitle?,
        @JsonProperty("coverImage") val coverImage: AnilistCover?,
        @JsonProperty("bannerImage") val bannerImage: String?
    )
    data class AnilistPage(
        @JsonProperty("media") val media: List<AnilistMedia>?
    )
    data class AnilistData(
        @JsonProperty("Page") val page: AnilistPage?
    )
    data class AnilistResponse(
        @JsonProperty("data") val data: AnilistData?
    )
    
    data class GraphQlQuery(
        val query: String,
        val variables: Map<String, Any?>
    )

    data class AnimexEpTitle(
        @JsonProperty("en") val en: String?,
        @JsonProperty("ja") val ja: String?,
        @JsonProperty("x-jat") val xJat: String?
    )
    data class AnimexEpData(
        @JsonProperty("number") val number: Double?,
        @JsonProperty("titles") val titles: AnimexEpTitle?,
        @JsonProperty("img") val img: String?,
        @JsonProperty("description") val description: String?
    )

    data class SourceData(
        @JsonProperty("url") val url: String?,
        @JsonProperty("quality") val quality: String?
    )
    data class SubtitleData(
        @JsonProperty("url") val url: String?,
        @JsonProperty("lang") val lang: String?,
        @JsonProperty("label") val label: String?
    )
    data class AnimexSources(
        @JsonProperty("sources") val sources: List<SourceData>?,
        @JsonProperty("subtitles") val subtitles: List<SubtitleData>?
    )

    // --- Main Logic ---

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val sortType = request.data
        val variables = mapOf(
            "page" to page,
            "perPage" to 20,
            "type" to "ANIME",
            "sort" to listOf(sortType)
        )
        val body = GraphQlQuery(queryMedia, variables)
        return fetchAnilist(body, request.name)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val variables = mapOf(
            "page" to 1,
            "perPage" to 20,
            "type" to "ANIME",
            "search" to query
        )
        val body = GraphQlQuery(queryMedia, variables)
        
        val response = app.post(
            anilistApi,
            json = body,
            headers = mapOf(
                "Content-Type" to "application/json",
                "Accept" to "application/json"
            )
        ).parsedSafe<AnilistResponse>()

        return response?.data?.page?.media?.mapNotNull { it.toSearchResponse() } ?: emptyList()
    }

    private suspend fun fetchAnilist(body: GraphQlQuery, name: String): HomePageResponse {
        try {
            val response = app.post(
                anilistApi,
                json = body,
                headers = mapOf(
                    "Content-Type" to "application/json",
                    "Accept" to "application/json",
                    "Origin" to mainUrl
                )
            ).parsedSafe<AnilistResponse>()

            val list = response?.data?.page?.media?.mapNotNull { media ->
                media.toSearchResponse()
            } ?: emptyList()

            return newHomePageResponse(name, list)
        } catch (e: Exception) {
            e.printStackTrace()
            return newHomePageResponse(name, emptyList())
        }
    }

    private fun AnilistMedia.toSearchResponse(): SearchResponse {
        val titleEn = this.title?.english ?: this.title?.romaji ?: "Unknown"
        val slug = titleEn.replace(Regex("[^a-zA-Z0-9]"), "-")
                          .replace(Regex("-+"), "-")
                          .lowercase()
                          .trim('-')
        val url = "$mainUrl/anime/$slug-${this.id}"
        val image = this.coverImage?.extraLarge 
            ?: this.coverImage?.large 
            ?: this.coverImage?.medium 
            ?: ""

        return newAnimeSearchResponse(titleEn, url, TvType.Anime) {
            this.posterUrl = image
        }
    }

    override suspend fun load(url: String): LoadResponse {
        val document = app.get(url).document

        val title = document.selectFirst("h1")?.text() 
            ?: document.selectFirst("title")?.text()?.substringBefore(" -") 
            ?: "Unknown"
        val description = document.selectFirst("meta[name=description]")?.attr("content")
        val poster = document.selectFirst("meta[property=og:image]")?.attr("content")
        val bg = document.selectFirst(".absolute.inset-0.bg-cover")?.attr("style")
            ?.substringAfter("url('")?.substringBefore("')")

        // Recommendations
        val recommendations = document.select("swiper-slide a").mapNotNull { element ->
            val href = element.attr("href")
            val img = element.selectFirst("img")?.attr("src")
            val name = element.selectFirst(".title-text")?.text() ?: element.selectFirst("h5")?.text()
            if (!href.isNullOrBlank() && !name.isNullOrBlank()) {
                newAnimeSearchResponse(name, fixUrl(href), TvType.Anime) {
                    this.posterUrl = img
                }
            } else null
        }

        // Episodes
        val episodes = mutableListOf<Episode>()
        val animeId = url.substringBefore("?").trimEnd('/').substringAfterLast("-")
        val watchButton = document.selectFirst("a[href^='/watch/']")
        val watchUrlTemplate = watchButton?.attr("href")?.let { fixUrl(it) }

        if (animeId.all { it.isDigit() }) {
            try {
                val apiUrl = "$mainUrl/api/anime/episodes/$animeId"
                val responseText = app.get(
                    apiUrl, 
                    headers = mapOf(
                        "Referer" to url,
                        "Accept" to "application/json",
                        "X-Requested-With" to "XMLHttpRequest"
                    )
                ).text

                val apiResponse = AppUtils.parseJson<List<AnimexEpData>>(responseText)

                apiResponse.forEach { epData ->
                    val epNum = epData.number?.toInt() ?: 0
                    
                    val epUrl = if (watchUrlTemplate != null) {
                        watchUrlTemplate.replace(Regex("episode-\\d+$"), "episode-$epNum")
                    } else {
                        "$mainUrl/watch/${url.substringAfter("/anime/")}-episode-$epNum"
                    }

                    val rawTitle = epData.titles?.en ?: epData.titles?.xJat
                    val epName = if (!rawTitle.isNullOrBlank()) {
                        "Episode $epNum - $rawTitle"
                    } else {
                        "Episode $epNum"
                    }

                    episodes.add(
                        newEpisode(epUrl) {
                            this.name = epName
                            this.episode = epNum
                            this.posterUrl = epData.img
                            this.description = epData.description
                        }
                    )
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }

        if (episodes.isEmpty() && watchUrlTemplate != null) {
            episodes.add(newEpisode(watchUrlTemplate) {
                this.name = "Watch Now"
                this.episode = 1
            })
        }

        return newAnimeLoadResponse(title, url, TvType.Anime) {
            this.posterUrl = poster
            this.backgroundPosterUrl = bg
            this.plot = description
            this.recommendations = recommendations
            addEpisodes(DubStatus.Subbed, episodes)
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val urlToLoad = if (data.contains("/watch/")) data else data.replace("/anime/", "/watch/")
        
        try {
            val responseText = app.get(urlToLoad).text
            
            // Tìm ID nguồn
            val sourceIdRegex = Regex("""api/anime/sources/([a-zA-Z0-9_-]+)""")
            val match = sourceIdRegex.find(responseText)
            
            val sourceId = if (match != null) {
                match.groupValues[1]
            } else {
                val fallbackRegex = Regex("""["']([a-zA-Z0-9_-]{50,})["']""")
                fallbackRegex.find(responseText)?.groupValues?.get(1)
            }

            if (sourceId != null) {
                val apiUrl = "$mainUrl/api/anime/sources/$sourceId"
                
                val apiResponseText = app.get(
                    apiUrl,
                    headers = mapOf(
                        "Referer" to urlToLoad,
                        "Accept" to "application/json",
                        "X-Requested-With" to "XMLHttpRequest"
                    )
                ).text
                
                val sourceData = AppUtils.parseJson<AnimexSources>(apiResponseText)

                // Subtitles
                sourceData.subtitles?.forEach { sub ->
                    val subUrl = sub.url ?: return@forEach
                    val lang = sub.label ?: sub.lang ?: "Unknown"
                    subtitleCallback.invoke(
                        SubtitleFile(lang, subUrl)
                    )
                }

                // Sources
                sourceData.sources?.forEach { source ->
                    val link = source.url ?: return@forEach
                    
                    // Xác định Type: M3U8 hoặc VIDEO
                    val type = if (link.contains(".m3u8")) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO

                    callback.invoke(
                        newExtractorLink(
                            source = name,
                            name = name,
                            url = link,
                            type = type // Sử dụng tham số type thay vì this.isM3u8
                        ) {
                            this.referer = mainUrl
                            this.quality = getQualityFromName(source.quality)
                        }
                    )
                }
                return true
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        
        return false
    }
}
