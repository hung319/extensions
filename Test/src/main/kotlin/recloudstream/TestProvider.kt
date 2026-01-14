package recloudstream

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.AppUtils
import com.fasterxml.jackson.annotation.JsonProperty
import java.net.URLEncoder

class AnimexProvider : MainAPI() {
    override var mainUrl = "https://animex.one"
    override var name = "AnimeX"
    override val hasMainPage = true
    override var lang = "en"
    override val supportedTypes = setOf(TvType.Anime, TvType.Movie)

    // Anilist API URL
    private val anilistApi = "https://graphql.anilist.co/"

    // --- GraphQL Queries ---
    private val queryMedia = """
        query ($"page": Int = 1, $"perPage": Int = 20, $"type": MediaType = ANIME, $"search": String, $"sort": [MediaSort]) {
          Page(page: $"page", perPage: $"perPage") {
            media(type: $"type", sort: $"sort", search: $"search") {
              id
              title {
                english
                romaji
              }
              coverImage {
                extraLarge
              }
              bannerImage
            }
          }
        }
    """.trimIndent()

    // Khai báo các mục trang chủ tương ứng với sort của Anilist
    override val mainPage = mainPageOf(
        "TRENDING_DESC" to "Trending",
        "POPULARITY_DESC" to "Popular",
        "FAVOURITES_DESC" to "Top Rated",
        "UPDATED_AT_DESC" to "Just Updated"
    )

    // --- Data Classes cho Anilist GraphQL ---
    data class AnilistTitle(
        @JsonProperty("english") val english: String?,
        @JsonProperty("romaji") val romaji: String?
    )
    data class AnilistCover(
        @JsonProperty("extraLarge") val extraLarge: String?
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
    
    // Request Body cho GraphQL
    data class GraphQlQuery(
        val query: String,
        val variables: Map<String, Any>
    )

    // --- Data Classes cho Episode API (Animex) ---
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

    // Xử lý lấy danh sách phim trang chủ qua GraphQL
    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val sortType = request.data // Ví dụ: TRENDING_DESC
        val variables = mapOf(
            "page" to page,
            "perPage" to 20,
            "type" to "ANIME",
            "sort" to listOf(sortType)
        )
        
        val body = GraphQlQuery(queryMedia, variables)
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

        return newHomePageResponse(request.name, list)
    }

    // Xử lý tìm kiếm qua GraphQL
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
                "Accept" to "application/json",
                "Origin" to mainUrl
            )
        ).parsedSafe<AnilistResponse>()

        return response?.data?.page?.media?.mapNotNull { media ->
            media.toSearchResponse()
        } ?: emptyList()
    }

    // Hàm tiện ích chuyển đổi Anilist Media sang Cloudstream SearchResponse
    private fun AnilistMedia.toSearchResponse(): SearchResponse {
        val titleEn = this.title?.english ?: this.title?.romaji ?: "Unknown"
        // Tạo URL dạng giả lập để hàm load có thể xử lý: /anime/slug-id
        // Slug không quá quan trọng, quan trọng là ID ở cuối
        val slug = titleEn.replace(Regex("[^a-zA-Z0-9]"), "-").replace(Regex("-+"), "-").lowercase()
        val url = "$mainUrl/anime/$slug-${this.id}"
        
        return newAnimeSearchResponse(titleEn, url, TvType.Anime) {
            this.posterUrl = this@toSearchResponse.coverImage?.extraLarge
        }
    }

    // Xử lý Load chi tiết phim & Tập
    override suspend fun load(url: String): LoadResponse {
        val document = app.get(url).document

        val title = document.selectFirst("h1")?.text() 
            ?: document.selectFirst("title")?.text()?.substringBefore(" -") 
            ?: "Unknown"
        val description = document.selectFirst("meta[name=description]")?.attr("content")
        val poster = document.selectFirst("meta[property=og:image]")?.attr("content")
        val bg = document.selectFirst(".absolute.inset-0.bg-cover")?.attr("style")
            ?.substringAfter("url('")?.substringBefore("')")

        // 1. Recommendations (giữ nguyên logic parse HTML vì đơn giản và hiệu quả)
        val recommendations = document.select("swiper-slide a").mapNotNull { element ->
            val href = element.attr("href")
            val img = element.selectFirst("img")?.attr("src")
            val name = element.selectFirst(".title-text")?.text() ?: element.selectFirst("h5")?.text()
            if (href != null && name != null) {
                newAnimeSearchResponse(name, fixUrl(href), TvType.Anime) {
                    this.posterUrl = img
                }
            } else null
        }

        // 2. Episodes (Dùng API riêng của Animex)
        val episodes = mutableListOf<Episode>()
        // Lấy ID từ URL (số cuối cùng sau dấu -)
        val animeId = url.substringBefore("?").trimEnd('/').substringAfterLast("-")

        // Tìm template link từ nút Watch Now (để sửa thành link tập)
        val watchButton = document.selectFirst("a[href^='/watch/']")
        val watchUrlTemplate = watchButton?.attr("href")?.let { fixUrl(it) }

        if (animeId.all { it.isDigit() }) {
            try {
                val apiUrl = "$mainUrl/api/anime/episodes/$animeId"
                val headers = mapOf(
                    "Referer" to url,
                    "Accept" to "application/json",
                    "X-Requested-With" to "XMLHttpRequest"
                )
                
                val apiResponse = app.get(apiUrl, headers = headers).parsedSafe<List<AnimexEpData>>()

                apiResponse?.forEach { epData ->
                    val epNum = epData.number?.toInt() ?: 0
                    
                    // Tạo link tập phim
                    val epUrl = if (watchUrlTemplate != null) {
                        watchUrlTemplate.replace(Regex("episode-\\d+$"), "episode-$epNum")
                    } else {
                        // Fallback
                        "$mainUrl/watch/${url.substringAfter("/anime/")}-episode-$epNum"
                    }

                    // Tên tập: Ưu tiên tiếng Anh, fallback sang xJat
                    val rawTitle = epData.titles?.en ?: epData.titles?.xJat
                    // Format tên tập đồng bộ: "Episode 1 - Title" hoặc "Episode 1"
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

        // Fallback nếu API lỗi
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
        // Chuyển đổi link ảo (nếu có) thành link thật để tải
        // Ví dụ data: .../anime/slug-id-episode-1 -> .../watch/slug-id-episode-1
        val urlToLoad = if (data.contains("/watch/")) {
            data
        } else {
            data.replace("/anime/", "/watch/")
        }

        val document = app.get(urlToLoad).document
        val iframe = document.selectFirst("iframe")
        
        if (iframe != null) {
            var src = iframe.attr("src")
            if (src.startsWith("//")) src = "https:$src"
            
            if (src.isNotEmpty()) {
                loadExtractor(src, subtitleCallback, callback)
                return true
            }
        }
        return false
    }
}
