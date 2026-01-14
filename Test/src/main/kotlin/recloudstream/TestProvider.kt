package recloudstream

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.loadExtractor
import org.jsoup.nodes.Element
import com.fasterxml.jackson.annotation.JsonProperty

class AnimexProvider : MainAPI() {
    override var mainUrl = "https://animex.one"
    override var name = "AnimeX"
    override val hasMainPage = true
    override var lang = "en"
    override val supportedTypes = setOf(TvType.Anime, TvType.Movie)

    override val mainPage = mainPageOf(
        "$mainUrl/home" to "Home",
        "$mainUrl/catalog?sort=TRENDING_DESC" to "Trending",
        "$mainUrl/catalog?sort=POPULARITY_DESC" to "Popular"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val document = app.get(request.data).document
        val home = document.select("a.item-link").mapNotNull {
            it.toSearchResult()
        }
        return newHomePageResponse(request.name, home)
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val href = this.attr("href") ?: return null
        val img = this.selectFirst("img")?.attr("src")
        val title = this.selectFirst(".title-text")?.text() 
            ?: this.selectFirst("h5")?.text() 
            ?: return null

        return newAnimeSearchResponse(title, fixUrl(href), TvType.Anime) {
            this.posterUrl = img
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val url = "$mainUrl/catalog?search=$query"
        val document = app.get(url).document
        return document.select("a.item-link").mapNotNull {
            it.toSearchResult()
        }
    }

    // --- Cấu trúc dữ liệu JSON trả về từ API ---
    data class EpisodeTitle(
        @JsonProperty("en") val en: String?,
        @JsonProperty("ja") val ja: String?,
        @JsonProperty("x-jat") val xJat: String?
    )

    data class EpisodeData(
        @JsonProperty("number") val number: Double?,
        @JsonProperty("titles") val titles: EpisodeTitle?,
        @JsonProperty("img") val img: String?,
        @JsonProperty("description") val description: String?,
        @JsonProperty("isFiller") val isFiller: Boolean?
    )
    // ------------------------------------------

    override suspend fun load(url: String): LoadResponse {
        val document = app.get(url).document

        val title = document.selectFirst("h1")?.text() 
            ?: document.selectFirst("title")?.text()?.substringBefore(" -") 
            ?: "Unknown"
        val description = document.selectFirst("meta[name=description]")?.attr("content")
        val poster = document.selectFirst("meta[property=og:image]")?.attr("content")
        val bg = document.selectFirst(".absolute.inset-0.bg-cover")?.attr("style")
            ?.substringAfter("url('")?.substringBefore("')")

        // 1. Lấy Recommendations
        val recommendations = document.select("swiper-slide a").mapNotNull {
            it.toSearchResult()
        }

        // 2. Lấy Episodes từ API
        val episodes = mutableListOf<Episode>()
        val animeId = url.substringAfterLast("-") // Ví dụ: ...-98310 -> lấy 98310

        // Lấy link mẫu từ nút Watch Now (ví dụ: /watch/...-episode-1)
        val watchButton = document.selectFirst("a[href^='/watch/']")
        val watchUrlTemplate = watchButton?.attr("href")?.let { fixUrl(it) }

        if (animeId.all { it.isDigit() }) {
            try {
                val apiUrl = "$mainUrl/api/anime/episodes/$animeId"
                
                // Gọi API
                val apiResponse = app.get(
                    apiUrl, 
                    headers = mapOf("Referer" to url) // Thêm Referer cho chắc
                ).parsedSafe<List<EpisodeData>>()

                apiResponse?.forEach { epData ->
                    val epNum = epData.number?.toInt() ?: 0
                    
                    // Logic tạo link: Lấy link mẫu, thay thế đoạn "episode-1" bằng "episode-SO_MOI"
                    val epUrl = if (watchUrlTemplate != null) {
                        // Regex tìm "episode-" theo sau là số ở cuối chuỗi
                        watchUrlTemplate.replace(Regex("episode-\\d+$"), "episode-$epNum")
                    } else {
                        // Fallback nếu không tìm thấy nút Watch Now
                        "$mainUrl/watch/${url.substringAfter("/anime/")}-episode-$epNum"
                    }

                    // Ưu tiên tên tiếng Anh, nếu không có thì dùng x-jat
                    val epName = epData.titles?.en ?: epData.titles?.xJat ?: "Episode $epNum"
                    
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

        // Fallback: Nếu API lỗi hoặc rỗng, dùng lại cách cũ lấy nút Watch Now
        if (episodes.isEmpty() && watchUrlTemplate != null) {
            episodes.add(newEpisode(watchUrlTemplate) {
                this.name = "Watch Now"
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
        // Tải trang Watch
        val document = app.get(data).document

        // Tìm iframe chứa video
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
